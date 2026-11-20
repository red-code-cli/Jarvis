package com.jarvis.harness.policy

/** What the supervisor should do after a watchdog check. */
sealed interface WatchdogDecision {
    /** Healthy: keep the task running. */
    data object None : WatchdogDecision

    /** Inject a corrective instruction into the agent and keep watching. */
    data class Correct(val reason: String) : WatchdogDecision

    /** Correction did not work (or the budget is gone); finalize the task with warnings. */
    data class Finalize(val reason: String) : WatchdogDecision
}

/**
 * Detects a task that is looping instead of finishing (B3.4).
 *
 * Signals it watches: the same tool+args repeated, one file edited over and over without a
 * new artifact, too many steps without progress, testing commands past the cap, and the tier
 * budget being exhausted. The first time a trigger fires the supervisor injects a correction;
 * if the same trigger fires again after that, the supervisor finalizes.
 *
 * Pure state machine: every method returns a [WatchdogDecision] and never touches Android.
 */
class LoopWatchdog(
    private val budget: TierBudget,
    private val thresholds: WatchdogThresholds,
) {
    private val toolCounts = mutableMapOf<String, Int>()
    private var editStreakPath: String? = null
    private var editStreak = 0
    private var stepsSinceProgress = 0
    private var testingAttempts = 0
    private val corrected = mutableSetOf<String>()
    private var finalized = false

    /** Same tool with the same arguments. */
    fun onToolInvocation(toolKey: String): WatchdogDecision {
        val count = (toolCounts[toolKey] ?: 0) + 1
        toolCounts[toolKey] = count
        if (count < thresholds.repeatedToolArgsLimit) return WatchdogDecision.None
        return escalate("tool:$toolKey", "same tool+args repeated ${count}x")
    }

    /** The same file edited repeatedly without a new artifact in between. */
    fun onFileEdit(path: String): WatchdogDecision {
        if (path == editStreakPath) {
            editStreak++
        } else {
            editStreakPath = path
            editStreak = 1
        }
        if (editStreak < thresholds.repeatedFileEditLimit) return WatchdogDecision.None
        return escalate("edit:$path", "file ${shortPath(path)} edited ${editStreak}x without new progress")
    }

    /** A testing/simulation command. Bounded by [WatchdogThresholds.testingAttemptsCap]. */
    fun onTestingCommand(): WatchdogDecision {
        testingAttempts++
        if (testingAttempts <= thresholds.testingAttemptsCap) return WatchdogDecision.None
        return escalate(
            "testing",
            "testing attempts exceeded the cap (${thresholds.testingAttemptsCap}) — testing band karo, ab finalize karo",
        )
    }

    /** A genuinely new artifact or milestone: the loop broke, so reset the no-progress streak. */
    fun onProgress() {
        stepsSinceProgress = 0
        editStreakPath = null
        editStreak = 0
        corrected.remove(TRIGGER_IDLE)
        corrected.removeAll { it.startsWith(TRIGGER_EDIT_PREFIX) }
    }

    /** One completed step. Checks the tier budget first, then the no-progress streak. */
    fun onStep(steps: Int, elapsedMinutes: Int): WatchdogDecision {
        stepsSinceProgress++
        if (budget.maxSteps > 0 && steps > budget.maxSteps) {
            return escalate("steps-budget", "step budget (${budget.maxSteps}) exhausted")
        }
        if (budget.maxMinutes > 0 && elapsedMinutes > budget.maxMinutes) {
            return escalate("time-budget", "time budget (${budget.maxMinutes} min) exhausted")
        }
        if (stepsSinceProgress >= thresholds.stepsWithoutProgressLimit) {
            return escalate(TRIGGER_IDLE, "$stepsSinceProgress steps without a new file or milestone")
        }
        return WatchdogDecision.None
    }

    /** Exposed for the live card: how many testing commands have run so far. */
    val testingCommandCount: Int get() = testingAttempts

    private fun escalate(trigger: String, reason: String): WatchdogDecision {
        if (finalized) return WatchdogDecision.None
        if (corrected.add(trigger)) return WatchdogDecision.Correct(reason)
        finalized = true
        return WatchdogDecision.Finalize(reason)
    }

    private fun shortPath(path: String): String = path.substringAfterLast('/').take(60)

    private companion object {
        const val TRIGGER_IDLE = "idle"
        const val TRIGGER_EDIT_PREFIX = "edit:"
    }
}
