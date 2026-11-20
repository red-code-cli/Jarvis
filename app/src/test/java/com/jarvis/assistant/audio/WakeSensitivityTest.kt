package com.jarvis.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the wake-sensitivity threshold table. The settings UI stores the level string
 * (`high` / `balanced` / `low`); these tests make sure the mapping keeps its shape and the
 * three profiles stay strictly ordered (high easier than balanced easier than low).
 */
class WakeSensitivityTest {

    @Test
    fun `unknown or null level falls back to balanced`() {
        assertEquals("balanced", WakeSensitivity.profile(null).level)
        assertEquals("balanced", WakeSensitivity.profile("nonsense").level)
        assertEquals("balanced", WakeSensitivity.profile("balanced").level)
    }

    @Test
    fun `levels map to their stored strings`() {
        assertEquals("high", WakeSensitivity.profile("high").level)
        assertEquals("low", WakeSensitivity.profile("low").level)
    }

    @Test
    fun `presets stay strictly ordered`() {
        val high = WakeSensitivity.profile("high")
        val balanced = WakeSensitivity.profile("balanced")
        val low = WakeSensitivity.profile("low")

        // Keyword boosting: high is boosted most, low least.
        assertTrue(high.keywordScore > balanced.keywordScore)
        assertTrue(balanced.keywordScore > low.keywordScore)

        // Posterior threshold: high fires most easily, low requires the strongest evidence.
        assertTrue(high.keywordThreshold < balanced.keywordThreshold)
        assertTrue(balanced.keywordThreshold < low.keywordThreshold)

        // Energy floor: high accepts the quietest utterance, low the loudest.
        assertTrue(high.minRms < balanced.minRms)
        assertTrue(balanced.minRms < low.minRms)

        // Noise-floor margin: same ordering.
        assertTrue(high.vadMargin < balanced.vadMargin)
        assertTrue(balanced.vadMargin < low.vadMargin)
    }

    @Test
    fun `no profile accepts a dead-silence hit`() {
        // A zero-energy peak must never count as voiced, on any profile.
        assertFalse(WakeVad.isVoiced(0f, 0f, WakeSensitivity.profile("high")))
        assertFalse(WakeVad.isVoiced(0f, 0f, WakeSensitivity.profile("low")))
    }
}
