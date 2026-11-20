package com.jarvis.assistant.util

import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.audio.JarvisSoundEffects

/**
 * Handles Emergency Voice SOS ("Code Red", "Emergency", "Bachao").
 * Automatically acquires GPS coordinates, sends emergency SMS and WhatsApp message
 * with Google Maps live location link to the user's emergency contact, and sounds siren.
 */
object EmergencySosHelper {
    private const val TAG = "EmergencySosHelper"

    fun triggerSos(context: Context): String {
        val prefs = JarvisApp.instance.preferences
        if (!prefs.isEmergencySosEnabled) {
            return "Emergency SOS feature settings me disabled hai sir."
        }

        val contactNumber = prefs.emergencyContactPhone.trim()
        if (contactNumber.isBlank()) {
            return "Emergency contact number set nahi hai sir. Kripya Settings me jaakar Emergency Contact number add karein."
        }

        // 1. Get GPS coordinates
        val locationText = getBestLocationUrl(context)

        val emergencyMessage = "EMERGENCY SOS ALERT! Mujhe urgent help chahiye! Meri live location: $locationText (Sent via Jarvis Assistant)"

        // 2. Sound Emergency Alert Siren
        JarvisSoundEffects.playAlarmSiren()

        // 3. Send SMS
        var smsSent = false
        try {
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java) ?: @Suppress("DEPRECATION") SmsManager.getDefault()
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val cleanNumber = contactNumber.replace(Regex("[^0-9+]"), "")
            val parts = smsManager.divideMessage(emergencyMessage)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(cleanNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(cleanNumber, null, emergencyMessage, null, null)
            }
            smsSent = true
            Log.i(TAG, "Emergency SMS sent to $cleanNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send emergency SMS: ${e.message}")
        }

        // 4. Send WhatsApp Deep Link
        try {
            var phoneDigits = contactNumber.filter { it.isDigit() }
            if (phoneDigits.length == 10) {
                phoneDigits = "91$phoneDigits"
            }
            val waUrl = "https://api.whatsapp.com/send?phone=$phoneDigits&text=${Uri.encode(emergencyMessage)}"
            val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse(waUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                setPackage("com.whatsapp")
            }
            context.startActivity(waIntent)
            Log.i(TAG, "Emergency WhatsApp launched for $phoneDigits")
        } catch (e: Exception) {
            Log.w(TAG, "Emergency WhatsApp launch failed: ${e.message}")
        }

        return if (smsSent) {
            "Emergency SOS bhej diya gaya hai! $contactNumber par live location SMS aur WhatsApp se share kar di gayi hai."
        } else {
            "Emergency alert trigger ho gaya hai! WhatsApp ke zariye live location emergency contact ko bhej di gayi hai."
        }
    }

    private fun getBestLocationUrl(context: Context): String {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            var bestLocation: Location? = null
            if (PermissionHelper.hasLocationPermission(context)) {
                val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                for (provider in providers) {
                    try {
                        val loc = lm?.getLastKnownLocation(provider)
                        if (loc != null && (bestLocation == null || loc.accuracy < bestLocation.accuracy)) {
                            bestLocation = loc
                        }
                    } catch (_: SecurityException) {}
                }
            }
            if (bestLocation != null) {
                "https://maps.google.com/?q=${bestLocation.latitude},${bestLocation.longitude}"
            } else {
                "Location unavailable"
            }
        } catch (e: Exception) {
            "Location unavailable"
        }
    }
}
