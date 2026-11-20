package com.jarvis.harness.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverPlannerTest {

    private val openai = GatewayTarget("openrouter", "OpenRouter", "m1", "k1", "Primary", "https://openrouter.ai/api/v1", GatewayWire.OPENAI_CHAT)
    private val anthropic = GatewayTarget("deepseek", "DeepSeek", "m2", "k2", "Key 2", "https://api.deepseek.com/anthropic", GatewayWire.ANTHROPIC)

    @Test
    fun switchesOnRateLimitAuthAndServerErrors() {
        val planner = FailoverPlanner()
        assertEquals(FailureClass.SWITCH, planner.classify(429, false))
        assertEquals(FailureClass.SWITCH, planner.classify(401, false))
        assertEquals(FailureClass.SWITCH, planner.classify(403, false))
        assertEquals(FailureClass.SWITCH, planner.classify(408, false))
        assertEquals(FailureClass.SWITCH, planner.classify(402, false))
        assertEquals(FailureClass.SWITCH, planner.classify(500, false))
        assertEquals(FailureClass.SWITCH, planner.classify(503, false))
    }

    @Test
    fun switchesOnNetworkErrorAndTimeout() {
        val planner = FailoverPlanner()
        assertEquals(FailureClass.SWITCH, planner.classify(null, networkError = true))
        assertEquals(FailureClass.SWITCH, planner.classify(0, networkError = false))
    }

    @Test
    fun switchesWhenBodyRevealsQuotaEvenWithOkStatus() {
        val planner = FailoverPlanner()
        assertEquals(FailureClass.SWITCH, planner.classify(200, false, """{"error":{"code":"insufficient_quota"}}"""))
        assertEquals(FailureClass.SWITCH, planner.classify(200, false, "You exceeded your current quota"))
    }

    @Test
    fun badRequestIsFatalAndDoesNotSwitch() {
        val planner = FailoverPlanner()
        assertEquals(FailureClass.FATAL, planner.classify(400, false))
        assertEquals(FailureClass.FATAL, planner.classify(404, false))
        assertEquals(FailureClass.FATAL, planner.classify(422, false))
    }

    @Test
    fun failedKeyCoolsDownAndReturnsAfterWindow() {
        val planner = FailoverPlanner()
        planner.onFailure(openai, 429, false, null, nowMillis = 1_000)
        assertFalse(planner.isUsable(openai, 2_000))
        assertFalse(planner.isUsable(openai, 30_000))
        assertTrue(planner.isUsable(openai, 31_001))
    }

    @Test
    fun circuitBreakerOpensAfterRepeatedFailuresThenClosesOnSuccess() {
        val planner = FailoverPlanner(maxConsecutiveFailures = 3, circuitOpenMillis = 50_000)
        planner.onFailure(openai, 500, false, null, 0)
        planner.onFailure(openai, 500, false, null, 0)
        planner.onFailure(openai, 500, false, null, 0)
        assertTrue(planner.snapshot(openai, 10).open)
        assertFalse(planner.snapshot(openai, 60_000).open)
        planner.onSuccess(openai, 61_000)
        assertFalse(planner.snapshot(openai, 61_000).open)
        assertEquals(100, planner.healthScore(openai))
    }

    @Test
    fun planKeepsPriorityOrderForHealthyTargets() {
        val planner = FailoverPlanner()
        assertEquals(listOf(openai, anthropic), planner.plan(listOf(openai, anthropic), 0))
    }

    @Test
    fun coolingTargetIsPushedBehindHealthyOnesButStillOffered() {
        val planner = FailoverPlanner()
        planner.onFailure(openai, 429, false, null, 0)
        val planned = planner.planWithFallback(listOf(openai, anthropic), 1_000)
        assertEquals(listOf(anthropic, openai), planned)
    }

    @Test
    fun scrubNeverLeavesAKeyInTheMessage() {
        val planner = FailoverPlanner()
        val message = planner.providerMessage("""{"error":{"message":"bad key sk-abcdefgh12345678"}}""")
        assertFalse(message.contains("sk-abcdefgh12345678"))
        assertTrue(message.contains("••••"))
    }
}
