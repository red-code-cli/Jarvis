package com.jarvis.assistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.ServiceSetupManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ROMs such as Funtouch/OriginOS silently clear third-party accessibility services
 * after a reboot or when they kill background apps. Restore the secure setting on
 * boot so the user does not have to toggle it again manually.
 */
class AccessibilityBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in ACTIONS) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (!PermissionHelper.isAccessibilityServiceEnabled(context)) {
                    val restored = ServiceSetupManager.restoreAccessibilitySetting(context)
                    Log.i(TAG, "Boot accessibility restore result: $restored")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Boot accessibility restore failed: ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AccessibilityBoot"
        private val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
