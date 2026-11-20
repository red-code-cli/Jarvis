package com.jarvis.assistant.audio

import kotlin.math.sqrt

/**
 * Lightweight pitch-based voice filter. It is intentionally conservative:
 * it only returns male/female when several voiced frames agree, otherwise
 * "unknown" so a noisy room can never flip the assistant's tone randomly.
 */
object VoiceGenderDetector {

    private const val SAMPLE_RATE = 16000
    private const val MIN_PITCH_HZ = 70.0
    private const val MAX_PITCH_HZ = 380.0
    private const val MIN_RMS = 420.0

    fun detectGender(pcm16: ByteArray): String? {
        if (pcm16.size < SAMPLE_RATE / 2) return null // < 0.5 s of audio
        val samples = ShortArray(pcm16.size / 2) { i ->
            val lo = pcm16[i * 2].toInt() and 0xFF
            val hi = pcm16[i * 2 + 1].toInt()
            ((hi shl 8) or lo).toShort()
        }
        val frameSize = 640  // 40 ms
        val hop = 320        // 20 ms
        val pitches = ArrayList<Float>(64)
        var offset = 0
        while (offset + frameSize <= samples.size) {
            val pitch = pitchOfFrame(samples, offset, frameSize)
            if (pitch != null) pitches.add(pitch)
            offset += hop
        }
        if (pitches.size < 5) return null
        pitches.sort()
        val median = pitches[pitches.size / 2]
        return when {
            median < 158f -> "male"
            median > 182f -> "female"
            else -> null
        }
    }

    private fun pitchOfFrame(samples: ShortArray, offset: Int, size: Int): Float? {
        var mean = 0.0
        for (i in 0 until size) mean += samples[offset + i]
        mean /= size

        var energy = 0.0
        for (i in 0 until size) {
            val v = samples[offset + i] - mean
            energy += v * v
        }
        val rms = sqrt(energy / size)
        if (rms < MIN_RMS) return null

        val minLag = (SAMPLE_RATE / MAX_PITCH_HZ).toInt().coerceAtLeast(20)
        val maxLag = (SAMPLE_RATE / MIN_PITCH_HZ).toInt().coerceAtMost(size / 2)

        var bestLag = -1
        var bestCorrelation = 0.0
        for (lag in minLag..maxLag) {
            var dot = 0.0
            var normA = 0.0
            var normB = 0.0
            val limit = size - lag
            for (i in 0 until limit) {
                val a = samples[offset + i] - mean
                val b = samples[offset + i + lag] - mean
                dot += a * b
                normA += a * a
                normB += b * b
            }
            if (normA <= 0.0 || normB <= 0.0) continue
            val corr = dot / sqrt(normA * normB)
            if (corr > bestCorrelation) {
                bestCorrelation = corr
                bestLag = lag
            }
        }
        if (bestLag <= 0 || bestCorrelation < 0.32) return null
        return SAMPLE_RATE.toFloat() / bestLag
    }
}
