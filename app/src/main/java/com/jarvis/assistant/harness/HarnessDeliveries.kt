package com.jarvis.assistant.harness

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Publishes coding-engine deliverables to shared storage so the user can actually find
 * them, as required by the engine contract:
 *
 *   Downloads/Jarvis/<project-name>/
 *
 * Scoped-storage safe: uses MediaStore on Android 10+ and the legacy public Downloads
 * directory on Android 8/9 (where the app already holds WRITE_EXTERNAL_STORAGE).
 */
object HarnessDeliveries {

    private const val ROOT = "Jarvis"

    /**
     * Copies [source] (an APK file or a project directory) into Downloads/Jarvis/<name>/.
     * Returns the user-visible location, e.g. "Downloads/Jarvis/todo-app/todo-app.zip".
     */
    suspend fun publish(context: Context, projectName: String, source: File): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val folder = safeName(projectName)
                val payload: File = if (source.isDirectory) {
                    zipProject(context, source, "$folder.zip")
                } else {
                    source
                }
                if (!payload.isFile || payload.length() == 0L) return@runCatching null
                val mime = mimeFor(payload.name)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveWithMediaStore(context, folder, payload, mime)
                } else {
                    saveLegacy(folder, payload)
                }
            }.getOrNull()
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveWithMediaStore(context: Context, folder: String, file: File, mime: String): String {
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/$ROOT/$folder"
        val resolver = context.contentResolver
        // Replace a previous copy of the same name so re-runs do not pile up duplicates.
        resolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(file.name, "$relative/"),
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore rejected the download entry")
        resolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: error("Could not open the MediaStore stream")
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "Downloads/$ROOT/$folder/${file.name}"
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(folder: String, file: File): String {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$ROOT/$folder",
        ).apply { mkdirs() }
        val target = File(dir, file.name)
        file.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        return "Downloads/$ROOT/$folder/${file.name}"
    }

    private fun zipProject(context: Context, project: File, zipName: String): File {
        val target = File(context.cacheDir, "harness-delivery/$zipName").apply {
            parentFile?.mkdirs()
            delete()
        }
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            val root = project.toPath()
            project.walkTopDown()
                .filter { it.isFile }
                .forEach { entry ->
                    // Never ship node_modules/build caches or the engine's own metadata.
                    val relative = root.relativize(entry.toPath()).toString().replace('\\', '/')
                    if (relative.split('/').any { it in EXCLUDED_DIRS }) return@forEach
                    if (entry.length() > MAX_SINGLE_FILE_BYTES) return@forEach
                    zip.putNextEntry(ZipEntry(relative))
                    entry.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
        }
        return target
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun safeName(value: String): String {
        val cleaned = value.replace(Regex("[^A-Za-z0-9._ -]"), "").trim().trim('.').ifBlank { "project" }
        return cleaned.take(48)
    }

    private val EXCLUDED_DIRS = setOf("node_modules", ".gradle", "build", ".git", ".dart_tool", "__pycache__", ".venv")
    private const val MAX_SINGLE_FILE_BYTES = 64L * 1024 * 1024
}