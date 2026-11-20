package com.jarvis.assistant.ui.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.audio.OwnerVoiceManager
import com.jarvis.assistant.audio.WakeModelDownloader
import com.jarvis.assistant.databinding.ActivityVoiceEnrollmentBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

class VoiceEnrollmentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoiceEnrollmentBinding
    private val preferences by lazy { JarvisApp.instance.preferences }

    private val samplePrompts = listOf(
        "\"Jarvis, subah 7 baje alarm lagao\"",
        "\"Jarvis, aaj mausam kaisa hai?\"",
        "\"Jarvis, WhatsApp kholo aur message padho\""
    )

    private val recordedVectors = mutableListOf<FloatArray>()
    private var currentSampleIndex = 0
    private var captureNoiseSuppressor: android.media.audiofx.NoiseSuppressor? = null
    private var maxDurationStopScheduled = false

    private companion object {
        /** 8 s at 16 kHz mono PCM16 — hard cap so a forgotten recording cannot grow forever. */
        const val MAX_SAMPLE_BYTES = 16_000 * 2 * 8
    }

    private var isRecording = false
    private var isTesting = false
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val recordingBuffer = ByteArrayOutputStream()

    private val requestAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Microphone permission required for voice enrollment", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceEnrollmentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        // Make sure the sherpa-onnx speaker model is available before enrolment.
        lifecycleScope.launch { WakeModelDownloader.ensureModels(applicationContext) }

        setupUI()
        setupListeners()
        checkPermissions()
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun setupUI() {
        val hasProfile = preferences.hasOwnerVoiceProfile()
        val isEnabled = preferences.isOwnerVoiceEnabled && hasProfile

        binding.switchOwnerOnly.isChecked = isEnabled
        // Always tappable: a user with a broken/legacy profile must be able to switch
        // owner-only mode OFF without enrolling first.
        binding.switchOwnerOnly.isEnabled = true

        if (hasProfile) {
            binding.tvEnrollmentStatus.text = if (isEnabled) "OWNER VOICE: ACTIVE" else "OWNER VOICE: PAUSED"
            binding.tvEnrollmentStatus.setTextColor(getColor(if (isEnabled) R.color.primary_cyan else R.color.text_secondary))
            binding.tvStatusDescription.text = "Jarvis aapki awaaz pe calibrated hai. Doosre logon ki speech ignore hogi."
            binding.viewStatusDot.setBackgroundResource(if (isEnabled) R.drawable.circle_cyan else R.drawable.circle_cyan)
        } else {
            binding.tvEnrollmentStatus.text = "OWNER VOICE: ENROLL NAHI HUA"
            binding.tvEnrollmentStatus.setTextColor(getColor(R.color.status_amber))
            binding.tvStatusDescription.text = "Neeche 3 samples enroll karo. Save hone ke baad Jarvis sirf aapki awaaz sunega."
        }

        // Sensitivity
        val threshold = preferences.ownerVoiceThreshold
        when {
            threshold <= OwnerVoiceManager.STRICT_THRESHOLD + 0.02f -> binding.rbStrict.isChecked = true
            threshold >= OwnerVoiceManager.RELAXED_THRESHOLD - 0.02f -> binding.rbRelaxed.isChecked = true
            else -> binding.rbBalanced.isChecked = true
        }

        updateEnrollmentStepUI()
    }

    private fun updateEnrollmentStepUI() {
        if (currentSampleIndex < samplePrompts.size) {
            binding.tvStepIndicator.text = "TRAIN STEP ${currentSampleIndex + 1} OF ${samplePrompts.size}"
            binding.tvPromptPhrase.text = samplePrompts[currentSampleIndex]
            binding.btnRecordSample.text = "TAP KARKE PHRASE BOLO"
            binding.tvRecordFeedback.text = "Sample ${currentSampleIndex + 1} record karne ke liye tap karo"
        } else {
            binding.tvStepIndicator.text = "ENROLLMENT COMPLETE"
            binding.tvPromptPhrase.text = "Teeno samples save ho gaye!"
            binding.btnRecordSample.text = "SAMPLES DOBARA RECORD KARO"
            binding.tvRecordFeedback.text = "Owner voice profile active aur encrypted hai."
        }

        // Dot indicators
        val tvAccent = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeAccent, tvAccent, true)
        val accentColor = if (tvAccent.data != 0) tvAccent.data else ContextCompat.getColor(this, R.color.primary_cyan)

        val tvMuted = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeTextMuted, tvMuted, true)
        val mutedColor = if (tvMuted.data != 0) tvMuted.data else ContextCompat.getColor(this, R.color.text_muted)

        val greenColor = ContextCompat.getColor(this, R.color.status_green)

        binding.tvStep1Dot.setTextColor(if (recordedVectors.size >= 1) greenColor else if (currentSampleIndex == 0) accentColor else mutedColor)
        binding.tvStep2Dot.setTextColor(if (recordedVectors.size >= 2) greenColor else if (currentSampleIndex == 1) accentColor else mutedColor)
        binding.tvStep3Dot.setTextColor(if (recordedVectors.size >= 3) greenColor else if (currentSampleIndex == 2) accentColor else mutedColor)
    }

    private fun setupListeners() {
        binding.switchOwnerOnly.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !preferences.hasOwnerVoiceProfile()) {
                // Fail-open by design: never leave the switch on with no profile.
                binding.switchOwnerOnly.isChecked = false
                Toast.makeText(this, "Pehle apni awaaz enroll karo, phir owner-only ON karo.", Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            preferences.isOwnerVoiceEnabled = isChecked
            setupUI()
        }

        binding.btnRecordSample.setOnClickListener {
            if (isRecording) {
                stopRecordingSample()
            } else {
                if (currentSampleIndex >= samplePrompts.size) {
                    // Reset to start over
                    recordedVectors.clear()
                    currentSampleIndex = 0
                    updateEnrollmentStepUI()
                }
                startRecordingSample()
            }
        }

        binding.btnTestVoice.setOnClickListener {
            if (isTesting) {
                stopTestingVoice()
            } else {
                startTestingVoice()
            }
        }

        binding.rgSensitivity.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbStrict -> preferences.ownerVoiceThreshold = OwnerVoiceManager.STRICT_THRESHOLD
                R.id.rbBalanced -> preferences.ownerVoiceThreshold = OwnerVoiceManager.DEFAULT_THRESHOLD
                R.id.rbRelaxed -> preferences.ownerVoiceThreshold = OwnerVoiceManager.RELAXED_THRESHOLD
            }
            Toast.makeText(this, "Sensitivity update ho gayi", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearProfile.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Voice profile reset karein?")
                .setMessage("Aapka enrolled voiceprint delete ho jayega. Jab tak dobara enroll nahi karte, Jarvis sabki awaaz sunega.")
                .setPositiveButton("RESET") { _, _ ->
                    preferences.clearOwnerVoiceProfile()
                    recordedVectors.clear()
                    currentSampleIndex = 0
                    setupUI()
                    Toast.makeText(this, "Voice profile clear ho gaya", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("CANCEL", null)
                .show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecordingSample() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions()
            return
        }

        isRecording = true
        maxDurationStopScheduled = false
        binding.btnRecordSample.text = "STOP & PROCESS"
        binding.btnRecordSample.setBackgroundColor(getColor(R.color.status_amber))
        binding.tvRecordFeedback.text = "Sun raha hoon... abhi phrase bolo!"

        startAudioCapture { pcmChunk ->
            var reachedMax = false
            synchronized(recordingBuffer) {
                recordingBuffer.write(pcmChunk)
                reachedMax = recordingBuffer.size() >= MAX_SAMPLE_BYTES
            }
            if (reachedMax && !maxDurationStopScheduled) {
                maxDurationStopScheduled = true
                runOnUiThread { if (isRecording) stopRecordingSample() }
            }
        }
    }

    private fun stopRecordingSample() {
        if (!isRecording) return
        isRecording = false
        stopAudioCapture()

        val pcmAudio = synchronized(recordingBuffer) {
            val data = recordingBuffer.toByteArray()
            recordingBuffer.reset()
            data
        }

        val quality = com.jarvis.assistant.audio.EnrollmentQuality.evaluate(pcmAudio)
        if (!quality.ok) {
            binding.btnRecordSample.isEnabled = true
            binding.btnRecordSample.setBackgroundColor(getColor(R.color.primary_cyan))
            binding.tvRecordFeedback.text = quality.hinglishMessage()
            binding.btnRecordSample.text = "RETRY SAMPLE ${currentSampleIndex + 1}"
            return
        }

        binding.btnRecordSample.isEnabled = false
        binding.tvRecordFeedback.text = "Awaaz ka acoustic signature nikaal raha hoon..."

        lifecycleScope.launch(Dispatchers.Default) {
            val vector = OwnerVoiceManager.extractSpeakerVector(pcmAudio)

            withContext(Dispatchers.Main) {
                binding.btnRecordSample.isEnabled = true
                binding.btnRecordSample.setBackgroundColor(getColor(R.color.primary_cyan))

                if (vector != null && vector.isNotEmpty()) {
                    recordedVectors.add(vector)
                    currentSampleIndex++

                    if (currentSampleIndex >= samplePrompts.size) {
                        // Average vectors and save both centroid and individual samples
                        val masterProfile = OwnerVoiceManager.averageVectors(recordedVectors)
                        if (masterProfile != null) {
                            preferences.saveOwnerVoiceProfile(masterProfile, recordedVectors)
                            preferences.isOwnerVoiceEnabled = true
                            Toast.makeText(this@VoiceEnrollmentActivity, "Voice profile train ho gaya!", Toast.LENGTH_LONG).show()
                            setupUI()
                        } else {
                            Toast.makeText(this@VoiceEnrollmentActivity, "Voice vectors average nahi ho paye. Dobara try karo.", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this@VoiceEnrollmentActivity, "Sample ${currentSampleIndex} save! Ab agla phrase bolo.", Toast.LENGTH_SHORT).show()
                        updateEnrollmentStepUI()
                    }
                } else {
                    binding.tvRecordFeedback.text = "Saaf speech detect nahi hui. Thoda loud aur clear bolke dobara try karo."
                    binding.btnRecordSample.text = "RETRY SAMPLE ${currentSampleIndex + 1}"
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startTestingVoice() {
        if (!preferences.hasOwnerVoiceProfile()) {
            Toast.makeText(this, "Please enroll your voice samples first!", Toast.LENGTH_SHORT).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions()
            return
        }

        isTesting = true
        binding.btnTestVoice.text = "STOP & VERIFY NOW"
        val amberColor = ContextCompat.getColor(this, R.color.status_amber)
        binding.btnTestVoice.setTextColor(amberColor)
        binding.btnTestVoice.strokeColor = android.content.res.ColorStateList.valueOf(amberColor)
        binding.layoutTestResult.visibility = View.GONE

        startAudioCapture { pcmChunk ->
            synchronized(recordingBuffer) { recordingBuffer.write(pcmChunk) }
        }
    }

    private fun stopTestingVoice() {
        if (!isTesting) return
        isTesting = false
        stopAudioCapture()

        val pcmAudio = synchronized(recordingBuffer) {
            val data = recordingBuffer.toByteArray()
            recordingBuffer.reset()
            data
        }

        binding.btnTestVoice.text = "TEST VOICE MATCH (SPEAK NOW)"
        val tvAccent = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeAccent, tvAccent, true)
        val accentColor = if (tvAccent.data != 0) tvAccent.data else ContextCompat.getColor(this, R.color.primary_cyan)
        binding.btnTestVoice.setTextColor(accentColor)
        binding.btnTestVoice.strokeColor = android.content.res.ColorStateList.valueOf(accentColor)

        if (pcmAudio.size < com.jarvis.assistant.audio.SherpaVoiceprint.MIN_BYTES) {
            binding.layoutTestResult.visibility = View.VISIBLE
            binding.tvTestVerdict.text = "RECORDING BAHUT CHHOTI"
            binding.tvTestVerdict.setTextColor(getColor(R.color.status_amber))
            binding.tvTestDetails.text = "Voice match ke liye poora sentence bolo (kam se kam 1-2 second)."
            return
        }

        binding.btnTestVoice.isEnabled = false

        lifecycleScope.launch(Dispatchers.Default) {
            val result = OwnerVoiceManager.verifySpeakerAudio(pcmAudio, preferences)

            withContext(Dispatchers.Main) {
                binding.btnTestVoice.isEnabled = true
                binding.layoutTestResult.visibility = View.VISIBLE

                if (!result.hasExtractedVector) {
                    binding.tvTestVerdict.text = "MODEL READY NAHI HAI"
                    binding.tvTestVerdict.setTextColor(getColor(R.color.status_amber))
                    binding.tvTestDetails.text = "Speaker model download/warm-up me hai. Thodi der baad dobara test karo."
                } else if (result.isMatch) {
                    binding.tvTestVerdict.text = "VERIFIED: OWNER (${result.similarityPercent}% MATCH)"
                    binding.tvTestVerdict.setTextColor(getColor(R.color.status_green))
                    binding.tvTestDetails.text = "Awaaz match ho gayi! Jarvis aapke commands accept karega. (Distance: ${String.format(Locale.ROOT, "%.2f", result.distance)}, Threshold: ${result.threshold})"
                } else {
                    binding.tvTestVerdict.text = "REJECTED: UNKNOWN VOICE (${result.similarityPercent}% MATCH)"
                    binding.tvTestVerdict.setTextColor(getColor(R.color.status_red))
                    binding.tvTestDetails.text = "Awaaz owner se match nahi hui. Jarvis ignore karega. (Distance: ${String.format(Locale.ROOT, "%.2f", result.distance)}, Threshold: ${result.threshold})"
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture(onChunk: (ByteArray) -> Unit) {
        stopAudioCapture()
        recordingBuffer.reset()

        recordingJob = lifecycleScope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = maxOf(minBufSize * 2, 32000)

            try {
                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    return@launch
                }

                // Apply the user's background-noise filter to enrollment too, so a noisy room
                // does not poison the averaged voiceprint.
                val filterEnabled = try {
                    preferences.isNoiseFilterEnabled
                } catch (_: Throwable) { true }
                if (filterEnabled && android.media.audiofx.NoiseSuppressor.isAvailable()) {
                    captureNoiseSuppressor = runCatching {
                        android.media.audiofx.NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
                    }.getOrNull()
                }

                record.startRecording()
                audioRecord = record

                val chunk = ByteArray(640) // 20ms chunk at 16kHz 16-bit
                while (isActive) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        onChunk(chunk.copyOf(read))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (_: Exception) {}
                audioRecord = null
                runCatching { captureNoiseSuppressor?.release() }
                captureNoiseSuppressor = null
            }
        }
    }

    private fun stopAudioCapture() {
        val job = recordingJob
        recordingJob = null
        job?.cancel()
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        // Let the blocking read leave native code before the record is released.
        runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(250L) { job?.join() }
            }
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        runCatching { captureNoiseSuppressor?.release() }
        captureNoiseSuppressor = null
    }

    override fun onDestroy() {
        stopAudioCapture()
        super.onDestroy()
    }
}
