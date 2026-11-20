package com.jarvis.assistant.update

/** What the app should do about the manifest it currently has cached. */
enum class UpdateAction { NONE, OPTIONAL, FORCE }

/**
 * Pure decision table for the updater. Kept free of Android types so every rule the product
 * spec depends on is unit-tested and cannot silently drift.
 *
 * Rules (spec section A):
 *  - no manifest / latest <= installed  -> NONE (fail-open, downgrade block)
 *  - mandatory && installed < latest    -> FORCE
 *  - installed < minSupportedVersionCode-> FORCE (even if not marked mandatory)
 *  - a version the user skipped         -> NONE (only for optional updates; FORCE always wins)
 *  - otherwise                          -> OPTIONAL
 */
object UpdateDecision {
    fun decide(
        installedVersionCode: Int,
        manifest: UpdateManifest?,
        skippedVersionCode: Int,
    ): UpdateAction {
        if (manifest == null) return UpdateAction.NONE
        if (manifest.latestVersionCode <= installedVersionCode) return UpdateAction.NONE
        val belowMinimum =
            manifest.minSupportedVersionCode > 0 && installedVersionCode < manifest.minSupportedVersionCode
        if (manifest.mandatory || belowMinimum) return UpdateAction.FORCE
        if (skippedVersionCode == manifest.latestVersionCode) return UpdateAction.NONE
        return UpdateAction.OPTIONAL
    }
}
