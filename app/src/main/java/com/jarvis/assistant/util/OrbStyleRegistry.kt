package com.jarvis.assistant.util

/**
 * Data-driven registry of neural orb animation styles.
 * Keys MUST match the STYLES registry in assets/orb.html exactly.
 */
data class OrbStyle(
    val key: String,
    val name: String,
    val desc: String
)

object OrbStyleRegistry {

    /** Default style, also the preference default. */
    const val DEFAULT_STYLE = "holographic_plasma"

    val ALL: List<OrbStyle> = listOf(
        OrbStyle("holographic_plasma", "Holographic Plasma", "Classic blue-violet plasma field"),
        OrbStyle("aurora_borealis", "Aurora Borealis", "Green-teal ribbons over deep space"),
        OrbStyle("cosmic_nebula", "Cosmic Nebula", "Purple-pink stellar nurseries"),
        OrbStyle("solar_flare", "Solar Flare", "White-hot core with corona bursts"),
        OrbStyle("ember_glow", "Ember Glow", "Smoldering orange drifting sparks"),
        OrbStyle("arctic_ice", "Arctic Ice", "Cold cyan shards, crystalline calm"),
        OrbStyle("deep_ocean", "Deep Ocean", "Abyssal blues, slow pressure pulses"),
        OrbStyle("violet_nights", "Violet Nights", "Twilight purple, low shimmer"),
        OrbStyle("toxic_waste", "Toxic Waste", "Radioactive lime-green swirl"),
        OrbStyle("candy_pop", "Candy Pop", "Saturated pink-mint confection"),
        OrbStyle("gold_rush", "Gold Rush", "Warm amber-gold particles"),
        OrbStyle("silver_linings", "Silver Linings", "Monotone silver mist"),
        OrbStyle("rose_quartz", "Rose Quartz", "Soft dusty pink glass"),
        OrbStyle("twilight_ombre", "Twilight Ombre", "Fades between indigo and coral"),
        OrbStyle("midnight_sky", "Midnight Sky", "Near-black with faint star speckle"),
        OrbStyle("blood_moon", "Blood Moon", "Crimson halo, warning glow"),
        OrbStyle("ultraviolet", "Ultraviolet", "Hidden violets, electric edge"),
        OrbStyle("infrared", "Infrared", "Heat-vision reds and oranges"),
        OrbStyle("matrix_rain", "Matrix Rain", "Digital green cascade effect"),
        OrbStyle("synthwave_sun", "Synthwave Sun", "Retro magenta-cyan split"),
        OrbStyle("forest_spirit", "Forest Spirit", "Mossy green with gold flecks"),
        OrbStyle("desert_storm", "Desert Storm", "Sand tan, dusty turbulence"),
        OrbStyle("volcanic_hellfire", "Volcanic Hellfire", "Lava veins, dark basalt"),
        OrbStyle("frost_aura", "Frost Aura", "Pale blue breath, low glow"),
        OrbStyle("electric_storm", "Electric Storm", "Sharp white-blue crackle"),
        OrbStyle("steampunk_copper", "Steampunk Copper", "Brass and rust gears vibe"),
        OrbStyle("iridescent", "Iridescent", "Oil-slick shifting hues"),
        OrbStyle("monochrome_shadow", "Monochrome Shadow", "Pure black & white grain"),
        OrbStyle("glitch_core", "Glitch Core", "Split RGB error-corruption"),
        OrbStyle("dreamlike_pastel", "Dreamlike Pastel", "Faded pastel cloud"),
        OrbStyle("bio_luminescent", "Bio-luminescent", "Algae green, deep-sea glow"),
        OrbStyle("supernova", "Supernova", "Explosive white-violet flash")
    )

    fun byKey(key: String?): OrbStyle? = if (key.isNullOrBlank()) null else ALL.firstOrNull { it.key == key }

    fun displayName(key: String?): String = byKey(key)?.name ?: "Holographic Plasma"
}
