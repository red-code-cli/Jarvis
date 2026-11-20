package com.jarvis.assistant.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * File, document and clipboard tools that run entirely on-device.
 * Every method returns a JSON tool result: success, message and file metadata.
 */
object SpecialTools {

    private const val TAG = "SpecialTools"
    private const val MAX_DOWNLOAD_BYTES = 100L * 1024L * 1024L
    private const val MAX_ARCHIVE_BYTES = 200L * 1024L * 1024L
    private const val MAX_ARCHIVE_ENTRIES = 500

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    fun handle(context: Context, tool: String, args: JSONObject): JSONObject? {
        return try {
            when (tool) {
                "create_pdf" -> createPdf(context, args)
                "images_to_pdf" -> imagesToPdf(context, args)
                "create_text_file" -> createTextFile(context, args)
                "read_text_file" -> readTextFile(context, args)
                "list_files" -> listFiles(context, args)
                "zip_files" -> zipFiles(context, args)
                "unzip_file" -> unzipFile(context, args)
                "generate_qr" -> generateQr(context, args)
                "clipboard" -> clipboard(context, args)
                "share_file" -> shareFile(context, args)
                "download_file" -> downloadFile(context, args)
                else -> null
            }
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "$tool failed: ${t.message}", t)
            fail("${tool.replace('_', ' ')} failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------
    // PDF
    // ------------------------------------------------------------------

    private fun createPdf(context: Context, args: JSONObject): JSONObject {
        val title = args.optString("title", "").trim()
        val content = args.optString("content", "")
        if (content.isBlank() && title.isBlank()) {
            return fail("PDF ke liye title ya content do.")
        }

        val pageWidth = 595
        val pageHeight = 842
        val margin = 42f
        val lineHeight = 17f
        val document = PdfDocument()
        val titlePaint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val bodyPaint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
            textSize = 12f
            typeface = Typeface.SANS_SERIF
        }
        val maxWidth = pageWidth - margin * 2

        val lines = mutableListOf<String>()
        content.replace("\r\n", "\n").split('\n').forEach { paragraph ->
            if (paragraph.isBlank()) {
                lines.add("")
                return@forEach
            }
            var remaining = paragraph.trim()
            while (remaining.isNotEmpty()) {
                val measured = bodyPaint.breakText(remaining, true, maxWidth, null)
                var cut = if (measured <= 0) remaining.length else measured
                if (cut < remaining.length) {
                    val lastSpace = remaining.substring(0, cut).lastIndexOf(' ')
                    if (lastSpace > cut * 0.55f) cut = lastSpace
                }
                if (cut <= 0) cut = 1
                lines.add(remaining.substring(0, cut).trimEnd())
                remaining = remaining.substring(cut).trimStart()
            }
        }

        var pageNumber = 1
        var page = document.startPage(
            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        )
        var canvas = page.canvas
        var y = margin + titlePaint.textSize
        if (title.isNotBlank()) {
            canvas.drawText(title, margin, y, titlePaint)
            y += titlePaint.textSize + 12f
        }
        for (line in lines) {
            if (y > pageHeight - margin - lineHeight) {
                document.finishPage(page)
                pageNumber++
                page = document.startPage(
                    PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                )
                canvas = page.canvas
                y = margin + lineHeight
            }
            if (line.isNotEmpty()) canvas.drawText(line, margin, y, bodyPaint)
            y += lineHeight
        }
        document.finishPage(page)

        val bytes = ByteArrayOutputStream().use { output ->
            document.writeTo(output)
            output.toByteArray()
        }
        document.close()

        val name = safeFileName(
            args.optString("file_name", "").trim().ifBlank {
                title.ifBlank { "Jarvis_Document" }
            },
            "pdf"
        )
        val saved = saveBytes(context, name, "application/pdf", bytes)
            ?: return fail("PDF save nahi ho payi. All files access grant karo aur retry karo.")
        return success(
            "PDF ban gayi: ${saved.displayName} (${pageNumber} page).",
            saved,
            JSONObject().apply {
                put("pages", pageNumber)
                put("size_bytes", bytes.size)
            }
        )
    }

    private fun imagesToPdf(context: Context, args: JSONObject): JSONObject {
        val array = args.optJSONArray("images") ?: return fail("images array me image paths do.")
        if (array.length() == 0) return fail("Koi image path nahi mila.")
        if (array.length() > 30) return fail("Ek PDF me maximum 30 images daal sakte hain.")

        val document = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 24f
        var pages = 0
        for (i in 0 until array.length()) {
            val raw = array.optString(i, "").trim()
            if (raw.isEmpty()) continue
            val bitmap = decodeBitmap(context, raw) ?: continue
            try {
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pages + 1).create()
                )
                val canvas = page.canvas
                val maxW = pageWidth - margin * 2
                val maxH = pageHeight - margin * 2
                val scale = minOf(maxW / bitmap.width, maxH / bitmap.height)
                val drawW = bitmap.width * scale
                val drawH = bitmap.height * scale
                val left = (pageWidth - drawW) / 2f
                val top = (pageHeight - drawH) / 2f
                canvas.drawBitmap(
                    bitmap,
                    null,
                    android.graphics.RectF(left, top, left + drawW, top + drawH),
                    Paint().apply { isAntiAlias = true; isFilterBitmap = true }
                )
                document.finishPage(page)
                pages++
            } finally {
                bitmap.recycle()
            }
        }
        if (pages == 0) {
            document.close()
            return fail("Images read nahi hui. All files access aur valid paths check karo.")
        }
        val bytes = ByteArrayOutputStream().use { output ->
            document.writeTo(output)
            output.toByteArray()
        }
        document.close()
        val name = safeFileName(
            args.optString("file_name", "").trim().ifBlank { "Jarvis_Images" },
            "pdf"
        )
        val saved = saveBytes(context, name, "application/pdf", bytes)
            ?: return fail("Images PDF save nahi ho payi.")
        return success(
            "$pages image(s) ki PDF ban gayi: ${saved.displayName}.",
            saved,
            JSONObject().put("pages", pages)
        )
    }

    private fun decodeBitmap(context: Context, raw: String): Bitmap? {
        return try {
            if (raw.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(raw))?.use {
                    BitmapFactory.decodeStream(it)
                }
            } else {
                val file = resolveFile(context, raw)
                file?.let { BitmapFactory.decodeFile(it.absolutePath) }
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Text files
    // ------------------------------------------------------------------

    private fun createTextFile(context: Context, args: JSONObject): JSONObject {
        val content = args.optString("content", "")
        if (content.isBlank()) return fail("File content blank hai.")
        val requested = args.optString("file_name", "").trim().ifBlank { "Jarvis_Note" }
        val extension = requested.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val name = safeFileName(requested, if (extension.isBlank()) "txt" else extension)
        val mime = mimeFor(name)
        val saved = saveBytes(context, name, mime, content.toByteArray(Charsets.UTF_8))
            ?: return fail("File save nahi ho payi. All files access grant karo.")
        return success("File ban gayi: ${saved.displayName}.", saved)
    }

    private fun readTextFile(context: Context, args: JSONObject): JSONObject {
        val raw = args.optString("file_name_or_path", "").trim()
        if (raw.isEmpty()) return fail("File ka naam ya path do.")
        if (raw.startsWith("content://")) {
            val text = context.contentResolver.openInputStream(Uri.parse(raw))?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return fail("File read nahi hui.")
            return textResult(raw, text, args.optInt("max_chars", 20_000))
        }
        val file = resolveFile(context, raw) ?: return fail("File nahi mili: $raw")
        if (!file.isFile) return fail("Ye file nahi hai: ${file.name}")
        if (file.length() > 2L * 1024L * 1024L) {
            return fail("File 2 MB se badi hai. Chhoti text file do.")
        }
        val bytes = file.readBytes()
        if (bytes.any { it == 0.toByte() }) {
            return fail("Ye binary file lagti hai, text ke roop me read nahi kar sakta.")
        }
        return textResult(file.name, bytes.toString(Charsets.UTF_8), args.optInt("max_chars", 20_000))
    }

    private fun textResult(name: String, text: String, maxChars: Int): JSONObject {
        val limit = maxChars.coerceIn(200, 100_000)
        val clipped = text.length > limit
        return success(
            "File read ho gayi: $name (${text.length} chars).",
            null,
            JSONObject().apply {
                put("file", name)
                put("text", text.take(limit))
                put("truncated", clipped)
                put("characters", text.length)
            }
        )
    }

    // ------------------------------------------------------------------
    // File manager
    // ------------------------------------------------------------------

    private fun listFiles(context: Context, args: JSONObject): JSONObject {
        val folder = args.optString("folder", "downloads").trim().lowercase(Locale.ROOT)
        val query = args.optString("query", "").trim().lowercase(Locale.ROOT)
        val limit = args.optInt("limit", 40).coerceIn(1, 100)
        val dir = folderDirectory(context, folder)
            ?: return fail("Folder nahi mila: $folder")
        if (!dir.exists()) return fail("Folder exist nahi karta: ${dir.absolutePath}")
        if (!dir.isDirectory) return fail("Ye folder nahi hai: ${dir.absolutePath}")

        val files = try {
            dir.listFiles()?.toList().orEmpty()
        } catch (t: Throwable) {
            return fail("Folder read nahi hua. All files access grant karo. (${t.message})")
        }
        val filtered = files
            .asSequence()
            .filter { query.isBlank() || it.name.lowercase(Locale.ROOT).contains(query) }
            .sortedByDescending { it.lastModified() }
            .take(limit)
            .toList()

        val array = JSONArray()
        filtered.forEach { file ->
            array.put(JSONObject().apply {
                put("name", file.name)
                put("path", file.absolutePath)
                put("is_directory", file.isDirectory)
                put("size_bytes", if (file.isFile) file.length() else 0L)
                put("modified_at_ms", file.lastModified())
            })
        }
        return success(
            "${filtered.size} item(s) mile ${dir.absolutePath} me.",
            null,
            JSONObject().apply {
                put("folder", dir.absolutePath)
                put("count", filtered.size)
                put("files", array)
            }
        )
    }

    private fun folderDirectory(context: Context, folder: String): File? {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return when {
            folder.startsWith("/") -> File(folder)
            folder == "downloads" -> downloads
            folder == "documents" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            folder == "pictures" || folder == "images" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            folder == "jarvis" -> File(downloads, "JARVIS")
            folder == "app" -> context.getExternalFilesDir(null) ?: context.filesDir
            else -> File(downloads, folder)
        }
    }

    // ------------------------------------------------------------------
    // ZIP
    // ------------------------------------------------------------------

    private fun zipFiles(context: Context, args: JSONObject): JSONObject {
        val array = args.optJSONArray("files") ?: return fail("files array me paths do.")
        if (array.length() == 0) return fail("Zip karne ke liye koi file nahi di.")
        val sources = mutableListOf<File>()
        for (i in 0 until array.length()) {
            val raw = array.optString(i, "").trim()
            if (raw.isBlank()) continue
            val file = resolveFile(context, raw) ?: continue
            sources.add(file)
        }
        if (sources.isEmpty()) return fail("Koi valid file nahi mili.")
        if (sources.size > 200) return fail("Ek zip me maximum 200 top-level items daal sakte hain.")

        val buffer = ByteArrayOutputStream()
        var total = 0L
        val usedNames = mutableSetOf<String>()
        ZipOutputStream(BufferedOutputStream(buffer)).use { zip ->
            fun addFile(file: File, entryName: String) {
                if (total > MAX_ARCHIVE_BYTES) throw IllegalStateException("Zip 200 MB limit cross kar gaya")
                var name = entryName
                var counter = 1
                while (!usedNames.add(name)) {
                    name = "${entryName.substringBeforeLast('.')}_$counter." +
                        entryName.substringAfterLast('.', "bin")
                    counter++
                }
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
                total += file.length()
            }
            fun addTree(file: File, prefix: String) {
                if (file.isDirectory) {
                    val children = file.listFiles().orEmpty()
                    for (child in children) addTree(child, "$prefix${file.name}/")
                } else {
                    addFile(file, "$prefix${file.name}")
                }
            }
            sources.forEach { source ->
                if (source.isDirectory) addTree(source, "")
                else addFile(source, source.name)
            }
        }
        val bytes = buffer.toByteArray()
        val name = safeFileName(
            args.optString("zip_name", "").trim().ifBlank { "Jarvis_Files" },
            "zip"
        )
        val saved = saveBytes(context, name, "application/zip", bytes)
            ?: return fail("Zip save nahi hui.")
        return success(
            "ZIP ban gayi: ${saved.displayName} (${bytes.size / 1024} KB).",
            saved,
            JSONObject().put("size_bytes", bytes.size)
        )
    }

    private fun unzipFile(context: Context, args: JSONObject): JSONObject {
        val raw = args.optString("zip_name_or_path", "").trim()
        if (raw.isEmpty()) return fail("Zip file ka naam ya path do.")
        val zipFile = resolveFile(context, raw) ?: return fail("Zip file nahi mili: $raw")
        if (!zipFile.isFile) return fail("Ye zip file nahi hai.")
        val destinationRaw = args.optString("destination", "").trim()
        val destination = if (destinationRaw.isBlank()) {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "JARVIS/${zipFile.nameWithoutExtension}"
            )
        } else if (destinationRaw.startsWith("/")) {
            File(destinationRaw)
        } else {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "JARVIS/$destinationRaw"
            )
        }
        if (!destination.exists() && !destination.mkdirs()) {
            return fail("Destination folder create nahi hua: ${destination.absolutePath}")
        }
        val destinationPath = destination.canonicalPath
        var extracted = 0
        var total = 0L

        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (extracted >= MAX_ARCHIVE_ENTRIES) break
                val outFile = File(destination, entry.name).canonicalFile
                if (!outFile.path.startsWith(destinationPath + File.separator) &&
                    outFile.path != destinationPath
                ) {
                    zip.closeEntry()
                    entry = zip.nextEntry
                    continue
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > MAX_ARCHIVE_BYTES) {
                                throw IllegalStateException("Uncompressed size 200 MB limit cross kar gaya")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                    extracted++
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return success(
            "$extracted file(s) extract ho gayi: ${destination.absolutePath}.",
            null,
            JSONObject().apply {
                put("destination", destination.absolutePath)
                put("extracted_files", extracted)
                put("size_bytes", total)
            }
        )
    }

    // ------------------------------------------------------------------
    // QR
    // ------------------------------------------------------------------

    private fun generateQr(context: Context, args: JSONObject): JSONObject {
        val content = args.optString("content", "").trim()
        if (content.isEmpty()) return fail("QR ke liye content do.")
        val size = 720
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.MARGIN to 1)
        )
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
        val name = safeFileName(
            args.optString("file_name", "").trim().ifBlank { "Jarvis_QR" },
            "png"
        )
        val saved = saveBytes(context, name, "image/png", bytes)
            ?: return fail("QR image save nahi hui.")
        return success("QR code ban gaya: ${saved.displayName}.", saved)
    }

    // ------------------------------------------------------------------
    // Clipboard / share / download
    // ------------------------------------------------------------------

    private fun clipboard(context: Context, args: JSONObject): JSONObject {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return fail("Clipboard service unavailable.")
        return when (args.optString("action", "read").trim().lowercase(Locale.ROOT)) {
            "write", "set", "copy" -> {
                val text = args.optString("text", "")
                if (text.isEmpty()) return fail("Clipboard me write karne ke liye text do.")
                manager.setPrimaryClip(ClipData.newPlainText("JARVIS", text))
                success("Clipboard me text copy ho gaya.", null, JSONObject().put("text", text))
            }
            else -> {
                val clip = manager.primaryClip
                val text = if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
                } else ""
                success(
                    if (text.isBlank()) "Clipboard khali hai." else "Clipboard read ho gaya.",
                    null,
                    JSONObject().put("text", text)
                )
            }
        }
    }

    private fun shareFile(context: Context, args: JSONObject): JSONObject {
        val raw = args.optString("file_name_or_path", "").trim()
        if (raw.isEmpty()) return fail("Share karne ke liye file do.")
        val uri: Uri
        val mime: String
        if (raw.startsWith("content://")) {
            uri = Uri.parse(raw)
            mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        } else {
            val file = resolveFile(context, raw) ?: return fail("File nahi mili: $raw")
            if (!file.isFile) return fail("Ye file nahi hai: ${file.name}")
            mime = mimeFor(file.name)
            uri = fileUri(context, file) ?: return fail("File share URI create nahi hua.")
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(send, "Share with JARVIS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        return success("Share sheet open ho gaya.", null, JSONObject().put("uri", uri.toString()))
    }

    private fun downloadFile(context: Context, args: JSONObject): JSONObject {
        val rawUrl = args.optString("url", "").trim()
        if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
            return fail("Valid http/https URL do.")
        }
        val request = Request.Builder().url(rawUrl).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return fail("Download failed: HTTP ${response.code}")
            val body = response.body ?: return fail("Download failed: empty response.")
            val length = body.contentLength()
            if (length > MAX_DOWNLOAD_BYTES) return fail("File 100 MB se badi hai.")
            val suggested = args.optString("file_name", "").trim().ifBlank {
                Uri.parse(rawUrl).lastPathSegment?.substringBefore('?') ?: "Jarvis_Download"
            }
            val name = safeFileName(suggested, "bin")
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_DOWNLOAD_BYTES) {
                        return fail("File 100 MB se badi hai.")
                    }
                    output.write(buffer, 0, read)
                }
            }
            val bytes = output.toByteArray()
            val saved = saveBytes(context, name, mimeFor(name), bytes)
                ?: return fail("Download save nahi hua. All files access grant karo.")
            return success(
                "Download ho gaya: ${saved.displayName} (${bytes.size / 1024} KB).",
                saved,
                JSONObject().put("size_bytes", bytes.size)
            )
        }
    }

    // ------------------------------------------------------------------
    // Storage helpers
    // ------------------------------------------------------------------

    private data class SavedFile(val uri: Uri, val displayName: String, val path: String?)

    private fun saveBytes(context: Context, fileName: String, mime: String, bytes: ByteArray): SavedFile? {
        val safe = safeFileName(fileName, "bin")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/JARVIS"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    return SavedFile(uri, safe, null)
                }
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "MediaStore save failed, trying app folder: ${t.message}")
            }
        }
        val dirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "JARVIS"),
            context.getExternalFilesDir(null),
            context.filesDir
        ).filterNotNull()
        for (dir in dirs) {
            try {
                if (!dir.exists() && !dir.mkdirs()) continue
                var file = File(dir, safe)
                var suffix = 1
                while (file.exists() && suffix < 1000) {
                    val base = safe.substringBeforeLast('.')
                    val ext = safe.substringAfterLast('.', "bin")
                    file = File(dir, "${base}_$suffix.$ext")
                    suffix++
                }
                file.writeBytes(bytes)
                if (file.parentFile?.absolutePath?.contains("Download") == true) {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(file.absolutePath),
                        arrayOf(mime),
                        null
                    )
                }
                return SavedFile(Uri.fromFile(file), file.name, file.absolutePath)
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun resolveFile(context: Context, raw: String): File? {
        val clean = raw.trim()
        if (clean.isEmpty()) return null
        val direct = File(clean)
        if (direct.exists()) return direct
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val candidates = listOf(
            File(File(downloads, "JARVIS"), clean),
            File(downloads, clean),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), clean),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), clean),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), clean),
            File(context.getExternalFilesDir(null), clean),
            File(context.filesDir, clean),
            File(context.cacheDir, clean)
        )
        return candidates.firstOrNull { it.exists() }
    }

    private fun fileUri(context: Context, file: File): Uri? {
        return try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (_: Throwable) {
            try {
                val cacheDir = File(context.cacheDir, "share").apply { mkdirs() }
                val copy = File(cacheDir, file.name)
                file.copyTo(copy, overwrite = true)
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    copy
                )
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun safeFileName(raw: String, fallbackExtension: String): String {
        var name = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
            .replace(Regex("\\s+"), " ")
            .take(120)
        if (name.isBlank()) name = "Jarvis_File"
        if (!name.contains('.')) name = "$name.$fallbackExtension"
        if (name.startsWith(".")) name = "Jarvis$name"
        return name
    }

    private fun mimeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                "txt", "md", "csv", "log" -> "text/plain"
                "json" -> "application/json"
                "html", "htm" -> "text/html"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                else -> "application/octet-stream"
            }
    }

    private fun success(
        message: String,
        saved: SavedFile?,
        extra: JSONObject? = null
    ): JSONObject = JSONObject().apply {
        put("success", true)
        put("message", message)
        if (saved != null) {
            put("file_name", saved.displayName)
            put("uri", saved.uri.toString())
            saved.path?.let { put("path", it) }
            put("saved_in", "Downloads/JARVIS")
        }
        if (extra != null) {
            val keys = extra.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, extra.opt(key))
            }
        }
    }

    private fun fail(message: String): JSONObject = JSONObject().apply {
        put("success", false)
        put("message", message)
    }
}
