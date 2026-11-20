package com.jarvis.assistant.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Prepares the sherpa-onnx wake-word and speaker models for first use.
 *
 * The KWS wake-word model is BUNDLED in the APK (assets/wake-models/bundled) as a fallback:
 * if the remote copy cannot be downloaded (offline, GitHub blocked, …) it is restored from
 * assets so wake listening always works. The speaker-embedding model stays download-only and is
 * optional — without it, owner verification fails open. Downloads support resume (HTTP Range)
 * and are verified by size before being promoted from `.part` to final.
 *
 * The URL list lives in `assets/wake-models/manifest.json` so a changed URL is a data
 * change, not a code change.
 */
object WakeModelDownloader {

    private const val TAG = "WakeModelDownloader"
    private const val ASSET = "wake-models/manifest.json"

    data class Entry(
        val id: String,
        val url: String,
        val archive: Boolean,
        val requiredBytes: Long,
        val readyMarker: String,
    )

    /** Root folder that holds every downloaded model. */
    fun modelsDir(context: Context): File = File(context.filesDir, "wake-models").apply { mkdirs() }

    /**
     * Folder containing the unpacked sherpa-onnx KWS wake model. The archive in the manifest
     * extracts to this sub-folder (see the manifest `readyMarker`).
     */
    fun wakeModelDir(context: Context): File =
        File(modelsDir(context), "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01")

    /** Speaker-embedding model used for owner voice verification. */
    fun speakerModelFile(context: Context): File =
        File(modelsDir(context), "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx")

    /**
     * True only when the KWS wake-word model is present. The speaker model is NOT required for
     * wake listening: when it is missing, owner verification fails open (no profile / no audio /
     * extraction failure all activate), so wake must never be blocked by a speaker-model download.
     */
    fun wakeModelReady(context: Context): Boolean =
        File(modelsDir(context), "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/tokens.txt").exists()

    fun isReady(context: Context): Boolean = entries(context).all { entry ->
        File(modelsDir(context), entry.readyMarker).exists()
    }

    fun entries(context: Context): List<Entry> = runCatching {
        val json = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        val array = JSONObject(json).optJSONArray("models") ?: JSONArray()
        List(array.length()) { i ->
            val item = array.getJSONObject(i)
            Entry(
                id = item.optString("id"),
                url = item.optString("url"),
                archive = item.optBoolean("archive", true),
                requiredBytes = item.optLong("bytes", 0L),
                readyMarker = item.optString("readyMarker"),
            )
        }
    }.onFailure { Log.w(TAG, "Manifest read failed: ${it.message}") }.getOrDefault(emptyList())

    /**
     * Ensures every model is present. [onProgress] receives 0..1 across all entries.
     * Safe to call repeatedly; already-downloaded entries are skipped.
     *
     * The KWS wake model has a bundled asset fallback: if the remote download fails (offline,
     * GitHub blocked, …) it is restored from the APK assets so wake keeps working.
     */
    suspend fun ensureModels(
        context: Context,
        onProgress: (Float) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val all = entries(context)
        if (all.isEmpty()) return@withContext false
        val root = modelsDir(context)
        var done = 0
        all.forEach { entry ->
            val marker = File(root, entry.readyMarker)
            if (!marker.exists()) {
                var ok = downloadAndExtract(context, entry, root)
                if (!ok && entry.id == KWS_ENTRY_ID) {
                    ok = restoreBundledWakeModel(context, entry, root)
                }
                if (!ok) return@withContext false
            }
            done++
            onProgress(done.toFloat() / all.size)
        }
        true
    }

    /**
     * Ensures ONLY the KWS wake model is present (remote download with bundled-asset fallback).
     * Used by the service gate so wake listening never depends on the speaker model.
     */
    suspend fun ensureWakeModels(context: Context): Boolean = withContext(Dispatchers.IO) {
        val entry = entries(context).firstOrNull { it.id == KWS_ENTRY_ID } ?: return@withContext false
        val root = modelsDir(context)
        val marker = File(root, entry.readyMarker)
        if (marker.exists()) return@withContext true
        val ok = downloadAndExtract(context, entry, root) || restoreBundledWakeModel(context, entry, root)
        ok
    }

    /** Copies the bundled KWS model (assets/wake-models/bundled/…) into the model folder. */
    private fun restoreBundledWakeModel(context: Context, entry: Entry, root: File): Boolean {
        return runCatching {
            val assetRoot = "wake-models/bundled/${entry.readyMarker.substringBefore('/')}"
            val names = context.assets.list(assetRoot) ?: emptyArray()
            names.forEach { name ->
                val asset = context.assets.open("$assetRoot/$name")
                val target = File(root, "${entry.readyMarker.substringBefore('/')}/$name")
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out -> asset.copyTo(out) }
                asset.close()
            }
            File(root, entry.readyMarker).exists()
        }.onFailure { Log.w(TAG, "Bundled restore ${entry.id} failed: ${it.message}") }.getOrDefault(false)
    }

    private fun downloadAndExtract(context: Context, entry: Entry, root: File): Boolean {
        val archive = File(root, "${entry.id}.part")
        if (!downloadResumable(context, entry, archive)) return false
        if (!entry.archive) {
            val target = File(root, entry.readyMarker)
            archive.renameTo(target)
            return target.exists()
        }
        return runCatching {
            BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()), true).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    generateSequence { tar.nextEntry }.forEach { tarEntry ->
                        val name = tarEntry.name.removePrefix("./")
                        if (!tarEntry.isDirectory && name.isNotBlank()) {
                            val target = File(root, name)
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out -> tar.copyTo(out) }
                        }
                    }
                }
            }
            archive.delete()
            true
        }.onFailure { Log.w(TAG, "Extract ${entry.id} failed: ${it.message}") }.getOrDefault(false)
    }

    private fun downloadResumable(context: Context, entry: Entry, target: File): Boolean {
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            val existing = if (target.exists()) target.length() else 0L
            runCatching {
                val connection = (URL(entry.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("Accept-Encoding", "identity")
                    if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                }
                try {
                    val code = connection.responseCode
                    if (code !in 200..299 && code != 206) {
                        Log.w(TAG, "HTTP $code for ${entry.url}")
                        return@runCatching false
                    }
                    val append = code == 206 && existing > 0
                    RandomAccessFile(target, "rw").use { raf ->
                        if (append) raf.seek(existing) else raf.setLength(0)
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                raf.write(buffer, 0, read)
                            }
                        }
                    }
                    true
                } finally {
                    connection.disconnect()
                }
            }.onFailure { Log.w(TAG, "Download ${entry.id} attempt $attempt failed: ${it.message}") }

            if (entry.requiredBytes > 0 && target.length() < entry.requiredBytes) {
                Log.w(TAG, "Incomplete ${entry.id}: ${target.length()}/${entry.requiredBytes}, retrying")
                continue
            }
            return true
        }
        return false
    }

    private const val MAX_ATTEMPTS = 4
    private const val KWS_ENTRY_ID = "kws-gigaspeech"
}
