package com.jarvis.harness

/**
 * Provider failure taxonomy. The UI/voice layer must never say a bare
 * "Connection failed": every failure carries a provider, an HTTP status (when the
 * provider reported one), a machine-readable kind, and the action the user can take.
 */
enum class HarnessErrorKind {
    RATE_LIMIT,
    INVALID_KEY,
    NETWORK,
    SERVER,
    TIMEOUT,
    UNKNOWN,
    ;

    /** Whether retrying later can plausibly succeed without user action. */
    val retryable: Boolean
        get() = this == RATE_LIMIT || this == NETWORK || this == SERVER || this == TIMEOUT
}

data class HarnessError(
    val kind: HarnessErrorKind,
    /** Human name of the coding provider, e.g. "Anthropic API", "OpenRouter". */
    val provider: String,
    val httpStatus: Int?,
    /** Trimmed, secret-scrubbed provider text. Safe to show and to speak. */
    val raw: String,
    /** One short instruction for what to do next. */
    val hint: String,
) {
    /** Compact one-line label: "Anthropic API · HTTP 429 · Rate limited". */
    val headline: String
        get() = buildString {
            append(provider.ifBlank { "Provider" })
            httpStatus?.let { append(" · HTTP ").append(it) }
            append(" · ").append(
                when (kind) {
                    HarnessErrorKind.RATE_LIMIT -> "Rate limited"
                    HarnessErrorKind.INVALID_KEY -> "Key rejected"
                    HarnessErrorKind.NETWORK -> "Network error"
                    HarnessErrorKind.SERVER -> "Provider error"
                    HarnessErrorKind.TIMEOUT -> "Timed out"
                    HarnessErrorKind.UNKNOWN -> "Failed"
                },
            )
        }

    /** What Jarvis says out loud: provider + status + action, never generic. */
    val spoken: String
        get() = buildString {
            append(headline)
            append(". ")
            append(hint)
            if (raw.isNotBlank()) append(" Provider said: ").append(raw.take(160))
        }

    companion object {
        fun classify(
            reason: String,
            provider: String,
            fallbackKind: HarnessErrorKind? = null,
        ): HarnessError {
            val text = reason.lowercase()
            val status = Regex("""\b(?:http|status|error[_\s-]?code)?\s*:?\s*(\d{3})\b""")
                .findAll(text)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .firstOrNull { it in 400..599 }

            val kind = when {
                status == 429 -> HarnessErrorKind.RATE_LIMIT
                status == 401 || status == 403 -> HarnessErrorKind.INVALID_KEY
                status != null && status >= 500 -> HarnessErrorKind.SERVER
                "rate limit" in text || "rate_limit" in text || "quota" in text ||
                    "too many requests" in text || "resource_exhausted" in text -> HarnessErrorKind.RATE_LIMIT
                "invalid api key" in text || "authentication" in text ||
                    "unauthorized" in text || "user not found" in text ||
                    "api key" in text || "expired" in text -> HarnessErrorKind.INVALID_KEY
                "timed out" in text || "timeout" in text -> HarnessErrorKind.TIMEOUT
                "unable to resolve host" in text || "no address associated" in text ||
                    "connection reset" in text || "connection refused" in text ||
                    "network" in text || "unknownhost" in text ||
                    "ssl" in text || "offline" in text -> HarnessErrorKind.NETWORK
                status != null -> HarnessErrorKind.SERVER
                else -> fallbackKind ?: HarnessErrorKind.UNKNOWN
            }

            val hint = when (kind) {
                HarnessErrorKind.RATE_LIMIT ->
                    "The provider rate limit is exhausted. I will retry automatically; a different API key also helps."
                HarnessErrorKind.INVALID_KEY ->
                    "This API key is invalid or out of credit. Add or switch the key in Settings, Coding Engine, Custom API."
                HarnessErrorKind.NETWORK ->
                    "The network dropped. I will retry when it is back."
                HarnessErrorKind.SERVER ->
                    "The provider is having a server problem. I will retry automatically."
                HarnessErrorKind.TIMEOUT ->
                    "The provider took too long to answer. I will retry."
                HarnessErrorKind.UNKNOWN ->
                    "Check the provider settings in Settings, Coding Engine, Custom API."
            }

            return HarnessError(
                kind = kind,
                provider = provider,
                httpStatus = status,
                raw = scrub(reason),
                hint = hint,
            )
        }

        /** Removes secrets and collapses whitespace before anything is stored or spoken. */
        fun scrub(value: String): String = value
            .replace(Regex("(?i)bearer\\s+\\S+"), "Bearer ••••")
            .replace(Regex("(?i)sk-[a-z0-9_-]{8,}"), "sk-••••")
            .replace(Regex("(?i)key[_-]?[a-z0-9]{16,}"), "key ••••")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(280)
    }
}