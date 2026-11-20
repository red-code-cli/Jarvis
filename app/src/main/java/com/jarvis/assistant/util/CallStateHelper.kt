package com.jarvis.assistant.util

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager

/**
 * Reliable, permission-free call detection used to avoid opening the microphone
 * (or launching Settings) while the phone is in a call.
 */
object CallStateHelper {

    /** True while the phone is ringing (before the call is answered). */
    fun isCallRinging(context: Context): Boolean {
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audio?.mode == AudioManager.MODE_RINGTONE
        } catch (_: Throwable) {
            false
        }
    }

    /** True only after a call is answered (mic must stay closed). */
    fun isCallOffhook(context: Context): Boolean {
        if (isCallRinging(context)) return false
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audio?.mode == AudioManager.MODE_IN_CALL ||
                audio?.mode == AudioManager.MODE_IN_COMMUNICATION
            ) {
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun isCallActive(context: Context): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val telecom = context.getSystemService(TelecomManager::class.java)
                if (telecom?.isInCall == true) return true
            }
        } catch (_: Throwable) {
        }
        try {
            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE)
                as? android.telephony.TelephonyManager
            if (telephony != null &&
                telephony.callState != android.telephony.TelephonyManager.CALL_STATE_IDLE
            ) {
                return true
            }
        } catch (_: Throwable) {
        }
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audio?.mode == AudioManager.MODE_IN_CALL ||
                audio?.mode == AudioManager.MODE_RINGTONE
        } catch (_: Throwable) {
            false
        }
    }
}
