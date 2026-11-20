package com.jarvis.assistant.network

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Small text-only Gemini client used by the auto-reply pipeline. The Live API
 * already owns the voice session; this client makes independent one-shot calls
 * so notifications can be answered without opening a microphone session.
 */
object GeminiTextClient {
    private const val TAG = "GeminiTextClient"
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun generateReply(
        context: Context,
        appName: String,
        sender: String,
        message: String
    ): String? = withContext(Dispatchers.IO) {
        val prefs = JarvisApp.instance.preferences
        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            Log.w(TAG, "Auto-reply skipped: Gemini API key is not configured")
            return@withContext null
        }

        val persona = com.jarvis.assistant.data.model.GeminiConstants.personaDisplayName(prefs.personality)
        val prompt = buildString {
            appendLine("You are replying on behalf of ${prefs.userName} to an incoming $appName chat message.")
            appendLine("Sender Name: $sender")
            appendLine("Incoming Message: \"$message\"")
            appendLine("Persona Style: $persona")
            appendLine()
            appendLine("Guidelines:")
            appendLine("- Carefully read and understand the incoming message.")
            appendLine("- Generate a smart, context-aware, helpful, and natural reply specifically answering or reacting to what was asked.")
            appendLine("- Match the language and tone of the message (Hindi, Hinglish, English, or Indian vernacular).")
            appendLine("- Keep it concise (1-2 sentences), natural like a real human chat reply. Zero robotic filler, zero quotes, zero markdown.")
            appendLine("- If someone asks where you/user is, what you're doing, or when you're available, answer naturally.")
            appendLine("- If asked for sensitive info (OTP, passwords, money), decline or say you will check later.")
            appendLine("- Output ONLY the reply text directly.")
        }

        for (model in candidateModels(prefs.aiModel)) {
            val reply = requestReply(model, prompt, apiKey)
            if (!reply.isNullOrBlank()) return@withContext reply
        }
        Log.w(TAG, "Auto-reply generation failed for every candidate model")
        null
    }

    private fun candidateModels(configuredModel: String): List<String> {
        val models = linkedSetOf<String>()
        // Prioritize fast, reliable text generation models for instant notifications
        models.add("gemini-2.5-flash")
        models.add("gemini-2.0-flash")
        models.add("gemini-1.5-flash")
        val liveModel = configuredModel
            .removePrefix("models/")
            .removeSuffix("-live-preview")
            .removeSuffix("-live")
            .removeSuffix("-agent")
        if (liveModel.matches(Regex("^gemini-[a-z0-9.\\-]+$"))) {
            models.add(liveModel)
        }
        return models.toList()
    }

    private fun requestReply(model: String, prompt: String, apiKey: String): String? {
        val payload = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.75)
                put("maxOutputTokens", 120)
                put("candidateCount", 1)
            })
        }

        val request = Request.Builder()
            .url("$ENDPOINT$model:generateContent?key=$apiKey")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Model $model failed (${response.code}): ${body.take(160)}")
                    return null
                }
                val text = JSONObject(body)
                    .optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.trim()
                text?.takeIf { it.isNotBlank() }?.take(500)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Model $model request failed: ${t.message}")
            null
        }
    }
}
