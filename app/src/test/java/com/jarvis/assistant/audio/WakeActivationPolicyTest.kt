package com.jarvis.assistant.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeActivationPolicyTest {

    @Test
    fun `verification disabled always activates and never prompts`() {
        val d = WakeActivationPolicy.decide(
            ownerVerificationEnabled = false,
            hasProfile = false,
            hasAudio = false,
            extractedVector = false,
            isMatch = false
        )
        assertTrue(d.activate)
        assertFalse(d.promptEnrollment)
        assertFalse(d.inconclusive)
    }

    @Test
    fun `enabled without profile fails open and prompts once`() {
        val d = WakeActivationPolicy.decide(
            ownerVerificationEnabled = true,
            hasProfile = false,
            hasAudio = true,
            extractedVector = true,
            isMatch = false
        )
        assertTrue("unenrolled owner must never lock the wake word", d.activate)
        assertTrue(d.promptEnrollment)
        assertFalse(d.verifiedOwner)
    }

    @Test
    fun `enrolled owner with a matching wake buffer activates`() {
        val d = WakeActivationPolicy.decide(
            ownerVerificationEnabled = true,
            hasProfile = true,
            hasAudio = true,
            extractedVector = true,
            isMatch = true
        )
        assertTrue(d.activate)
        assertTrue(d.verifiedOwner)
        assertFalse(d.inconclusive)
    }

    @Test
    fun `enrolled owner with a definite mismatch is rejected`() {
        val d = WakeActivationPolicy.decide(
            ownerVerificationEnabled = true,
            hasProfile = true,
            hasAudio = true,
            extractedVector = true,
            isMatch = false
        )
        assertFalse("a confirmed stranger must not wake Jarvis", d.activate)
        assertFalse(d.verifiedOwner)
        assertFalse(d.inconclusive)
    }

    @Test
    fun `missing wake audio fails open for command audio verification`() {
        val d = WakeActivationPolicy.decide(
            ownerVerificationEnabled = true,
            hasProfile = true,
            hasAudio = false,
            extractedVector = false,
            isMatch = false
        )
        assertTrue(d.activate)
        assertTrue(d.inconclusive)
        assertFalse(d.verifiedOwner)
    }

    @Test
    fun `failed embedding extraction fails open instead of rejecting`() {
        val d = WakeActivationPolicy.decide(
            ownerVerificationEnabled = true,
            hasProfile = true,
            hasAudio = true,
            extractedVector = false,
            isMatch = false
        )
        assertTrue(d.activate)
        assertTrue(d.inconclusive)
    }

    @Test
    fun `enrollment prompt is throttled for six hours`() {
        val now = 10_000_000L
        assertTrue(WakeActivationPolicy.shouldPromptEnrollment(0L, now))
        assertFalse(WakeActivationPolicy.shouldPromptEnrollment(now - 1_000L, now))
        assertTrue(WakeActivationPolicy.shouldPromptEnrollment(now - 6 * 60 * 60 * 1000L, now))
        // Clock moved backwards: prompt rather than stay silent forever.
        assertTrue(WakeActivationPolicy.shouldPromptEnrollment(now + 5_000L, now))
    }
}
