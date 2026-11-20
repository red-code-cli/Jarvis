package com.jarvis.assistant.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.notifications.JarvisNotificationListenerService
import com.jarvis.assistant.privilege.PrivilegeManager
import kotlinx.coroutines.delay

/**
 * Android normally requires the user to toggle Accessibility and Notification access
 * manually. JARVIS already owns root/Shizuku access, so these helpers perform the same
 * secure-setting change that the Settings app performs, then verify the service really
 * connected. If privilege is unavailable the caller falls back to the system Settings UI.
 */
object ServiceSetupManager {

    private val componentPattern = Regex("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$")

    suspend fun enableAccessibility(context: Context): Boolean {
        if (PermissionHelper.isAccessibilityServiceEnabled(context)) {
            JarvisAccessibilityService.instance?.let { return true }
        }
        restoreAccessibilitySetting(context)
        return waitUntil(6_000) {
            PermissionHelper.isAccessibilityServiceEnabled(context) &&
                JarvisAccessibilityService.instance != null
        }
    }

    /**
     * Writes the accessibility secure settings immediately (no verification wait).
     * Used by the boot receiver and the self-heal watchdog when the ROM clears them.
     */
    suspend fun restoreAccessibilitySetting(context: Context): Boolean {
        val component = ComponentName(
            context,
            JarvisAccessibilityService::class.java
        ).flattenToString()
        val current = secureValue(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val updated = appendComponent(current, component)
        val quoted = shellQuote(updated)
        val appops = PrivilegeManager.runShell(
            "appops set ${context.packageName} ACCESS_RESTRICTED_SETTINGS allow",
            timeoutMs = 8_000
        )
        val services = PrivilegeManager.runShell(
            "settings put secure enabled_accessibility_services $quoted",
            timeoutMs = 8_000
        )
        val enabled = PrivilegeManager.runShell(
            "settings put secure accessibility_enabled 1",
            timeoutMs = 8_000
        )
        Log.i(
            "ServiceSetupManager",
            "Accessibility restore: appops=${appops.success} services=${services.success} enabled=${enabled.success} mode=${services.mode}"
        )
        return appops.success && services.success && enabled.success
    }

    suspend fun enableNotificationListener(context: Context): Boolean {
        if (PermissionHelper.hasNotificationListenerAccess(context)) return true
        val component = ComponentName(
            context,
            JarvisNotificationListenerService::class.java
        ).flattenToString()
        PrivilegeManager.runShell(
            "appops set ${context.packageName} ACCESS_RESTRICTED_SETTINGS allow",
            timeoutMs = 8_000
        )
        PrivilegeManager.runShell(
            "cmd notification allow_listener ${shellQuote(component)}",
            timeoutMs = 8_000
        )
        // Some OEM builds ignore the cmd shortcut; the secure setting is the source of truth.
        val current = secureValue(context, "enabled_notification_listeners")
        val updated = appendComponent(current, component)
        PrivilegeManager.runShell(
            "settings put secure enabled_notification_listeners ${shellQuote(updated)}",
            timeoutMs = 8_000
        )
        return waitUntil(5_000) { PermissionHelper.hasNotificationListenerAccess(context) }
    }

    private fun secureValue(context: Context, key: String): String? = try {
        Settings.Secure.getString(context.contentResolver, key)
    } catch (_: Throwable) {
        null
    }

    private fun appendComponent(raw: String?, component: String): String {
        val expectedPkg = component.substringBefore('/')
        val expectedClass = component.substringAfter('/')
        val existing = raw.orEmpty()
            .split(':')
            .map { it.trim() }
            .filter { it.matches(componentPattern) }
            .filterNot { flat ->
                val pkg = flat.substringBefore('/')
                val cls = flat.substringAfter('/')
                val full = when {
                    cls.startsWith(".") -> pkg + cls
                    cls.contains('.') -> cls
                    else -> "$pkg.$cls"
                }
                pkg == expectedPkg && full == expectedClass
            }
        return (existing + component).distinct().joinToString(":")
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private suspend fun waitUntil(timeoutMs: Long, check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return true
            delay(250)
        }
        return check()
    }
}
