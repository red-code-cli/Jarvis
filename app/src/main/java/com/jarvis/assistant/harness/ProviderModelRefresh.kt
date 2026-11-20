package com.jarvis.assistant.harness

import android.content.Context
import android.util.Log
import com.jarvis.harness.data.ApiKeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Daily refresh of each provider's free models.
 *
 * For every provider that has a saved key it fetches the provider's own `/models`, keeps the
 * free ones, and only stores them when a tiny key probe passes. The gateway then prefers a
 * probe-passing free model, so the priority chain never silently falls back to a paid model the
 * user did not choose. Runs at most once a day unless [force] is set.
 */
object ProviderModelRefresh {

    private const val TAG = "ProviderModelRefresh"

    suspend fun refreshIfDue(context: Context, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val store = ProviderPriorityStore(context)
        if (!force && !store.isModelRefreshDue()) return@withContext false
        val vault = ApiKeyVault(context)
        val providers = ProviderCatalog.load(context) + store.customProviders().map { it.asCatalogProvider() }
        providers.forEach { provider ->
            val key = runCatching { vault.credentials(provider.id).firstOrNull()?.secret }.getOrNull()
            if (key.isNullOrBlank()) return@forEach
            runCatching {
                val probePassed = ProviderModelFetcher.probeKey(provider, key)
                val models = ProviderModelFetcher.fetch(provider, key)
                val free = models.filter { it.isFree }.map { it.id }
                store.setWorkingModels(provider.id, if (probePassed) free.toSet() else emptySet())
                if (store.selectedModel(provider.id).isBlank()) {
                    val pick = free.firstOrNull() ?: models.firstOrNull()?.id
                    if (!pick.isNullOrBlank()) store.setSelectedModel(provider.id, pick)
                }
            }.onFailure { Log.w(TAG, "Model refresh failed for ${provider.id}: ${it.message}") }
        }
        store.lastModelRefreshMillis = System.currentTimeMillis()
        true
    }
}
