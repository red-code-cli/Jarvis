package com.jarvis.assistant.notifications

/**
 * Shared rules for notification content safety:
 *  - never read out or auto-reply to one-time passwords, bank PINs or passwords;
 *  - never log notification bodies.
 *
 * Pure logic (no Android API) so it is unit-testable.
 */
object NotificationPolicy {

    /** True when the text looks like an OTP / PIN / password / verification secret. */
    fun isSensitive(text: String): Boolean {
        val lower = text.lowercase()
        if (Regex("\\b(otp|one[- ]time password|verification code|passcode|password)\\b")
                .containsMatchIn(lower)
        ) {
            return true
        }
        // A 4-8 digit code combined with a secret keyword is almost certainly a 2FA code.
        if (Regex("\\b\\d{4,8}\\b").containsMatchIn(text) &&
            Regex("\\b(code|pin|password|otp|verify|verification|secret)\\b").containsMatchIn(lower)
        ) {
            return true
        }
        // Bank-related terms with numbers (account guards, OTP references).
        if (Regex("\\b(bank|account|card|upi|netbanking)\\b").containsMatchIn(lower) &&
            Regex("\\b\\d{4,6}\\b").containsMatchIn(text)
        ) {
            return true
        }
        return false
    }
}
