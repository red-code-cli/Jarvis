package com.jarvis.assistant.harness

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Remote-updatable catalog of API providers (id, name, protocol, base URL, where to get a
 * key and how to spot free models).
 *
 * The app ships a bundled copy in `assets/providers/providers.json`. A remote copy with the
 * same schema can be cached here by "Update all" so a changed URL is fixed without an app
 * update. If the remote copy is missing or invalid, the bundled copy is always used.
 */
object ProviderCatalog {

    private const val TAG = "ProviderCatalog"
    private const val ASSET = "providers/providers.json"
    private const val PREFS = "provider_catalog"
    private const val KEY_REMOTE = "remote_json"

    /** Optional remote catalog; failures are non-fatal and keep the bundled copy. */
    const val REMOTE_URL = "https://raw.githubusercontent.com/Vempire-X/Jarvis/main/providers/providers.json"

    data class CatalogProvider(
        val id: String,
        val name: String,
        val protocol: String,
        val baseUrl: String,
        val modelsPath: String,
        val keyUrl: String,
        val freeRule: String,
        val needsRegion: Boolean = false,
        val needsResource: Boolean = false,
    ) {
        /** Official-asset-free avatar: the provider's initial. */
        val letterAvatar: String get() = name.trim().take(1).uppercase().ifBlank { "?" }
        val isLocal: Boolean get() = baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1")
    }

    fun load(context: Context): List<CatalogProvider> {
        val bundled = parse(readAsset(context))
        val remoteJson = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REMOTE, null)
        val remote = remoteJson?.let(::parse).orEmpty()
        return if (remote.size >= 10) remote else bundled
    }

    fun saveRemote(context: Context, json: String): Boolean {
        val parsed = parse(json)
        if (parsed.size < 10) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_REMOTE, json).apply()
        return true
    }

    /**
     * Fetches the remote catalog and caches it. Returns true only when a valid catalog was
     * stored; any network or parse error leaves the previous/bundled copy untouched.
     */
    suspend fun refreshRemote(context: Context, url: String = REMOTE_URL): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 12_000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    if (connection.responseCode !in 200..299) return@runCatching false
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    saveRemote(context, body)
                } finally {
                    connection.disconnect()
                }
            }.onFailure { Log.w(TAG, "Remote provider catalog refresh failed: ${it.message}") }
                .getOrDefault(false)
        }

    private fun readAsset(context: Context): String =
        runCatching { context.assets.open(ASSET).bufferedReader().use { it.readText() } }.getOrDefault("")

    private fun parse(json: String): List<CatalogProvider> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val array = JSONObject(json).optJSONArray("providers") ?: JSONArray()
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                CatalogProvider(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    protocol = item.optString("protocol", "openai"),
                    baseUrl = item.optString("baseUrl"),
                    modelsPath = item.optString("modelsPath", "/models"),
                    keyUrl = item.optString("keyUrl"),
                    freeRule = item.optString("freeRule"),
                    needsRegion = item.optBoolean("needsRegion", false),
                    needsResource = item.optBoolean("needsResource", false),
                )
            }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
        }.onFailure { Log.w(TAG, "Provider catalog parse failed: ${it.message}") }
            .getOrDefault(emptyList())
    }
}
