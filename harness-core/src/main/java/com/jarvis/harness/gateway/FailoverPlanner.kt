package com.jarvis.harness.gateway

/**
 * Pure failover brain for the local gateway.
 *
 * All decisions are time-injected ([nowMillis] is passed in) and free of Android APIs, so the
 * whole policy — which failures switch, how long a key cools down, when a circuit opens — is
 * unit-tested without a device or a network.
 *
 * Behaviour:
 *  - 429 / quota / 401 / 403 / 408 / 402 / 5xx / timeout / network error → [FailureClass.SWITCH].
 *  - A key that keeps failing gets an exponential per-key cooldown so it is skipped quickly.
 *  - A target that keeps failing opens a circuit breaker; it is probed again only after the
 *    breaker window elapses (half-open), and a success closes it again.
 */
class FailoverPlanner(
    private val maxConsecutiveFailures: Int = 3,
    private val circuitOpenMillis: Long = 60_000L,
    private val rateLimitCooldownMillis: Long = 30_000L,
    private val authCooldownMillis: Long = 300_000L,
    private val quotaCooldownMillis: Long = 600_000L,
    private val serverCooldownMillis: Long = 15_000L,
    private val networkCooldownMillis: Long = 10_000L,
) {

    private class Health {
        var successes: Int = 0
        var consecutiveFailures: Int = 0
        var cooldownUntilMillis: Long = 0L
        var breakerOpenedAtMillis: Long = -1L
    }

    private val health = mutableMapOf<String, Health>()

    private fun healthOf(target: GatewayTarget): Health = health.getOrPut(target.key) { Health() }

    // ── Classic classification ───────────────────────────────────────────────

    /**
     * Decide whether [body] looks like a provider quota/credit exhaustion message. Providers
     * return these with assorted HTTP codes (often 429, sometimes 200), so the body matters.
     */
    fun looksLikeQuota(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val lower = body.lowercase()
        return listOf(
            "insufficient_quota",
            "quota exceeded",
            "exceeded your current quota",
            "out of credits",
            "no credits",
            "credit balance",
            "billing hard limit",
            "payment required",
            "free tier",
        ).any { it in lower }
    }

    /** Classify a single upstream outcome into an action. */
    fun classify(status: Int?, networkError: Boolean, body: String? = null): FailureClass {
        if (networkError) return FailureClass.SWITCH
        val code = status ?: 0
        return when {
            code == 0 -> FailureClass.SWITCH
            code == 402 -> FailureClass.SWITCH
            code == 401 || code == 403 -> FailureClass.SWITCH
            code == 408 -> FailureClass.SWITCH
            code == 429 -> FailureClass.SWITCH
            looksLikeQuota(body) -> FailureClass.SWITCH
            code in 500..599 -> FailureClass.SWITCH
            else -> FailureClass.FATAL
        }
    }

    private fun cooldownFor(status: Int?, networkError: Boolean, body: String?): Long = when {
        looksLikeQuota(body) -> quotaCooldownMillis
        networkError -> networkCooldownMillis
        else -> when (status) {
            429 -> rateLimitCooldownMillis
            401, 403 -> authCooldownMillis
            408 -> networkCooldownMillis
            in 500..599 -> serverCooldownMillis
            else -> serverCooldownMillis
        }
    }

    // ── Selection ────────────────────────────────────────────────────────────

    /** True when [target] may be tried now (not cooling down and breaker not open). */
    fun isUsable(target: GatewayTarget, nowMillis: Long): Boolean {
        val current = health[target.key] ?: return true
        if (nowMillis < current.cooldownUntilMillis) return false
        val openedAt = current.breakerOpenedAtMillis
        return openedAt < 0L || nowMillis - openedAt >= circuitOpenMillis
    }

    /** The ordered subset of [targets] that is currently usable. */
    fun plan(targets: List<GatewayTarget>, nowMillis: Long): List<GatewayTarget> =
        targets.filter { isUsable(it, nowMillis) }

    /**
     * Targets for a request: healthy ones first (priority order preserved), then the cooling
     * ones as a last resort so a transient cooldown can never make a request impossible.
     */
    fun planWithFallback(targets: List<GatewayTarget>, nowMillis: Long): List<GatewayTarget> {
        val usable = mutableListOf<GatewayTarget>()
        val cooling = mutableListOf<GatewayTarget>()
        targets.forEach { target -> if (isUsable(target, nowMillis)) usable += target else cooling += target }
        return usable + cooling
    }

    // ── Feedback ─────────────────────────────────────────────────────────────

    fun onSuccess(target: GatewayTarget, nowMillis: Long) {
        val current = healthOf(target)
        current.successes++
        current.consecutiveFailures = 0
        current.cooldownUntilMillis = 0L
        current.breakerOpenedAtMillis = -1L
    }

    /** Records a failure and returns the classified action so the caller can switch or stop. */
    fun onFailure(
        target: GatewayTarget,
        status: Int?,
        networkError: Boolean,
        body: String?,
        nowMillis: Long,
    ): GatewayFailure {
        val failureClass = classify(status, networkError, body)
        val current = healthOf(target)
        current.consecutiveFailures++
        if (failureClass == FailureClass.SWITCH) {
            val cooldown = cooldownFor(status, networkError, body)
            // Back off further each consecutive time the same key fails.
            val backoff = cooldown shl (current.consecutiveFailures - 1).coerceIn(0, 4)
            current.cooldownUntilMillis = nowMillis + backoff
            if (current.consecutiveFailures >= maxConsecutiveFailures && current.breakerOpenedAtMillis < 0L) {
                current.breakerOpenedAtMillis = nowMillis
            }
        }
        return GatewayFailure(
            status = status,
            networkError = networkError,
            message = scrub(body).ifBlank {
                when {
                    networkError -> "Network error"
                    status != null -> "Provider returned HTTP $status"
                    else -> "Provider request failed"
                }
            },
            failureClass = failureClass,
        )
    }

    /** Health score 0..100 for a target, used to sort/report; 100 is fully healthy. */
    fun healthScore(target: GatewayTarget): Int {
        val current = health[target.key] ?: return 100
        val penalty = current.consecutiveFailures * 25
        val bonus = (current.successes.coerceAtMost(4)) * 2
        return (100 - penalty + bonus).coerceIn(0, 100)
    }

    fun snapshot(target: GatewayTarget, nowMillis: Long): CircuitSnapshot {
        val current = health[target.key]
        val openedAt = current?.breakerOpenedAtMillis ?: -1L
        val open = openedAt >= 0L && nowMillis - openedAt < circuitOpenMillis
        val remaining = when {
            !open -> 0L
            else -> (openedAt + circuitOpenMillis - nowMillis).coerceAtLeast(0L)
        }
        return CircuitSnapshot(
            open = open,
            consecutiveFailures = current?.consecutiveFailures ?: 0,
            cooldownRemainingMillis = maxOf(remaining, (current?.cooldownUntilMillis ?: 0L) - nowMillis).coerceAtLeast(0L),
        )
    }

    fun reset() {
        health.clear()
    }

    /** Best-effort secret scrubber for provider error bodies echoed back to a client. */
    fun scrub(body: String?): String {
        if (body.isNullOrBlank()) return ""
        return body
            .replace(Regex("(?i)bearer\\s+\\S+"), "Bearer ••••")
            .replace(Regex("(?i)sk-[a-z0-9_-]{8,}"), "sk-••••")
            .replace(Regex("(?i)(api[_-]?key\"?\\s*[:=]\\s*\")[^\"]+"), "$1••••")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(500)
    }

    /** Extracts the human-readable provider error, scrubbed, from a JSON error body. */
    fun providerMessage(body: String?): String = runCatching {
        val root = org.json.JSONObject(body.orEmpty())
        val error = root.opt("error")
        val text = when (error) {
            is org.json.JSONObject -> error.optString("message").ifBlank { error.optString("detail") }
            is String -> error
            else -> root.optString("message").ifBlank { root.optString("detail") }
        }
        if (text.isBlank()) scrub(body) else scrub(text)
    }.getOrDefault(scrub(body))
}
