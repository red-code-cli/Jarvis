package com.jarvis.harness.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskClassifierTest {

    private val config = AgentPolicyConfig.DEFAULT

    @Test
    fun `short tweak request is classified TRIVIAL`() {
        val result = TaskClassifier.classify("fix a typo in the heading", "web", config)
        assertEquals(TaskTier.TRIVIAL, result.tier)
        assertTrue(result.reason.contains("tweak", ignoreCase = true))
    }

    @Test
    fun `ordinary small project request is classified SIMPLE`() {
        val result = TaskClassifier.classify("make me a portfolio website", "web", config)
        assertEquals(TaskTier.SIMPLE, result.tier)
    }

    @Test
    fun `multi feature request is classified COMPLEX`() {
        val result = TaskClassifier.classify(
            "build a full ecommerce store with authentication, payment and an admin dashboard",
            "web",
            config,
        )
        assertEquals(TaskTier.COMPLEX, result.tier)
    }

    @Test
    fun `large request is classified COMPLEX by length alone`() {
        val long = "build a tool " + "with a detailed description ".repeat(20)
        assertEquals(TaskTier.COMPLEX, TaskClassifier.classify(long, "web", config).tier)
    }

    @Test
    fun `feature signal pushes a small request to MEDIUM`() {
        val result = TaskClassifier.classify("add a login page", "web", config)
        assertEquals(TaskTier.MEDIUM, result.tier)
    }

    @Test
    fun `android request is at least MEDIUM`() {
        val result = TaskClassifier.classify("make a to do app", "android", config)
        assertEquals(TaskTier.MEDIUM, result.tier)
    }

    @Test
    fun `optional refinement overrides the heuristic tier`() {
        val result = TaskClassifier.classify(
            "make me a portfolio website",
            "web",
            config,
            refine = { TaskTier.COMPLEX },
        )
        assertEquals(TaskTier.COMPLEX, result.tier)
        assertTrue(result.reason.contains("refinement", ignoreCase = true))
    }

    @Test
    fun `failing refinement falls back to the offline heuristic`() {
        val result = TaskClassifier.classify(
            "make me a portfolio website",
            "web",
            config,
            refine = { error("no provider key") },
        )
        assertEquals(TaskTier.SIMPLE, result.tier)
    }

    @Test
    fun `word boundary matching does not fire inside larger words`() {
        // "api" must not match inside "capital"; "label" still makes this a small tweak.
        val result = TaskClassifier.classify("adjust the capital city label", "web", config)
        assertEquals(TaskTier.TRIVIAL, result.tier)
    }

    @Test
    fun `default config keeps the requested tier budgets`() {
        assertEquals(10, config.budgetFor(TaskTier.TRIVIAL).maxSteps)
        assertEquals(3, config.budgetFor(TaskTier.TRIVIAL).maxMinutes)
        assertEquals(25, config.budgetFor(TaskTier.SIMPLE).maxSteps)
        assertEquals(8, config.budgetFor(TaskTier.SIMPLE).maxMinutes)
        assertEquals(80, config.budgetFor(TaskTier.MEDIUM).maxSteps)
        assertEquals(25, config.budgetFor(TaskTier.MEDIUM).maxMinutes)
        val complex = config.budgetFor(TaskTier.COMPLEX)
        assertTrue(!complex.capped)
        assertTrue(complex.milestones.isNotEmpty())
    }
}
