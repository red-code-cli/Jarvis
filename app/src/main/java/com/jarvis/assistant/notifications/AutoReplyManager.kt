package com.jarvis.assistant.notifications

import android.app.KeyguardManager
import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.service.notification.StatusBarNotification
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.network.GeminiTextClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads incoming WhatsApp / Instagram notifications, asks Gemini for a short
 * contextual reply, and sends it through the notification's own RemoteInput
 * action. No screen scraping is needed for apps that support direct reply.
 */
object AutoReplyManager {
    private const val TAG = "AutoReplyManager"
    private const val HANDLED_TTL_MS = 10 * 60 * 1000L

    private data class Incoming(
        val appName: String,
        val sender: String,
        val body: String,
        val threadKey: String,
        val notification: Notification,
        val notificationKey: String
    )

    private data class RecentEntry(val seenAt: Long, val textHash: Int)
    private data class ThreadHistory(
        val lastRepliedAt: Long,
        val lastMessageText: String,
        val lastReplyText: String
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generateLock = Mutex()

    private val handled = object : LinkedHashMap<String, RecentEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RecentEntry>?): Boolean {
            return size > 300
        }
    }
    private val processing = mutableSetOf<String>()
    private val threadHistories = mutableMapOf<String, ThreadHistory>()

    private val supportedApps = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.facebook.orca" to "Messenger",
        "com.instagram.android" to "Instagram"
    )

    private fun appKeyFor(packageName: String): String? = when {
        packageName.startsWith("com.whatsapp") -> "whatsapp"
        packageName == "com.facebook.orca" -> "messenger"
        packageName == "com.instagram.android" -> "instagram"
        else -> null
    }

    fun onNotification(context: Context, sbn: StatusBarNotification) {
        val appName = supportedApps[sbn.packageName] ?: return
        val preferences = try {
            JarvisApp.instance.preferences
        } catch (_: Throwable) {
            return
        }
        if (!preferences.isAutoReplyEnabled) return
        val selectedKey = appKeyFor(sbn.packageName) ?: return
        if (selectedKey !in preferences.autoReplyApps) return
        // Per-app deny policy: never auto-reply to apps the owner blocked.
        if (preferences.isNotificationPackageDenied(sbn.packageName)) return

        // If the owner is already using that messaging app, let them answer normally.
        val activePackage = JarvisAccessibilityService.instance
            ?.getActiveRootNode()?.packageName?.toString()
        if (activePackage == sbn.packageName) return

        val incoming = parse(sbn, appName) ?: return

        // Thread-level deduplication: Prevent sending duplicate replies to the same conversation
        val now = System.currentTimeMillis()
        val threadKey = incoming.threadKey
        synchronized(threadHistories) {
            val history = threadHistories[threadKey]
            if (history != null) {
                // Minimum 6-second debounce for the same thread to absorb WhatsApp/Instagram notification re-posting
                if (now - history.lastRepliedAt < 6_000L) {
                    Log.d(TAG, "Suppressed duplicate notification in '$threadKey' (cooldown active)")
                    return
                }
                // Do not re-reply to the same incoming message body or if the notification contains our own sent reply
                if (incoming.body.equals(history.lastMessageText, ignoreCase = true) ||
                    (history.lastReplyText.isNotBlank() && incoming.body.contains(history.lastReplyText))
                ) {
                    Log.d(TAG, "Suppressed duplicate reply to previously answered message in '$threadKey'")
                    return
                }
            }
        }

        val key = "${sbn.packageName}|${incoming.notificationKey}|${incoming.body.hashCode()}"

        synchronized(handled) {
            val previous = handled[key]
            if (previous != null && now - previous.seenAt < HANDLED_TTL_MS) return
            handled[key] = RecentEntry(now, incoming.body.hashCode())
        }
        synchronized(processing) {
            if (!processing.add(key)) return
        }

        scope.launch {
            try {
                // A tiny settle delay lets the messaging app finish publishing its RemoteInput actions
                delay(120L)

                val reply = generateLock.withLock {
                    GeminiTextClient.generateReply(
                        context = context,
                        appName = incoming.appName,
                        sender = incoming.sender,
                        message = incoming.body
                    )
                }?.trim()?.takeIf { it.isNotBlank() }
                    ?: fallbackReply(incoming.body)

                val sent = sendDirectReply(context, incoming, reply) ||
                    openAppForAccessibilityReply(context, sbn, reply)
                if (sent) {
                    synchronized(threadHistories) {
                        threadHistories[threadKey] = ThreadHistory(
                            lastRepliedAt = System.currentTimeMillis(),
                            lastMessageText = incoming.body,
                            lastReplyText = reply
                        )
                    }
                    Log.i(TAG, "Auto-replied to ${incoming.sender} on ${incoming.appName}: \"$reply\"")
                } else {
                    Log.w(TAG, "No usable reply action for ${sbn.packageName}; message left untouched")
                    synchronized(handled) { handled.remove(key) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Auto-reply failed: ${t.message}")
                synchronized(handled) { handled.remove(key) }
            } finally {
                synchronized(processing) { processing.remove(key) }
            }
        }
    }

    private fun parse(sbn: StatusBarNotification, appName: String): Incoming? {
        val notification = sbn.notification ?: return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return null
        if (notification.category == Notification.CATEGORY_CALL ||
            notification.category == Notification.CATEGORY_MISSED_CALL ||
            notification.category == Notification.CATEGORY_SERVICE
        ) return null

        val extras = notification.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val rawText = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString()?.trim().orEmpty()
        if (title.isBlank() || rawText.isBlank()) return null

        // Ignore messages sent by ourselves or echo notifications
        if (title.equals("You", ignoreCase = true) ||
            title.startsWith("You (", ignoreCase = true) ||
            rawText.startsWith("You: ", ignoreCase = true) ||
            rawText.startsWith("Aap: ", ignoreCase = true)
        ) return null

        if (isGenericText(rawText) || NotificationPolicy.isSensitive(rawText)) return null

        var sender = title
        var body = rawText
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()?.trim().orEmpty()
        if (conversationTitle.isNotBlank()) {
            val separator = rawText.indexOf(": ")
            if (separator in 1..40) {
                val possibleSender = rawText.substring(0, separator).trim()
                val possibleBody = rawText.substring(separator + 2).trim()
                if (possibleSender.isNotBlank() && possibleBody.isNotBlank()) {
                    sender = possibleSender
                    body = possibleBody
                }
            }
        }
        if (body.isBlank() || body.length > 4000) return null

        // Do not process messages that are clearly our own
        if (sender.equals("You", ignoreCase = true) || body.startsWith("You: ", ignoreCase = true)) {
            return null
        }

        val threadKey = "${sbn.packageName}|${conversationTitle.ifBlank { title }}"
        val notificationKey = sbn.key ?: "${sbn.id}"
        return Incoming(appName, sender, body, threadKey, notification, notificationKey)
    }

    private fun isGenericText(text: String): Boolean {
        val clean = text.lowercase().trim()
        if (clean.matches(Regex("^\\d+ new messages?$"))) return true
        return clean in setOf(
            "new message", "sent you a message", "sent a message", "message",
            "photo", "video", "voice message", "audio message", "missed call",
            "missed voice call", "tap to view", "attachment", "sticker", "gif",
            "document", "contact card", "location", "you have a new message"
        )
    }

    private fun sendDirectReply(context: Context, incoming: Incoming, reply: String): Boolean {
        val action = findReplyAction(incoming.notification) ?: return false
        val remoteInput = action.remoteInputs
            ?.firstOrNull { it.allowFreeFormInput }
            ?: action.remoteInputs?.firstOrNull()
            ?: return false

        return try {
            val intent = Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val results = Bundle().apply {
                putCharSequence(remoteInput.resultKey, reply)
            }
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, results)
            action.actionIntent.send(context, 0, intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Direct reply action failed: ${t.message}")
            false
        }
    }

    private fun findReplyAction(notification: Notification): Notification.Action? {
        val actions = notification.actions ?: return null
        return actions.firstOrNull { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true &&
                action.title?.toString()?.contains("reply", ignoreCase = true) == true
        } ?: actions.firstOrNull { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        } ?: actions.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
    }

    /**
     * Some apps do not expose a direct-reply action on every build. If the phone is
     * unlocked, open the conversation from the notification and let the accessibility
     * service type + send the generated reply. This also covers WhatsApp builds whose
     * RemoteInput action is missing or rejected.
     */
    private fun openAppForAccessibilityReply(
        context: Context,
        sbn: StatusBarNotification,
        reply: String
    ): Boolean {
        if (!supportedApps.containsKey(sbn.packageName)) return false
        val service = JarvisAccessibilityService.instance ?: return false
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        if (!power.isInteractive) return false
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard?.isKeyguardLocked == true) return false

        val contentIntent = sbn.notification.contentIntent ?: return false
        service.armAutoReply(reply, sbn.packageName)
        return try {
            contentIntent.send(
                context,
                0,
                Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Accessibility reply fallback could not open the conversation: ${t.message}")
            service.cancelAutoReply()
            false
        }
    }

    private fun fallbackReply(message: String): String {
        val lower = message.lowercase().trim()
        return when {
            // Greetings
            Regex("\\b(hi|hello|hey|namaste|namaskar|salaam|heyy|yo)\\b").containsMatchIn(lower) ->
                "Hey! Kaise ho? Sab theek?"

            // Where are you / Location
            Regex("\\b(kahan ho|kidhar ho|where are you|kaha ho|kaha pe ho|kidhar)\\b").containsMatchIn(lower) ->
                "Abhi thoda bahar hoon dost, free hote hi baat karta hoon."

            // What are you doing / Work
            Regex("\\b(kya kar rahe ho|kya kar raha|kya chal raha|what are you doing|busy ho|free ho)\\b").containsMatchIn(lower) ->
                "Bas ek zaroori kaam me laga tha, thodi der me message/call karta hoon."

            // When / Time
            Regex("\\b(kab aaoge|kab tak|when will you|kitna time|time lagega)\\b").containsMatchIn(lower) ->
                "Bas thoda time lagega, nikalte hi inform karta hoon."

            // Urgent / Call me
            Regex("\\b(call me|urgent|emergency|phone uthao|call karo)\\b").containsMatchIn(lower) ->
                "Message dekha. Agar urgent hai toh phone kar lo, warna thodi der me call back karta hoon."

            // Thanks / Appreciation
            Regex("\\b(thanks|thank you|shukriya|dhanyawad)\\b").containsMatchIn(lower) ->
                "Most welcome! Anytime 😊"

            // Ok / Haan / Cool
            Regex("\\b(ok|okay|theek hai|thik hai|haan|done|cool|bye)\\b").containsMatchIn(lower) ->
                "Done! Baat karte hain."

            // Any question (?)
            lower.contains("?") ->
                "Message mil gaya hai, thoda free ho kar detail me reply karta hoon."

            // Default contextual acknowledgement
            else ->
                "Message padh liya, free hote hi connect karta hoon."
        }
    }
}
