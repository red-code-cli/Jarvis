package com.jarvis.harness.policy

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Tiny static file server for a finished web project, so the Agent Live finalize step can
 * always hand Jarvis Browser a real preview URL - even when the project is plain
 * `index.html` and the agent never started a dev server.
 *
 * Binds 127.0.0.1 on a free port and serves [root]. Path traversal is rejected; unknown
 * paths and directories without an index return 404. Pure (java.net/java.io) and
 * unit-testable on the JVM.
 */
class StaticPreviewServer(private val root: File) : Closeable {

    private var socket: ServerSocket? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    val port: Int get() = socket?.localPort ?: 0
    val url: String get() = "http://127.0.0.1:$port/"

    fun start(): String {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        socket = server
        running = true
        thread = Thread({ acceptLoop(server) }, "harness-preview").apply {
            isDaemon = true
            start()
        }
        return url
    }

    override fun close() {
        running = false
        runCatching { socket?.close() }
        socket = null
        thread = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val client = try {
                server.accept()
            } catch (_: Throwable) {
                break
            }
            runCatching { serve(client) }
            runCatching { client.close() }
        }
    }

    private fun serve(client: Socket) {
        val input = client.getInputStream().bufferedReader(StandardCharsets.UTF_8)
        val requestLine = input.readLine() ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        if (method != "GET" && method != "HEAD") {
            respond(client.getOutputStream(), 405, "Method Not Allowed", "text/plain", "Method not allowed".toByteArray())
            return
        }
        val rawPath = parts[1].substringBefore('?').substringBefore('#')
        val decoded = runCatching { URLDecoder.decode(rawPath, "UTF-8") }.getOrDefault(rawPath)
        val target = resolve(decoded)
        if (target == null || !target.isFile) {
            respond(client.getOutputStream(), 404, "Not Found", "text/plain", "Not found".toByteArray())
            return
        }
        val bytes = runCatching { target.readBytes() }.getOrNull()
        if (bytes == null) {
            respond(client.getOutputStream(), 500, "Internal Server Error", "text/plain", "Read error".toByteArray())
            return
        }
        val body = if (method == "HEAD") ByteArray(0) else bytes
        respond(client.getOutputStream(), 200, "OK", contentType(target.name), body, fullLength = bytes.size)
    }

    /** Resolves a request path under [root]; null rejects traversal and escapes. */
    private fun resolve(path: String): File? {
        val rootCanonical = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val relative = path.trimStart('/').ifBlank { "index.html" }
        var candidate = File(rootCanonical, relative).canonicalFile
        if (!candidate.toPath().startsWith(rootCanonical.toPath())) return null
        if (candidate.isDirectory) candidate = File(candidate, "index.html")
        return candidate
    }

    private fun respond(
        rawOut: java.io.OutputStream,
        status: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        fullLength: Int = body.size,
    ) {
        val out = BufferedOutputStream(rawOut)
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(fullLength).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(headers.toByteArray(StandardCharsets.UTF_8))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "txt" -> "text/plain; charset=utf-8"
        "xml" -> "application/xml; charset=utf-8"
        else -> "application/octet-stream"
    }
}
