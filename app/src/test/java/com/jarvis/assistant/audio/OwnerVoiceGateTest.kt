package com.jarvis.assistant.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerVoiceGateTest {

    @Test
    fun disabledOrUnenrolledAllowsEverything() {
        assertFalse(OwnerVoiceGate.required(enabled = false, hasProfile = true))
        assertFalse(OwnerVoiceGate.required(enabled = true, hasProfile = false))
        assertTrue(OwnerVoiceGate.mayStream(false, true, verified = false, inconclusive = false))
        assertTrue(OwnerVoiceGate.mayStream(true, false, verified = false, inconclusive = false))
    }

    @Test
    fun unverifiedUtteranceIsNeverStreamed() {
        assertTrue(OwnerVoiceGate.required(enabled = true, hasProfile = true))
        assertFalse(OwnerVoiceGate.mayStream(true, true, verified = false, inconclusive = false))
    }

    @Test
    fun verifiedUtteranceStreamsAndInconclusiveIsAllowedButToolsStayLocked() {
        assertTrue(OwnerVoiceGate.mayStream(true, true, verified = true, inconclusive = false))
        assertTrue(OwnerVoiceGate.mayStream(true, true, verified = false, inconclusive = true))
    }
}
