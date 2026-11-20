package com.jarvis.assistant.audio

/**
 * Pure decision logic for the standby wake path.
 *
 * Rules (owner voice verification):
 *  - Verification is OFF or nobody is enrolled  -> always activate. An unenrolled user must
 *    never be locked out; the UI only asks them to enroll.
 *  - Verification is ON and a profile exists:
 *      * a definite speaker mismatch -> do NOT activate
 *      * missing/short wake audio or a failed embedding extraction -> activate anyway
 *        (fail-open). The live turn is still gated by streaming verification, so a stranger
 *        cannot run commands just because the small wake buffer could not be scored.
 *
 * Keeping this in a tiny pure object makes every branch unit-testable without Android.
 */
object WakeActivationPolicy {
    data class Decision(
        val activate: Boolean,
        val promptEnrollment: Boolean,
        val verifiedOwner: Boolean,
        val inconclusive: Boolean
    )

    fun decide(
        ownerVerificationEnabled: Boolean,
        hasProfile: Boolean,
        hasAudio: Boolean,
        extractedVector: Boolean,
        isMatch: Boolean
    ): Decision {
        if (!ownerVerificationEnabled) {
            return Decision(activate = true, promptEnrollment = false, verifiedOwner = false, inconclusive = false)
        }
        if (!hasProfile) {
            return Decision(activate = true, promptEnrollment = true, verifiedOwner = false, inconclusive = false)
        }
        if (!hasAudio || !extractedVector) {
            // The speaker model may still be downloading/warming up, or the wake utterance was
            // too short. Never kill the wake word for an infrastructure problem.
            return Decision(activate = true, promptEnrollment = false, verifiedOwner = false, inconclusive = true)
        }
        return Decision(
            activate = isMatch,
            promptEnrollment = false,
            verifiedOwner = isMatch,
            inconclusive = false
        )
    }

    /** Don't nag on every wake attempt: at most one enrollment prompt per window. */
    fun shouldPromptEnrollment(lastPromptAt: Long, now: Long, windowMs: Long = 6 * 60 * 60 * 1000L): Boolean {
        if (lastPromptAt <= 0L) return true
        return now - lastPromptAt >= windowMs || now < lastPromptAt
    }
}
