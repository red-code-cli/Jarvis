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
import com.jarvis.assistant.privilege.PrivilegeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Answers or ends the current call through TelecomManager. When the platform
 * rejects the privileged call (or an OEM exposes only an in-call UI button),
 * JARVIS falls back to the accessibility service and taps the real button.
 */
object PhoneCallController {
    private const val TAG = "PhoneCallController"

    private val privilegeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Last-resort fallback: when TelecomManager and Accessibility both fail, a root or
     * Shizuku shell can inject KEYCODE_HEADSETHOOK (79), which toggles answer/hangup.
     * Returns false when no privileged shell is available (LOCAL mode).
     */
    private fun privilegedHook(): Boolean {
        if (PrivilegeManager.currentMode() == PrivilegeManager.Mode.LOCAL) return false
        privilegeScope.launch {
            runCatching { PrivilegeManager.runShell("input keyevent 79") }
        }
        return true
    }

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
        if (JarvisAccessibilityService.instance?.answerCallByUi() == true) {
            return Result(true, "Answered the incoming call from the call screen.")
        }
        if (privilegedHook()) {
            return Result(true, "Answered the incoming call via shell input.")
        }
        return Result(false, "No ringing call was found to answer.")
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
        if (JarvisAccessibilityService.instance?.endCallByUi() == true) {
            return Result(true, "Ended the call from the call screen.")
        }
        if (privilegedHook()) {
            return Result(true, "Ended the call via shell input.")
        }
        return Result(false, "No active call was found to end.")
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
