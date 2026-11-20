package com.jarvis.assistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Continuous 16 kHz wake-word engine built on sherpa-onnx keyword spotting (Apache-2.0).
 *
 * This is the free, on-device wake-word recognizer. It:
 *  - runs a streaming KWS model over the microphone in 20 ms frames;
 *  - keeps a rolling ~2 s PCM window so the wake utterance can be speaker-verified
 *    before Jarvis activates (no audio is forwarded to Gemini on a mismatch);
 *  - runs a tiny energy VAD over the same frames so a faint hit can be rejected without
 *    involving the (much heavier) speaker model;
 *  - retries the microphone forever instead of stopping after one error.
 *
 * Thread model
 * ------------
 * Exactly one daemon thread (`SherpaWake`) touches the microphone and the sherpa-onnx
 * stream. A keyword hit is *not* handled on that thread: it is dispatched to a separate
 * single callback thread so the heavy service work (speaker verification, persona switch,
 * session hand-off) can never stall audio capture. This keeps the mic read loop tight and
 * makes [stop] deterministic, because the callback never runs on the worker thread.
 *
 * It is the only component that touches the microphone while Jarvis is idle, which keeps the
 * standby footprint small: KWS + VAD, no session, no speaker model.
 *
 * Model files are supplied by [WakeModelDownloader]; nothing large is bundled in the APK.
 */
