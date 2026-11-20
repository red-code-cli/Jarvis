package com.jarvis.assistant.harness

import android.content.Context
import android.util.Log
import com.jarvis.harness.HarnessEngine
import com.jarvis.harness.gateway.ActiveLocalGateway
import com.jarvis.harness.gateway.GatewayFailure
import com.jarvis.harness.gateway.GatewayRouteResolver
import com.jarvis.harness.gateway.GatewayTarget
import com.jarvis.harness.gateway.GatewayWire
import com.jarvis.harness.gateway.LocalGatewayRegistry
import com.jarvis.harness.gateway.LocalProviderGateway
import com.jarvis.harness.data.ApiKeyVault
import com.jarvis.harness.model.ProviderKind
import com.jarvis.harness.model.ProviderProfile

/**
 * Starts and drives the in-app local gateway.
 *
 * It builds the ordered target list from the provider catalog + custom providers + the
 * Keystore vault, keyed by the user's saved priority order, and forwards every switch to the
 * harness engine so it appears in the event log and on the Agent Live card. It never logs a key.
 */
class AgentGatewayController private constructor(private val context: Context) :
    GatewayRouteResolver, ActiveLocalGateway {

    private val store = ProviderPriorityStore(context)
    private val vault = ApiKeyVault(context)
    private val engine by lazy { HarnessEngine.get(context) }

    private var gateway: LocalProviderGateway? = null

    override val url: String get() = gateway?.url.orEmpty()
    override val token: String get() = gateway?.token.orEmpty()

    fun isRunning(): Boolean = gateway != null

    /** Starts the gateway if it is not already running. Returns true when one is active. */
    fun start(): Boolean {
        if (gateway != null) return true
        val available = availableProviderIds()
        if (available.isEmpty()) return false
        store.reconcile(available)
        val created = LocalProviderGateway(this)
        created.coversProvider = { profile -> coversProfile(profile) }
        created.start()
        gateway = created
        LocalGatewayRegistry.set(created)
        Log.i(TAG, "Local gateway listening on ${created.url} with ${available.size} provider(s)")
        return true
    }

    fun stop() {
        gateway?.close()
        gateway = null
        LocalGatewayRegistry.set(null)
    }

    override fun covers(profile: ProviderProfile): Boolean = coversProfile(profile)

    private fun coversProfile(profile: ProviderProfile): Boolean {
        if (gateway == null) return false
        if (profile.kind == ProviderKind.CLAUDE) return false
        val targets = targets()
        if (profile.kind == ProviderKind.CUSTOM) {
            return targets.any { it.baseUrl.trimEnd('/') == profile.resolvedBaseUrl.trimEnd('/') }
        }
        val id = catalogIdFor(profile.kind) ?: return false
        return targets.any { it.providerId == id }
    }

    override fun targets(): List<GatewayTarget> {
        val byId = (ProviderCatalog.load(context) + store.customProviders().map { it.asCatalogProvider() })
            .associateBy { it.id }
        val result = mutableListOf<GatewayTarget>()
        store.order.forEach { providerId ->
            val provider = byId[providerId] ?: return@forEach
            val keys = runCatching { vault.credentials(providerId) }.getOrDefault(emptyList())
            if (keys.isEmpty()) return@forEach
            val model = resolveModel(providerId, provider)
            val wire = wireFor(provider.protocol)
            keys.forEach { credential ->
                result += GatewayTarget(
                    providerId = providerId,
                    providerName = provider.name,
                    model = model,
                    keyId = credential.id,
                    keyName = credential.name,
                    baseUrl = provider.baseUrl,
                    wire = wire,
                )
            }
        }
        return result
    }

    override fun secret(target: GatewayTarget): String? =
        runCatching {
            vault.credentials(target.providerId).firstOrNull { it.id == target.keyId }?.secret
        }.getOrNull()

    override fun onSwitch(from: GatewayTarget, to: GatewayTarget, failure: GatewayFailure) {
        Log.i(TAG, "Switching ${from.providerName} → ${to.providerName} (${failure.status ?: "network"})")
        runCatching {
            engine.recordGatewaySwitch(
                fromLabel = from.label,
                toLabel = to.label,
                reason = failure.message,
                providerName = to.providerName,
                model = to.model,
            )
        }
    }

    override fun onActive(target: GatewayTarget) {
        runCatching { engine.noteActiveProvider(target.providerName, target.model) }
    }

    private fun resolveModel(providerId: String, provider: ProviderCatalog.CatalogProvider): String {
        val selected = store.selectedModel(providerId)
        if (selected.isNotBlank()) return selected
        val working = store.workingModels(providerId).firstOrNull()
        if (!working.isNullOrBlank()) return working
        return BUILTIN_DEFAULT_MODELS[providerId].orEmpty()
    }

    private fun availableProviderIds(): List<String> {
        val ids = (ProviderCatalog.load(context) + store.customProviders().map { it.asCatalogProvider() })
            .map { it.id }
        return ids.filter { id -> runCatching { vault.list(id).isNotEmpty() }.getOrDefault(false) }
    }

    private fun wireFor(protocol: String): GatewayWire =
        if (protocol.equals("anthropic", ignoreCase = true)) GatewayWire.ANTHROPIC else GatewayWire.OPENAI_CHAT

    companion object {
        private const val TAG = "AgentGateway"

        /** Maps the engine's [ProviderKind] to the catalog id used by the priority chain. */
        fun catalogIdFor(kind: ProviderKind): String? = when (kind) {
            ProviderKind.ANTHROPIC -> "anthropic"
            ProviderKind.LLM_ROUTER -> "openrouter"
            ProviderKind.DEEPSEEK -> "deepseek"
            ProviderKind.KIMI -> "moonshot"
            ProviderKind.NVIDIA_NIM -> "nvidia-nim"
            ProviderKind.OPENCODE_ZEN -> null
            ProviderKind.CUSTOM -> null
            ProviderKind.CLAUDE -> null
        }

        /** Last-resort model per catalog id so the gateway always sends a concrete model. */
        private val BUILTIN_DEFAULT_MODELS = mapOf(
            "openrouter" to "~anthropic/claude-sonnet-latest",
            "deepseek" to "deepseek-chat",
            "moonshot" to "kimi-k2-0905-preview",
            "nvidia-nim" to "qwen/qwen2.5-coder-32b-instruct",
            "groq" to "llama-3.3-70b-versatile",
            "cerebras" to "llama-3.3-70b",
            "mistral" to "mistral-large-latest",
            "google-ai-studio" to "gemini-2.0-flash",
            "openai" to "gpt-4o-mini",
            "anthropic" to "claude-sonnet-4-6",
        )

        @Volatile
        private var instance: AgentGatewayController? = null

        /** Single shared controller for the app process. */
        fun get(context: Context): AgentGatewayController =
            instance ?: synchronized(this) {
                instance ?: AgentGatewayController(context.applicationContext).also { instance = it }
            }
    }
}
