package com.jarvis.assistant.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.ui.home.MainActivity

/**
 * Announces a ringing call through the active JARVIS voice and posts a notification
 * with real Answer / Reject actions so the decision is always one tap away.
 */
object IncomingCallManager {
    private const val TAG = "IncomingCallManager"
    private const val CHANNEL_ID = "jarvis_incoming_calls"
    private const val NOTIFICATION_ID = 73

    @Volatile
    private var lastAnnouncedAt = 0L

    @Volatile
    private var lastCaller: String? = null

    @Volatile
    var pendingCaller: String? = null
        private set

    fun onRinging(context: Context, caller: String?) {
        val prefs = try {
            JarvisApp.instance.preferences
        } catch (_: Throwable) {
            return
        }
        if (!prefs.isCallAnnouncementEnabled) return

        val displayCaller = caller?.trim()?.takeIf { it.isNotBlank() } ?: "unknown number"
        val now = System.currentTimeMillis()
        if (now - lastAnnouncedAt < 8_000L && displayCaller == lastCaller) return
        lastAnnouncedAt = now
        lastCaller = displayCaller
        pendingCaller = displayCaller

        Log.i(TAG, "Ringing call from $displayCaller")
        showNotification(context, displayCaller)
        JarvisConversationService.announceIncomingCall(displayCaller)
    }

    fun clearPending() {
        pendingCaller = null
        lastCaller = null
    }

    fun cancelNotification(context: Context) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancel(NOTIFICATION_ID)
        } catch (_: Throwable) {
        }
    }

    private fun showNotification(context: Context, caller: String) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Incoming calls",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "JARVIS call announcements with answer and reject actions"
                    setSound(null, null)
                    enableVibration(false)
                }
                manager.createNotificationChannel(channel)
            }

            val openIntent = PendingIntent.getActivity(
                context,
                70,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val answerIntent = PendingIntent.getBroadcast(
                context,
                71,
                Intent(context, CallActionReceiver::class.java)
                    .setAction(CallActionReceiver.ACTION_ANSWER),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val rejectIntent = PendingIntent.getBroadcast(
                context,
                72,
                Intent(context, CallActionReceiver::class.java)
                    .setAction(CallActionReceiver.ACTION_REJECT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic_jarvis)
                .setContentTitle("Incoming call")
                .setContentText("$caller — pick karna hai ya reject?")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setTimeoutAfter(45_000L)
                .setContentIntent(openIntent)
                .addAction(0, "PICK", answerIntent)
                .addAction(0, "REJECT", rejectIntent)
                .build()

            manager.notify(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not post call notification: ${t.message}")
        }
    }
}
