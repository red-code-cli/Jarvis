package com.jarvis.assistant.harness

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches and filters the models a provider offers, straight from its own `/models`
 * endpoint, and can make one tiny "does this key actually work" call.
 *
 * Nothing is guessed: free models are detected from the provider's own pricing fields
 * when present, and otherwise fall back to the id convention (`:free`). The pure
 * filter is unit-tested; the network part is best-effort and never throws.
 */
object ProviderModelFetcher {

    private const val TAG = "ProviderModelFetcher"

    data class CatalogModel(
        val id: String,
        val isFree: Boolean,
        val inputPricePerMillion: Double?,
        val outputPricePerMillion: Double?,
    )

    /**
     * Pure free-model decision.
     *
     * @param id model id
     * @param inputPrice null when the provider omits pricing
     * @param outputPrice null when the provider omits pricing
     * @param curatedFreeIds ids the catalog marked free (used when pricing is missing)
     */
    fun isFreeModel(
        id: String,
        inputPrice: Double?,
        outputPrice: Double?,
        curatedFreeIds: Set<String> = emptySet(),
    ): Boolean {
        if (id.endsWith(":free", ignoreCase = true)) return true
        if (inputPrice != null && outputPrice != null) {
            return inputPrice <= 0.0 && outputPrice <= 0.0
        }
        return id in curatedFreeIds
    }

    suspend fun fetch(
        provider: ProviderCatalog.CatalogProvider,
        apiKey: String,
    ): List<CatalogModel> = withContext(Dispatchers.IO) {
        runCatching {
            val base = provider.baseUrl.trimEnd('/')
            val url = if (base.endsWith(provider.modelsPath)) base else base + provider.modelsPath
            val body = request(url, provider.protocol, apiKey) ?: return@runCatching emptyList()
            parseModels(body)
        }.onFailure { Log.w(TAG, "Model fetch for ${provider.id} failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /**
     * One tiny request to confirm the key works. Uses a models call so it is free on every
     * provider and never burns tokens.
     */
    suspend fun probeKey(provider: ProviderCatalog.CatalogProvider, apiKey: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = provider.baseUrl.trimEnd('/')
                val url = if (base.endsWith(provider.modelsPath)) base else base + provider.modelsPath
                request(url, provider.protocol, apiKey) != null
            }.onFailure { Log.w(TAG, "Probe for ${provider.id} failed: ${it.message}") }
                .getOrDefault(false)
        }

    /** Parses common `/models` shapes (OpenAI `{data:[…]}`, Ollama `{models:[…]}`). */
    fun parseModels(body: String): List<CatalogModel> {
        return runCatching {
            val root = JSONObject(body)
            val array = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id").ifBlank { item.optString("name") }
                    if (id.isBlank()) continue
                    val pricing = item.optJSONObject("pricing")
                    val input = pricing?.optString("prompt")?.toDoubleOrNull()
                    val output = pricing?.optString("completion")?.toDoubleOrNull()
                    add(
                        CatalogModel(
                            id = id,
                            isFree = isFreeModel(id, input, output),
                            inputPricePerMillion = input,
                            outputPricePerMillion = output,
                        ),
                    )
                }
            }
        }.onFailure { Log.w(TAG, "Model parse failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    private fun request(url: String, protocol: String, apiKey: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            when (protocol) {
                "anthropic" -> {
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("anthropic-version", "2023-06-01")
                }
                else -> if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            }
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code for $url")
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            connection.disconnect()
        }
    }
}
