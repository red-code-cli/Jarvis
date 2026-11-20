package com.jarvis.assistant.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin

class AudioFiltersTest {

    private fun tone(hz: Double, amplitude: Double = 0.7, ms: Int = 200): ByteArray {
        val samples = 16_000 * ms / 1000
        val out = ByteArrayOutputStream()
        for (i in 0 until samples) {
            val v = (sin(2 * PI * hz * i / 16_000.0) * amplitude * 32767).toInt()
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        return out.toByteArray()
    }

    @Test
    fun `high pass keeps voice band and removes rumble`() {
        val rumble = AudioFilters.highPassRms16k(tone(50.0))
        val voice = AudioFilters.highPassRms16k(tone(1_000.0))
        assertTrue("voice band must survive: voice=$voice rumble=$rumble", voice > rumble * 3f)
        assertTrue(voice > 0.05f)
    }

    @Test
    fun `silence has no energy`() {
        assertTrue(AudioFilters.highPassRms16k(ByteArray(640)) < 0.0001f)
    }

    @Test
    fun `clipping detector sees a loud square-ish signal`() {
        val pcm = ByteArray(640) { 0x7F }
        assertTrue(AudioFilters.clippingRatio(pcm) > 0.9f)
    }
}
