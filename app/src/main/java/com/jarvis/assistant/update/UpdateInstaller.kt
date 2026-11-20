package com.jarvis.assistant.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.jarvis.assistant.privilege.PrivilegeManager
import com.jarvis.harness.runtime.AndroidAppInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs a verified APK. Reuses the existing [AndroidAppInstaller] (PackageInstaller session
 * + FileProvider path for MIUI) and the existing [PrivilegeManager] for an optional silent
 * fast path when the user already granted root or Shizuku. Everything falls back to the normal
 * user-action install, so the feature works with no privileges at all.
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"

    sealed class Result {
        /** Install session / system installer was launched; the user completes or confirms it. */
        object Started : Result()

        /** "Install unknown apps" is not granted for this app yet. */
        object NeedsUnknownSourcesPermission : Result()

        data class Failure(val reason: String) : Result()
    }

    /** True when this app may request package installs (always true below Android 8). */
    fun canInstall(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }.getOrDefault(true)

    /** System page where the user allows "Install unknown apps" for this app. */
    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Optional fast path: silent install when root/Shizuku is already active. Returns true on
     * success; any failure returns false so the caller uses the normal installer.
     */
    suspend fun silentInstallIfPossible(apk: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val mode = PrivilegeManager.currentMode()
            if (mode == PrivilegeManager.Mode.LOCAL) return@withContext false
            val result = PrivilegeManager.runShell("pm install -r -d '${apk.absolutePath}'", 60_000)
            val ok = result.success || result.output.contains("Success", ignoreCase = true)
            Log.i(TAG, "Silent install ($mode): success=$ok exit=${result.exitCode}")
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "Silent install failed: ${t.message}")
            false
        }
    }

    /** Normal PackageInstaller / MIUI FileProvider path with a user confirmation. */
    fun installWithUserAction(context: Context, apk: File): Result {
        return try {
            AndroidAppInstaller.install(context, apk, launchAfterInstall = true)
            Result.Started
        } catch (t: Throwable) {
            Log.w(TAG, "User-action install failed: ${t.message}")
            Result.Failure(t.message ?: "install failed")
        }
    }
}
