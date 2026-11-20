package com.jarvis.assistant.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tiny PCM helpers used on the analysis copy only. The audio sent to Gemini is never
 * filtered, so speech recognition quality is unchanged.
 */
object AudioFilters {

    /**
     * One-pole high-pass RMS (mono PCM16, 16 kHz), normalised to 0..1.
     * Used to tell wind rumble (mostly < 100 Hz) from a human voice before a barge-in.
     * A 200 Hz cutoff keeps the whole voice band (~200 Hz and up) while cutting 50 Hz
     * rumble to about a quarter of its input level.
     */
    fun highPassRms16k(pcm: ByteArray, cutoffHz: Float = 200f): Float {
        if (pcm.size < 4) return 0f
        val sampleRate = 16_000f
        val dt = 1f / sampleRate
        val rc = 1f / (2f * PI.toFloat() * cutoffHz)
        val alpha = rc / (rc + dt)
        var previousInput = 0f
        var previousOutput = 0f
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort() / 32768f
            val output = alpha * (previousOutput + sample - previousInput)
            previousInput = sample
            previousOutput = output
            sum += output.toDouble() * output
            count++
            i += 2
        }
        if (count == 0) return 0f
        return sqrt(sum / count).toFloat()
    }

    /** Fraction of samples at/above the PCM16 clipping rail. */
    fun clippingRatio(pcm: ByteArray): Float {
        var clipped = 0
        var count = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val s = ((hi shl 8) or lo).toShort().toInt()
            if (abs(s) >= 32000) clipped++
            count++
            i += 2
        }
        return if (count == 0) 0f else clipped.toFloat() / count
    }
}
