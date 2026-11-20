package com.jarvis.harness.gateway

import com.jarvis.harness.model.AgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCapabilityTableTest {

    @Test
    fun apiKeyAgentsCanRouteThroughTheGateway() {
        assertTrue(AgentCapabilityTable.canRouteThroughGateway(AgentKind.CLAUDE_CODE))
        assertTrue(AgentCapabilityTable.canRouteThroughGateway(AgentKind.DEEPSEEK_HARNESS))
    }

    @Test
    fun antigravityIsOauthManagedAndCannotTakeACustomEndpoint() {
        val antigravity = AgentCapabilityTable.of(AgentKind.ANTIGRAVITY)
        assertFalse(antigravity.customBaseUrl)
        assertFalse(antigravity.apiKeys)
        assertTrue(antigravity.oauthManaged)
        assertFalse(antigravity.multiKeyFailover)
        assertTrue(antigravity.resumableFromCheckpoint)
        assertFalse(AgentCapabilityTable.canRouteThroughGateway(AgentKind.ANTIGRAVITY))
    }

    @Test
    fun tableCoversEveryAgent() {
        assertEquals(AgentKind.entries.size, AgentCapabilityTable.all().size)
    }

    @Test
    fun oauthAgentRecoversFromCheckpointWhileKeyAgentsFailOver() {
        assertEquals(RecoveryStrategy.GATEWAY_FAILOVER, AgentRecovery.strategy(AgentKind.CLAUDE_CODE))
        assertEquals(RecoveryStrategy.GATEWAY_FAILOVER, AgentRecovery.strategy(AgentKind.DEEPSEEK_HARNESS))
        assertEquals(RecoveryStrategy.RESUME_FROM_CHECKPOINT, AgentRecovery.strategy(AgentKind.ANTIGRAVITY))
    }
}
