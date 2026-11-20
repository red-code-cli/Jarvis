package com.jarvis.assistant.automation.command

import android.util.Log
import com.jarvis.assistant.automation.phone.PhoneControlManager
import com.jarvis.assistant.automation.whatsapp.WhatsAppAssistant
import com.jarvis.assistant.automation.youtube.YouTubeAutomationManager
import com.jarvis.assistant.data.memory.MemoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CommandExecutor(
    private val phoneManager: PhoneControlManager,
    private val youtubeManager: YouTubeAutomationManager,
    private val whatsappAssistant: WhatsAppAssistant,
    private val memoryManager: MemoryManager
) {
    companion object {
        private const val TAG = "CommandExecutor"
    }

    suspend fun execute(command: JarvisCommand): String = withContext(Dispatchers.Main) {
        Log.i(TAG, "Executing command: $command")
        when (command) {
            // YouTube
            is JarvisCommand.PlayYouTube -> {
                val resolvedSong = memoryManager.resolveSongQuery(command.query)
                memoryManager.trackSongPlay(resolvedSong)
                youtubeManager.playVideo(resolvedSong)
            }
            is JarvisCommand.SearchYouTube -> youtubeManager.searchYouTube(command.query)
            is JarvisCommand.PauseMedia -> youtubeManager.pausePlayback()
            is JarvisCommand.ResumeMedia -> youtubeManager.resumePlayback()
            is JarvisCommand.NextMedia -> youtubeManager.nextMedia()
            is JarvisCommand.PreviousMedia -> youtubeManager.previousMedia()
            is JarvisCommand.SubscribeChannel -> youtubeManager.subscribeToChannel()
            is JarvisCommand.ToggleFullscreen -> youtubeManager.toggleFullscreen()

            // Phone Controls
            is JarvisCommand.Flash -> phoneManager.setFlashlight(command.enabled)
            is JarvisCommand.Brightness -> {
                if (command.isRelative) {
                    phoneManager.adjustBrightness(command.delta > 0)
                } else {
                    phoneManager.setBrightnessPercent(command.level)
                }
            }
            is JarvisCommand.Volume -> {
                if (command.isRelative) {
                    phoneManager.adjustVolume(command.delta > 0)
                } else {
                    phoneManager.setVolumePercent(command.level)
                }
            }
            is JarvisCommand.OpenApp -> phoneManager.openApp(command.packageName)
            is JarvisCommand.OpenWebUrls -> phoneManager.openMultipleUrls(command.urls)
            is JarvisCommand.PhoneCall -> phoneManager.makePhoneCall(command.contactOrNumber)
            is JarvisCommand.SearchWeb -> phoneManager.searchWeb(command.query)

            // WhatsApp
            is JarvisCommand.WhatsAppMessage -> whatsappAssistant.sendMessageDirectly(command.contact, command.message)
            is JarvisCommand.WhatsAppCall -> whatsappAssistant.makeCall(command.contact, command.video)
            is JarvisCommand.WhatsAppDeleteMessage -> whatsappAssistant.deleteLastMessage(command.contact, command.forEveryone)
            is JarvisCommand.ConfirmAction -> {
                if (command.confirmed) {
                    whatsappAssistant.confirmAndSend()
                } else {
                    whatsappAssistant.cancelDraft()
                }
            }
            is JarvisCommand.DisambiguationChoice -> whatsappAssistant.handleDisambiguationChoice(command.choice)

            // Memory
            is JarvisCommand.Remember -> memoryManager.rememberFact(command.text)
            is JarvisCommand.Forget -> memoryManager.forgetFact(command.query)
            is JarvisCommand.WhatDoYouRemember -> memoryManager.getWhatDoYouRemember()
            is JarvisCommand.RecallBirthday -> memoryManager.getBirthdayResponse()
            is JarvisCommand.RecallFavoriteSong -> memoryManager.getFavoriteSongResponse()
            is JarvisCommand.RecallRoutine -> memoryManager.getRoutineResponse()
            is JarvisCommand.GoToBackground -> "Going to background."
            is JarvisCommand.ReadUnreadMessages -> readUnreadMessages(command.app)
            is JarvisCommand.EmergencySos -> com.jarvis.assistant.util.EmergencySosHelper.triggerSos(com.jarvis.assistant.JarvisApp.instance)
            is JarvisCommand.AntiTheftGuard -> {
                val context = com.jarvis.assistant.JarvisApp.instance
                if (command.enable) {
                    com.jarvis.assistant.security.AntiTheftManager.arm(context) {
                        com.jarvis.assistant.background.JarvisConversationService.triggerAntiTheftAlert()
                    }
                    "Table Guard active ho gaya hai sir. 5 second me phone table par rakh dijiye."
                } else {
                    com.jarvis.assistant.security.AntiTheftManager.disarm(context)
                    "Table Guard band kar diya gaya hai sir."
                }
            }
        }
    }

    private fun readUnreadMessages(appFilter: String?): String {
        val prefs = com.jarvis.assistant.JarvisApp.instance.preferences
        if (!prefs.isUnreadMessagesReaderEnabled) {
            return "Unread messages voice reader settings me disabled hai sir."
        }
        val targetApp = appFilter ?: "whatsapp"
        val notifications = com.jarvis.assistant.notifications.JarvisNotificationListenerService
            .buildNotificationsJson(targetApp, 10)
            ?: return "Notification access enabled nahi hai sir. Settings me jaakar Notification permission allow karein."

        if (notifications.length() == 0) {
            return "Koi naya unread message nahi hai sir."
        }

        val sb = StringBuilder("Aapke pass ${notifications.length()} unread messages hain: ")
        for (i in 0 until notifications.length()) {
            val obj = notifications.optJSONObject(i) ?: continue
            val sender = obj.optString("sender", "Unknown")
            val message = obj.optString("message", "")
            if (message.isNotBlank()) {
                sb.append("$sender se message hai: \"$message\". ")
            }
        }
        return sb.toString().trim()
    }
}
