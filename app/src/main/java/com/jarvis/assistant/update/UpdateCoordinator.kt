package com.jarvis.assistant.update

import android.content.Context
import com.jarvis.assistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates one update check: fetch -> parse -> persist -> decide, then notifies the user.
 * Reused by the WorkManager worker, the Settings screen and app resume so the behaviour is
 * identical everywhere.
 */
object UpdateCoordinator {

    /** Runs a full check. Never throws; on failure the previous (latched) state is preserved. */
    suspend fun checkNow(context: Context): UpdateAction = withContext(Dispatchers.IO) {
        val raw = UpdateChecker.fetchJson()
        if (raw == null) {
            UpdateManager.recordFailure("network")
            return@withContext UpdateManager.currentAction()
        }
        val manifest = UpdateManifest.fromJson(raw)
        if (manifest == null) {
            UpdateManager.recordFailure("invalid-manifest")
            return@withContext UpdateManager.currentAction()
        }
        UpdateManager.recordSuccess(raw)
        val action = UpdateManager.evaluate(BuildConfig.VERSION_CODE)
        when (action) {
            UpdateAction.FORCE -> UpdateNotifications.showUpdateAvailable(context, manifest, mandatory = true)
            UpdateAction.OPTIONAL -> UpdateNotifications.showUpdateAvailable(context, manifest, mandatory = false)
            UpdateAction.NONE -> UpdateNotifications.cancelUpdateAvailable(context)
        }
        action
    }

    /** Cheap gate so app-resume checks don't hammer the server. */
    fun shouldCheckOnResume(minIntervalMs: Long = 15 * 60 * 1000L): Boolean =
        System.currentTimeMillis() - UpdateManager.lastCheckAt() >= minIntervalMs

    /**
     * Re-runs the decision against the cached manifest (no network) and shows/updates the
     * notification. Used on every app resume so the update prompt never disappears until the
     * user updates, even when a fresh network check is still throttled.
     */
    fun evaluateCached(context: Context): UpdateAction {
        val action = UpdateManager.evaluate(BuildConfig.VERSION_CODE)
        val manifest = UpdateManager.cachedManifest() ?: return action
        when (action) {
            UpdateAction.FORCE -> UpdateNotifications.showUpdateAvailable(context, manifest, mandatory = true)
            UpdateAction.OPTIONAL -> UpdateNotifications.showUpdateAvailable(context, manifest, mandatory = false)
            UpdateAction.NONE -> UpdateNotifications.cancelUpdateAvailable(context)
        }
        return action
    }
}
