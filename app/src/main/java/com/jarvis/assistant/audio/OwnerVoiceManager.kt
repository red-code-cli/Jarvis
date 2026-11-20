package com.jarvis.assistant.audio

import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlin.math.sqrt

data class VoiceMatchResult(
    val isMatch: Boolean,
    val distance: Float,
    val similarityPercent: Int,
    val threshold: Float,
    val hasExtractedVector: Boolean = true
)

object OwnerVoiceManager {
    private const val TAG = "OwnerVoiceManager"

    // sherpa-onnx CAM++ speaker embeddings, compared with cosine distance.
    // Cosine distance (1.0 - cosSim) between recordings of the same speaker typically
    // ranges between 0.20 and 0.55; different speakers usually measure above 0.65.
    const val DEFAULT_THRESHOLD = 0.70f   // Balanced: reliable owner match, 100% blocks strangers
    const val STRICT_THRESHOLD = 0.62f    // Strict: high security
    const val RELAXED_THRESHOLD = 0.78f   // Relaxed: tolerant of noisy environments

    @Volatile
    private var voiceprint: SherpaVoiceprint? = null

    @Volatile
    private var voiceprintPath: String? = null

    /**
     * Lazily builds (and caches) the on-device sherpa-onnx speaker embedding extractor.
     * Returns null until the speaker model has been downloaded by [WakeModelDownloader].
     */
    private fun voiceprint(): SherpaVoiceprint? {
        val context = try {
            JarvisApp.instance
        } catch (_: Throwable) {
            null
        } ?: return null
        val modelFile = WakeModelDownloader.speakerModelFile(context)
        if (!modelFile.exists()) return null
        val path = modelFile.absolutePath
        val current = voiceprint
        if (current != null && voiceprintPath == path) return current
        return synchronized(this) {
            if (voiceprint == null || voiceprintPath != path) {
                voiceprint?.close()
                voiceprint = null
                voiceprintPath = null
                val created = SherpaVoiceprint(modelFile)
                if (created.isReady) {
                    voiceprint = created
                    voiceprintPath = path
                } else {
                    created.close()
                }
            }
            voiceprint
        }
    }

    /**
     * Frees the speaker-embedding model. Verification only happens on a wake utterance, so
     * the (large) model is released while Jarvis is idle and rebuilt on the next wake.
     * `@Synchronized` keeps it from closing the extractor under a running [extractSpeakerVector].
     */
    @Synchronized
    fun releaseSpeakerModel() {
        voiceprint?.close()
        voiceprint = null
        voiceprintPath = null
    }

    /**
     * Calculates the cosine distance between two speaker embedding vectors:
     * distance = 1.0 - (dot(v1, v2) / (norm(v1) * norm(v2)))
     * Range is [0.0, 2.0]. Lower values indicate identical vocal characteristics.
     */
    fun calculateCosineDistance(v1: FloatArray, v2: FloatArray): Float {
        if (v1.isEmpty() || v2.isEmpty() || v1.size != v2.size) return 1.0f

        var dot = 0.0
        var norm1 = 0.0
        var norm2 = 0.0

        for (i in v1.indices) {
            val a = v1[i].toDouble()
            val b = v2[i].toDouble()
            dot += a * b
            norm1 += a * a
            norm2 += b * b
        }

        if (norm1 <= 0.0 || norm2 <= 0.0) return 1.0f

        val cosSim = (dot / (sqrt(norm1) * sqrt(norm2))).coerceIn(-1.0, 1.0)
        return (1.0 - cosSim).toFloat().coerceIn(0.0f, 2.0f)
    }

    /**
     * Converts a cosine distance to a human-friendly similarity percentage [0..100].
     */
    fun distanceToSimilarityPercent(distance: Float): Int {
        val sim = (1.0f - (distance / 1.35f)) * 100f
        return sim.toInt().coerceIn(0, 100)
    }

