package com.jarvis.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BargeInPolicyTest {

    private fun snapshot(
        required: Boolean = true,
        verified: Boolean = false,
        grace: Boolean = true,
        amplitude: Float = 0.4f,
        floor: Float = 0.15f,
        hp: Float = 0.12f,
        frames: Int = 15,
        minFrames: Int = 15,
        inFlight: Boolean = false,
        rejectedRecently: Boolean = false
    ) = BargeInPolicy.Snapshot(
        ownerVerificationRequired = required,
        ownerAlreadyVerified = verified,
        graceElapsed = grace,
        amplitude = amplitude,
        echoFloor = floor,
        highPassRms = hp,
        consecutiveVoicedFrames = frames,
        minFrames = minFrames,
        verificationInFlight = inFlight,
        rejectedRecently = rejectedRecently
    )

    @Test
    fun `short blip is ignored`() {
        assertEquals(BargeInPolicy.Action.IGNORE, BargeInPolicy.decide(snapshot(frames = 5)))
    }

    @Test
    fun `energy below echo floor is ignored`() {
        assertEquals(BargeInPolicy.Action.IGNORE, BargeInPolicy.decide(snapshot(amplitude = 0.1f)))
    }

    @Test
    fun `wind rumble with no high frequency energy is ignored`() {
        assertEquals(
            BargeInPolicy.Action.IGNORE,
            BargeInPolicy.decide(snapshot(amplitude = 0.9f, hp = 0.005f))
        )
    }

    @Test
    fun `owner not required interrupts on sustained speech`() {
        assertEquals(
            BargeInPolicy.Action.INTERRUPT,
            BargeInPolicy.decide(snapshot(required = false))
        )
    }

    @Test
    fun `already verified owner interrupts without another model pass`() {
        assertEquals(
            BargeInPolicy.Action.INTERRUPT,
            BargeInPolicy.decide(snapshot(verified = true))
        )
    }

    @Test
    fun `unverified speaker is checked before interrupting`() {
        assertEquals(BargeInPolicy.Action.VERIFY, BargeInPolicy.decide(snapshot()))
    }

    @Test
    fun `no parallel verification while one is running`() {
        assertEquals(
            BargeInPolicy.Action.IGNORE,
            BargeInPolicy.decide(snapshot(inFlight = true))
        )
    }

    @Test
    fun `recently rejected voice cannot retry every 300 ms`() {
        assertEquals(
            BargeInPolicy.Action.IGNORE,
            BargeInPolicy.decide(snapshot(rejectedRecently = true))
        )
    }

    @Test
    fun `only a real owner embedding may interrupt`() {
        assertTrue(BargeInPolicy.verificationAllowsInterrupt(matched = true, extractedVector = true))
        assertFalse(BargeInPolicy.verificationAllowsInterrupt(matched = false, extractedVector = true))
        assertFalse("noise must not barge in when extraction failed", BargeInPolicy.verificationAllowsInterrupt(matched = false, extractedVector = false))
        assertFalse(BargeInPolicy.verificationAllowsInterrupt(matched = true, extractedVector = false))
    }
}
