package com.jarvis.assistant.data.model

import com.google.gson.annotations.SerializedName

object GeminiConstants {
    const val MODEL_3_1_FLASH = "models/gemini-3.1-flash-live-preview"
    const val MODEL_3_8_LIVE = "models/gemini-3.8-live"
    const val MODEL_3_1_FLASH_AGENT = "models/gemini-3.1-flash-agent"
    const val MODEL_2_0_FLASH = MODEL_3_8_LIVE
    const val MODEL_2_0_FLASH_REALTIME = MODEL_3_8_LIVE

    const val RELEASE_API_KEY = ""

    const val DEFAULT_MODEL = MODEL_3_8_LIVE

    val SUPPORTED_MODELS = listOf(
        MODEL_3_1_FLASH,
        MODEL_3_8_LIVE,
        MODEL_3_1_FLASH_AGENT
    )

    const val DEFAULT_LANGUAGE = "Auto"

    val SUPPORTED_LANGUAGES = listOf(
        "Auto",
        "English",
        "Hindi",
        "Hinglish",
        "Telugu",
        "Tamil",
        "Kannada",
        "Malayalam",
        "Bengali",
        "Marathi",
        "Gujarati",
        "Bhojpuri",
        "Punjabi",
        "Odia",
        "Urdu"
    )

    const val DEFAULT_VOICE = "Puck"

    val SUPPORTED_VOICES = listOf(
        "Aoede",
        "Charon",
        "Kore",
        "Fenrir",
        "Puck",
        "Leda",
        "Orus",
        "Zephyr"
    )

    // Only three personas exist. Values are kept stable so older installs migrate cleanly.
    const val PERSONALITY_ASSISTANT = "Jarvis Pro"
    const val PERSONALITY_VIRUS = "Virus Mode"
    const val PERSONALITY_GIRLFRIEND = "Girlfriend Mode"

    val SUPPORTED_PERSONALITIES = listOf(
        PERSONALITY_ASSISTANT,
        PERSONALITY_VIRUS,
        PERSONALITY_GIRLFRIEND
    )

    /** Maps removed/legacy mode names to the closest surviving persona. */
    fun normalizePersonality(value: String?): String = when (value) {
        PERSONALITY_VIRUS -> PERSONALITY_VIRUS
        PERSONALITY_GIRLFRIEND, "Companion Mode" -> PERSONALITY_GIRLFRIEND
        else -> PERSONALITY_ASSISTANT
    }

    fun personaDisplayName(personality: String?): String = when (normalizePersonality(personality)) {
        PERSONALITY_VIRUS -> "Virus"
        PERSONALITY_GIRLFRIEND -> "Isha"
        else -> "Jarvis"
    }

    fun defaultVoiceFor(personality: String?): String = when (normalizePersonality(personality)) {
        PERSONALITY_VIRUS -> "Fenrir"     // deep, menacing male
        PERSONALITY_GIRLFRIEND -> "Aoede" // warm, cute female
        else -> "Puck"                    // witty, energetic male
    }

    const val WS_BASE_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val SESSION_RENEWAL_MS = 9 * 60 * 1000L // 9 minutes
}

// Request Models
data class GeminiBidiSetup(
    @SerializedName("setup") val setup: SetupPayload
)

data class SetupPayload(
    @SerializedName("model") val model: String,
    @SerializedName("generationConfig") val generationConfig: GenerationConfigPayload,
    @SerializedName("inputAudioTranscription") val inputAudioTranscription: EmptyConfig = EmptyConfig(),
    @SerializedName("outputAudioTranscription") val outputAudioTranscription: EmptyConfig = EmptyConfig(),
    @SerializedName("systemInstruction") val systemInstruction: ContentPayload? = null
)

data class GenerationConfigPayload(
    @SerializedName("responseModalities") val responseModalities: List<String> = listOf("AUDIO"),
    @SerializedName("speechConfig") val speechConfig: SpeechConfigPayload
)

data class EmptyConfig(val unused: String? = null)

data class SpeechConfigPayload(
    @SerializedName("voiceConfig") val voiceConfig: VoiceConfigPayload
)

data class VoiceConfigPayload(
    @SerializedName("prebuiltVoiceConfig") val prebuiltVoiceConfig: PrebuiltVoiceConfigPayload
)

data class PrebuiltVoiceConfigPayload(
    @SerializedName("voiceName") val voiceName: String
)

data class ContentPayload(
    @SerializedName("parts") val parts: List<PartTextPayload>
)

data class PartTextPayload(
    @SerializedName("text") val text: String
)

data class GeminiRealtimeInput(
    @SerializedName("realtimeInput") val realtimeInput: RealtimeAudioInput
)

data class RealtimeAudioInput(
    @SerializedName("audio") val audio: RealtimeAudioPayload? = null,
    // Signals a locally detected end of speech so Gemini need not wait for its
    // slower server-side silence timeout before starting a reply.
    @SerializedName("audioStreamEnd") val audioStreamEnd: Boolean? = null
)

data class RealtimeAudioPayload(
    @SerializedName("mimeType") val mimeType: String = "audio/pcm;rate=16000",
    @SerializedName("data") val data: String
)

// Response Models
data class GeminiBidiServerResponse(
    @SerializedName("serverContent") val serverContent: ServerContentPayload? = null
)

data class ServerContentPayload(
    @SerializedName("modelTurn") val modelTurn: ModelTurnPayload? = null,
    @SerializedName("turnComplete") val turnComplete: Boolean = false,
    @SerializedName("interrupted") val interrupted: Boolean = false
)

data class ModelTurnPayload(
    @SerializedName("parts") val parts: List<ModelPartPayload>? = null
)

data class ModelPartPayload(
    @SerializedName("text") val text: String? = null,
    @SerializedName("inlineData") val inlineData: InlineAudioPayload? = null
)

data class InlineAudioPayload(
    @SerializedName("mimeType") val mimeType: String? = null,
    @SerializedName("data") val data: String? = null
)
