package com.jarvis.harness.gateway

/** Wire protocol an upstream provider speaks. The local gateway translates between these. */
enum class GatewayWire { OPENAI_CHAT, ANTHROPIC }

/**
 * One (provider, model, key) the gateway may try, in the user's priority order.
 *
 * This never holds a secret: the plaintext key lives only in [com.jarvis.harness.data.ApiKeyVault]
 * and is fetched by the host at request time through the route resolver.
 */
data class GatewayTarget(
    val providerId: String,
    val providerName: String,
    val model: String,
    val keyId: String,
    val keyName: String,
    val baseUrl: String,
    val wire: GatewayWire,
) {
    /** Stable identity of a single key+model attempt within a provider. */
    val key: String get() = "$providerId\u0000$keyId\u0000$model"

    /** Short label for the Agent Live feed, e.g. "OpenRouter · claude-sonnet". */
    val label: String
        get() = when {
            model.isBlank() || model == providerName -> providerName
            else -> "$providerName · $model"
        }
}

/** What the gateway should do after an upstream failure. */
enum class FailureClass {
    /** Switch to the next healthy (provider, model, key) and replay the same request. */
    SWITCH,

    /** A bad request that another provider cannot fix; return the upstream error as-is. */
    FATAL,
}

/** A classified upstream failure. [message] is already scrubbed of any secret. */
data class GatewayFailure(
    val status: Int?,
    val networkError: Boolean,
    val message: String,
    val failureClass: FailureClass,
)

/** Read-only view of one target's breaker state, for the health endpoint and the UI. */
data class CircuitSnapshot(
    val open: Boolean,
    val consecutiveFailures: Int,
    val cooldownRemainingMillis: Long,
)
