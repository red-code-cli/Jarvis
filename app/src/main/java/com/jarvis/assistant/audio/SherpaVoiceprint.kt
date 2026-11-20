package com.jarvis.assistant.audio

import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import kotlin.math.sqrt

/**
 * On-device speaker verification built on sherpa-onnx speaker embeddings (Apache-2.0).
 *
 * It converts mono PCM16 @16 kHz audio
 * into an embedding and compares it to the enrolled owner profile with cosine distance,
 * reusing the same distance-threshold scale the app already exposes in Settings.
 */
class SherpaVoiceprint(modelFile: File) {

    private val extractor: SpeakerEmbeddingExtractor? = runCatching {
        SpeakerEmbeddingExtractor(
            assetManager = null,
            config = SpeakerEmbeddingExtractorConfig(
                model = modelFile.absolutePath,
                numThreads = 1,
                provider = "cpu",
            ),
        )
    }.onFailure { Log.w(TAG, "Speaker extractor unavailable: ${it.message}") }
        .getOrNull()

    val isReady: Boolean get() = extractor != null

    /** Extracts a speaker embedding from mono PCM16 @16 kHz, or null when too short/noisy. */
    fun extract(pcm: ByteArray): FloatArray? {
        val extractor = extractor ?: return null
        if (pcm.size < MIN_BYTES) return null
        return runCatching {
            val stream = extractor.createStream()
            try {
                val samples = FloatArray(pcm.size / 2)
                var i = 0
                while (i + 1 < pcm.size) {
                    val lo = pcm[i].toInt() and 0xFF
                    val hi = pcm[i + 1].toInt()
                    val value = (hi shl 8) or lo
                    samples[i / 2] = value / 32768.0f
                    i += 2
                }
                stream.acceptWaveform(samples, SAMPLE_RATE)
                stream.inputFinished()
                if (!extractor.isReady(stream)) null else extractor.compute(stream)
            } finally {
                stream.release()
            }
        }.onFailure { Log.w(TAG, "Embedding failed: ${it.message}") }.getOrNull()
    }

    /**
     * @param profileEnrolled the stored owner centroid, or null when not enrolled
     * @param samplesEnrolled additional enrolled sample vectors
     * @param threshold cosine-distance threshold; smaller is stricter
     */
    fun verify(pcm: ByteArray, profileEnrolled: FloatArray?, samplesEnrolled: List<FloatArray>, threshold: Float): Result {
        if (profileEnrolled == null && samplesEnrolled.isEmpty()) {
            return Result(isMatch = true, distance = 0f, hasVector = false)
        }
        val vector = extract(pcm)
            ?: return Result(isMatch = false, distance = 1f, hasVector = false)
        var best = Float.MAX_VALUE
        if (profileEnrolled != null && profileEnrolled.isNotEmpty()) {
            best = minOf(best, cosineDistance(vector, profileEnrolled))
        }
        samplesEnrolled.forEach { sample -> best = minOf(best, cosineDistance(vector, sample)) }
        return Result(isMatch = best <= threshold, distance = best, hasVector = true)
    }

    fun close() {
        runCatching { extractor?.release() }
    }

    data class Result(val isMatch: Boolean, val distance: Float, val hasVector: Boolean)

    private fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        val size = minOf(a.size, b.size)
        if (size == 0) return 1f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until size) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 1f
        val cos = dot / (sqrt(na) * sqrt(nb))
        return (1.0 - cos).toFloat().coerceIn(0f, 2f)
    }

    companion object {
        private const val TAG = "SherpaVoiceprint"
        private const val SAMPLE_RATE = 16000

        /**
         * Minimum audio length (mono PCM16 @ 16 kHz) required before a speaker embedding can
         * be extracted. This is the single source of truth shared by every caller: the live
         * early-verification window (15 x 20 ms frames == [MIN_BYTES]), the silence-pause
         * verification and the voice-enrollment UI. 0.3 s is enough for the embedding model
         * while keeping first-syllable verification latency low; anything shorter is rejected
         * as too short to embed.
         */
        const val MIN_BYTES = SAMPLE_RATE * 3 / 10 * 2 // 0.3 s -> 9,600 bytes
    }
}
