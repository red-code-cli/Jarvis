package com.jarvis.assistant.ui.settings

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.ActivityPersonaBinding
import com.jarvis.assistant.util.ThemeManager

class PersonaActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPersonaBinding
    private val preferences by lazy { JarvisApp.instance.preferences }

    private var currentSelectedPersona = GeminiConstants.PERSONALITY_ASSISTANT

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPersonaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        setupUI()
        loadInitialState()
        setupListeners()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun loadInitialState() {
        selectPersona(preferences.personality, updateVoiceDefault = false)
        binding.etUserName.setText(preferences.userName)
        selectVoice(preferences.voice)
        updatePersonaPreview()
        binding.scrollPersona.post { binding.scrollPersona.scrollTo(0, 0) }
    }

    private fun setupListeners() {
        binding.etUserName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updatePersonaPreview()
            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.cardPersonaJarvis.setOnClickListener {
            selectPersona(GeminiConstants.PERSONALITY_ASSISTANT, updateVoiceDefault = true)
        }
        binding.cardPersonaVirus.setOnClickListener {
            selectPersona(GeminiConstants.PERSONALITY_VIRUS, updateVoiceDefault = true)
        }
        binding.cardPersonaIsha.setOnClickListener {
            selectPersona(GeminiConstants.PERSONALITY_GIRLFRIEND, updateVoiceDefault = true)
        }

        binding.btnActivatePersona.setOnClickListener { saveAndActivate() }
    }

    private fun selectPersona(personaKey: String, updateVoiceDefault: Boolean) {
        currentSelectedPersona = GeminiConstants.normalizePersonality(personaKey)

        binding.rbSelectJarvis.isChecked = currentSelectedPersona == GeminiConstants.PERSONALITY_ASSISTANT
        binding.rbSelectVirus.isChecked = currentSelectedPersona == GeminiConstants.PERSONALITY_VIRUS
        binding.rbSelectIsha.isChecked = currentSelectedPersona == GeminiConstants.PERSONALITY_GIRLFRIEND

        if (updateVoiceDefault) {
            selectVoice(GeminiConstants.defaultVoiceFor(currentSelectedPersona))
        }
        if (::binding.isInitialized) updatePersonaPreview()
    }

    private fun updatePersonaPreview() {
        if (!::binding.isInitialized) return
        val persona = GeminiConstants.personaDisplayName(currentSelectedPersona)
        val profileName = binding.etUserName.text?.toString()?.trim()
            .takeUnless { it.isNullOrBlank() } ?: "dost"
        binding.tvPersonaPreview.text = "$persona · ${getSelectedVoice()}"
        binding.tvPersonaGreeting.text = when (currentSelectedPersona) {
            GeminiConstants.PERSONALITY_VIRUS -> "Bolo $profileName... Virus sun raha hai."
            GeminiConstants.PERSONALITY_GIRLFRIEND -> "Haan jaan $profileName, bolo na..."
            else -> "Ready to help, $profileName."
        }
    }

    private fun selectVoice(voiceName: String) {
        when (voiceName.lowercase()) {
            "aoede" -> binding.rbVoiceAoede.isChecked = true
            "kore" -> binding.rbVoiceKore.isChecked = true
            "fenrir" -> binding.rbVoiceFenrir.isChecked = true
            "charon" -> binding.rbVoiceCharon.isChecked = true
            else -> binding.rbVoicePuck.isChecked = true
        }
    }

    private fun getSelectedVoice(): String = when {
        binding.rbVoiceAoede.isChecked -> "Aoede"
        binding.rbVoiceKore.isChecked -> "Kore"
        binding.rbVoiceFenrir.isChecked -> "Fenrir"
        binding.rbVoiceCharon.isChecked -> "Charon"
        else -> "Puck"
    }

    private fun saveAndActivate() {
        preferences.userName = binding.etUserName.text?.toString()?.trim().orEmpty()
        preferences.voice = getSelectedVoice()
        preferences.personality = currentSelectedPersona

        // If a live voice session is already running, reconnect with the new
        // persona prompt and voice immediately instead of waiting for standby.
        if (JarvisConversationService.isSessionOn.value) {
            JarvisConversationService.restartSession()
        }

        Toast.makeText(
            this,
            "Activated: ${GeminiConstants.personaDisplayName(currentSelectedPersona)} with ${getSelectedVoice()} voice",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }
}
