package com.jarvis.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the pure energy-VAD math used by the standby wake detector. These rules decide whether
 * a keyword hit is backed by real speech energy, so a loud room or a stray transient cannot
 * fake a wake.
 */
class WakeVadTest {

    private val balanced = WakeSensitivity.profile("balanced")

    @Test
    fun `threshold is the larger of the absolute floor and the noise-scaled bar`() {
        // Quiet room: the absolute minRms floor dominates.
        assertEquals(balanced.minRms, WakeVad.threshold(0f, balanced), 0.001f)
        // Noisy room: noiseFloor * vadMargin dominates.
        val floor = 100f
        assertEquals(floor * balanced.vadMargin, WakeVad.threshold(floor, balanced), 0.001f)
    }

    @Test
    fun `noise floor is capped so a loud room cannot scale the bar past speech`() {
        val huge = 10_000f
        val capped = WakeVad.threshold(huge, balanced)
        assertEquals(WakeVad.NOISE_FLOOR_CAP * balanced.vadMargin, capped, 0.001f)
    }

    @Test
    fun `isVoiced accepts clear speech and rejects faint hits`() {
        assertTrue(WakeVad.isVoiced(400f, 50f, balanced))
        assertFalse(WakeVad.isVoiced(20f, 50f, balanced))
    }

    @Test
    fun `isVoiced is stricter as the sensitivity level gets lower`() {
        val peak = 70f
        val floor = 5f
        assertTrue(WakeVad.isVoiced(peak, floor, WakeSensitivity.profile("high")))
        assertFalse(WakeVad.isVoiced(peak, floor, WakeSensitivity.profile("low")))
    }

    @Test
    fun `noise floor drops fast below and creeps up slowly above`() {
        val floor = 100f
        val dropped = WakeVad.nextNoiseFloor(floor, 10f)
        val crept = WakeVad.nextNoiseFloor(floor, 1000f)
        assertTrue(dropped < floor)          // follows a quieter moment quickly
        assertTrue(crept > floor)            // climbs slowly toward loud noise
        assertTrue(crept - floor < floor - dropped) // up is much slower than down
    }

    @Test
    fun `noise floor ignores silent frames and adopts the first real level`() {
        assertEquals(100f, WakeVad.nextNoiseFloor(100f, 0f), 0.001f)
        assertEquals(42f, WakeVad.nextNoiseFloor(0f, 42f), 0.001f)
    }
}
