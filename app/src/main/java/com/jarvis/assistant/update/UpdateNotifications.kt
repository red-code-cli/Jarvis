package com.jarvis.assistant.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R

/**
 * Small notification helper for the updater. Never throws: if notifications are not permitted
 * (Android 13+) it simply skips, because the in-app UI is the primary surface.
 */
object UpdateNotifications {

    private const val CHANNEL_ID = "jarvis_update"
    private const val NOTIFICATION_ID = 4201

    fun showUpdateAvailable(context: Context, manifest: UpdateManifest, mandatory: Boolean) {
        if (!canNotify(context)) return
        try {
            ensureChannel(context)
            val intent = Intent(context, UpdateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val title = if (mandatory) "Update zaroori hai" else "Naya update available"
            val text = "Jarvis ${manifest.latestVersionName} aa gaya hai. Update karke naye features paayein."
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_jarvis_logo)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setOngoing(mandatory)
                .setContentIntent(pending)
                .setPriority(if (mandatory) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: Throwable) {
        }
    }

    fun cancelUpdateAvailable(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } catch (_: Throwable) {
        }
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "App updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Jarvis app ke naye update ki suchna" }
        manager.createNotificationChannel(channel)
    }
}
