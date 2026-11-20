package com.jarvis.assistant.audio

/**
 * Pure table for the "user talks over Jarvis" decision.
 *
 * Energy alone is not enough: wind, a TV or the speaker's own echo can cross the amplitude
 * floor. A barge-in now requires:
 *  - sustained voiced frames for the selected sensitivity (min duration),
 *  - energy above both the adaptive room floor and the assistant playback echo floor,
 *  - enough high-frequency content to rule out low-band wind rumble,
 *  - and, when owner-only mode is on, a positive speaker match for the barge-in audio.
 */
object BargeInPolicy {

    enum class Action { IGNORE, INTERRUPT, VERIFY }

    data class Snapshot(
        val ownerVerificationRequired: Boolean,
        val ownerAlreadyVerified: Boolean,
        val graceElapsed: Boolean,
        val amplitude: Float,
        val echoFloor: Float,
        val highPassRms: Float,
        val consecutiveVoicedFrames: Int,
        val minFrames: Int,
        val verificationInFlight: Boolean,
        val rejectedRecently: Boolean
    )

    fun decide(s: Snapshot): Action {
        if (!s.graceElapsed) return Action.IGNORE
        if (s.consecutiveVoicedFrames < s.minFrames) return Action.IGNORE
        if (s.amplitude <= s.echoFloor) return Action.IGNORE
        // Wind/rumble: strong broadband level but almost no energy above ~120 Hz.
        val windFloor = maxOf(0.015f, s.echoFloor * 0.35f)
        if (s.highPassRms < windFloor) return Action.IGNORE
        if (!s.ownerVerificationRequired || s.ownerAlreadyVerified) return Action.INTERRUPT
        if (s.verificationInFlight || s.rejectedRecently) return Action.IGNORE
        return Action.VERIFY
    }

    /**
     * Only a real owner embedding may interrupt. A failed extraction is NOT permission to
     * barge in (that would let noise stop Jarvis), unlike the wake path where fail-open is
     * required so an unenrolled/misconfigured user is never locked out.
     */
    fun verificationAllowsInterrupt(matched: Boolean, extractedVector: Boolean): Boolean =
        matched && extractedVector
}
