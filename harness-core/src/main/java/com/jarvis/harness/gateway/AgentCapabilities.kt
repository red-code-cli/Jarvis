package com.jarvis.harness.gateway

import com.jarvis.harness.model.AgentKind

/** Process-wide handle to the running loopback gateway, so runtime bridges can route through it. */
object LocalGatewayRegistry {

    @Volatile
    private var active: ActiveLocalGateway? = null

    /** Last switch line for the Agent Live card; overwritten on every switch. */
    @Volatile
    var lastSwitchLine: String? = null

    fun set(gateway: ActiveLocalGateway?) {
        active = gateway
    }

    fun current(): ActiveLocalGateway? = active

    /** The gateway to use for [profile], or null when it does not cover this provider. */
    fun forProfile(profile: com.jarvis.harness.model.ProviderProfile): ActiveLocalGateway? =
        active?.takeIf { runCatching { it.covers(profile) }.getOrDefault(false) }
}

/**
 * Pure capability matrix per coding agent.
 *
 * Section 5 needs this because Google-managed agents (Antigravity) authenticate with an OAuth
 * account and cannot be pointed at a custom base URL or our local gateway. For those, failover
 * is impossible mid-request; the only safe recovery is to resume from the last checkpoint on
 * the next available agent/provider. Everything else needs no guessing, hence the table.
 */
data class AgentCapabilities(
    val agent: AgentKind,
    /** May be pointed at a custom provider base URL (or our local gateway). */
    val customBaseUrl: Boolean,
    /** Uses API-key providers, so the gateway can fail over between keys. */
    val apiKeys: Boolean,
    /** Google/Anthropic OAuth only: no API key can be injected. */
    val oauthManaged: Boolean,
    /** Several providers may be configured and switched between. */
    val multipleProviders: Boolean,
    /** More than one key per provider, tried on failure. */
    val multiKeyFailover: Boolean,
    /** Can resume a task from its saved checkpoint after a hard provider failure. */
    val resumableFromCheckpoint: Boolean,
    /** One-line explanation for the Settings table. */
    val note: String,
)

object AgentCapabilityTable {

    fun of(agent: AgentKind): AgentCapabilities = when (agent) {
        AgentKind.CLAUDE_CODE -> AgentCapabilities(
            agent = agent,
            customBaseUrl = true,
            apiKeys = true,
            oauthManaged = false,
            multipleProviders = true,
            multiKeyFailover = true,
            resumableFromCheckpoint = true,
            note = "Routes through the local gateway: key and provider failover both work.",
        )
        AgentKind.DEEPSEEK_HARNESS -> AgentCapabilities(
            agent = agent,
            customBaseUrl = true,
            apiKeys = true,
            oauthManaged = false,
            multipleProviders = true,
            multiKeyFailover = true,
            resumableFromCheckpoint = true,
            note = "API-key providers only; routes through the local gateway.",
        )
        AgentKind.ANTIGRAVITY -> AgentCapabilities(
            agent = agent,
            customBaseUrl = false,
            apiKeys = false,
            oauthManaged = true,
            multipleProviders = false,
            multiKeyFailover = false,
            resumableFromCheckpoint = true,
            note = "Google account only; cannot take a custom endpoint. On quota we resume from checkpoint on the next available agent.",
        )
    }

    fun all(): List<AgentCapabilities> = AgentKind.entries.map(::of)

    /** True when the gateway can be the agent's endpoint (and therefore fail over mid-request). */
    fun canRouteThroughGateway(agent: AgentKind): Boolean = of(agent).customBaseUrl
}

/** How a task recovers from a switchable provider failure, given the active agent. */
enum class RecoveryStrategy {
    /** The gateway fails over to the next healthy (provider, model, key) mid-request. */
    GATEWAY_FAILOVER,

    /** The agent cannot take a custom endpoint: resume the task from its checkpoint. */
    RESUME_FROM_CHECKPOINT,
}

object AgentRecovery {

    /**
     * OAuth-managed agents (Antigravity) cannot be pointed at a custom base URL, so on quota or
     * error the only safe recovery is to resume from the last checkpoint on the next available
     * agent/provider. Key-based agents fail over through the gateway instead.
     */
    fun strategy(agent: AgentKind): RecoveryStrategy =
        if (AgentCapabilityTable.canRouteThroughGateway(agent)) RecoveryStrategy.GATEWAY_FAILOVER
        else RecoveryStrategy.RESUME_FROM_CHECKPOINT
}

