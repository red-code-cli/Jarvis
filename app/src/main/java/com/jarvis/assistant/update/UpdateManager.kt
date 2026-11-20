package com.jarvis.assistant.update

import android.content.Context
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the in-app updater's persisted state.
 *
 * Persistence lives in [com.jarvis.assistant.data.preferences.AppPreferences] (new keys only,
 * existing keys untouched). The force flag is latched only after a *valid* mandatory response
 * and is cleared automatically once the installed version catches up — so a network problem
 * can never lock a user out.
 */
object UpdateManager {

    private fun prefs() = JarvisApp.instance.preferences

    private val _forceActive = MutableStateFlow(false)
    /** Emits true while a mandatory update is blocking the app. */
    val forceActiveFlow: StateFlow<Boolean> = _forceActive.asStateFlow()

    /** Call once at process start so the UI/service gate reflects persisted state before any check. */
    fun primeFromStorage() {
        _forceActive.value = prefs().updateForceActive
    }

    fun isForceUpdateActive(): Boolean = _forceActive.value || prefs().updateForceActive

    fun cachedManifest(): UpdateManifest? = UpdateManifest.fromJson(prefs().updateCachedManifestJson)

    fun lastCheckAt(): Long = prefs().updateLastCheckAt

    fun lastError(): String = prefs().updateLastError

    /** A check could not run (offline/server down/invalid json): keep any latched force state, record why. */
    fun recordFailure(reason: String) {
        val p = prefs()
        p.updateLastCheckAt = System.currentTimeMillis()
        p.updateLastError = reason
    }

    /** A valid manifest was fetched. Stores the raw document so the decision can be re-run offline. */
    fun recordSuccess(rawJson: String) {
        val p = prefs()
        p.updateLastCheckAt = System.currentTimeMillis()
        p.updateCachedManifestJson = rawJson
        p.updateLastError = ""
    }

    /**
     * Re-run the decision against the cached manifest and latch/clear the force flag.
     * Returns the action the UI should present.
     */
    fun evaluate(installedVersionCode: Int = BuildConfig.VERSION_CODE): UpdateAction {
        val action = UpdateDecision.decide(installedVersionCode, cachedManifest(), prefs().updateSkipVersionCode)
        val force = action == UpdateAction.FORCE
        prefs().updateForceActive = force
        _forceActive.value = force
        return action
    }

    /** Current action without re-fetching (used when a check failed). */
    fun currentAction(installedVersionCode: Int = BuildConfig.VERSION_CODE): UpdateAction =
        UpdateDecision.decide(installedVersionCode, cachedManifest(), prefs().updateSkipVersionCode)

    /** User tapped "Later" on an optional update. */
    fun skipVersion(versionCode: Int) {
        prefs().updateSkipVersionCode = versionCode
    }

    fun installBaseUrl(context: Context): String {
        // The manifest URL lives in exactly one place (BuildConfig), never scattered.
        @Suppress("UNUSED_PARAMETER") val unused = context
        return BuildConfig.UPDATE_MANIFEST_URL
    }
}
