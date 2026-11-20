package com.jarvis.assistant.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verifies the SHA-256 hashing that gates installation. A mismatch against the manifest hash
 * must abort the install, so this hashing must be exact and deterministic.
 */
class ApkVerifierHashTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun emptyFileHasKnownSha256() {
        val f = folder.newFile("empty.bin")
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ApkVerifier.sha256(f),
        )
    }

    @Test
    fun knownContentHasKnownSha256() {
        val f = folder.newFile("hello.bin")
        f.writeBytes("hello".toByteArray())
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            ApkVerifier.sha256(f),
        )
    }

    @Test
    fun differentBytesGiveDifferentHash() {
        val a = folder.newFile("a.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val b = folder.newFile("b.bin").apply { writeBytes(byteArrayOf(1, 2, 4)) }
        assertNotEquals(ApkVerifier.sha256(a), ApkVerifier.sha256(b))
    }

    @Test
    fun hashIsLowercaseHexOf64Chars() {
        val f = folder.newFile("x.bin").apply { writeBytes("jarvis".toByteArray()) }
        val h = ApkVerifier.sha256(f)
        assertEquals(64, h.length)
        assertTrue(h.all { it.isDigit() || it in 'a'..'f' })
    }
}
