package com.jarvis.assistant.harness

import android.content.Context
import com.jarvis.harness.gateway.GatewayPriorityOrder
import org.json.JSONArray
import org.json.JSONObject

/** A user-added custom provider (OpenAI- or Anthropic-compatible), stored beside the catalog. */
data class CustomProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    /** "openai" | "anthropic" */
    val protocol: String,
    val modelsPath: String = "/models",
    val model: String = "",
) {
    fun asCatalogProvider(): ProviderCatalog.CatalogProvider = ProviderCatalog.CatalogProvider(
        id = id,
        name = name,
        protocol = protocol,
        baseUrl = baseUrl,
        modelsPath = modelsPath,
        keyUrl = "",
        freeRule = "",
    )
}

/**
 * Persists the Settings → Coding state that Section 5 needs: the user-editable provider
 * priority order, which free models actually passed a probe, the last daily-refresh time, and
 * any custom providers the user added.
 *
 * Only provider ids and public metadata live here; API keys stay in the Keystore-backed
 * [com.jarvis.harness.data.ApiKeyVault].
 */
class ProviderPriorityStore(context: Context) {

    private val preferences = context.getSharedPreferences("provider_priority", Context.MODE_PRIVATE)

    /** Ordered provider ids, highest priority first. */
    var order: List<String>
        get() = GatewayPriorityOrder.decode(preferences.getString(KEY_ORDER, null))
        set(value) {
            preferences.edit().putString(KEY_ORDER, GatewayPriorityOrder.encode(value)).apply()
        }

    /** Keeps the saved order, keeping only still-available providers and appending new ones. */
    fun reconcile(available: List<String>) {
        order = GatewayPriorityOrder.reconcile(order, available)
    }

    fun move(from: Int, to: Int) {
        order = GatewayPriorityOrder.move(order, from, to)
    }

    /** Model the user (or the free-model probe) selected for a provider. */
    fun selectedModel(providerId: String): String =
        preferences.getString(modelKey(providerId), null).orEmpty()

    fun setSelectedModel(providerId: String, model: String) {
        preferences.edit().putString(modelKey(providerId), model.trim()).apply()
    }

    /** Free model ids that passed the key probe for a provider. */
    fun workingModels(providerId: String): Set<String> {
        val raw = preferences.getString(workingKey(providerId), null) ?: return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet()
        }.getOrDefault(emptySet())
    }

    fun setWorkingModels(providerId: String, ids: Set<String>) {
        val array = JSONArray()
        ids.sorted().forEach(array::put)
        preferences.edit().putString(workingKey(providerId), array.toString()).apply()
    }

    var lastModelRefreshMillis: Long
        get() = preferences.getLong(KEY_LAST_REFRESH, 0L)
        set(value) {
            preferences.edit().putLong(KEY_LAST_REFRESH, value).apply()
        }

    fun isModelRefreshDue(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis - lastModelRefreshMillis >= DAY_MILLIS

    fun customProviders(): List<CustomProvider> {
        val raw = preferences.getString(KEY_CUSTOM, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                val baseUrl = item.optString("baseUrl")
                if (id.isBlank() || baseUrl.isBlank()) return@mapNotNull null
                CustomProvider(
                    id = id,
                    name = item.optString("name").ifBlank { id },
                    baseUrl = baseUrl,
                    protocol = item.optString("protocol", "openai"),
                    modelsPath = item.optString("modelsPath", "/models"),
                    model = item.optString("model").orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveCustomProviders(providers: List<CustomProvider>) {
        val array = JSONArray()
        providers.forEach { provider ->
            array.put(
                JSONObject()
                    .put("id", provider.id)
                    .put("name", provider.name)
                    .put("baseUrl", provider.baseUrl)
                    .put("protocol", provider.protocol)
                    .put("modelsPath", provider.modelsPath)
                    .put("model", provider.model),
            )
        }
        preferences.edit().putString(KEY_CUSTOM, array.toString()).apply()
    }

    fun addCustomProvider(provider: CustomProvider) {
        saveCustomProviders(customProviders().filterNot { it.id == provider.id } + provider)
    }

    fun removeCustomProvider(id: String) {
        saveCustomProviders(customProviders().filterNot { it.id == id })
        preferences.edit().remove(modelKey(id)).remove(workingKey(id)).apply()
    }

    private fun modelKey(providerId: String) = "model_$providerId"
    private fun workingKey(providerId: String) = "working_$providerId"

    companion object {
        private const val KEY_ORDER = "priority_order"
        private const val KEY_LAST_REFRESH = "last_model_refresh"
        private const val KEY_CUSTOM = "custom_providers"
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

        /** Stable id for the built-in custom provider slot used by ProviderKind.CUSTOM. */
        const val CUSTOM_PREFIX = "custom-"
    }
}
