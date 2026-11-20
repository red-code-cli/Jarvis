package com.jarvis.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the wake-phrase normalisation/classification rules so the service and the tests can
 * never drift. The spotter can report the same phrase as "jarvis", "JARVIS" or reconstructed
 * BPE tokens such as "CO DE ▁RE D".
 */
class WakeKeywordNormalizerTest {

    @Test
    fun `normalises case, separators and whitespace`() {
        assertEquals("jarvis", WakeKeywordNormalizer.normalize("JARVIS"))
        assertEquals("code red", WakeKeywordNormalizer.normalize("code_red"))
        assertEquals("code red", WakeKeywordNormalizer.normalize("CODE_RED"))
        assertEquals("code red", WakeKeywordNormalizer.normalize("  Code   Red  "))
        assertEquals("ja r vi s", WakeKeywordNormalizer.normalize("▁JA R VI S"))
        assertEquals("", WakeKeywordNormalizer.normalize("   "))
    }

    @Test
    fun `persona wake word resolves isha and virus before jarvis`() {
        assertEquals("isha", WakeKeywordNormalizer.personaWakeWord("isha"))
        assertEquals("virus", WakeKeywordNormalizer.personaWakeWord("virus"))
        assertEquals("jarvis", WakeKeywordNormalizer.personaWakeWord("jarvis"))
        // Ordering: a phrase that contains more than one persona stays deterministic.
        assertEquals("isha", WakeKeywordNormalizer.personaWakeWord("isha jarvis"))
    }

    @Test
    fun `non-persona phrase has no persona wake word`() {
        assertEquals(null, WakeKeywordNormalizer.personaWakeWord("code red"))
        assertEquals(null, WakeKeywordNormalizer.personaWakeWord("table guard"))
        assertEquals(null, WakeKeywordNormalizer.personaWakeWord(""))
    }

    @Test
    fun `emergency phrases`() {
        assertTrue(WakeKeywordNormalizer.isEmergency("code red"))
        assertTrue(WakeKeywordNormalizer.isEmergency("emergency"))
        assertFalse(WakeKeywordNormalizer.isEmergency("jarvis"))
    }

    @Test
    fun `guard phrases and on-off state`() {
        assertTrue(WakeKeywordNormalizer.isGuard("table guard"))
        assertTrue(WakeKeywordNormalizer.isGuard("guard on"))
        assertTrue(WakeKeywordNormalizer.isGuard("guard off"))
        assertFalse(WakeKeywordNormalizer.isGuard("jarvis"))

        assertTrue(WakeKeywordNormalizer.guardEnabled("guard on"))
        assertFalse(WakeKeywordNormalizer.guardEnabled("guard off"))
    }
}
