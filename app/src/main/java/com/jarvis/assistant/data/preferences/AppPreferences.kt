package com.jarvis.assistant.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.model.GeminiConstants

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val secureApiPrefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            ENCRYPTED_API_PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Secure API-key storage could not be initialized", e)
        null
    }

    /**
     * Speaker embeddings are biometric data. They live in their own AES-256 encrypted
     * SharedPreferences file, separate from the API key. If the keystore is temporarily
     * unavailable we fall back to the old plain file so an existing profile is never lost.
     */
    private val secureVoicePrefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            ENCRYPTED_VOICE_PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Encrypted voiceprint storage could not be initialized; using legacy storage", e)
        null
    }

    /**
     * Per-app notification allow/deny policy (which apps may be read aloud or
     * auto-replied). App choices are personal data, so they are stored in their
     * own AES-256 encrypted prefs file, never in the plain one.
     */
    private val secureNotificationPrefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            ENCRYPTED_NOTIF_PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Encrypted notification policy storage could not be initialized", e)
        null
    }
    private val gson = Gson()

    init {
        migrateApiKeyToEncryptedStorage()
        migrateVoiceProfileToEncryptedStorage()
        val current = prefs.getString(KEY_MODEL, null)
        if (current == null || current !in GeminiConstants.SUPPORTED_MODELS) {
            prefs.edit().putString(KEY_MODEL, GeminiConstants.DEFAULT_MODEL).apply()
        }
    }

    companion object {
        private const val PREF_NAME = "jarvis_prefs"
        private const val ENCRYPTED_API_PREF_NAME = "jarvis_secure_api_prefs"
        private const val ENCRYPTED_VOICE_PREF_NAME = "jarvis_secure_voice_prefs"
        private const val ENCRYPTED_NOTIF_PREF_NAME = "jarvis_secure_notif_prefs"
        private const val KEY_NOTIFICATION_DENIED_PACKAGES = "key_notification_denied_packages"
        private const val KEY_NOTIFICATION_SEEN_PACKAGES = "key_notification_seen_packages"
        private const val TAG = "AppPreferences"
        private const val KEY_API_KEY = "key_api_key"
        private const val KEY_MODEL = "key_model"
        private const val KEY_VOICE = "key_voice"
        private const val KEY_PERSONALITY = "key_personality"
        private const val KEY_USER_NAME = "key_user_name"
        private const val KEY_MIC_MUTED = "key_mic_muted"
        private const val KEY_WAKE_SENSITIVITY = "key_wake_sensitivity"
        private const val KEY_WAKE_WORD_JARVIS = "key_wake_word_jarvis"
        private const val KEY_WAKE_WORD_ISHA = "key_wake_word_isha"
        private const val KEY_WAKE_WORD_VIRUS = "key_wake_word_virus"
        private const val KEY_INTERRUPT_SENSITIVITY = "key_interrupt_sensitivity"
        private const val KEY_SMART_BATTERY_SAVER = "key_smart_battery_saver"
        private const val KEY_NOISE_FILTER_ENABLED = "key_noise_filter_enabled"
        private const val KEY_VOICE_CALL_ANSWERING = "key_voice_call_answering"
        private const val KEY_UNREAD_MESSAGES_READER = "key_unread_messages_reader"
        private const val KEY_EMERGENCY_SOS_ENABLED = "key_emergency_sos_enabled"
        private const val KEY_EMERGENCY_CONTACT_PHONE = "key_emergency_contact_phone"
        private const val KEY_ANTI_THEFT_ALARM_ENABLED = "key_anti_theft_alarm_enabled"
        private const val KEY_SHAKE_TO_TALK_ENABLED = "key_shake_to_talk_enabled"
        private const val KEY_ADVANCED_VOICE_PHONE_EXPANDED = "key_advanced_voice_phone_expanded"
        private const val KEY_CHAT_HISTORY = "key_chat_history"
        private const val KEY_FAVORITE_SONG = "key_favorite_song"
        private const val KEY_FAVORITE_APP = "key_favorite_app"
        private const val KEY_SONG_COUNTS = "key_song_counts"
        private const val KEY_USER_BIRTHDAY = "key_user_birthday"
        private const val KEY_USER_LOCATION = "key_user_location"
        private const val KEY_USER_AGE = "key_user_age"
        private const val KEY_DAILY_ROUTINE = "key_daily_routine"
        private const val KEY_OWNER_VOICE_ENABLED = "key_owner_voice_enabled"
        private const val KEY_OWNER_VOICE_PROFILE = "key_owner_voice_profile"
        private const val KEY_OWNER_VOICE_SAMPLES = "key_owner_voice_samples"
        private const val KEY_OWNER_VOICE_THRESHOLD = "key_owner_voice_threshold"
        private const val KEY_OWNER_VOICE_SAMPLE_COUNT = "key_owner_voice_sample_count"
        private const val KEY_OWNER_VOICE_FORMAT = "key_owner_voice_format"
        private const val KEY_VOICE_ENROLL_PROMPT_AT = "key_voice_enroll_prompt_at"
        // Speaker-embedding version stored alongside the profile: legacy builds saved 128-d
        // x-vectors, the sherpa-onnx engine saves CAM++ embeddings (192-d). Because the two live
        // in different vector spaces, a legacy profile is treated as "not enrolled" so the user
        // is asked to re-enroll instead of the voiceprint never matching.
        private const val OWNER_VOICE_FORMAT_SHERPA = 2
        private const val KEY_LANGUAGE = "key_language"
        private const val KEY_SILENT_EXECUTION_MODE = "key_silent_execution_mode"
        private const val KEY_AUTO_REPLY_ENABLED = "key_auto_reply_enabled"
        private const val KEY_AUTO_REPLY_COOLDOWN_SECONDS = "key_auto_reply_cooldown_seconds"
        private const val KEY_AUTO_REPLY_APPS = "key_auto_reply_apps"
        private const val KEY_PROFILE_PHOTO_PATH = "key_profile_photo_path"
        private const val KEY_USER_VOICE_GENDER = "key_user_voice_gender"
        private const val KEY_CALL_ANNOUNCEMENTS = "key_call_announcements"
        private const val KEY_TASK_OVERLAY_ENABLED = "key_task_overlay_enabled"
        private const val KEY_TASK_OVERLAY_AVATAR = "key_task_overlay_avatar"
        private const val KEY_TASK_OVERLAY_MODE = "key_task_overlay_mode"
        private const val KEY_TASK_OVERLAY_EDGE = "key_task_overlay_edge"
        private const val KEY_TASK_OVERLAY_FREE_POSITION = "key_task_overlay_free_position"
        private const val KEY_TASK_OVERLAY_X = "key_task_overlay_x"
        private const val KEY_TASK_OVERLAY_Y = "key_task_overlay_y"
        private const val KEY_APP_THEME = "key_app_theme"
        private const val KEY_CUSTOM_THEME_COLOR = "key_custom_theme_color"
        private const val KEY_ORB_ANIMATION = "key_orb_animation"
        private const val KEY_ORB_SPEED = "key_orb_speed"
        private const val KEY_ORB_GLOW = "key_orb_glow"
        private const val KEY_ORB_SCALE = "key_orb_scale"
        private const val KEY_ORB_HUE = "key_orb_hue"
        private const val KEY_CUSTOM_ENDPOINT = "key_custom_endpoint"
        // In-app updater (new keys only; existing keys/defaults are never changed).
        private const val KEY_UPDATE_FORCE_ACTIVE = "key_update_force_active"
        private const val KEY_UPDATE_LAST_CHECK = "key_update_last_check"
        private const val KEY_UPDATE_CACHED_MANIFEST = "key_update_cached_manifest"
        private const val KEY_UPDATE_SKIP_VERSION = "key_update_skip_version"
        private const val KEY_UPDATE_LAST_ERROR = "key_update_last_error"
    }

    // ── In-app updater ────────────────────────────────────────────────────────
    var updateForceActive: Boolean
        get() = prefs.getBoolean(KEY_UPDATE_FORCE_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_UPDATE_FORCE_ACTIVE, value).apply()

    var updateLastCheckAt: Long
        get() = prefs.getLong(KEY_UPDATE_LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_UPDATE_LAST_CHECK, value).apply()

    var updateCachedManifestJson: String
        get() = prefs.getString(KEY_UPDATE_CACHED_MANIFEST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_UPDATE_CACHED_MANIFEST, value).apply()

    var updateSkipVersionCode: Int
        get() = prefs.getInt(KEY_UPDATE_SKIP_VERSION, 0)
        set(value) = prefs.edit().putInt(KEY_UPDATE_SKIP_VERSION, value).apply()

    var updateLastError: String
        get() = prefs.getString(KEY_UPDATE_LAST_ERROR, "") ?: ""
        set(value) = prefs.edit().putString(KEY_UPDATE_LAST_ERROR, value).apply()

    var isOwnerVoiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_OWNER_VOICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_OWNER_VOICE_ENABLED, value).apply()

    var ownerVoiceThreshold: Float
        get() {
            val stored = prefs.getFloat(KEY_OWNER_VOICE_THRESHOLD, 0.70f)
            // Auto-migrate older builds that saved overly strict 0.50f threshold
            return if (stored < 0.60f) 0.70f else stored
        }
        set(value) = prefs.edit().putFloat(KEY_OWNER_VOICE_THRESHOLD, value).apply()

    var ownerVoiceSampleCount: Int
        get() = prefs.getInt(KEY_OWNER_VOICE_SAMPLE_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_OWNER_VOICE_SAMPLE_COUNT, value).apply()

    /** Last time the "enroll your voice" hint was spoken/shown (new key, default 0 = never). */
    var voiceEnrollPromptAt: Long
        get() = prefs.getLong(KEY_VOICE_ENROLL_PROMPT_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_VOICE_ENROLL_PROMPT_AT, value).apply()

    /** Reads a biometric blob from the encrypted store first, then the legacy plain store. */
    private fun readVoiceString(key: String): String? {
        val encrypted = secureVoicePrefs?.getString(key, null)
        if (!encrypted.isNullOrBlank()) return encrypted
        return prefs.getString(key, null)
    }

    /** Copies any pre-upgrade plaintext voiceprint into encrypted storage exactly once. */
    private fun migrateVoiceProfileToEncryptedStorage() {
        val secure = secureVoicePrefs ?: return
        try {
            if (prefs.getInt(KEY_OWNER_VOICE_FORMAT, 1) < OWNER_VOICE_FORMAT_SHERPA) return
            val legacyProfile = prefs.getString(KEY_OWNER_VOICE_PROFILE, null)
            val legacySamples = prefs.getString(KEY_OWNER_VOICE_SAMPLES, null)
            if (legacyProfile.isNullOrBlank()) return
            if (!secure.getString(KEY_OWNER_VOICE_PROFILE, null).isNullOrBlank()) {
                // Already migrated earlier; just remove the plaintext copy.
                prefs.edit().remove(KEY_OWNER_VOICE_PROFILE).remove(KEY_OWNER_VOICE_SAMPLES).apply()
                return
            }
            val editor = secure.edit().putString(KEY_OWNER_VOICE_PROFILE, legacyProfile)
            if (!legacySamples.isNullOrBlank()) editor.putString(KEY_OWNER_VOICE_SAMPLES, legacySamples)
            if (editor.commit()) {
                prefs.edit().remove(KEY_OWNER_VOICE_PROFILE).remove(KEY_OWNER_VOICE_SAMPLES).apply()
                Log.i(TAG, "Voiceprint migrated to encrypted storage")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Voiceprint encryption migration skipped: ${t.message}")
        }
    }

    fun hasOwnerVoiceProfile(): Boolean {
        if (prefs.getInt(KEY_OWNER_VOICE_FORMAT, 1) < OWNER_VOICE_FORMAT_SHERPA) return false
        return !readVoiceString(KEY_OWNER_VOICE_PROFILE).isNullOrBlank()
    }

    fun getOwnerVoiceProfile(): FloatArray? {
        if (prefs.getInt(KEY_OWNER_VOICE_FORMAT, 1) < OWNER_VOICE_FORMAT_SHERPA) return null
        val json = readVoiceString(KEY_OWNER_VOICE_PROFILE) ?: return null
        return try {
            val arr = org.json.JSONArray(json)
            FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
        } catch (e: Exception) {
            null
        }
    }

    fun getOwnerVoiceSamples(): List<FloatArray> {
        if (prefs.getInt(KEY_OWNER_VOICE_FORMAT, 1) < OWNER_VOICE_FORMAT_SHERPA) return emptyList()
        val json = readVoiceString(KEY_OWNER_VOICE_SAMPLES) ?: return emptyList()
        return try {
            val root = org.json.JSONArray(json)
            val list = mutableListOf<FloatArray>()
            for (i in 0 until root.length()) {
                val arr = root.getJSONArray(i)
                val vec = FloatArray(arr.length()) { j -> arr.getDouble(j).toFloat() }
                if (vec.isNotEmpty()) list.add(vec)
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveOwnerVoiceProfile(masterProfile: FloatArray, sampleVectors: List<FloatArray>) {
        val masterArr = org.json.JSONArray()
        for (v in masterProfile) {
            masterArr.put(v.toDouble())
        }

        val samplesArr = org.json.JSONArray()
        for (vec in sampleVectors) {
            val arr = org.json.JSONArray()
            for (v in vec) {
                arr.put(v.toDouble())
            }
            samplesArr.put(arr)
        }

        val editor = prefs.edit()
            .putInt(KEY_OWNER_VOICE_SAMPLE_COUNT, sampleVectors.size)
            .putInt(KEY_OWNER_VOICE_FORMAT, OWNER_VOICE_FORMAT_SHERPA)
            .putBoolean(KEY_OWNER_VOICE_ENABLED, true)
        val secure = secureVoicePrefs
        if (secure != null) {
            val saved = secure.edit()
                .putString(KEY_OWNER_VOICE_PROFILE, masterArr.toString())
                .putString(KEY_OWNER_VOICE_SAMPLES, samplesArr.toString())
                .commit()
            if (saved) {
                editor.remove(KEY_OWNER_VOICE_PROFILE).remove(KEY_OWNER_VOICE_SAMPLES).apply()
                return
            }
            Log.w(TAG, "Encrypted voiceprint save failed; keeping a functional legacy copy")
        }
        editor
            .putString(KEY_OWNER_VOICE_PROFILE, masterArr.toString())
            .putString(KEY_OWNER_VOICE_SAMPLES, samplesArr.toString())
            .apply()
    }

    fun saveOwnerVoiceProfile(profile: FloatArray, sampleCount: Int) {
        val arr = org.json.JSONArray()
        for (v in profile) {
            arr.put(v.toDouble())
        }
        val editor = prefs.edit()
            .putInt(KEY_OWNER_VOICE_SAMPLE_COUNT, sampleCount)
            .putInt(KEY_OWNER_VOICE_FORMAT, OWNER_VOICE_FORMAT_SHERPA)
            .putBoolean(KEY_OWNER_VOICE_ENABLED, true)
        val secure = secureVoicePrefs
        if (secure != null) {
            val saved = secure.edit()
                .putString(KEY_OWNER_VOICE_PROFILE, arr.toString())
                .commit()
            if (saved) {
                editor.remove(KEY_OWNER_VOICE_PROFILE).apply()
                return
            }
        }
        editor.putString(KEY_OWNER_VOICE_PROFILE, arr.toString()).apply()
    }

    fun clearOwnerVoiceProfile() {
        prefs.edit()
            .remove(KEY_OWNER_VOICE_PROFILE)
            .remove(KEY_OWNER_VOICE_SAMPLES)
            .remove(KEY_OWNER_VOICE_SAMPLE_COUNT)
            .remove(KEY_OWNER_VOICE_FORMAT)
            .putBoolean(KEY_OWNER_VOICE_ENABLED, false)
            .apply()
        secureVoicePrefs?.edit()
            ?.remove(KEY_OWNER_VOICE_PROFILE)
            ?.remove(KEY_OWNER_VOICE_SAMPLES)
            ?.apply()
    }

    var apiKey: String
        get() = secureApiPrefs?.getString(KEY_API_KEY, "")?.trim() ?: ""
        set(value) {
            val secure = checkNotNull(secureApiPrefs) { "Secure API-key storage is unavailable on this device." }
            val clean = value.trim()
            val saved = if (clean.isEmpty()) secure.edit().remove(KEY_API_KEY).commit()
            else secure.edit().putString(KEY_API_KEY, clean).commit()
            check(saved) { "Could not save API key to encrypted storage." }
        }

    val canStoreApiKeySecurely: Boolean get() = secureApiPrefs != null

    private fun migrateApiKeyToEncryptedStorage() {
        val legacyKey = prefs.getString(KEY_API_KEY, null)?.trim().orEmpty()
        if (legacyKey.isEmpty()) return
        val secure = secureApiPrefs ?: run {
            prefs.edit().remove(KEY_API_KEY).commit()
            return
        }
        try {
            val existing = secure.getString(KEY_API_KEY, null)
            if (existing.isNullOrEmpty()) {
                check(secure.edit().putString(KEY_API_KEY, legacyKey).commit())
            }
            if (!secure.getString(KEY_API_KEY, null).isNullOrEmpty()) prefs.edit().remove(KEY_API_KEY).commit()
        } catch (e: Exception) {
            Log.e(TAG, "Legacy API-key migration to encrypted storage failed", e)
        }
    }

    var aiModel: String
        get() {
            val stored = prefs.getString(KEY_MODEL, GeminiConstants.DEFAULT_MODEL) ?: GeminiConstants.DEFAULT_MODEL
            if (stored !in GeminiConstants.SUPPORTED_MODELS) {
                prefs.edit().putString(KEY_MODEL, GeminiConstants.DEFAULT_MODEL).apply()
                return GeminiConstants.DEFAULT_MODEL
            }
            return stored
        }
        set(value) {
            val toSave = if (value in GeminiConstants.SUPPORTED_MODELS) value else GeminiConstants.DEFAULT_MODEL
            prefs.edit().putString(KEY_MODEL, toSave).apply()
        }

    var language: String
        get() {
            val stored = prefs.getString(KEY_LANGUAGE, GeminiConstants.DEFAULT_LANGUAGE) ?: GeminiConstants.DEFAULT_LANGUAGE
            return if (stored.contains("Auto", ignoreCase = true)) "Auto" else stored
        }
        set(value) {
            val clean = if (value.contains("Auto", ignoreCase = true)) "Auto" else value.trim()
            prefs.edit().putString(KEY_LANGUAGE, clean).apply()
        }

    var voice: String
        get() = prefs.getString(KEY_VOICE, GeminiConstants.DEFAULT_VOICE) ?: GeminiConstants.DEFAULT_VOICE
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    var personality: String
        get() = GeminiConstants.normalizePersonality(
            prefs.getString(KEY_PERSONALITY, GeminiConstants.PERSONALITY_ASSISTANT)
        )
        set(value) = prefs.edit()
            .putString(KEY_PERSONALITY, GeminiConstants.normalizePersonality(value))
            .apply()

    /** Last voice-gender guess from the mic pitch analysis: male / female / unknown. */
    var userVoiceGender: String
        get() = prefs.getString(KEY_USER_VOICE_GENDER, "unknown") ?: "unknown"
        set(value) {
            val clean = value.lowercase().takeIf { it == "male" || it == "female" } ?: "unknown"
            prefs.edit().putString(KEY_USER_VOICE_GENDER, clean).apply()
        }

    /** Local copy of the user's chosen profile picture, shown on every persona. */
    var profilePhotoPath: String
        get() = prefs.getString(KEY_PROFILE_PHOTO_PATH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROFILE_PHOTO_PATH, value.trim()).apply()

    var isCallAnnouncementEnabled: Boolean
        get() = prefs.getBoolean(KEY_CALL_ANNOUNCEMENTS, true)
        set(value) = prefs.edit().putBoolean(KEY_CALL_ANNOUNCEMENTS, value).apply()

    var userName: String
        get() {
            val name = prefs.getString(KEY_USER_NAME, "") ?: ""
            return if (name.isBlank() || name == "Salaam") "dost" else name
        }
        set(value) {
            prefs.edit().putString(KEY_USER_NAME, value.trim()).commit()
        }

    var appTheme: String
        get() {
            val stored = prefs.getString(KEY_APP_THEME, "light") ?: "light"
            return when (stored) {
                "light", "pearl_white" -> "light"
                "dark", "midnight_onyx", "cyberpunk_neon", "emerald_obsidian" -> "dark"
                "red" -> "red"
                "sunset", "rose_gold" -> "sunset"
                "custom" -> "custom"
                else -> "light"
            }
        }
        set(value) = prefs.edit().putString(KEY_APP_THEME, value).apply()

    var customThemeColor: String
        get() = prefs.getString(KEY_CUSTOM_THEME_COLOR, "#FFFFFF") ?: "#FFFFFF"
        set(value) = prefs.edit().putString(KEY_CUSTOM_THEME_COLOR, value.trim()).apply()

    var orbAnimation: String
        get() {
            val valid = listOf("holographic_plasma", "cosmic_pulsar", "quantum_core", "cyber_ring", "siri_glow")
            val stored = prefs.getString(KEY_ORB_ANIMATION, "holographic_plasma") ?: "holographic_plasma"
            return if (stored in valid) stored else "holographic_plasma"
        }
        set(value) = prefs.edit().putString(KEY_ORB_ANIMATION, value).apply()

    var orbSpeed: Float
        get() = prefs.getFloat(KEY_ORB_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_ORB_SPEED, value).apply()

    var orbGlow: Float
        get() = prefs.getFloat(KEY_ORB_GLOW, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_ORB_GLOW, value).apply()

    var orbScale: Float
        get() = prefs.getFloat(KEY_ORB_SCALE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_ORB_SCALE, value).apply()

    var orbHue: Float
        get() = prefs.getFloat(KEY_ORB_HUE, -1f)
        set(value) = prefs.edit().putFloat(KEY_ORB_HUE, value).apply()

    var customEndpoint: String
        get() = prefs.getString(KEY_CUSTOM_ENDPOINT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_ENDPOINT, value.trim()).apply()

    var userBirthday: String
        get() = prefs.getString(KEY_USER_BIRTHDAY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USER_BIRTHDAY, value.trim()).commit()
        }

    var userLocation: String
        get() = prefs.getString(KEY_USER_LOCATION, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USER_LOCATION, value.trim()).commit()
        }

    var userAge: String
        get() = prefs.getString(KEY_USER_AGE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USER_AGE, value.trim()).commit()
        }

    var dailyRoutine: String
        get() = prefs.getString(KEY_DAILY_ROUTINE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DAILY_ROUTINE, value.trim()).commit()
        }

    var favoriteSong: String
        get() = prefs.getString(KEY_FAVORITE_SONG, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_FAVORITE_SONG, value.trim()).commit()
        }

    var favoriteApp: String
        get() = prefs.getString(KEY_FAVORITE_APP, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_FAVORITE_APP, value.trim()).commit()
        }

    var isMicMuted: Boolean
        get() = prefs.getBoolean(KEY_MIC_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_MIC_MUTED, value).apply()

    var wakeSensitivity: String
        get() {
            val s = prefs.getString(KEY_WAKE_SENSITIVITY, "balanced") ?: "balanced"
            return if (s in setOf("high", "balanced", "low")) s else "balanced"
        }
        set(value) {
            val toSave = if (value in setOf("high", "balanced", "low")) value else "balanced"
            prefs.edit().putString(KEY_WAKE_SENSITIVITY, toSave).apply()
        }

    /**
     * Which persona wake words the on-device detector listens for. Each phrase maps to one
     * persona (Jarvis / Isha / Virus); switching a phrase off removes it from the keyword
     * spotter so the same sound can never switch personas behind the user's back.
     */
    var isWakeWordJarvisEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD_JARVIS, true)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_WORD_JARVIS, value).apply()

    var isWakeWordIshaEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD_ISHA, true)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_WORD_ISHA, value).apply()

    var isWakeWordVirusEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD_VIRUS, true)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_WORD_VIRUS, value).apply()

    /** Display names of the wake words that are currently armed, in detector order. */
    val enabledWakeWords: List<String>
        get() {
            val words = mutableListOf<String>()
            if (isWakeWordJarvisEnabled) words += "Jarvis"
            if (isWakeWordIshaEnabled) words += "Isha"
            if (isWakeWordVirusEnabled) words += "Virus"
            return words
        }

    /**
     * How hard it is for background noise to interrupt Jarvis while it is speaking.
     * `strict` needs loud, sustained speech; `relaxed` lets softer speech cut in.
     */
    var interruptSensitivity: String
        get() {
            val s = prefs.getString(KEY_INTERRUPT_SENSITIVITY, "balanced") ?: "balanced"
            return if (s in setOf("strict", "balanced", "relaxed")) s else "balanced"
        }
        set(value) {
            val toSave = if (value in setOf("strict", "balanced", "relaxed")) value else "balanced"
            prefs.edit().putString(KEY_INTERRUPT_SENSITIVITY, toSave).apply()
        }

    var isSmartBatterySaverEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMART_BATTERY_SAVER, true)
        set(value) = prefs.edit().putBoolean(KEY_SMART_BATTERY_SAVER, value).apply()

    var isNoiseFilterEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOISE_FILTER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_NOISE_FILTER_ENABLED, value).apply()

    /** Hands-free call answering: Pick/Cut incoming calls by voice. */
    var isVoiceCallAnsweringEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_CALL_ANSWERING, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_CALL_ANSWERING, value).apply()

    /** Unread messages voice reader: "WhatsApp padh ke sunao". */
    var isUnreadMessagesReaderEnabled: Boolean
        get() = prefs.getBoolean(KEY_UNREAD_MESSAGES_READER, true)
        set(value) = prefs.edit().putBoolean(KEY_UNREAD_MESSAGES_READER, value).apply()

    /** True when notifications from [pkg] are never read aloud or auto-replied. */
    fun isNotificationPackageDenied(pkg: String): Boolean {
        val secure = secureNotificationPrefs ?: return false
        return try {
            val raw = secure.getString(KEY_NOTIFICATION_DENIED_PACKAGES, null) ?: return false
            val map: Map<String, Boolean>? =
                gson.fromJson(raw, object : TypeToken<Map<String, Boolean>>() {}.type)
            map?.get(pkg) == true
        } catch (_: Throwable) {
            false
        }
    }

    /** Sets the deny state for [pkg] in the encrypted store. */
    fun setNotificationPackageDenied(pkg: String, denied: Boolean) {
        val secure = secureNotificationPrefs ?: return
        try {
            val raw = secure.getString(KEY_NOTIFICATION_DENIED_PACKAGES, null)
            val map: MutableMap<String, Boolean> = raw?.let {
                gson.fromJson(it, object : TypeToken<MutableMap<String, Boolean>>() {}.type)
            } ?: mutableMapOf()
            if (denied) map[pkg] = true else map.remove(pkg)
            secure.edit().putString(KEY_NOTIFICATION_DENIED_PACKAGES, gson.toJson(map)).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "Could not persist notification policy", e)
        }
    }

    /** Recently seen notification packages, used only to populate the per-app settings list. */
    var notificationSeenPackages: Set<String>
        get() = prefs.getStringSet(KEY_NOTIFICATION_SEEN_PACKAGES, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_NOTIFICATION_SEEN_PACKAGES, value).apply()

    /** Records that we saw a notification from [pkg] (capped to keep the list small). */
    fun addNotificationSeenPackage(pkg: String) {
        val seen = notificationSeenPackages.toMutableSet()
        seen.add(pkg)
        if (seen.size > 50) {
            val sorted = seen.sortedByDescending { it }.take(50).toMutableSet()
            notificationSeenPackages = sorted
        } else {
            notificationSeenPackages = seen
        }
    }

    /** Emergency Voice SOS: "Code Red" / "Emergency" sends live location to contact. */
    var isEmergencySosEnabled: Boolean
        get() = prefs.getBoolean(KEY_EMERGENCY_SOS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_EMERGENCY_SOS_ENABLED, value).apply()

    /** Emergency contact phone number for SOS alerts. */
    var emergencyContactPhone: String
        get() = prefs.getString(KEY_EMERGENCY_CONTACT_PHONE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EMERGENCY_CONTACT_PHONE, value.trim()).apply()

    /** Anti-theft motion guard: Sounds alarm and Virus warning if moved. */
    var isAntiTheftAlarmEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANTI_THEFT_ALARM_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ANTI_THEFT_ALARM_ENABLED, value).apply()

    /** Shake to talk: Shaking the device activates voice conversation in loud places. */
    var isShakeToTalkEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHAKE_TO_TALK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SHAKE_TO_TALK_ENABLED, value).apply()

    /** Whether the "Advanced voice & phone controls" settings card is expanded. */
    var isAdvancedVoicePhoneControlsExpanded: Boolean
        get() = prefs.getBoolean(KEY_ADVANCED_VOICE_PHONE_EXPANDED, false)
        set(value) = prefs.edit().putBoolean(KEY_ADVANCED_VOICE_PHONE_EXPANDED, value).apply()

    var isSilentExecutionMode: Boolean
        get() = prefs.getBoolean(KEY_SILENT_EXECUTION_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_SILENT_EXECUTION_MODE, value).apply()

    /** JARVIS replies to incoming messages on the owner's behalf. */
    var isAutoReplyEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_REPLY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_REPLY_ENABLED, value).apply()

    /**
     * Which messaging apps may receive an automatic reply.
     * Supported logical keys: whatsapp, messenger, instagram.
     */
    var autoReplyApps: Set<String>
        get() {
            val stored = prefs.getStringSet(KEY_AUTO_REPLY_APPS, null)
                ?: return setOf("whatsapp", "messenger", "instagram")
            return stored.filter { it in setOf("whatsapp", "messenger", "instagram") }.toSet()
        }
        set(value) {
            prefs.edit()
                .putStringSet(
                    KEY_AUTO_REPLY_APPS,
                    value.filter { it in setOf("whatsapp", "messenger", "instagram") }.toSet()
                )
                .apply()
        }

    /** No cooldown by default: auto-replies stay instant. Kept for backward compatibility. */
    @Deprecated("Auto-reply cooldown was removed")
    var autoReplyCooldownSeconds: Int
        get() = 0
        set(_: Int) = Unit

    var isTaskOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_TASK_OVERLAY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TASK_OVERLAY_ENABLED, value).apply()

    var taskOverlayAvatar: String
        get() = prefs.getString(KEY_TASK_OVERLAY_AVATAR, "ghost") ?: "ghost"
        set(value) {
            val available = listOf("alien", "blob", "cat", "circle", "cloud", "droid", "drop", "flower", "ghost", "hexagon", "mech", "pebble", "pill", "puddle", "square", "star", "triangle")
            prefs.edit().putString(KEY_TASK_OVERLAY_AVATAR, value.takeIf { it in available } ?: "ghost").apply()
        }

    /** always keeps the avatar visible while the service is alive; working limits it to active tasks. */
    var taskOverlayMode: String
        get() = prefs.getString(KEY_TASK_OVERLAY_MODE, "always") ?: "always"
        set(value) = prefs.edit().putString(KEY_TASK_OVERLAY_MODE, if (value == "working") "working" else "always").apply()

    /** Preferred dock edge. Dragging the overlay updates this to the closest edge. */
    var taskOverlayEdge: String
        get() = prefs.getString(KEY_TASK_OVERLAY_EDGE, "right") ?: "right"
        set(value) = prefs.edit().putString(KEY_TASK_OVERLAY_EDGE, value.takeIf { it in setOf("left", "right", "top", "bottom") } ?: "right").apply()

    var taskOverlayFreePosition: Boolean
        get() = prefs.getBoolean(KEY_TASK_OVERLAY_FREE_POSITION, false)
        set(value) = prefs.edit().putBoolean(KEY_TASK_OVERLAY_FREE_POSITION, value).apply()

    var taskOverlayX: Int
        get() = prefs.getInt(KEY_TASK_OVERLAY_X, -1)
        set(value) = prefs.edit().putInt(KEY_TASK_OVERLAY_X, value.coerceAtLeast(0)).apply()

    var taskOverlayY: Int
        get() = prefs.getInt(KEY_TASK_OVERLAY_Y, -1)
        set(value) = prefs.edit().putInt(KEY_TASK_OVERLAY_Y, value.coerceAtLeast(0)).apply()

    fun getSongPlayCounts(): MutableMap<String, Int> {
        val json = prefs.getString(KEY_SONG_COUNTS, null) ?: return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, Int>>() {}.type
            gson.fromJson(json, type) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    fun recordSongPlay(rawSong: String): String {
        val cleanSong = rawSong.trim()
        if (cleanSong.isBlank()) return favoriteSong

        val counts = getSongPlayCounts()
        val currentCount = counts.getOrDefault(cleanSong.lowercase(), 0) + 1
        counts[cleanSong.lowercase()] = currentCount

        // Update stored JSON
        prefs.edit().putString(KEY_SONG_COUNTS, gson.toJson(counts)).apply()

        // Auto-promote to favorite song if played at least 2 times, or if favoriteSong is empty
        val currentFav = favoriteSong
        if (currentFav.isBlank() || currentCount >= 2) {
            // Find most played song
            val topSong = counts.maxByOrNull { it.value }?.key
            if (!topSong.isNullOrBlank()) {
                favoriteSong = cleanSong
            }
        }
        return favoriteSong
    }

    fun loadChatHistory(): List<ChatTurn> {
        val json = prefs.getString(KEY_CHAT_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ChatTurn>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveChatHistory(history: List<ChatTurn>) {
        val json = gson.toJson(history)
        prefs.edit().putString(KEY_CHAT_HISTORY, json).apply()
    }

    fun clearChatHistory() {
        prefs.edit().remove(KEY_CHAT_HISTORY).apply()
    }
}
