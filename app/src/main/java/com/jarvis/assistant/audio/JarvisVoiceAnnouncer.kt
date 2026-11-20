package com.jarvis.assistant.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.network.GeminiLiveWebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Replacement for Android's [android.speech.tts.TextToSpeech]: speaks short
 * standby/offline phrases without the platform TTS engine.
 *
 * Priority order:
 *  1. In-memory PCM cache (text -> rendered audio), so repeated phrases
 *     (e.g. the same call question) are instant and cost no API calls.
 *  2. A one-shot Gemini Live session that is asked to speak exactly the
 *     requested sentence; its audio streams into an AudioPlayer.
 *  3. A notification fallback when synthesis is unavailable (no key,
 *     offline, quota) so the message is never silently lost.
 *
 * The announcer is only used for standby/offline speech. While a live
 * conversation is running, the main Gemini Live session speaks instead.
 */
class JarvisVoiceAnnouncer(
    private val context: Context,
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String,
    private val voiceNameProvider: () -> String,
    private val notificationChannelId: String
) {
    companion object {
        private const val TAG = "JarvisAnnouncer"
        private const val MIN_SYNTH_BYTES = 320 // 20 ms @ 16 kHz mono PCM16
        private const val MAX_SYNTH_BYTES = 1_500_000 // ~30 s @ 24 kHz PCM16
        private const val FIRST_AUDIO_TIMEOUT_MS = 9_000L
        private const val TOTAL_SYNTH_TIMEOUT_MS = 18_000L
        private const val PLAYBACK_WAIT_TIMEOUT_MS = 60_000L
        private const val MAX_CACHE_ENTRIES = 24
        private const val MAX_CACHE_BYTES = 4 * 1024 * 1024
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val speaking = AtomicBoolean(false)
    private val sessionToken = AtomicLong(0L)

    @Volatile
    private var sharedPlayer: AudioPlayer? = null

    @Volatile
    private var ws: GeminiLiveWebSocket? = null

    @Volatile
    private var synthesisBuffer: ByteArrayOutputStream? = null

    @Volatile
    private var pendingText: String? = null

    @Volatile
    private var pendingUtteranceId: String = ""

    @Volatile
    private var pendingCallback: (() -> Unit)? = null

    private val cache = HashMap<String, ByteArray>()
    private var cacheBytes = 0

    @Volatile
    private var internalPlayer: AudioPlayer? = null

    /** Point the announcer at the live conversation's player when one exists. */
    fun attachAudioPlayer(player: AudioPlayer?) {
        sharedPlayer = player
    }

    /**
     * Speaks [text]. When another announcement is still in flight, the newest
     * text replaces the pending one (the current utterance is not interrupted).
     */
    fun speak(text: String, utteranceId: String = "", onDone: (() -> Unit)? = null) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (!speaking.compareAndSet(false, true)) {
            pendingText = clean
            pendingUtteranceId = utteranceId
            pendingCallback = onDone
            return
        }
        val token = sessionToken.get()
        scope.launch {
            try {
                deliver(clean, utteranceId, onDone, token)
            } catch (t: Throwable) {
                Log.w(TAG, "speak failed: ${t.message}")
                fallbackNotify(clean)
                finish(token, onDone)
            }
        }
    }

    fun isSpeaking(): Boolean = speaking.get() || (internalPlayer?.isPlaying() == true)

    /** Stops synthesis and playback immediately (e.g. a barge-in interrupts it). */
    fun stop() {
        sessionToken.incrementAndGet()
        speaking.set(false)
        pendingText = null
        pendingCallback = null
        val current = ws
        ws = null
        runCatching { current?.disconnect() }
        synthesisBuffer = null
        sharedPlayer?.flush()
        internalPlayer?.flush()
    }

    fun shutdown() {
        stop()
        runCatching { internalPlayer?.release() }
        internalPlayer = null
        scope.cancel()
    }

    private fun deliver(text: String, utteranceId: String, onDone: (() -> Unit)?, token: Long) {
        if (token != sessionToken.get()) return
        val cached = cache[text]
        if (cached != null) {
            playPcm(cached, text, utteranceId, onDone, token)
            return
        }
        if (apiKeyProvider().isBlank()) {
            fallbackNotify(text)
            finish(token, onDone)
            return
        }
        startSynthesis(text, utteranceId, onDone, token)
    }

    // ------------------------------------------------------------------
    // One-shot Gemini Live synthesis
    // ------------------------------------------------------------------

    private fun startSynthesis(text: String, utteranceId: String, onDone: (() -> Unit)?, token: Long) {
        val buffer = ByteArrayOutputStream()
        synthesisBuffer = buffer
        val turnCompleted = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val lastChunkAt = AtomicLong(0L)
        val startedAt = SystemClock.elapsedRealtime()

        val announce = object : GeminiLiveWebSocket.Listener {
            override fun onConnectionStateChanged(status: String) {
                Log.i(TAG, "One-shot synthesis state: $status")
                if (status == "LIVE") {
                    ws?.sendClientContent(
                        "Speak this EXACTLY as ONE short spoken sentence and nothing else: " +
                            "no tools, no commentary, no preamble. Sentence: \"$text\""
                    )
                }
            }

            override fun onAudioDataReceived(pcmData: ByteArray) {
                if (token != sessionToken.get()) return
                val buf = synthesisBuffer ?: return
                synchronized(buf) {
                    if (buf.size() < MAX_SYNTH_BYTES) buf.write(pcmData)
                }
                lastChunkAt.set(SystemClock.elapsedRealtime())
            }

            override fun onAssistantTextReceived(textChunk: String) {}

            override fun onUserTextReceived(textChunk: String) {}

            override fun onInterrupted() {
                turnCompleted.set(true)
            }

            override fun onTurnCompleted() {
                turnCompleted.set(true)
            }

            override fun onError(message: String, isFatal: Boolean, errorCode: String?) {
                Log.w(TAG, "One-shot synthesis error: $message (code=$errorCode)")
                failed.set(true)
            }

            override fun onToolCall(callId: String, name: String, args: JSONObject) {}
        }

        val socket = GeminiLiveWebSocket(
            apiKey = apiKeyProvider(),
            model = modelProvider(),
            voiceName = voiceNameProvider(),
            systemPrompt = "You are JARVIS, a voice assistant. Your only job here is to " +
                "speak the exact sentence the app requests, out loud, as one short spoken " +
                "sentence. Never call tools. Never add extra commentary.",
            listener = announce
        )
        ws = socket
        socket.connect(scope)

        // Watchdog: wait for first audio, then for a silence gap / turn end.
        scope.launch {
            while (isActive && token == sessionToken.get()) {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (elapsed > TOTAL_SYNTH_TIMEOUT_MS) {
                    Log.w(TAG, "One-shot synthesis timed out")
                    failed.set(true)
                    break
                }
                if (failed.get()) break
                val lastChunk = lastChunkAt.get()
                if (lastChunk > 0L) {
                    val sinceChunk = SystemClock.elapsedRealtime() - lastChunk
                    if (turnCompleted.get() || sinceChunk >= 1_200L) break
                } else if (elapsed > FIRST_AUDIO_TIMEOUT_MS) {
                    Log.w(TAG, "No synthesis audio within ${FIRST_AUDIO_TIMEOUT_MS}ms")
                    failed.set(true)
                    break
                }
                kotlinx.coroutines.delay(200L)
            }
            if (token != sessionToken.get()) return@launch

            val bytes = synchronized(buffer) { buffer.toByteArray() }
            if (ws === socket) {
                ws = null
                runCatching { socket.disconnect() }
            }
            synthesisBuffer = null

            if (failed.get() || bytes.size < MIN_SYNTH_BYTES) {
                Log.w(TAG, "One-shot synthesis produced no usable audio (${bytes.size} bytes)")
                fallbackNotify(text)
                finish(token, onDone)
            } else {
                putCache(text, bytes)
                playPcm(bytes, text, utteranceId, onDone, token)
            }
        }
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    private fun playPcm(bytes: ByteArray, text: String, utteranceId: String, onDone: (() -> Unit)?, token: Long) {
        if (token != sessionToken.get()) return
        val player = sharedPlayer ?: internalPlayer()
        player.enqueueAudio(bytes)
        val startedPlayback = SystemClock.elapsedRealtime()
        scope.launch {
            while (isActive && token == sessionToken.get()) {
                if (SystemClock.elapsedRealtime() - startedPlayback > PLAYBACK_WAIT_TIMEOUT_MS) break
                if (!player.isPlaying()) {
                    kotlinx.coroutines.delay(400L)
                    if (!player.isPlaying()) break
                } else {
                    kotlinx.coroutines.delay(200L)
                }
            }
            if (token == sessionToken.get()) {
                finish(token, onDone)
            }
        }
    }

    private fun finish(token: Long, onDone: (() -> Unit)?) {
        if (token != sessionToken.get()) return
        speaking.set(false)
        val next = pendingText
        val nextId = pendingUtteranceId
        val nextCallback = pendingCallback
        pendingText = null
        pendingCallback = null
        onDone?.invoke()
        if (!next.isNullOrBlank()) {
            speak(next, nextId, nextCallback)
        }
    }

    // ------------------------------------------------------------------
    // Cache and notification fallback
    // ------------------------------------------------------------------

    private fun putCache(text: String, bytes: ByteArray) {
        synchronized(cache) {
            if (cache.containsKey(text) || bytes.size > MAX_CACHE_BYTES) return
            if (cache.size >= MAX_CACHE_ENTRIES || cacheBytes + bytes.size > MAX_CACHE_BYTES) {
                cache.clear()
                cacheBytes = 0
            }
            cache[text] = bytes
            cacheBytes += bytes.size
        }
    }

    private fun fallbackNotify(text: String) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                manager.getNotificationChannel(notificationChannelId) == null
            ) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        notificationChannelId,
                        "Jarvis",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            manager.notify(
                "jarvis_announcer".hashCode(),
                NotificationCompat.Builder(context, notificationChannelId)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("Jarvis")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()
            )
        }.onFailure { Log.w(TAG, "Fallback notification failed: ${it.message}") }
    }

    private fun internalPlayer(): AudioPlayer {
        val existing = internalPlayer
        if (existing != null) return existing
        return synchronized(this) {
            internalPlayer ?: AudioPlayer(
                onPlaybackStateChanged = {},
                onPlaybackAmplitude = {}
            ).also { player ->
                player.start()
                internalPlayer = player
            }
        }
    }
}
