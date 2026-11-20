package com.jarvis.assistant.update

import org.json.JSONObject

/**
 * Parsed form of the hosted `update.json` manifest.
 *
 * This is intentionally parsed by hand with [org.json] (never Gson reflection) so it keeps
 * working under R8 minification without extra keep rules, and so a malformed or hostile
 * document can only ever produce `null` — never a crash and never a lock-out.
 */
data class UpdateManifest(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val minSupportedVersionCode: Int,
    val apkUrl: String,
    val apkSha256: String,
    val apkSizeBytes: Long,
    val releaseNotes: String,
    val mandatory: Boolean,
    val publishedAt: String,
) {
    companion object {
        /**
         * Parse the manifest. Returns null when the document is missing, malformed, uses a
         * non-HTTPS APK URL, or lacks a usable version/hash. Callers treat null as
         * "no update" so a broken server can never block the app.
         */
        fun fromJson(raw: String?): UpdateManifest? {
            if (raw.isNullOrBlank()) return null
            return try {
                val o = JSONObject(raw)
                val latestCode = o.optInt("latestVersionCode", 0)
                val apkUrl = o.optString("apkUrl", "").trim()
                val sha = o.optString("apkSha256", "").trim()
                if (latestCode <= 0) return null
                if (!isHttps(apkUrl)) return null
                if (!isValidSha256(sha)) return null
                UpdateManifest(
                    latestVersionCode = latestCode,
                    latestVersionName = o.optString("latestVersionName", "").trim(),
                    minSupportedVersionCode = o.optInt("minSupportedVersionCode", 0),
                    apkUrl = apkUrl,
                    apkSha256 = sha.lowercase(),
                    apkSizeBytes = o.optLong("apkSizeBytes", 0L),
                    releaseNotes = o.optString("releaseNotes", "").trim(),
                    mandatory = o.optBoolean("mandatory", false),
                    publishedAt = o.optString("publishedAt", "").trim(),
                )
            } catch (t: Throwable) {
                null
            }
        }

        /** Only HTTPS is accepted anywhere in the update flow. */
        fun isHttps(url: String): Boolean = url.startsWith("https://", ignoreCase = true)

        /** A SHA-256 must be exactly 64 hexadecimal characters. */
        fun isValidSha256(value: String): Boolean =
            value.length == 64 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }
}
