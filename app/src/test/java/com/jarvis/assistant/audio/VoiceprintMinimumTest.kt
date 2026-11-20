package com.jarvis.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the single shared minimum audio length used by the extractor, the live
 * early-verification window and the enrollment UI (bugs 2.1 / 2.2 / 4.1) so the three
 * call sites can never drift apart again.
 */
class VoiceprintMinimumTest {

    @Test
    fun minimumIsThreeHundredMillisecondsOfMonoPcm16() {
        val expected = (16_000 * 0.3 * 2).toInt() // 9,600 bytes
        assertEquals(expected, SherpaVoiceprint.MIN_BYTES)
    }

    @Test
    fun earlyVerificationFrameWindowReachesTheSharedMinimum() {
        // JarvisConversationService.EARLY_VERIFY_VOICED_FRAMES is MIN_BYTES / CHUNK_SIZE_BYTES.
        assertEquals(15, SherpaVoiceprint.MIN_BYTES / AudioRecorder.CHUNK_SIZE_BYTES)
        assertTrue(15 * AudioRecorder.CHUNK_SIZE_BYTES >= SherpaVoiceprint.MIN_BYTES)
    }
}
