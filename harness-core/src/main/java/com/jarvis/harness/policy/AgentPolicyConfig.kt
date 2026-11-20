package com.jarvis.harness.policy

/**
 * Complexity tier assigned to a coding request before the task starts.
 *
 * The tier decides how many steps and how long the supervisor lets the agent run.
 * [COMPLEX] has no global cap: it is steered by milestones that carry their own budgets.
 */
enum class TaskTier { TRIVIAL, SIMPLE, MEDIUM, COMPLEX }

/** One milestone inside an uncapped [TaskTier.COMPLEX] task, carrying its own step budget. */
data class TierMilestone(val name: String, val maxSteps: Int)

/**
 * Step/time budget for one [TaskTier]. A non-positive value means "no global cap"
 * ([TaskTier.COMPLEX] is milestone-driven instead).
 */
data class TierBudget(
    val maxSteps: Int,
    val maxMinutes: Int,
    val milestones: List<TierMilestone> = emptyList(),
) {
    val capped: Boolean get() = maxSteps > 0 || maxMinutes > 0

    /** Short human label for the Agent Live card and project memory. */
    val label: String
        get() = if (capped) {
            buildString {
                if (maxSteps > 0) append("$maxSteps steps")
                if (maxSteps > 0 && maxMinutes > 0) append(" / ")
                if (maxMinutes > 0) append("$maxMinutes min")
            }
        } else {
            "no cap · ${milestones.size} milestones"
        }
}

/** Loop-watchdog thresholds. Every number the supervisor uses lives here, never inline. */
data class WatchdogThresholds(
    val repeatedToolArgsLimit: Int = 3,
    val repeatedFileEditLimit: Int = 5,
    val stepsWithoutProgressLimit: Int = 12,
    val testingAttemptsCap: Int = 2,
)

/** Cheap heuristic classifier thresholds (character counts / signal counts). */
data class ClassifierThresholds(
    val trivialMaxChars: Int = 80,
    val simpleMaxChars: Int = 200,
    val complexMinChars: Int = 400,
    val complexSignalCount: Int = 3,
    val mediumSignalCount: Int = 1,
)

/** Where the headless-browser capability probe looks for a chrome binary and a usable module. */
data class CapabilityProbeConfig(
    val chromeBinaryPaths: List<String> = listOf(
        "usr/bin/chromium",
        "usr/bin/chromium-browser",
        "usr/bin/google-chrome",
        "usr/local/bin/chrome",
        "usr/bin/chrome",
    ),
    val browserModulePaths: List<String> = listOf(
        "node_modules/puppeteer",
        "node_modules/playwright",
        "node_modules/puppeteer-core",
        "node_modules/playwright-core",
    ),
)

private val DEFAULT_TIER_BUDGETS: Map<TaskTier, TierBudget> = mapOf(
    TaskTier.TRIVIAL to TierBudget(maxSteps = 10, maxMinutes = 3),
    TaskTier.SIMPLE to TierBudget(maxSteps = 25, maxMinutes = 8),
    TaskTier.MEDIUM to TierBudget(maxSteps = 80, maxMinutes = 25),
    TaskTier.COMPLEX to TierBudget(
        maxSteps = -1,
        maxMinutes = -1,
        milestones = listOf(
            TierMilestone("scaffold", 20),
            TierMilestone("core features", 40),
            TierMilestone("polish and verify", 40),
        ),
    ),
)

/** Commands that count as "testing" for the B3 testing-attempt cap and the step breakdown. */
private val DEFAULT_TESTING_PATTERNS: List<Regex> = listOf(
    Regex("""\b(puppeteer|playwright|cypress|selenium|webdriver)\b"""),
    Regex("""\b(jest|vitest|mocha|karma|pytest|phpunit)\b"""),
    Regex("""\b(npm|yarn|pnpm|bun)\s+(run\s+)?test\b"""),
    Regex("""\bgradle\b.*\btest\b""", RegexOption.IGNORE_CASE),
    Regex("""\./gradlew\b.*\btest\b""", RegexOption.IGNORE_CASE),
    Regex("""\b(go|cargo|dotnet)\s+test\b"""),
    Regex("""\b(chrome|chromium|google-chrome)\b.*(--headless|--dump-dom|--screenshot)"""),
    Regex("""--headless\b"""),
)

/**
 * The one place every supervisor policy number lives: tier budgets, watchdog thresholds,
 * classifier limits and the capability-probe layout. Nothing in the engine hardcodes these.
 */
data class AgentPolicyConfig(
    val tierBudgets: Map<TaskTier, TierBudget> = DEFAULT_TIER_BUDGETS,
    val watchdog: WatchdogThresholds = WatchdogThresholds(),
    val classifier: ClassifierThresholds = ClassifierThresholds(),
    val capability: CapabilityProbeConfig = CapabilityProbeConfig(),
    val testingPatterns: List<Regex> = DEFAULT_TESTING_PATTERNS,
) {
    fun budgetFor(tier: TaskTier): TierBudget = tierBudgets.getValue(tier)

    companion object {
        val DEFAULT: AgentPolicyConfig = AgentPolicyConfig()

        // Agent Live feed + verification policy (used by FeedText and WorkspaceFiles too, so
        // there is still exactly one place for these numbers).
        const val FEED_LINE_MAX = 220
        const val FEED_DETAIL_MAX = 120
        const val FEED_BOUNDARY_MIN = 40
        const val FEED_DEDUPE_PREFIX = 60
        const val ENTRY_FILE_MAX_DEPTH = 3
        const val MAX_PROOF_FILES = 30
        const val MAX_FILE_TREE = 200
        val EXCLUDED_DIRS: Set<String> =
            setOf("node_modules", ".git", "build", ".gradle", "__pycache__", ".venv")
    }
}