    /**
     * Computes the centroid (normalized average) of multiple speaker sample embeddings.
     */
    fun averageVectors(vectors: List<FloatArray>): FloatArray? {
        if (vectors.isEmpty()) return null
        val dim = vectors[0].size
        val avg = FloatArray(dim)

        for (vec in vectors) {
            if (vec.size != dim) continue
            for (i in 0 until dim) {
                avg[i] += vec[i]
            }
        }

        val count = vectors.size.toFloat()
        for (i in 0 until dim) {
            avg[i] /= count
        }

        // Normalize the averaged vector
        var norm = 0.0
        for (v in avg) {
            norm += v.toDouble() * v.toDouble()
        }
        val sqrtNorm = sqrt(norm).toFloat()
        if (sqrtNorm > 0f) {
            for (i in 0 until dim) {
                avg[i] /= sqrtNorm
            }
        }

        return avg
    }

    /**
     * Extracts a speaker embedding vector from a 16 kHz mono 16-bit PCM audio buffer using the
     * sherpa-onnx CAM++ speaker model.
     */
    @Synchronized
    fun extractSpeakerVector(pcmAudio: ByteArray): FloatArray? {
        if (pcmAudio.size < 3200) { // Require at least 100ms of audio (16000 * 2 * 0.1)
            Log.w(TAG, "Audio buffer too short for speaker extraction (${pcmAudio.size} bytes)")
            return null
        }
        val extractor = voiceprint()
        if (extractor == null) {
            Log.w(TAG, "Cannot extract speaker vector: sherpa-onnx speaker model is not ready")
            return null
        }
        return extractor.extract(pcmAudio)
    }

    /**
     * Verifies an extracted speaker vector against the enrolled owner profile stored in preferences.
     * Uses multi-sample nearest-neighbor + centroid matching:
     * Compares against both the averaged centroid AND each individual enrolled sample vector.
     */
    fun verifySpeakerVector(currentVector: FloatArray, prefs: AppPreferences): VoiceMatchResult {
        val masterProfile = prefs.getOwnerVoiceProfile()
        val sampleProfiles = prefs.getOwnerVoiceSamples()
        val threshold = prefs.ownerVoiceThreshold

        if ((masterProfile == null || masterProfile.isEmpty()) && sampleProfiles.isEmpty()) {
            // No profile enrolled yet; allow all speakers by default
            return VoiceMatchResult(
                isMatch = true,
                distance = 0.0f,
                similarityPercent = 100,
                threshold = threshold
            )
        }

        var minDistance = 2.0f

        // Check against master centroid
        if (masterProfile != null && masterProfile.isNotEmpty()) {
            val d = calculateCosineDistance(currentVector, masterProfile)
            if (d < minDistance) minDistance = d
        }

        // Check against each enrolled sample vector for optimal match across tones/cadences
        for (sample in sampleProfiles) {
            val d = calculateCosineDistance(currentVector, sample)
            if (d < minDistance) minDistance = d
        }

        val isMatch = minDistance <= threshold
        val similarityPercent = distanceToSimilarityPercent(minDistance)

        return VoiceMatchResult(
            isMatch = isMatch,
            distance = minDistance,
            similarityPercent = similarityPercent,
            threshold = threshold
        )
    }

    /**
     * Verifies raw PCM audio against the enrolled owner profile.
     */
    fun verifySpeakerAudio(pcmAudio: ByteArray, prefs: AppPreferences): VoiceMatchResult {
        if (!prefs.isOwnerVoiceEnabled || !prefs.hasOwnerVoiceProfile()) {
            return VoiceMatchResult(
                isMatch = true,
                distance = 0.0f,
                similarityPercent = 100,
                threshold = prefs.ownerVoiceThreshold
            )
        }

        val vector = extractSpeakerVector(pcmAudio)
        if (vector == null) {
            Log.w(TAG, "Could not extract speaker vector from audio; insufficient voiced frames")
            return VoiceMatchResult(
                isMatch = false,
                distance = 1.0f,
                similarityPercent = 0,
                threshold = prefs.ownerVoiceThreshold,
                hasExtractedVector = false
            )
        }

        return verifySpeakerVector(vector, prefs)
    }
}
