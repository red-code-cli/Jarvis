package com.jarvis.assistant.audio

/**
 * Translates the "Wake sensitivity" setting into concrete detector thresholds.
 *
 * sherpa-onnx keyword spotting exposes two knobs that decide how easily a phrase fires:
 *  - `keywordsScore`: how strongly the keyword tokens are boosted. Higher = easier to hit.
 *  - `keywordsThreshold`: minimum keyword posterior before a hit counts. Lower = easier.
 *
 * The profile also carries the energy floor used by the tiny VAD in [SherpaWakeEngine], so a
 * faint, far-field hit is accepted exactly as far as the user asked it to be. The owner
 * **verification** threshold (`AppPreferences.ownerVoiceThreshold`) is a separate setting and
 * is never derived from this one.
 */
object WakeSensitivity {

    /**
     * @param level value stored in preferences (`high` / `balanced` / `low`)
     * @param keywordScore sherpa-onnx keyword boosting score
     * @param keywordThreshold sherpa-onnx keyword posterior threshold
     * @param minRms absolute energy floor for a wake utterance (mono PCM16 @16 kHz RMS)
     * @param vadMargin how far above the tracked noise floor speech must sit to count as voiced
     */
    data class Profile(
        val level: String,
        val keywordScore: Float,
        val keywordThreshold: Float,
        val minRms: Float,
        val vadMargin: Float,
    )

    /** "High (Far-field)": keep the detector loose so a distant or soft wake word still lands. */
    private val HIGH = Profile(
        level = "high",
        keywordScore = 2.0f,
        keywordThreshold = 0.16f,
        minRms = 26f,
        vadMargin = 2.0f,
    )

    /** "Balanced": the tuned default for a normal room. */
    private val BALANCED = Profile(
        level = "balanced",
        keywordScore = 1.5f,
        keywordThreshold = 0.25f,
        minRms = 45f,
        vadMargin = 2.6f,
    )

    /**
     * "Low (Strict)": a real acoustic hit plus real speech energy, so shop/TV noise does not
     * wake Jarvis. This is the only profile that makes false wakes materially rarer.
     */
    private val LOW = Profile(
        level = "low",
        keywordScore = 1.0f,
        keywordThreshold = 0.40f,
        minRms = 75f,
        vadMargin = 3.2f,
    )

    fun profile(level: String?): Profile = when (level) {
        "high" -> HIGH
        "low" -> LOW
        else -> BALANCED
    }
}