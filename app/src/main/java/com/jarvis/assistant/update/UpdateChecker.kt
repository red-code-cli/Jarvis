package com.jarvis.assistant.update

import com.jarvis.assistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches the hosted `update.json` over the app's existing OkHttp client (no new networking
 * stack). HTTPS is enforced both on the requested URL and on the final, post-redirect URL, so
 * a redirect to plain HTTP is rejected. Any failure returns null (fail-open).
 */
object UpdateChecker {

    private const val TIMEOUT_MS = 9_000L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // Cross-protocol (https -> http) redirects are never followed silently.
            .followSslRedirects(true)
            .build()
    }

    fun manifestUrl(): String = BuildConfig.UPDATE_MANIFEST_URL

    /** Raw JSON body, or null when the URL is not HTTPS / unreachable / non-200 / redirects to HTTP. */
    suspend fun fetchJson(url: String = manifestUrl()): String? = withContext(Dispatchers.IO) {
        if (!UpdateManifest.isHttps(url)) return@withContext null
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                if (!UpdateManifest.isHttps(response.request.url.toString())) return@withContext null
                if (!response.isSuccessful) return@withContext null
                response.body?.string()
            }
        } catch (t: Throwable) {
            null
        }
    }
}
