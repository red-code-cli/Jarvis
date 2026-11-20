package com.jarvis.harness.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepBreakdownTest {

    private val config = AgentPolicyConfig.DEFAULT

    @Test
    fun `commands are classified into build edit verify test`() {
        assertEquals(StepCategory.BUILD, StepClassifier.classify("bash", "npm install", config))
        assertEquals(StepCategory.BUILD, StepClassifier.classify("Bash", "./gradlew assembleDebug", config))
        assertEquals(StepCategory.TEST, StepClassifier.classify("bash", "npx playwright test", config))
        assertEquals(StepCategory.TEST, StepClassifier.classify("bash", "pytest -q", config))
        assertEquals(StepCategory.VERIFY, StepClassifier.classify("bash", "curl http://127.0.0.1:8000", config))
        assertEquals(StepCategory.EDIT, StepClassifier.classify("write_file", "index.html", config))
        assertEquals(StepCategory.EDIT, StepClassifier.classify("str_replace_editor", "src/app.js", config))
    }

    @Test
    fun `testing command detection matches the cap patterns`() {
        assertTrue(StepClassifier.isTestingCommand("npx playwright test", config.testingPatterns))
        assertTrue(StepClassifier.isTestingCommand("google-chrome --headless --dump-dom http://x", config.testingPatterns))
        assertFalse(StepClassifier.isTestingCommand("node app.js", config.testingPatterns))
    }

    @Test
    fun `breakdown tallies and renders the four buckets`() {
        val breakdown = StepBreakdown()
            .plus(StepCategory.BUILD)
            .plus(StepCategory.BUILD)
            .plus(StepCategory.EDIT)
            .plus(StepCategory.VERIFY)
            .plus(StepCategory.TEST)
        assertEquals("build 2 · edit 1 · verify 1 · test 1", breakdown.render())
    }

    @Test
    fun `prompt policy tells the agent visual testing is unavailable and to finalize`() {
        val text = AgentPromptPolicy.efficiencyInstructions(
            TaskTier.SIMPLE,
            AgentPolicyConfig.DEFAULT.budgetFor(TaskTier.SIMPLE),
            VisualTestResult.unavailable("no chrome"),
        )
        assertTrue(text.contains("Visual test: unavailable"))
        assertTrue(text.contains("finalize"))
        assertTrue(text.contains("25 steps / 8 min"))
        assertTrue(text.contains("Do NOT base64-embed"))
    }

    @Test
    fun `prompt wrapper keeps the original request`() {
        val wrapped = AgentPromptPolicy.wrap(
            "build a todo list",
            TaskTier.TRIVIAL,
            AgentPolicyConfig.DEFAULT.budgetFor(TaskTier.TRIVIAL),
            VisualTestResult.AVAILABLE,
        )
        assertTrue(wrapped.contains("build a todo list"))
        assertTrue(wrapped.contains("Visual test: available"))
    }
}
