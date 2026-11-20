package com.jarvis.assistant.automation.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService

/**
 * Answers or ends the current call through TelecomManager. When the platform
 * rejects the privileged call (or an OEM exposes only an in-call UI button),
 * JARVIS falls back to the accessibility service and taps the real button.
 */
object PhoneCallController {
    private const val TAG = "PhoneCallController"

    data class Result(val success: Boolean, val message: String)

    @SuppressLint("MissingPermission")
    fun answer(context: Context): Result {
        if (!hasAnswerPermission(context)) {
            return Result(false, "Phone call permission is not granted. Allow 'Answer phone calls' in Permissions.")
        }
        val telecom = context.getSystemService(TelecomManager::class.java)
        if (telecom != null) {
            try {
                telecom.acceptRingingCall()
                return Result(true, "Answered the incoming call.")
            } catch (t: Throwable) {
                Log.w(TAG, "TelecomManager.acceptRingingCall failed: ${t.message}")
            }
        }
        return if (JarvisAccessibilityService.instance?.answerCallByUi() == true) {
            Result(true, "Answered the incoming call from the call screen.")
        } else {
            Result(false, "No ringing call was found to answer.")
        }
    }

    @SuppressLint("MissingPermission")
    fun end(context: Context): Result {
        if (!hasAnswerPermission(context)) {
            return Result(false, "Phone call permission is not granted. Allow 'Answer phone calls' in Permissions.")
        }
        val telecom = context.getSystemService(TelecomManager::class.java)
        if (telecom != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                telecom.endCall()
                return Result(true, "Ended the current call.")
            } catch (t: Throwable) {
                Log.w(TAG, "TelecomManager.endCall failed: ${t.message}")
            }
        }
        return if (JarvisAccessibilityService.instance?.endCallByUi() == true) {
            Result(true, "Ended the call from the call screen.")
        } else {
            Result(false, "No active call was found to end.")
        }
    }

    fun status(context: Context): Result {
        // TelecomManager.isInCall only exists on API 31+. CallStateHelper already implements a
        // correct cross-version check (Telecom -> TelephonyManager -> AudioManager mode), so use
        // it on every Android version instead of reporting "unavailable" below API 31.
        val inCall = com.jarvis.assistant.util.CallStateHelper.isCallActive(context)
        return if (inCall) {
            Result(true, "A call is currently active.")
        } else {
            Result(true, "No call is currently active.")
        }
    }

    private fun hasAnswerPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ANSWER_PHONE_CALLS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
