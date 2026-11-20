package com.jarvis.harness.policy

/**
 * Builds the efficiency wrapper prepended to the agent's task prompt (B2.2).
 *
 * This is a supervisor wrapper, NOT a change to any runtime bridge: the engine composes the
 * instructions and passes them alongside the user's request, so every agent CLI benefits
 * without the bridges knowing about the policy.
 */
object AgentPromptPolicy {

    fun efficiencyInstructions(
        tier: TaskTier,
        budget: TierBudget,
        visualTest: VisualTestResult,
    ): String = buildString {
        appendLine("AGENT EFFICIENCY RULES (follow strictly, do not restate them):")
        appendLine("1. Start with a short plan of 3-6 bullets, then build immediately. Do not re-plan in a loop.")
        appendLine("2. Write each file completely in one pass. Never fix a file with repeated sed/edit loops.")
        appendLine("3. Split large output into small logical files (for web: index.html, styles.css, app.js) instead of one giant file.")
        appendLine("4. Do NOT base64-embed fonts, images or other binary assets. Use the system font stack or separate asset files.")
        appendLine("5. Do NOT install, search for, or scaffold puppeteer/playwright/chrome/web-driver unless the task explicitly needs browser automation.")
        if (visualTest.available) {
            appendLine("6. Visual test: available. You may run ONE headless browser check, then stop.")
        } else {
            appendLine("6. Visual test: unavailable. SKIP browser testing. Do ONE light static check (files exist, HTML parses, linked assets resolve) and then finalize.")
        }
        appendLine("7. Run only ONE verification pass. When the work looks done, stop and finalize. Do not re-open finished files.")
        appendLine("8. This task is $tier tier (budget: ${budget.label}). Finish inside the budget and say what you built.")
        append("Finalize means: preview/serve is started if relevant, then a short summary.")
    }

    /** The instructions plus the original request, exactly as the agent should receive it. */
    fun wrap(
        userPrompt: String,
        tier: TaskTier,
        budget: TierBudget,
        visualTest: VisualTestResult,
    ): String = buildString {
        appendLine(efficiencyInstructions(tier, budget, visualTest))
        appendLine()
        appendLine("---")
        appendLine("TASK:")
        append(userPrompt.trim())
    }
}
