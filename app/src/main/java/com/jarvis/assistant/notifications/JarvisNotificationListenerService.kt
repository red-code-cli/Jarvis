package com.jarvis.assistant.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * System-bound listener for the auto-reply pipeline and the read_notifications tool.
 * It only forwards messaging notifications to AutoReplyManager, and only exposes the
 * active notification list when the owner explicitly asks JARVIS to read notifications.
 */
class JarvisNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "JarvisNotificationListener"

        @Volatile
        var instance: JarvisNotificationListenerService? = null
            private set

        fun isConnected(): Boolean = instance != null

        /**
         * Returns the currently visible notifications as JSON, newest first.
         * Returns null when notification access is not connected.
         */
        fun buildNotificationsJson(appFilter: String?, limit: Int = 20): JSONArray? {
            val service = instance ?: return null
            val active = try {
                service.activeNotifications
            } catch (t: Throwable) {
                Log.w(TAG, "Could not read active notifications: ${t.message}")
                return null
            } ?: return null

            val filter = appFilter?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val selected = active
                .asSequence()
                .filter { !it.isOngoing }
                .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
                .filter { it.packageName != service.packageName }
                .filter { sbn ->
                    when {
                        filter.contains("whatsapp") ->
                            sbn.packageName.contains("whatsapp", ignoreCase = true)
                        filter.contains("instagram") ->
                            sbn.packageName.contains("instagram", ignoreCase = true)
                        filter.contains("sms") || filter.contains("message") ->
                            sbn.packageName.contains("messag", ignoreCase = true) ||
                                sbn.packageName.contains("sms", ignoreCase = true)
                        else -> true
                    }
                }
                .sortedByDescending { it.postTime }
                .take(limit.coerceIn(1, 30))
                .toList()

            val formatter = SimpleDateFormat("hh:mm a", Locale.getDefault())
            val result = JSONArray()
            for (sbn in selected) {
                val extras = sbn.notification.extras ?: continue
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)
                    ?.toString()?.trim().orEmpty()
                var body = (
                    extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                        ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                        ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
                    )?.toString()?.trim().orEmpty()
                if (title.isBlank() && body.isBlank()) continue

                var sender = title.ifBlank { "Unknown" }
                val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                    ?.toString()?.trim().orEmpty()
                if (conversation.isNotBlank()) {
                    val separator = body.indexOf(": ")
                    if (separator in 1..40) {
                        sender = body.substring(0, separator).trim().ifBlank { conversation }
                        body = body.substring(separator + 2).trim()
                    } else {
                        sender = conversation
                    }
                }

                val appLabel = try {
                    val info = service.packageManager.getApplicationInfo(sbn.packageName, 0)
                    service.packageManager.getApplicationLabel(info).toString()
                } catch (_: Throwable) {
                    sbn.packageName
                }

                result.put(JSONObject().apply {
                    put("app", appLabel)
                    put("package", sbn.packageName)
                    put("sender", sender)
                    put("message", body.take(500))
                    put("time", formatter.format(Date(sbn.postTime)))
                    put("posted_at_ms", sbn.postTime)
                    put("category", sbn.notification.category.orEmpty())
                })
            }
            return result
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        try {
            if (notification.notification.category == Notification.CATEGORY_CALL &&
                com.jarvis.assistant.util.CallStateHelper.isCallRinging(applicationContext)
            ) {
                val title = notification.notification.extras
                    ?.getCharSequence(Notification.EXTRA_TITLE)
                    ?.toString()
                    ?.trim()
                if (!title.isNullOrBlank()) {
                    IncomingCallManager.onRinging(applicationContext, title)
                    return
                }
            }
            AutoReplyManager.onNotification(applicationContext, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not process notification: ${t.message}")
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
