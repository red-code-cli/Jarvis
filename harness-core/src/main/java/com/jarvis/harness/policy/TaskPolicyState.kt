package com.jarvis.harness.policy

/**
 * Living supervisor state for one task: the classified tier, its budget, the once-probed
 * visual-test capability, the loop watchdog and the running step breakdown.
 *
 * Kept in memory by the engine and re-seeded from project memory after a restart.
 */
class TaskPolicyState(
    val tier: TaskTier,
    val reason: String,
    val budget: TierBudget,
    var visualTest: VisualTestResult,
    val watchdog: LoopWatchdog,
    var breakdown: StepBreakdown = StepBreakdown(),
    var startedAtMillis: Long = System.currentTimeMillis(),
) {
    fun elapsedMinutes(nowMillis: Long = System.currentTimeMillis()): Int =
        ((nowMillis - startedAtMillis) / 60_000L).toInt().coerceAtLeast(0)
}
