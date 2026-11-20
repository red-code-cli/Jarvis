package com.jarvis.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin

class EnrollmentQualityTest {

    /** amplitude is in raw PCM16 units (0..32767). */
    private fun pcm(ms: Int, amplitude: Double, freq: Double = 180.0): ByteArray {
        val samples = 16_000 * ms / 1000
        val out = ByteArrayOutputStream()
        for (i in 0 until samples) {
            val v = (sin(2 * PI * freq * i / 16_000.0) * amplitude).toInt()
                .coerceIn(-32768, 32767)
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        return out.toByteArray()
    }

    /**
     * Speech-like signal: loud syllable bursts separated by low-energy gaps, so the
     * room-floor-relative voiced detector sees real dynamics (a constant-amplitude
     * tone would be (correctly) read as noise/hum and rejected).
     */
    private fun speechLikePcm(ms: Int, amplitude: Double, freq: Double = 180.0): ByteArray {
        val samples = 16_000 * ms / 1000
        val burst = 80 // ms: 4 loud frames
        val gap = 40 // ms: 2 near-silent frames
        val cycle = burst + gap
        val out = ByteArrayOutputStream()
        for (i in 0 until samples) {
            val phase = (i * 1000 / 16_000) % cycle
            val amp = if (phase < burst) amplitude else amplitude * 0.005
            val v = (sin(2 * PI * freq * i / 16_000.0) * amp).toInt()
                .coerceIn(-32768, 32767)
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        return out.toByteArray()
    }

    @Test
    fun `normal speech passes`() {
        val result = EnrollmentQuality.evaluate(speechLikePcm(2_000, 8_000.0))
        assertTrue(result.hinglishMessage(), result.ok)
        assertEquals(EnrollmentQuality.Reason.OK, result.reason)
    }

    @Test
    fun `short clip is rejected`() {
        val result = EnrollmentQuality.evaluate(pcm(600, 8_000.0))
        assertFalse(result.ok)
        assertEquals(EnrollmentQuality.Reason.TOO_SHORT, result.reason)
    }

    @Test
    fun `too long clip is rejected`() {
        val result = EnrollmentQuality.evaluate(pcm(9_000, 8_000.0))
        assertFalse(result.ok)
        assertEquals(EnrollmentQuality.Reason.TOO_LONG, result.reason)
    }

    @Test
    fun `silence is rejected`() {
        val result = EnrollmentQuality.evaluate(pcm(2_000, 5.0))
        assertFalse(result.ok)
        assertTrue(
            result.reason == EnrollmentQuality.Reason.TOO_QUIET ||
                result.reason == EnrollmentQuality.Reason.NO_SPEECH
        )
    }

    @Test
    fun `clipped audio is rejected`() {
        val result = EnrollmentQuality.evaluate(pcm(2_000, 32_767.0))
        assertFalse(result.ok)
        assertEquals(EnrollmentQuality.Reason.CLIPPING, result.reason)
    }

    @Test
    fun `empty buffer is rejected`() {
        val result = EnrollmentQuality.evaluate(ByteArray(0))
        assertFalse(result.ok)
        assertEquals(EnrollmentQuality.Reason.TOO_SHORT, result.reason)
    }
}
