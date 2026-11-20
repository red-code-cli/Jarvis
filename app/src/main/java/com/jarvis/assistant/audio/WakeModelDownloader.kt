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
 * Downloads the free sherpa-onnx wake-word and speaker models on first use.
 *
 * Large models are never bundled in the APK (which keeps the download small): they are
 * fetched from the official sherpa-onnx GitHub releases and extracted on device with
 * Apache Commons Compress (pure Java, so no native bz2 library). Downloads support resume
 * (HTTP Range) and are verified by size before being promoted from `.part` to final.
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
                val ok = downloadAndExtract(context, entry, root)
                if (!ok) return@withContext false
            }
            done++
            onProgress(done.toFloat() / all.size)
        }
        true
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
}
