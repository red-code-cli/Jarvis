package com.jarvis.assistant.audio

/**
 * Pure decision table for the per-utterance owner-voice gate.
 *
 * Kept free of Android types so the rule that decides whether microphone audio may
 * ever reach Gemini is unit-testable.
 */
object OwnerVoiceGate {

    /** Verification only applies when the owner enabled it and enrolled a profile. */
    fun required(enabled: Boolean, hasProfile: Boolean): Boolean = enabled && hasProfile

    /**
     * @param verified the current utterance was matched to the owner
     * @param inconclusive no usable embedding (too short / pure noise)
     *
     * A definite mismatch is the only case that must never reach Gemini. An
     * inconclusive utterance is allowed through so short commands still work, but
     * risky tools stay locked until a real match happens.
     */
    fun mayStream(
        enabled: Boolean,
        hasProfile: Boolean,
        verified: Boolean,
        inconclusive: Boolean,
    ): Boolean = when {
        !required(enabled, hasProfile) -> true
        verified -> true
        inconclusive -> true
        else -> false
    }
}
