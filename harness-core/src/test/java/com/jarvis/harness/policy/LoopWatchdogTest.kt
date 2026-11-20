package com.jarvis.harness.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopWatchdogTest {

    private val thresholds = WatchdogThresholds(
        repeatedToolArgsLimit = 3,
        repeatedFileEditLimit = 5,
        stepsWithoutProgressLimit = 12,
        testingAttemptsCap = 2,
    )

    private fun watchdog(budget: TierBudget = TierBudget(maxSteps = 80, maxMinutes = 25)) =
        LoopWatchdog(budget, thresholds)

    @Test
    fun `repeated command corrects at the limit then finalizes`() {
        val wd = watchdog()
        assertEquals(WatchdogDecision.None, wd.onToolInvocation("bash|ls"))
        assertEquals(WatchdogDecision.None, wd.onToolInvocation("bash|ls"))
        val first = wd.onToolInvocation("bash|ls")
        assertTrue("3rd repeat must inject a correction", first is WatchdogDecision.Correct)
        val second = wd.onToolInvocation("bash|ls")
        assertTrue("4th repeat must finalize", second is WatchdogDecision.Finalize)
    }

    @Test
    fun `different tool args do not trip the repeated-command rule`() {
        val wd = watchdog()
        assertEquals(WatchdogDecision.None, wd.onToolInvocation("bash|ls"))
        assertEquals(WatchdogDecision.None, wd.onToolInvocation("bash|pwd"))
        assertEquals(WatchdogDecision.None, wd.onToolInvocation("bash|cat x"))
    }

    @Test
    fun `repeated file edit corrects at the limit then finalizes`() {
        val wd = watchdog()
        for (i in 1 until 5) assertEquals(WatchdogDecision.None, wd.onFileEdit("src/app.js"))
        val first = wd.onFileEdit("src/app.js")
        assertTrue(first is WatchdogDecision.Correct)
        val second = wd.onFileEdit("src/app.js")
        assertTrue(second is WatchdogDecision.Finalize)
    }

    @Test
    fun `editing a different file resets the streak`() {
        val wd = watchdog()
        repeat(4) { assertEquals(WatchdogDecision.None, wd.onFileEdit("a.js")) }
        assertEquals(WatchdogDecision.None, wd.onFileEdit("b.js"))
        assertEquals(WatchdogDecision.None, wd.onFileEdit("a.js"))
    }

    @Test
    fun `progress resets the repeated file edit streak`() {
        val wd = watchdog()
        repeat(4) { wd.onFileEdit("a.js") }
        wd.onProgress()
        assertEquals(WatchdogDecision.None, wd.onFileEdit("a.js"))
    }

    @Test
    fun `testing over the cap corrects then finalizes`() {
        val wd = watchdog()
        assertEquals(WatchdogDecision.None, wd.onTestingCommand())
        assertEquals(WatchdogDecision.None, wd.onTestingCommand())
        val first = wd.onTestingCommand()
        assertTrue(first is WatchdogDecision.Correct)
        assertTrue((first as WatchdogDecision.Correct).reason.contains("finalize"))
        val second = wd.onTestingCommand()
        assertTrue(second is WatchdogDecision.Finalize)
    }

    @Test
    fun `exhausting the step budget corrects then finalizes`() {
        val wd = watchdog(TierBudget(maxSteps = 10, maxMinutes = 25))
        for (step in 1..10) assertEquals(WatchdogDecision.None, wd.onStep(step, elapsedMinutes = 1))
        val first = wd.onStep(11, elapsedMinutes = 1)
        assertTrue(first is WatchdogDecision.Correct)
        val second = wd.onStep(12, elapsedMinutes = 1)
        assertTrue(second is WatchdogDecision.Finalize)
    }

    @Test
    fun `exhausting the time budget corrects`() {
        val wd = watchdog(TierBudget(maxSteps = 80, maxMinutes = 5))
        val decision = wd.onStep(steps = 3, elapsedMinutes = 6)
        assertTrue(decision is WatchdogDecision.Correct)
    }

    @Test
    fun `too many steps without progress corrects`() {
        val wd = watchdog()
        for (step in 1 until 12) assertEquals(WatchdogDecision.None, wd.onStep(step, elapsedMinutes = 1))
        val decision = wd.onStep(12, elapsedMinutes = 1)
        assertTrue(decision is WatchdogDecision.Correct)
    }

    @Test
    fun `progress resets the no-progress streak`() {
        val wd = watchdog()
        repeat(11) { wd.onStep(1, elapsedMinutes = 1) }
        wd.onProgress()
        for (step in 1 until 12) assertEquals(WatchdogDecision.None, wd.onStep(step, elapsedMinutes = 1))
    }

    @Test
    fun `uncapped complex budget never trips on step count`() {
        val wd = watchdog(TierBudget(maxSteps = -1, maxMinutes = -1))
        for (step in 1..500) {
            wd.onProgress()
            assertEquals(WatchdogDecision.None, wd.onStep(step, elapsedMinutes = 1))
        }
    }
}
