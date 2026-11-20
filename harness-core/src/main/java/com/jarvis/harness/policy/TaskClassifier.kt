package com.jarvis.harness.policy

/** Result of the cheap pre-task classification: the chosen tier plus the reason for it. */
data class TaskClassification(val tier: TaskTier, val reason: String)

/**
 * Cheap, offline complexity classifier run before a task starts (B2).
 *
 * It only reads the request text and the project type — no model call — so it always works
 * with no network. A caller that has a provider key may pass [classify]'s optional [refine]
 * hook to override the heuristic with a tiny model verdict; failures fall back silently.
 */
object TaskClassifier {

    private val COMPLEXITY_SIGNALS = listOf(
        "full", "complete", "entire", "whole", "multi", "multiple", "dashboard",
        "platform", "saas", "crm", "ecommerce", "e-commerce", "marketplace", "social",
        "booking", "realtime", "real-time", "chat", "admin", "role", "authentication",
        "authorization", "login", "signup", "sign up", "database", "backend", "back-end",
        "api", "payment", "checkout", "deploy", "integration", "clone",
    ).map(::wordRegex)

    private val TRIVIAL_SIGNALS = listOf(
        "typo", "rename", "small", "quick", "tweak", "spelling", "label", "margin",
        "padding", "color", "title", "one line", "one-line", "button text", "font size",
        "font-size", "wording",
    ).map(::wordRegex)

    /**
     * @param request the user's raw request text.
     * @param projectType lower-case project type (e.g. "web", "android", "python").
     * @param refine optional model verdict; when it returns a non-null tier it wins.
     */
    fun classify(
        request: String,
        projectType: String,
        config: AgentPolicyConfig = AgentPolicyConfig.DEFAULT,
        refine: ((String) -> TaskTier?)? = null,
    ): TaskClassification {
        val lower = request.lowercase()
        val length = request.trim().length
        val complexityHits = COMPLEXITY_SIGNALS.count { it.containsMatchIn(lower) }
        val trivialHits = TRIVIAL_SIGNALS.count { it.containsMatchIn(lower) }
        val android = projectType.equals("android", ignoreCase = true)
        val limits = config.classifier

        val (tier, reason) = when {
            length >= limits.complexMinChars ->
                TaskTier.COMPLEX to "large request ($length chars)"
            complexityHits >= limits.complexSignalCount ->
                TaskTier.COMPLEX to "multi-feature request ($complexityHits feature signals)"
            android && length >= limits.simpleMaxChars ->
                TaskTier.COMPLEX to "large Android app request"
            trivialHits >= 1 && complexityHits == 0 && length <= limits.trivialMaxChars ->
                TaskTier.TRIVIAL to "small tweak request ($length chars)"
            android ->
                TaskTier.MEDIUM to "Android build request"
            complexityHits >= limits.mediumSignalCount ->
                TaskTier.MEDIUM to "feature request ($complexityHits feature signals)"
            length >= limits.simpleMaxChars ->
                TaskTier.MEDIUM to "long request ($length chars)"
            else ->
                TaskTier.SIMPLE to "default small project request"
        }

        val refined = refine?.let { fn -> runCatching { fn(request) }.getOrNull() }
        return if (refined != null) {
            TaskClassification(refined, "model refinement over ${tier.name.lowercase()}")
        } else {
            TaskClassification(tier, reason)
        }
    }

    private fun wordRegex(signal: String): Regex =
        Regex("""(?<![a-z0-9])${Regex.escape(signal)}(?![a-z0-9])""")
}
