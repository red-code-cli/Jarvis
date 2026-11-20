package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.FragmentSettingsBinding
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.ui.permission.PermissionActivity
import com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private var harnessSettings: HarnessSettingsController? = null
    private val preferences by lazy { JarvisApp.instance.preferences }

    private val profilePhotoPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) saveProfilePhoto(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        loadUserData()
        setupClickListeners()
        setupCategoryJump()
        setupVoiceThreshold()
        harnessSettings = HarnessSettingsController(this, binding).also { it.bind() }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            loadUserData()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            loadUserData()
            binding.settingsScroll.scrollTo(0, 0)
        } else if (hidden && _binding != null) {
            binding.root.visibility = View.GONE
        }
    }

    private fun loadUserData() {
        val userName = preferences.userName.ifBlank { "dost" }
        binding.tvProfileName.text = userName
        binding.tvProfileSubtitle.text = "JARVIS OPERATOR • PRIMARY USER"
        loadProfilePhoto()

        // Owner Voice Profile Status
        val hasVoiceProfile = preferences.hasOwnerVoiceProfile()
        val ownerVoiceOn = preferences.isOwnerVoiceEnabled
        if (hasVoiceProfile) {
            binding.tvOwnerVoiceBadge.text = if (ownerVoiceOn) "ACTIVE" else "PAUSED"
            binding.tvOwnerVoiceBadge.setTextColor(Color.parseColor(if (ownerVoiceOn) "#10B981" else "#F59E0B"))
            binding.tvOwnerVoiceBadge.setBackgroundResource(if (ownerVoiceOn) R.drawable.bg_jarvis_status_badge_green else R.drawable.bg_jarvis_status_badge)
            binding.tvOwnerVoiceSubtitle.text = if (ownerVoiceOn) {
                "Voice profile active • sirf aapki awaaz"
            } else {
                "Voice profile paused • owner-only OFF hai"
            }

            binding.tvProfileVoiceStatus.text = if (ownerVoiceOn) "VOICE ENROLLED" else "VOICE PAUSED"
            binding.tvProfileVoiceStatus.setTextColor(Color.parseColor(if (ownerVoiceOn) "#10B981" else "#F59E0B"))
            binding.chipProfileVoiceStatus.setBackgroundResource(if (ownerVoiceOn) R.drawable.bg_jarvis_status_badge_green else R.drawable.bg_jarvis_status_badge)
            binding.ivProfileVoiceBadgeIcon.setImageResource(R.drawable.ic_verified_badge)
            binding.ivProfileVoiceBadgeIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(if (ownerVoiceOn) "#10B981" else "#F59E0B"))
        } else {
            binding.tvOwnerVoiceBadge.text = "SETUP"
            binding.tvOwnerVoiceBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_sapphire))
            binding.tvOwnerVoiceBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.tvOwnerVoiceSubtitle.text = if (ownerVoiceOn) {
                "Profile missing • fail-open hai, enroll karo"
            } else {
                "Enroll nahi hua • tap karke voice profile record karo"
            }

            binding.tvProfileVoiceStatus.text = "ENROLL VOICE"
            binding.tvProfileVoiceStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_sapphire))
            binding.chipProfileVoiceStatus.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.ivProfileVoiceBadgeIcon.setImageResource(R.drawable.ic_settings_owner_voice)
            binding.ivProfileVoiceBadgeIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.accent_sapphire)
            )
        }

        // Language
        binding.tvCurrentLanguage.text = preferences.language
        updateVoiceThresholdLabel()

        // Persona & Voice
        val currentPersona = GeminiConstants.personaDisplayName(preferences.personality)
        val currentVoice = preferences.voice
        binding.tvCurrentPersona.text = "$currentPersona • Voice: $currentVoice"
        binding.tvProfilePersonaChip.text = currentPersona.uppercase()

        // Silent Execution Mode
        binding.switchSilentMode.isChecked = preferences.isSilentExecutionMode

        // Wake sensitivity
        binding.tvCurrentWakeSensitivity.text = when (preferences.wakeSensitivity) {
            "high" -> "High (Far-field) • Door se bhi sune"
            "low" -> "Low (Strict) • Shor me false wake nahi"
            else -> "Balanced • Standard room (Recommended)"
        }

        // Persona wake words
        binding.tvCurrentWakeWords.text =
            preferences.enabledWakeWords.joinToString(" • ").ifBlank { "Off • Koi wake word nahi" }

        // Smart Battery Saver
        binding.switchBatterySaver.isChecked = preferences.isSmartBatterySaverEnabled

        // Background Noise Filter
        binding.switchNoiseFilter.isChecked = preferences.isNoiseFilterEnabled

        // Voice Call Control (Pick/Cut)
        binding.switchVoiceCallAnswering.isChecked = preferences.isVoiceCallAnsweringEnabled

        // Unread Messages Reader
        binding.switchUnreadMessages.isChecked = preferences.isUnreadMessagesReaderEnabled

        // Shake to Talk
        binding.switchShakeToTalk.isChecked = preferences.isShakeToTalkEnabled

        // Anti Theft Alarm
        binding.switchAntiTheftAlarm.isChecked = preferences.isAntiTheftAlarmEnabled

        // Emergency SOS
        binding.switchEmergencySos.isChecked = preferences.isEmergencySosEnabled

        // Emergency Contact
        val contact = preferences.emergencyContactPhone
        binding.tvCurrentEmergencyContact.text = if (contact.isNotBlank()) contact else "Not configured"

        // Model
        val modelDisplay = preferences.aiModel.removePrefix("models/")
        binding.tvCurrentModel.text = "$modelDisplay • Live WebSocket"

        // Theme
        binding.tvCurrentTheme.text = when (preferences.appTheme) {
            "dark" -> "Dark Theme"
            "red" -> "Red Theme"
            "sunset" -> "Sunset Theme"
            "custom" -> "Custom (${preferences.customThemeColor})"
            else -> "Ice Blue Theme"
        }
    }

    private fun setupCategoryJump() {
        val targets = listOf(
            binding.catProfileVoice to binding.cardProfile,
            binding.catVoice to binding.rowOwnerVoice,
            binding.catPhone to binding.rowVoiceCallAnswering,
            binding.catCoding to binding.cardCodingEngine,
            binding.catAi to binding.rowApiConnection,
            binding.catMemory to binding.rowMemory,
            binding.catAppearance to binding.rowAppTheme,
            binding.catPermissions to binding.rowPermissions,
            binding.catAbout to binding.rowAppIdentity,
        )
        targets.forEach { (chip, target) ->
            chip.setOnClickListener { scrollToSettings(target) }
        }
    }

    /** Scrolls the existing settings ScrollView to a section; no layout or values change. */
    private fun scrollToSettings(target: View) {
        var y = 0
        var current: View? = target
        while (current != null && current !== binding.settingsScroll) {
            y += current.top
            current = current.parent as? View
        }
        binding.settingsScroll.smoothScrollTo(0, (y - 12).coerceAtLeast(0))
    }

    private fun setupVoiceThreshold() {
        updateVoiceThresholdLabel()
        binding.rowOwnerVoiceThreshold.setOnClickListener {
            val labels = arrayOf(
                "Strict — high security, shor me aapko bhi reject kar sakta hai",
                "Balanced — recommended",
                "Relaxed — TV/background noise tolerate karta hai",
            )
            val current = preferences.ownerVoiceThreshold
            val checked = when {
                current <= com.jarvis.assistant.audio.OwnerVoiceManager.STRICT_THRESHOLD -> 0
                current >= com.jarvis.assistant.audio.OwnerVoiceManager.RELAXED_THRESHOLD -> 2
                else -> 1
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Voice verification")
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    preferences.ownerVoiceThreshold = when (which) {
                        0 -> com.jarvis.assistant.audio.OwnerVoiceManager.STRICT_THRESHOLD
                        2 -> com.jarvis.assistant.audio.OwnerVoiceManager.RELAXED_THRESHOLD
                        else -> com.jarvis.assistant.audio.OwnerVoiceManager.DEFAULT_THRESHOLD
                    }
                    updateVoiceThresholdLabel()
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun updateVoiceThresholdLabel() {
        val threshold = preferences.ownerVoiceThreshold
        binding.tvOwnerVoiceThresholdMode.text = when {
            threshold <= com.jarvis.assistant.audio.OwnerVoiceManager.STRICT_THRESHOLD -> "Strict · zyada security"
            threshold >= com.jarvis.assistant.audio.OwnerVoiceManager.RELAXED_THRESHOLD -> "Relaxed · shor wale kamre"
            else -> "Balanced · recommended"
        }    }

    private fun setupClickListeners() {
        // Profile Card -> Change Name
        binding.cardProfile.setOnClickListener {
            showEditNameDialog()
        }

        // Profile photo -> pick from gallery (applies to all personas)
        binding.ivProfilePhoto.setOnClickListener {
            profilePhotoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        // Voice Status Chip on Profile Card -> Opens Voice Enrollment
        binding.chipProfileVoiceStatus.setOnClickListener {
            startActivity(Intent(requireContext(), VoiceEnrollmentActivity::class.java))
        }

        // Section 1: AI & Voice Intelligence
        binding.rowOwnerVoice.setOnClickListener {
            startActivity(Intent(requireContext(), VoiceEnrollmentActivity::class.java))
        }

        binding.rowLanguage.setOnClickListener {
            showLanguageDialog()
        }

        binding.rowPersonaVoice.setOnClickListener {
            startActivity(Intent(requireContext(), PersonaActivity::class.java))
        }

        binding.switchSilentMode.setOnCheckedChangeListener { _, isChecked ->
            preferences.isSilentExecutionMode = isChecked
            val msg = if (isChecked) "Silent Execution Mode Enabled" else "Silent Execution Mode Disabled"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.rowWakeSensitivity.setOnClickListener {
            showWakeSensitivityDialog()
        }

        binding.rowWakeWords.setOnClickListener {
            showWakeWordDialog()
        }

        binding.switchBatterySaver.setOnCheckedChangeListener { _, isChecked ->
            preferences.isSmartBatterySaverEnabled = isChecked
            val msg = if (isChecked) "Smart Battery Saver Enabled" else "Smart Battery Saver Disabled"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchNoiseFilter.setOnCheckedChangeListener { _, isChecked ->
            preferences.isNoiseFilterEnabled = isChecked
            val msg = if (isChecked) "Background Noise Filter Enabled" else "Background Noise Filter Disabled"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchVoiceCallAnswering.setOnCheckedChangeListener { _, isChecked ->
            preferences.isVoiceCallAnsweringEnabled = isChecked
            val msg = if (isChecked) "Voice Call Control Enabled (Pick/Cut)" else "Voice Call Control Disabled"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchUnreadMessages.setOnCheckedChangeListener { _, isChecked ->
            preferences.isUnreadMessagesReaderEnabled = isChecked
            val msg = if (isChecked) "Unread Messages Reader Enabled" else "Unread Messages Reader Disabled"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchShakeToTalk.setOnCheckedChangeListener { _, isChecked ->
            preferences.isShakeToTalkEnabled = isChecked
            val msg = if (isChecked) "Shake-to-Talk Enabled" else "Shake-to-Talk Disabled"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchAntiTheftAlarm.setOnCheckedChangeListener { _, isChecked ->
            preferences.isAntiTheftAlarmEnabled = isChecked
            if (isChecked) {
                com.jarvis.assistant.security.AntiTheftManager.arm(requireContext()) {
                    com.jarvis.assistant.background.JarvisConversationService.triggerAntiTheftAlert()
                }
                Toast.makeText(requireContext(), "Table Guard Armed (5s grace period)", Toast.LENGTH_SHORT).show()
            } else {
                com.jarvis.assistant.security.AntiTheftManager.disarm(requireContext())
                Toast.makeText(requireContext(), "Table Guard Disarmed", Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchEmergencySos.setOnCheckedChangeListener { _, isChecked ->
            preferences.isEmergencySosEnabled = isChecked
            val msg = if (isChecked) "Emergency Voice SOS Enabled" else "Emergency Voice SOS Disabled"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.rowEmergencyContact.setOnClickListener {
            showEmergencyContactDialog()
        }

        // Section 2: System & Intelligence Data
        binding.rowApiConnection.setOnClickListener {
            startActivity(Intent(requireContext(), ApiConnectionActivity::class.java))
        }

        binding.rowMemory.setOnClickListener {
            (activity as? MainActivity)?.selectPage(MainActivity.TAB_MEMORY)
        }

        binding.rowPermissions.setOnClickListener {
            startActivity(Intent(requireContext(), PermissionActivity::class.java))
        }

        binding.rowOverlayAvatars.setOnClickListener {
            startActivity(Intent(requireContext(), OverlayAvatarActivity::class.java))
        }

        binding.rowApplications.setOnClickListener {
            startActivity(Intent(requireContext(), HandsFreeActivity::class.java))
        }

        // Moved out of the sidebar so Settings stays the single home for these.
        binding.rowAccessibility.setOnClickListener {
            com.jarvis.assistant.util.PermissionHelper.openAccessibilitySettings(requireContext())
        }

        binding.rowRootAccess.setOnClickListener {
            startActivity(Intent(requireContext(), com.jarvis.assistant.ui.power.PowerActivity::class.java))
        }

        binding.rowDefaultLauncher.setOnClickListener {
            (activity as? MainActivity)?.requestDefaultLauncher()
        }

        binding.rowUpdatesHub.setOnClickListener {
            (activity as? MainActivity)?.selectPage(MainActivity.TAB_UPDATES)
        }

        binding.rowApiProviders.setOnClickListener {
            startActivity(Intent(requireContext(), ProvidersActivity::class.java))
        }

        // Section 3: Appearance & Security
        binding.rowAppTheme.setOnClickListener {
            ThemeSelectionBottomSheet.newInstance().show(childFragmentManager, ThemeSelectionBottomSheet.TAG)
        }

        binding.rowPrivacyPolicy.setOnClickListener {
            startActivity(Intent(requireContext(), PrivacyShieldActivity::class.java))
        }

        binding.rowAppIdentity.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("JARVIS App Identity")
                .setMessage(com.jarvis.assistant.security.AppIdentityVerifier.report(requireContext()))
                .setPositiveButton("Close", null)
                .show()
        }

        // Social Media Community Buttons
        binding.btnSocialTelegram.setOnClickListener {
            openUrl("https://t.me/+sYana61LvHdmZmE1")
        }

        binding.btnSocialInstagram.setOnClickListener {
            openUrl("https://www.instagram.com/techgptx.ai")
        }

        binding.btnSocialYoutube.setOnClickListener {
            openUrl("https://youtube.com/@TechGptX")
        }

        binding.tvFooterCredit.setOnClickListener {
            openUrl("https://t.me/+sYana61LvHdmZmE1")
        }
    }

    private fun showLanguageDialog() {
        val languages = GeminiConstants.SUPPORTED_LANGUAGES.toTypedArray()
        val currentIndex = languages.indexOfFirst { it.equals(preferences.language, ignoreCase = true) }.let { if (it >= 0) it else 0 }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Conversation Language")
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val chosen = languages[which]
                preferences.language = chosen
                binding.tvCurrentLanguage.text = chosen
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showWakeWordDialog() {
        val labels = arrayOf("Jarvis", "Isha", "Virus")
        val checked = booleanArrayOf(
            preferences.isWakeWordJarvisEnabled,
            preferences.isWakeWordIshaEnabled,
            preferences.isWakeWordVirusEnabled,
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Wake Words")
            .setMessage(
                "Jis naam se bulaoge, wahi persona active hoga.\n" +
                    "Jarvis → assistant, Isha → companion, Virus → rebel.\n\n" +
                    "Off kiya hua naam detector se hat jaata hai, isliye wo persona apne aap switch nahi hogi."
            )
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                when (which) {
                    0 -> preferences.isWakeWordJarvisEnabled = isChecked
                    1 -> preferences.isWakeWordIshaEnabled = isChecked
                    2 -> preferences.isWakeWordVirusEnabled = isChecked
                }
                loadUserData()
            }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showWakeSensitivityDialog() {
        val options = arrayOf(
            "Balanced (Recommended • Standard room)",
            "High (Far-field • Door ya dheemi aawaz me bhi sunega)",
            "Low (Strict • Shor-sharabe me false triggers kam karega)"
        )
        val currentIndex = when (preferences.wakeSensitivity) {
            "high" -> 1
            "low" -> 2
            else -> 0
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Wake Word Sensitivity")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                when (which) {
                    0 -> preferences.wakeSensitivity = "balanced"
                    1 -> preferences.wakeSensitivity = "high"
                    2 -> preferences.wakeSensitivity = "low"
                }
                loadUserData()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEmergencyContactDialog() {
        val input = EditText(requireContext()).apply {
            hint = "+919876543210"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(preferences.emergencyContactPhone)
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Emergency Contact Number")
            .setMessage("Emergency Voice SOS ('Code Red' / 'Emergency') bolne par is number par live location SMS aur WhatsApp bheja jayega:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val number = input.text.toString().trim()
                preferences.emergencyContactPhone = number
                binding.tvCurrentEmergencyContact.text = if (number.isNotBlank()) number else "Not configured"
                Toast.makeText(requireContext(), "Emergency contact saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAppAutomationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Autonomous App Automation")
            .setMessage("Jarvis is integrated with your device capabilities:\n\n• WhatsApp: Hands-free messaging & calls\n• YouTube: Search and playback automation\n• Phone Controls: Flashlight, Volume, Brightness, Bluetooth\n• Apps & Tools: Launch, search, and manage apps via voice")
            .setPositiveButton("Close", null)
            .show()
    }

    private fun loadProfilePhoto() {
        if (_binding == null) return
        val path = preferences.profilePhotoPath
        val bitmap = path.takeIf { it.isNotBlank() }?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        if (bitmap != null) {
            binding.ivProfilePhoto.setImageBitmap(bitmap)
            binding.ivProfilePhoto.imageTintList = null
            binding.ivProfilePhoto.scaleType = ImageView.ScaleType.CENTER_CROP
            binding.ivProfilePhoto.setPadding(0, 0, 0, 0)
        } else {
            binding.ivProfilePhoto.setImageResource(R.drawable.ic_user_avatar)
            binding.ivProfilePhoto.imageTintList = ContextCompat.getColorStateList(
                requireContext(),
                R.color.accent_sapphire
            )
            binding.ivProfilePhoto.scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = (13 * resources.displayMetrics.density).toInt()
            binding.ivProfilePhoto.setPadding(pad, pad, pad, pad)
        }
    }

    private fun saveProfilePhoto(uri: Uri) {
        try {
            val target = java.io.File(requireContext().filesDir, "profile_photo.jpg")
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not read the selected photo")
            preferences.profilePhotoPath = target.absolutePath
            loadProfilePhoto()
            Toast.makeText(requireContext(), "Profile photo updated", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(
                requireContext(),
                "Photo could not be saved: ${t.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showEditNameDialog() {
        val input = EditText(requireContext()).apply {
            setText(preferences.userName.ifBlank { "Rehaan" })
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Operator Name")
            .setMessage("Enter the name Jarvis should use to address you:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    preferences.userName = newName
                    loadUserData()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "Unable to open link", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
