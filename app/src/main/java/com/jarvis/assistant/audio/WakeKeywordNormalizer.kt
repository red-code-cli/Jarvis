package com.jarvis.assistant.audio

import java.util.Locale

/**
 * Pure normalisation and classification of the raw phrase the on-device keyword spotter
 * reports, so the wake-phrase rules are unit-testable and identical everywhere.
 *
 * The sherpa-onnx spotter can hand back the phrase as "jarvis", "JARVIS" or as
 * reconstructed BPE tokens such as "CO DE ▁RE D". [normalize] collapses the
 * word-boundary marker and separators to plain single-spaced lowercase text.
 */
object WakeKeywordNormalizer {

    private val WHITESPACE = Regex("\\s+")

    fun normalize(raw: String): String = raw.lowercase(Locale.ROOT)
        .replace('▁', ' ')
        .replace('_', ' ')
        .replace(WHITESPACE, " ")
        .trim()

    /**
     * Persona wake word for an already-normalised phrase, or null when the phrase is not a
     * persona. Order matters: "isha" and "virus" are checked before "jarvis" so a phrase
     * that contains both still resolves deterministically.
     */
    fun personaWakeWord(clean: String): String? = when {
        clean.contains("isha") -> "isha"
        clean.contains("virus") -> "virus"
        clean.contains("jarvis") -> "jarvis"
        else -> null
    }

    /** Emergency SOS phrases ("Code Red" / "Emergency"). */
    fun isEmergency(clean: String): Boolean =
        clean.contains("code red") || clean.contains("emergency")

    /** Anti-theft guard phrases ("Table guard" / "Guard on" / "Guard off"). */
    fun isGuard(clean: String): Boolean =
        clean.contains("table guard") || clean.contains("guard on") || clean.contains("guard off")

    /** Guard "on" unless the phrase explicitly says "off". */
    fun guardEnabled(clean: String): Boolean = !clean.contains("off")
}
