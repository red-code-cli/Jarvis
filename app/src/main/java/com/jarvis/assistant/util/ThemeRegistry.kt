package com.jarvis.assistant.util

import com.jarvis.assistant.R

/**
 * Data-driven theme registry (Phase 9).
 *
 * Every entry maps to a real style in res/values/themes.xml that inherits one
 * of the four base families (Light / Dark / Red / Sunset) and overrides the
 * background + accent tokens. The orb uses [orbTheme] to pick a matching
 * palette preset from orb.html, or "custom" for user-defined colors.
 */
data class JarvisTheme(
    val key: String,
    val name: String,
    val subtitle: String,
    val bgColor: String,
    val lightStatusBar: Boolean,
    val styleRes: Int,
    val orbTheme: String,
)

object ThemeRegistry {

    /** Key of the special user-defined color theme. */
    const val CUSTOM_KEY = "custom"

    val ALL: List<JarvisTheme> = listOf(
        // ── Light family ────────────────────────────────────────────────────
        JarvisTheme(
            key = "light", name = "Ice Blue", subtitle = "Crisp studio white • Blue & violet accents",
            bgColor = "#E7EEF9", lightStatusBar = true,
            styleRes = R.style.Theme_JarvisAssistant_Light, orbTheme = "light",
        ),
        JarvisTheme(
            key = "pearl", name = "Pearl White", subtitle = "Soft porcelain white • Indigo glow",
            bgColor = "#F4F6FB", lightStatusBar = true,
            styleRes = R.style.Theme_JarvisAssistant_PearlWhite, orbTheme = "light",
        ),
        JarvisTheme(
            key = "snow", name = "Snow", subtitle = "Pure white • Electric blue",
            bgColor = "#FFFFFF", lightStatusBar = true,
            styleRes = R.style.Theme_JarvisAssistant_Snow, orbTheme = "light",
        ),
        JarvisTheme(
            key = "lavender", name = "Lavender", subtitle = "Lilac haze • Purple accents",
            bgColor = "#F3F0FB", lightStatusBar = true,
            styleRes = R.style.Theme_JarvisAssistant_Lavender, orbTheme = "light",
        ),
        JarvisTheme(
            key = "blush", name = "Blush", subtitle = "Powder pink • Rose energy",
            bgColor = "#FDF0F5", lightStatusBar = true,
            styleRes = R.style.Theme_JarvisAssistant_Blush, orbTheme = "light",
        ),
        JarvisTheme(
            key = "sand", name = "Sand", subtitle = "Warm neutral • Amber accents",
            bgColor = "#FAF4EC", lightStatusBar = true,
            styleRes = R.style.Theme_JarvisAssistant_Sand, orbTheme = "light",
        ),
        JarvisTheme(
            key = "mint", name = "Mint", subtitle = "Fresh mint • Emerald accents",
            bgColor = "#EDF9F3", lightStatusBar = true,
            styleRes = R.style.Theme_JarvisAssistant_Mint, orbTheme = "light",
        ),
        JarvisTheme(
            key = "sky", name = "Sky", subtitle = "Clear sky • Ocean blue",
            bgColor = "#EDF6FD", lightStatusBar = true,
            styleRes = R.style.Theme_JarvisAssistant_Sky, orbTheme = "light",
        ),
        JarvisTheme(
            key = "lemon", name = "Lemon", subtitle = "Sunny yellow • Gold accents",
            bgColor = "#FAF9E8", lightStatusBar = true,
            styleRes = R.style.Theme_JarvisAssistant_Lemon, orbTheme = "light",
        ),
        JarvisTheme(
            key = "peach", name = "Peach", subtitle = "Juicy peach • Tangerine",
            bgColor = "#FFF4EC", lightStatusBar = true,
            styleRes = R.style.Theme_JarvisAssistant_Peach, orbTheme = "light",
        ),
        JarvisTheme(
            key = "cloud", name = "Cloud", subtitle = "Overcast slate • Steel blue",
            bgColor = "#EEF2F8", lightStatusBar = true,
            styleRes = R.style.Theme_JarvisAssistant_Cloud, orbTheme = "light",
        ),
        // ── Sunset family ───────────────────────────────────────────────────
        JarvisTheme(
            key = "sunset", name = "Sunset", subtitle = "Golden hour • Coral & violet",
            bgColor = "#FFF0EA", lightStatusBar = true,
            styleRes = R.style.Theme_JarvisAssistant_Sunset, orbTheme = "sunset",
        ),
        JarvisTheme(
            key = "rose", name = "Rose Gold", subtitle = "Rose quartz • Warm metallic",
            bgColor = "#FFE9E4", lightStatusBar = true,
            styleRes = R.style.Theme_JarvisAssistant_RoseGold, orbTheme = "sunset",
        ),
        // ── Dark family ─────────────────────────────────────────────────────
        JarvisTheme(
            key = "dark", name = "Midnight", subtitle = "Deep cosmic • Neon cyan & purple",
            bgColor = "#070914", lightStatusBar = false,
            styleRes = R.style.Theme_JarvisAssistant_Dark, orbTheme = "dark",
        ),
        JarvisTheme(
            key = "obsidian", name = "Obsidian", subtitle = "Black glass • Periwinkle",
            bgColor = "#0B0F1A", lightStatusBar = false,
            styleRes = R.style.Theme_JarvisAssistant_Obsidian, orbTheme = "dark",
        ),
        JarvisTheme(
            key = "graphite", name = "Graphite", subtitle = "Plain charcoal • Steel",
            bgColor = "#121212", lightStatusBar = false,
            styleRes = R.style.Theme_JarvisAssistant_Graphite, orbTheme = "dark",
        ),
        JarvisTheme(
            key = "forest", name = "Forest", subtitle = "Deep forest • Emerald fireflies",
            bgColor = "#071A12", lightStatusBar = false,
            styleRes = R.style.Theme_JarvisAssistant_Forest, orbTheme = "dark",
        ),
        JarvisTheme(
            key = "emerald", name = "Emerald", subtitle = "Dark teal • Jade pulses",
            bgColor = "#042F2E", lightStatusBar = false,
            styleRes = R.style.Theme_JarvisAssistant_Emerald, orbTheme = "dark",
        ),
        JarvisTheme(
            key = "ocean", name = "Ocean", subtitle = "Abyssal blue • Cyan currents",
            bgColor = "#06121F", lightStatusBar = false,
            styleRes = R.style.Theme_JarvisAssistant_Ocean, orbTheme = "dark",
        ),
        JarvisTheme(
            key = "cyan", name = "Cyanosis", subtitle = "Deep cyan • Electric teal",
            bgColor = "#041821", lightStatusBar = false,
            styleRes = R.style.Theme_JarvisAssistant_Cyanosis, orbTheme = "dark",
        ),
        JarvisTheme(
            key = "amethyst", name = "Amethyst", subtitle = "Royal purple • Violet crystals",
            bgColor = "#140F2E", lightStatusBar = false,
            styleRes = R.style.Theme_JarvisAssistant_Amethyst, orbTheme = "dark",
        ),
        JarvisTheme(
            key = "violet", name = "Violet", subtitle = "Deep violet • Orchid glow",
            bgColor = "#1A102E", lightStatusBar = false,
            styleRes = R.style.Theme_JarvisAssistant_Violet, orbTheme = "dark",
        ),
        JarvisTheme(
            key = "magenta", name = "Magenta", subtitle = "Plum noir • Hot pink",
            bgColor = "#2A0A20", lightStatusBar = false,
            styleRes = R.style.Theme_JarvisAssistant_Magenta, orbTheme = "dark",
        ),
        JarvisTheme(
            key = "crimson", name = "Crimson", subtitle = "Blood wine • Red pulse",
            bgColor = "#200810", lightStatusBar = false,
            styleRes = R.style.Theme_JarvisAssistant_Crimson, orbTheme = "dark",
        ),
        JarvisTheme(
            key = "amber", name = "Amber", subtitle = "Dark honey • Gold sparks",
            bgColor = "#1E1503", lightStatusBar = false,
            styleRes = R.style.Theme_JarvisAssistant_Amber, orbTheme = "dark",
        ),
        JarvisTheme(
            key = "lime", name = "Lime", subtitle = "Deep olive • Acid lime",
            bgColor = "#131A05", lightStatusBar = false,
            styleRes = R.style.Theme_JarvisAssistant_Lime, orbTheme = "dark",
        ),
        JarvisTheme(
            key = "teal", name = "Teal", subtitle = "Dark lagoon • Aqua rings",
            bgColor = "#04211E", lightStatusBar = false,
            styleRes = R.style.Theme_JarvisAssistant_Teal, orbTheme = "dark",
        ),
        JarvisTheme(
            key = "cobalt", name = "Cobalt", subtitle = "Navy steel • Sapphire",
            bgColor = "#081229", lightStatusBar = false,
            styleRes = R.style.Theme_JarvisAssistant_Cobalt, orbTheme = "dark",
        ),
        JarvisTheme(
            key = "slate", name = "Slate", subtitle = "Minimal slate • Mist grey",
            bgColor = "#0F172A", lightStatusBar = false,
            styleRes = R.style.Theme_JarvisAssistant_Slate, orbTheme = "dark",
        ),
        JarvisTheme(
            key = "rust", name = "Rust", subtitle = "Burnt umber • Ember orange",
            bgColor = "#250F05", lightStatusBar = false,
            styleRes = R.style.Theme_JarvisAssistant_Rust, orbTheme = "dark",
        ),
        JarvisTheme(
            key = "wine", name = "Wine", subtitle = "Bordeaux • Crimson glow",
            bgColor = "#230909", lightStatusBar = false,
            styleRes = R.style.Theme_JarvisAssistant_Wine, orbTheme = "dark",
        ),
        JarvisTheme(
            key = "royal", name = "Royal", subtitle = "Indigo night • Periwinkle",
            bgColor = "#0C1226", lightStatusBar = false,
            styleRes = R.style.Theme_JarvisAssistant_Royal, orbTheme = "dark",
        ),
        JarvisTheme(
            key = "nebula", name = "Nebula", subtitle = "Cosmic purple • Auroras",
            bgColor = "#0A0920", lightStatusBar = false,
            styleRes = R.style.Theme_JarvisAssistant_Nebula, orbTheme = "dark",
        ),
        // ── Red family ──────────────────────────────────────────────────────
        JarvisTheme(
            key = "red", name = "Red", subtitle = "Liquid ruby • Alarm red",
            bgColor = "#0E0307", lightStatusBar = false,
            styleRes = R.style.Theme_JarvisAssistant_Red, orbTheme = "red",
        ),
    )

    fun byKey(key: String): JarvisTheme? = ALL.firstOrNull { it.key == key }

    /** Display name for a saved theme key (falls back to the key itself). */
    fun displayName(key: String): String =
        byKey(key)?.name ?: if (key == CUSTOM_KEY) "Custom" else key.replaceFirstChar { it.uppercase() }
}