class SherpaWakeEngine(
    private val modelDir: File,
    private val keywords: List<String>,
    private val onKeyword: (String) -> Unit,
    private val onError: (String) -> Unit = {},
    private val sensitivity: WakeSensitivity.Profile = WakeSensitivity.profile("balanced"),
    /** Attach the platform [NoiseSuppressor] to the wake capture session (Settings → Noise filter). */
    private val noiseSuppression: Boolean = false,
) {
    companion object {
        private const val TAG = "SherpaWakeEngine"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 320 // 20 ms at 16 kHz
        private const val HISTORY_BYTES = 16_000 * 2 * 2 // ~2 s
        // The wake utterance's energy is judged over this window, not on a single frame. The
        // spotter reports the hit a little after the keyword (trailing blanks), so the window
        // must cover that delay — but keeping it short stops a loud transient from more than
        // half a second before the keyword from faking speech energy.
        private const val PEAK_WINDOW_MS = 500L
        // Bound for a single native read/join so teardown can never hang.
        private const val STOP_JOIN_MS = 1_500L
    }

    /**
     * Tiny VAD state: an energy floor that adapts down quickly and up slowly, so steady room
     * noise is learned but a burst of speech does not raise the bar under itself.
     */
    @Volatile
    var noiseFloor: Float = 0f
        private set

    @Volatile
    private var peakRms: Float = 0f

    @Volatile
    private var peakRmsAt: Long = 0L

    @Volatile
    var lastAudioFrameAt: Long = 0L
        private set

    @Volatile
    var lastRms: Float = 0f
        private set

    @Volatile
    private var running = false

    @Volatile
    private var activeRecord: AudioRecord? = null

    private var activeNoiseSuppressor: NoiseSuppressor? = null

    private var worker: Thread? = null

    /** Serialises [start]/[stop] so a restart can never leave two workers behind. */
    private val lifecycleLock = Any()

    /**
     * Handles keyword hits off the audio thread. `onKeyword` may do heavy work (speaker
     * verification) and may call back into [stop]; running it on a separate thread keeps the
     * microphone read loop unblocked either way. Recreated per [start] so a restart is safe.
     */
    private var callbackExecutor: ExecutorService? = null

    private val pcmHistory = java.util.ArrayDeque<ByteArray>()
    private var pcmHistoryBytes = 0

    fun start(): Boolean {
        synchronized(lifecycleLock) {
            if (running) return true
            running = true
            callbackExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "SherpaWakeCallback").apply { isDaemon = true }
            }
            worker = Thread({ runLoop() }, "SherpaWake").apply {
                isDaemon = true
                start()
            }
            return true
        }
    }

    fun stop() {
        val thread: Thread?
        val executor: ExecutorService?
        synchronized(lifecycleLock) {
            running = false
            thread = worker
            worker = null
            executor = callbackExecutor
            callbackExecutor = null
        }
        // Unblock the in-flight native read on the worker thread before joining it.
        runCatching { activeRecord?.stop() }
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(STOP_JOIN_MS) }
        }
        activeRecord = null
        releaseNoiseSuppressor()
        executor?.shutdown()
    }

    /** Last ~2 s of microphone PCM (mono PCM16 @16 kHz), for owner verification. */
    fun recentPcm(): ByteArray = synchronized(pcmHistory) {
        val out = java.io.ByteArrayOutputStream()
        for (chunk in pcmHistory) out.write(chunk)
        out.toByteArray()
    }

    /**
     * Tiny VAD verdict for the wake utterance: did the recent peak carry speech-level energy,
     * given the sensitivity the user picked and the noise floor learned in this room?
     */
    fun isVoiced(): Boolean = WakeVad.isVoiced(recentPeakRms(), noiseFloor, sensitivity)

    /** Highest frame energy in the last [PEAK_WINDOW_MS]. */
    fun recentPeakRms(): Float {
        val now = System.currentTimeMillis()
        return if (now - peakRmsAt <= PEAK_WINDOW_MS) peakRms else lastRms
    }

    private fun updatePeakRms(rms: Float) {
        val now = System.currentTimeMillis()
        if (rms >= peakRms || now - peakRmsAt > PEAK_WINDOW_MS) {
            peakRms = rms
            peakRmsAt = now
        }
    }

    private fun dispatchKeyword(keyword: String) {
        if (!running) return
        val executor = callbackExecutor ?: return
        runCatching {
            executor.execute {
                runCatching { onKeyword(keyword) }
                    .onFailure { Log.w(TAG, "onKeyword failed: ${it.message}") }
            }
        }.onFailure { Log.w(TAG, "Keyword dispatch skipped: ${it.message}") }
    }

    private fun runLoop() {
        var failures = 0
        while (running) {
            var spotter: KeywordSpotter? = null
            var stream: OnlineStream? = null
            var record: AudioRecord? = null
            try {
                spotter = buildSpotter()
                // Each element is one keyword phrase: BPE tokens separated by spaces, with an
                // optional "@display phrase". The native API separates phrases with "/".
                stream = spotter.createStream(keywords.joinToString("/"))
                record = openMicrophone() ?: throw IllegalStateException("No usable microphone input")
                activeRecord = record
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("Microphone did not start recording")
                }
                failures = 0
                Log.i(TAG, "sherpa-onnx KWS active (source=${record.audioSource})")

                val shortBuffer = ShortArray(CHUNK_SAMPLES)
                val floatBuffer = FloatArray(CHUNK_SAMPLES)
                while (running) {
                    val read = record.read(shortBuffer, 0, shortBuffer.size)
                    if (read <= 0) {
                        if (read == AudioRecord.ERROR_DEAD_OBJECT || read == AudioRecord.ERROR_INVALID_OPERATION) {
                            throw IllegalStateException("Microphone read failed: $read")
                        }
                        continue
                    }
                    lastAudioFrameAt = System.currentTimeMillis()
                    var sum = 0.0
                    for (i in 0 until read) {
                        val s = shortBuffer[i]
                        sum += s.toDouble() * s
                        floatBuffer[i] = s / 32768.0f
                    }
                    lastRms = sqrt(sum / read).toFloat()
                    updatePeakRms(lastRms)
                    noiseFloor = WakeVad.nextNoiseFloor(noiseFloor, lastRms)
                    appendHistory(shortBuffer, read)

                    stream.acceptWaveform(floatBuffer.copyOf(read), SAMPLE_RATE)
                    while (spotter.isReady(stream)) {
                        spotter.decode(stream)
                        val result = spotter.getResult(stream)
                        val keyword = result.keyword
                        if (keyword.isNotBlank()) {
                            spotter.reset(stream)
                            dispatchKeyword(keyword)
                        }
                    }
                }
            } catch (t: Throwable) {
                if (running) {
                    failures++
                    Log.w(TAG, "Wake mic restart #$failures: ${t.message}")
                    onError("Wake microphone retry: ${t.message}")
                    sleepWhileRunning(min(3000L, 350L * failures))
                }
            } finally {
                runCatching { stream?.release() }
                runCatching { spotter?.release() }
                runCatching { record?.stop() }
                runCatching { record?.release() }
                activeRecord = null
                releaseNoiseSuppressor()
            }
        }
    }

    /** Cancellable sleep so [stop] returns promptly instead of waiting out a backoff. */
    private fun sleepWhileRunning(millis: Long) {
        var remaining = millis
        while (running && remaining > 0) {
            val step = min(remaining, 100L)
            try {
                Thread.sleep(step)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            remaining -= step
        }
    }

    private fun buildSpotter(): KeywordSpotter {
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, "encoder-epoch-12-avg-2-chunk-16-left-64.onnx").absolutePath,
                    decoder = File(modelDir, "decoder-epoch-12-avg-2-chunk-16-left-64.onnx").absolutePath,
                    joiner = File(modelDir, "joiner-epoch-12-avg-2-chunk-16-left-64.onnx").absolutePath,
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer2",
            ),
            // The native config requires a keywords file to exist even when per-stream keywords
            // are supplied; the KWS model ships one next to tokens.txt.
            keywordsFile = File(modelDir, "keywords.txt").absolutePath,
            keywordsScore = sensitivity.keywordScore,
            keywordsThreshold = sensitivity.keywordThreshold,
            maxActivePaths = 4,
            numTrailingBlanks = 2,
        )
        return KeywordSpotter(config = config)
    }

    private fun appendHistory(buffer: ShortArray, count: Int) {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = buffer[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        synchronized(pcmHistory) {
            pcmHistory.addLast(bytes)
            pcmHistoryBytes += bytes.size
            while (pcmHistoryBytes > HISTORY_BYTES && pcmHistory.size > 1) {
                pcmHistoryBytes -= pcmHistory.pollFirst()?.size ?: 0
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openMicrophone(): AudioRecord? {
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) return null
        val bufferSize = maxOf(minimum * 2, CHUNK_SAMPLES * 4)
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        )
        for (source in sources) {
            var record: AudioRecord? = null
            try {
                record = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    applyNoiseSuppression(record)
                    return record
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Audio source $source unavailable: ${t.message}")
            }
            runCatching { record?.release() }
        }
        return null
    }

    /** Attaches the platform noise suppressor when the user enabled the noise filter. */
    private fun applyNoiseSuppression(record: AudioRecord) {
        if (!noiseSuppression) return
        if (!NoiseSuppressor.isAvailable()) return
        try {
            activeNoiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.apply {
                enabled = true
            }
            Log.i(TAG, "Wake NoiseSuppressor enabled on session ${record.audioSessionId}")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to enable wake NoiseSuppressor: ${t.message}")
        }
    }

    private fun releaseNoiseSuppressor() {
        try {
            activeNoiseSuppressor?.release()
        } catch (_: Throwable) {
        } finally {
            activeNoiseSuppressor = null
        }
    }
}
