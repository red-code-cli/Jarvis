package com.jarvis.assistant.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Downloads the update APK into app-private storage (`filesDir/updates`) so it is never
 * world-readable. Supports HTTP range resume (a partial `.part` file continues where it left
 * off) and a few retries. Returns null on any failure; the caller then reports the error and
 * keeps the previous state.
 */
object UpdateDownloader {

    private const val TAG = "UpdateDownloader"
    private const val MAX_ATTEMPTS = 3
    private const val BUFFER = 64 * 1024

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun updatesDir(context: Context): File = File(context.filesDir, "updates").apply { mkdirs() }

    fun apkFile(context: Context, manifest: UpdateManifest): File =
        File(updatesDir(context), "jarvis-${manifest.latestVersionCode}.apk")

    /** True when a previously verified APK for this exact version already sits on disk. */
    fun hasVerifiedApk(context: Context, manifest: UpdateManifest): Boolean {
        val apk = apkFile(context, manifest)
        return apk.isFile && ApkVerifier.verify(context, apk, manifest) is ApkVerifier.Result.Ok
    }

    suspend fun download(
        context: Context,
        manifest: UpdateManifest,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        if (!UpdateManifest.isHttps(manifest.apkUrl)) return@withContext null
        val target = apkFile(context, manifest)
        val part = File(target.parentFile, target.name + ".part")
        repeat(MAX_ATTEMPTS) { attempt ->
            val result = runCatching { attemptDownload(manifest, part, onProgress) }.getOrNull()
            if (result == true) {
                if (target.exists()) target.delete()
                if (part.renameTo(target)) return@withContext target
            }
            Log.w(TAG, "Download attempt ${attempt + 1} failed")
            delay(1_200L * (attempt + 1))
        }
        return@withContext null
    }

    private fun attemptDownload(
        manifest: UpdateManifest,
        part: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Boolean {
        var existing = if (part.isFile) part.length() else 0L
        val requestBuilder = Request.Builder()
            .url(manifest.apkUrl)
            .header("Accept", "application/octet-stream")
        if (existing > 0L) requestBuilder.header("Range", "bytes=$existing-")

        client.newCall(requestBuilder.build()).execute().use { response ->
            // Reject a redirect that lands on HTTP.
            if (!UpdateManifest.isHttps(response.request.url.toString())) return false
            if (!response.isSuccessful) return false
            val body = response.body ?: return false

            val append = existing > 0L && response.code == 206
            if (!append) {
                existing = 0L
                part.delete()
            }
            val remaining = body.contentLength()
            val total = if (remaining > 0L) existing + remaining else manifest.apkSizeBytes

            RandomAccessFile(part, "rw").use { raf ->
                if (append) raf.seek(existing) else raf.setLength(0L)
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER)
                    var done = existing
                    var read: Int
                    while (input.read(buffer).also { read = it } > 0) {
                        raf.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
            }
        }
        // Optionally guard against a truncated stream.
        if (manifest.apkSizeBytes > 0L && part.length() < manifest.apkSizeBytes) {
            Log.w(TAG, "Partial download: ${part.length()}/${manifest.apkSizeBytes}")
            return false
        }
        return true
    }
}
