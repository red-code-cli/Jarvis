package com.jarvis.assistant.security

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.audio.JarvisSoundEffects
import kotlin.math.sqrt

/**
 * Table Guard / Anti-Theft Motion Alarm.
 *
 * When armed, monitors the accelerometer after a 5-second placement grace period.
 * If the device is picked up or moved, sounds a procedural high-urgency siren
 * and invokes the alarm callback for Virus persona warning alert.
 */
object AntiTheftManager : SensorEventListener {
    private const val TAG = "AntiTheftManager"
    private const val MOTION_THRESHOLD = 3.2f // m/s² delta

    @Volatile
    var isArmed: Boolean = false
        private set

    @Volatile
    var isAlarmTriggered: Boolean = false
        private set

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var baselineX = 0f
    private var baselineY = 0f
    private var baselineZ = 0f
    private var baselineCalibrated = false

    private var onAlarmTriggeredCallback: (() -> Unit)? = null

    fun arm(context: Context, onAlarm: () -> Unit) {
        if (isArmed) return
        isArmed = true
        isAlarmTriggered = false
        baselineCalibrated = false
        onAlarmTriggeredCallback = onAlarm

        try {
            sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            accelerometer?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register accelerometer: ${e.message}")
        }

        // 5-second grace period for owner to place the phone down
        mainHandler.postDelayed({
            if (isArmed) {
                baselineCalibrated = true
                Log.i(TAG, "Table guard armed & calibrated. Watching for unauthorized motion...")
            }
        }, 5000L)

        // Save preference state
        try {
            JarvisApp.instance.preferences.isAntiTheftAlarmEnabled = true
        } catch (_: Throwable) {}
    }

    fun disarm(context: Context) {
        if (!isArmed && !isAlarmTriggered) return
        isArmed = false
        isAlarmTriggered = false
        baselineCalibrated = false
        onAlarmTriggeredCallback = null
        mainHandler.removeCallbacksAndMessages(null)

        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {}

        try {
            JarvisApp.instance.preferences.isAntiTheftAlarmEnabled = false
        } catch (_: Throwable) {}

        Log.i(TAG, "Table guard disarmed.")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isArmed || event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (!baselineCalibrated) {
            baselineX = x
            baselineY = y
            baselineZ = z
            return
        }

        val deltaX = x - baselineX
        val deltaY = y - baselineY
        val deltaZ = z - baselineZ
        val delta = sqrt((deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ).toDouble()).toFloat()

        if (delta > MOTION_THRESHOLD && !isAlarmTriggered) {
            isAlarmTriggered = true
            Log.w(TAG, "Motion detected! Delta=$delta exceeds threshold=$MOTION_THRESHOLD. TRIGGERING ALARM!")
            triggerAlarm()
        }
    }

    private fun triggerAlarm() {
        // 1. Procedural siren sound
        JarvisSoundEffects.playAlarmSiren()

        // 2. Invoke alarm callback for voice warning
        mainHandler.post {
            onAlarmTriggeredCallback?.invoke()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
