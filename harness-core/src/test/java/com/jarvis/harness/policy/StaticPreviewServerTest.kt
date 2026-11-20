package com.jarvis.harness.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.Socket

class StaticPreviewServerTest {

    private fun tempRoot(): File = File.createTempFile("preview", "").let { it.delete(); it.mkdirs(); it }

    private fun rawRequest(port: Int, target: String): String {
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("GET $target HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            return socket.getInputStream().readBytes().decodeToString()
        }
    }

    @Test
    fun servesIndexAtRoot() {
        val root = tempRoot()
        File(root, "index.html").writeText("<h1>preview works</h1>")
        val server = StaticPreviewServer(root)
        try {
            server.start()
            val response = rawRequest(server.port, "/")
            assertTrue(response.startsWith("HTTP/1.1 200"))
            assertTrue(response.contains("preview works"))
            assertTrue(response.contains("text/html"))
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun servesNestedAsset() {
        val root = tempRoot()
        File(root, "css").mkdirs()
        File(root, "css/site.css").writeText("body{color:red}")
        val server = StaticPreviewServer(root)
        try {
            server.start()
            val response = rawRequest(server.port, "/css/site.css")
            assertTrue(response.startsWith("HTTP/1.1 200"))
            assertTrue(response.contains("body{color:red}"))
            assertTrue(response.contains("text/css"))
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun missingPathIs404() {
        val root = tempRoot()
        val server = StaticPreviewServer(root)
        try {
            server.start()
            assertTrue(rawRequest(server.port, "/nope.html").startsWith("HTTP/1.1 404"))
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun pathTraversalIsRejected() {
        val root = tempRoot()
        val outside = File(root.parentFile, "secret-${root.name}.txt")
        outside.writeText("top secret")
        val server = StaticPreviewServer(root)
        try {
            server.start()
            val response = rawRequest(server.port, "/%2e%2e/${outside.name}")
            // Either rejected (404/405) or, at worst, never the secret content.
            assertTrue(response.startsWith("HTTP/1.1 404") || response.startsWith("HTTP/1.1 405"))
            assertTrue(!response.contains("top secret"))
        } finally {
            server.close()
            outside.delete()
            root.deleteRecursively()
        }
    }

    @Test
    fun urlUsesLoopbackAndAssignedPort() {
        val root = tempRoot()
        val server = StaticPreviewServer(root)
        try {
            val url = server.start()
            assertTrue(url.startsWith("http://127.0.0.1:"))
            assertEquals(url, server.url)
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }
}
