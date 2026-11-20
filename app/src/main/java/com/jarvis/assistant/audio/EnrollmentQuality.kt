package com.jarvis.assistant.audio

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Quality gate for an owner-voice enrollment sample. A bad sample (too short, clipped,
 * whispered, or mostly silence) poisons the averaged embedding, so the user is asked to
 * re-record instead of silently saving it.
 *
 * Pure Kotlin on purpose: the thresholds are unit-tested without Android.
 */
object EnrollmentQuality {
    const val MIN_DURATION_MS = 1_200
    const val MAX_DURATION_MS = 8_000
    const val SAMPLE_RATE = 16_000

    enum class Reason { OK, TOO_SHORT, TOO_LONG, TOO_QUIET, CLIPPING, NO_SPEECH }

    data class Result(
        val reason: Reason,
        val durationMs: Int,
        val rms: Float,
        val voicedRatio: Float,
        val clippingRatio: Float
    ) {
        val ok: Boolean get() = reason == Reason.OK

        fun hinglishMessage(): String = when (reason) {
            Reason.OK -> "Sample achha hai."
            Reason.TOO_SHORT -> "Recording bahut chhoti hai. Poora phrase bolo (kam se kam 1.2 second)."
            Reason.TOO_LONG -> "Recording bahut lambi hai. 8 second ke andar phrase poora karo."
            Reason.TOO_QUIET -> "Awaaz bahut dheemi hai. Phone ke paas aakar thoda loud bolo."
            Reason.CLIPPING -> "Awaaz distort ho rahi hai. Phone thoda door rakho aur normal volume me bolo."
            Reason.NO_SPEECH -> "Saaf speech detect nahi hui. Shor kam karke dobara bolo."
        }
    }

    fun evaluate(pcm: ByteArray): Result {
        val durationMs = (pcm.size / 32).coerceAtLeast(0) // 16 kHz * 2 bytes
        val frameBytes = 640 // 20 ms
        val frameCount = pcm.size / frameBytes
        if (durationMs < MIN_DURATION_MS || frameCount == 0) {
            return Result(Reason.TOO_SHORT, durationMs, 0f, 0f, 0f)
        }
        if (durationMs > MAX_DURATION_MS) {
            return Result(Reason.TOO_LONG, durationMs, 0f, 0f, 0f)
        }

        var totalSquares = 0.0
        var totalSamples = 0
        var clippedSamples = 0
        val frameRms = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            var sum = 0.0
            val start = f * frameBytes
            for (i in start until start + frameBytes step 2) {
                val lo = pcm[i].toInt() and 0xFF
                val hi = pcm[i + 1].toInt()
                val value = (hi shl 8) or lo
                val s = value.toShort()
                sum += s.toDouble() * s
                totalSquares += s.toDouble() * s
                totalSamples++
                if (s >= 32000 || s <= -32000) clippedSamples++
            }
            frameRms[f] = sqrt(sum / (frameBytes / 2)).toFloat()
        }

        val rms = sqrt(totalSquares / max(1, totalSamples)).toFloat() / 32768f
        val clippingRatio = clippedSamples.toFloat() / max(1, totalSamples)
        // Estimate the room floor from the quietest 20% of frames, then count frames that
        // clearly rise above it. Comparing to the room (not a fixed number) keeps quiet rooms
        // usable while still rejecting "recording of silence".
        val sorted = frameRms.sorted()
        val floor = sorted[(sorted.size * 0.20f).toInt().coerceIn(0, sorted.lastIndex)] / 32768f
        val voicedThreshold = max(0.008f, floor * 2.2f)
        var voiced = 0
        for (v in frameRms) {
            if (v / 32768f >= voicedThreshold) voiced++
        }
        val voicedRatio = voiced.toFloat() / frameCount

        val reason = when {
            clippingRatio > 0.08f -> Reason.CLIPPING
            rms < 0.008f -> Reason.TOO_QUIET
            voicedRatio < 0.12f -> Reason.NO_SPEECH
            else -> Reason.OK
        }
        return Result(reason, durationMs, rms, voicedRatio, clippingRatio)
    }
}
