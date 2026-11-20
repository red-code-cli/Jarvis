package com.jarvis.assistant.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the update manifest parser. Covers the "server sends garbage" paths
 * that must resolve to null (= fail-open, never lock the app).
 */
class UpdateManifestTest {

    private val goodSha = "a".repeat(64)

    private fun json(
        latestCode: Int = 200,
        apkUrl: String = "https://example.com/jarvis.apk",
        sha: String = goodSha,
        mandatory: Boolean = false,
        minSupported: Int = 0,
        latestName: String = "1.16.0",
    ): String = """
        {
          "latestVersionCode": $latestCode,
          "latestVersionName": "$latestName",
          "minSupportedVersionCode": $minSupported,
          "apkUrl": "$apkUrl",
          "apkSha256": "$sha",
          "apkSizeBytes": 12345,
          "releaseNotes": "Naya update",
          "mandatory": $mandatory,
          "publishedAt": "2026-11-20"
        }
    """.trimIndent()

    @Test
    fun parsesValidManifest() {
        val m = UpdateManifest.fromJson(json())
        assertEquals(200, m?.latestVersionCode)
        assertEquals("1.16.0", m?.latestVersionName)
        assertEquals("https://example.com/jarvis.apk", m?.apkUrl)
        assertEquals(goodSha, m?.apkSha256)
        assertEquals(12345L, m?.apkSizeBytes)
        assertEquals(false, m?.mandatory)
        assertEquals("Naya update", m?.releaseNotes)
    }

    @Test
    fun blankOrNullIsRejected() {
        assertNull(UpdateManifest.fromJson(null))
        assertNull(UpdateManifest.fromJson(""))
        assertNull(UpdateManifest.fromJson("   "))
    }

    @Test
    fun malformedJsonIsRejected() {
        assertNull(UpdateManifest.fromJson("{ not json"))
        assertNull(UpdateManifest.fromJson("[1,2,3]"))
    }

    @Test
    fun nonHttpsApkUrlIsRejected() {
        assertNull(UpdateManifest.fromJson(json(apkUrl = "http://example.com/jarvis.apk")))
        assertNull(UpdateManifest.fromJson(json(apkUrl = "ftp://example.com/jarvis.apk")))
        assertNull(UpdateManifest.fromJson(json(apkUrl = "")))
    }

    @Test
    fun badShaIsRejected() {
        assertNull(UpdateManifest.fromJson(json(sha = "abc")))
        assertNull(UpdateManifest.fromJson(json(sha = "z".repeat(64))))
        assertNull(UpdateManifest.fromJson(json(sha = "")))
    }

    @Test
    fun nonPositiveVersionCodeIsRejected() {
        assertNull(UpdateManifest.fromJson(json(latestCode = 0)))
        assertNull(UpdateManifest.fromJson(json(latestCode = -5)))
    }

    @Test
    fun shaIsNormalisedToLowerCase() {
        val upper = "A".repeat(64)
        val m = UpdateManifest.fromJson(json(sha = upper))
        assertEquals("a".repeat(64), m?.apkSha256)
    }

    @Test
    fun missingOptionalFieldsFallBackToDefaults() {
        val raw = """{"latestVersionCode":200,"apkUrl":"https://example.com/a.apk","apkSha256":"$goodSha"}"""
        val m = UpdateManifest.fromJson(raw)
        assertEquals(200, m?.latestVersionCode)
        assertEquals(0, m?.minSupportedVersionCode)
        assertEquals(0L, m?.apkSizeBytes)
        assertEquals(false, m?.mandatory)
    }

    @Test
    fun isHttpsAcceptsOnlyHttps() {
        assertTrue(UpdateManifest.isHttps("https://x/y"))
        assertTrue(UpdateManifest.isHttps("HTTPS://x/y"))
        assertFalse(UpdateManifest.isHttps("http://x/y"))
        assertFalse(UpdateManifest.isHttps("//x/y"))
        assertFalse(UpdateManifest.isHttps(""))
    }

    @Test
    fun isValidSha256ChecksLengthAndHex() {
        assertTrue(UpdateManifest.isValidSha256("0123456789abcdef".repeat(4)))
        assertFalse(UpdateManifest.isValidSha256("0123456789abcdef".repeat(3)))
        assertFalse(UpdateManifest.isValidSha256("g".repeat(64)))
    }
}
