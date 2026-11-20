package com.jarvis.assistant.audio

/**
 * Pure energy-VAD math shared by the standby wake detector ([SherpaWakeEngine]).
 *
 * Kept free of Android types so the decision that turns a raw frame RMS into a
 * "this is speech, not room noise" verdict is unit-testable and can never drift
 * away from the threshold table in [WakeSensitivity].
 *
 * The rule is unchanged from the original inline implementation:
 *  - the noise floor follows quiet quickly and climbs slowly, so steady room noise
 *    is learned but a burst of speech does not raise the bar under itself;
 *  - a hit counts only when the recent peak energy clears both an absolute floor
 *    (from the sensitivity profile) and `noiseFloor * vadMargin`;
 *  - the noise floor is capped so a loud room cannot push the bar above speech.
 */
object WakeVad {

    /**
     * Above this the room is loud enough that the absolute floor is already doing the
     * work; without the cap a noisy room would scale the bar up faster than speech can
     * follow.
     */
    const val NOISE_FLOOR_CAP = 600f

    /** How far above the tracked noise floor speech must sit for this profile. */
    fun threshold(noiseFloor: Float, profile: WakeSensitivity.Profile): Float {
        val floor = noiseFloor.coerceAtMost(NOISE_FLOOR_CAP)
        return maxOf(profile.minRms, floor * profile.vadMargin)
    }

    /** Did the wake utterance carry speech-level energy for [profile] given the learned floor? */
    fun isVoiced(peakRms: Float, noiseFloor: Float, profile: WakeSensitivity.Profile): Boolean =
        peakRms >= threshold(noiseFloor, profile)

    /**
     * Next value of the adaptive noise floor. A non-positive [rms] leaves the floor
     * untouched; otherwise the floor drops fast when [rms] is below it and creeps up
     * slowly when it is above.
     */
    fun nextNoiseFloor(current: Float, rms: Float): Float = when {
        rms <= 0f -> current
        current <= 0f -> rms
        rms < current -> current * 0.7f + rms * 0.3f
        else -> current * 0.999f + rms * 0.001f
    }
}
