package com.jarvis.harness

import android.content.Context
import android.content.Intent

/**
 * Opens the host application from a harness notification.
 *
 * Replaces Mobile Harness's hard reference to its own `MainActivity` so that the
 * module never names a host-app class. Uses the package's own launcher intent,
 * which already includes the correct (and possibly dynamic) main activity.
 */
object HarnessIntents {
    fun launchIntent(context: Context): Intent =
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
}
