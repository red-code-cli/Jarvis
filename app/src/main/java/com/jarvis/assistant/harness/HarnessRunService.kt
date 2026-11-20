package com.jarvis.assistant.harness

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.harness.HarnessLiveStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Keeps the coding engine alive while a task is running, completely independent of
 * the Gemini Live session, the microphone or the orb.
 *
 * It runs in the app process but owns its own lifecycle: closing the voice session,
 * muting the mic or recreating the Home screen never stops it. Only an explicit
 * cancel/stop or an Android force-stop ends the work.
 */
class HarnessRunService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastText: String = "Working on your project…"

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Coding engine", lastText, null))
        scope.launch {
            runCatching {
                JarvisHarnessBridge.liveStatus.collect { live -> updateNotification(live) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // START_STICKY: if Android kills the process, the service comes back and the
        // engine reloads its task state from disk instead of silently dropping the task.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun updateNotification(live: HarnessLiveStatus) {
        if (live.taskId == null) return
        val name = live.projectName.ifBlank { "Project" }
        val state = when (live.phase) {
            HarnessLiveStatus.Phase.PAUSED -> "PAUSED"
            HarnessLiveStatus.Phase.WAITING_RETRY -> "WAITING_RETRY"
            HarnessLiveStatus.Phase.ERROR -> "FAILED"
            HarnessLiveStatus.Phase.SUCCESS -> "DONE"
            else -> "RUNNING"
        }
        val progress = (live.progress * 100).toInt().coerceIn(0, 100)
        lastText = "${live.line.ifBlank { "Working" }} · $progress%"
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(
                "$name · $state",
                "$lastText · Step ${live.step} · ${live.files} files",
                progress.takeIf { live.phase != HarnessLiveStatus.Phase.SUCCESS },
            ),
        )
    }

    private fun buildNotification(title: String, text: String, progress: Int?): android.app.Notification {
        val intent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (progress != null) builder.setProgress(100, progress, false)
        return builder.build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Coding engine", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_coding_engine_run"
        private const val NOTIFICATION_ID = 8108
        private const val ACTION_STOP = "com.jarvis.assistant.harness.STOP_RUN"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, HarnessRunService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, HarnessRunService::class.java))
            }
        }
    }
}
