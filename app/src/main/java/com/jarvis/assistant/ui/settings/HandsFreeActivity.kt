package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.ActivityHandsFreeBinding
import com.jarvis.assistant.ui.permission.PermissionActivity
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.ServiceSetupManager
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.launch

class HandsFreeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHandsFreeBinding
    private var updatingUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityHandsFreeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnToggleAccessibility.setOnClickListener {
            lifecycleScope.launch {
                val enabled = ServiceSetupManager.enableAccessibility(this@HandsFreeActivity)
                updateServiceStatus()
                if (enabled) {
                    Toast.makeText(
                        this@HandsFreeActivity,
                        "Accessibility Service enabled ✓",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@HandsFreeActivity,
                        "Root/Shizuku not available. Opening Android settings…",
                        Toast.LENGTH_LONG
                    ).show()
                    PermissionHelper.openAccessibilitySettings(this@HandsFreeActivity)
                }
            }
        }

        binding.btnOpenAccessibilitySettings.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
        }

        binding.switchAutoReply.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            JarvisApp.instance.preferences.isAutoReplyEnabled = checked
            Toast.makeText(
                this,
                if (checked) "Auto-reply enabled" else "Auto-reply disabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.cbAutoReplyWhatsapp.setOnCheckedChangeListener { _, _ -> persistAutoReplyApps() }
        binding.cbAutoReplyMessenger.setOnCheckedChangeListener { _, _ -> persistAutoReplyApps() }
        binding.cbAutoReplyInstagram.setOnCheckedChangeListener { _, _ -> persistAutoReplyApps() }

        binding.switchCallAnnouncements.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            JarvisApp.instance.preferences.isCallAnnouncementEnabled = checked
        }

        binding.btnEnableNotificationAccess.setOnClickListener {
            lifecycleScope.launch {
                val enabled = ServiceSetupManager.enableNotificationListener(this@HandsFreeActivity)
                updateServiceStatus()
                if (enabled) {
                    Toast.makeText(
                        this@HandsFreeActivity,
                        "Notification access enabled ✓ Auto-reply is ready",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@HandsFreeActivity,
                        "Root/Shizuku not available. Enable JARVIS in Notification access.",
                        Toast.LENGTH_LONG
                    ).show()
                    PermissionHelper.openNotificationListenerSettings(this@HandsFreeActivity)
                }
            }
        }

        binding.btnOpenAllPermissions.setOnClickListener {
            startActivity(Intent(this, PermissionActivity::class.java))
        }

        binding.btnConfigureApi.setOnClickListener {
            startActivity(Intent(this, ApiConnectionActivity::class.java))
        }

        binding.btnHandsFreeOfficialTelegram.setOnClickListener {
            openUrl("https://t.me/+sYana61LvHdmZmE1")
        }

        binding.btnHandsFreeDiscussion.setOnClickListener {
            openUrl("https://t.me/+DmyO7NFUia01YTVl")
        }

        binding.btnHandsFreeInstagram.setOnClickListener {
            openUrl("https://www.instagram.com/techgptx.ai")
        }

        binding.tvFooterHandsFree.setOnClickListener {
            openUrl("https://t.me/+sYana61LvHdmZmE1")
        }
    }

    private fun persistAutoReplyApps() {
        if (updatingUi) return
        val selected = mutableSetOf<String>()
        if (binding.cbAutoReplyWhatsapp.isChecked) selected.add("whatsapp")
        if (binding.cbAutoReplyMessenger.isChecked) selected.add("messenger")
        if (binding.cbAutoReplyInstagram.isChecked) selected.add("instagram")
        JarvisApp.instance.preferences.autoReplyApps = selected
    }

    private fun updateServiceStatus() {
        val isEnabled = PermissionHelper.isAccessibilityServiceEnabled(this)
        if (isEnabled) {
            binding.dotAccessibilityStatus.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_green)
            )
            binding.tvAccessibilityStatus.text = "HANDS-FREE SERVICE: ACTIVE"
            binding.tvAccessibilityStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_green)
            )
            binding.btnToggleAccessibility.text = "Accessibility Service is Active"
        } else {
            binding.dotAccessibilityStatus.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_red)
            )
            binding.tvAccessibilityStatus.text = "SERVICE DISABLED • TAP TO ENABLE"
            binding.tvAccessibilityStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_red)
            )
            binding.btnToggleAccessibility.text = "Enable Accessibility Service"
        }

        updatingUi = true
        val prefs = JarvisApp.instance.preferences
        binding.switchAutoReply.isChecked = prefs.isAutoReplyEnabled
        val replyApps = prefs.autoReplyApps
        binding.cbAutoReplyWhatsapp.isChecked = "whatsapp" in replyApps
        binding.cbAutoReplyMessenger.isChecked = "messenger" in replyApps
        binding.cbAutoReplyInstagram.isChecked = "instagram" in replyApps
        binding.switchCallAnnouncements.isChecked = prefs.isCallAnnouncementEnabled
        updatingUi = false
        val notificationAccess = PermissionHelper.hasNotificationListenerAccess(this)
        if (notificationAccess) {
            binding.tvNotificationAccessStatus.text = "Notification access: ACTIVE • Auto-reply can read WhatsApp/Instagram messages"
            binding.tvNotificationAccessStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_green)
            )
            binding.btnEnableNotificationAccess.text = "Notification Access is Active"
        } else {
            binding.tvNotificationAccessStatus.text = "Notification access: DISABLED • Auto-reply needs this permission"
            binding.tvNotificationAccessStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_red)
            )
            binding.btnEnableNotificationAccess.text = "Enable Notification Access"
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to open link", Toast.LENGTH_SHORT).show()
        }
    }
}
