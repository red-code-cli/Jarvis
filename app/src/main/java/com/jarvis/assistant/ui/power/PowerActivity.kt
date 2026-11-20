package com.jarvis.assistant.ui.power

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.ActivityPowerBinding
import com.jarvis.assistant.privilege.PrivilegeManager
import com.jarvis.assistant.ui.browser.BrowserActivity
import com.jarvis.assistant.ui.terminal.TerminalActivity
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PowerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPowerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPowerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnRefresh.setOnClickListener {
            PrivilegeManager.refresh()
            Toast.makeText(this, "Checking root and Shizuku…", Toast.LENGTH_SHORT).show()
        }

        binding.btnGrantShizuku.setOnClickListener {
            val status = PrivilegeManager.status.value
            when {
                status.shizukuRunning && status.shizukuPermissionGranted -> {
                    PrivilegeManager.refresh()
                    Toast.makeText(this, "Shizuku access is already granted.", Toast.LENGTH_SHORT).show()
                }
                status.shizukuRunning -> PrivilegeManager.requestShizukuPermission()
                else -> {
                    PrivilegeManager.openShizukuManager()
                    Toast.makeText(this, "Start Shizuku, then return here.", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnOpenShizuku.setOnClickListener {
            PrivilegeManager.openShizukuManager()
        }

        binding.btnTestRoot.setOnClickListener { runIdentityTest() }

        binding.btnTerminal.setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java))
        }

        binding.btnBrowser.setOnClickListener {
            startActivity(
                Intent(this, BrowserActivity::class.java)
                    .putExtra(BrowserActivity.EXTRA_OPENED_BY_JARVIS, false)
            )
        }

        lifecycleScope.launch {
            PrivilegeManager.status.collect { renderStatus() }
        }
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        PrivilegeManager.refresh()
    }

    private fun runIdentityTest() {
        binding.btnTestRoot.isEnabled = false
        binding.btnTestRoot.alpha = 0.6f
        binding.tvTestOutput.text = "Running real command…"
        lifecycleScope.launch {
            val result = try {
                PrivilegeManager.runShell(
                    "id; echo ---; getprop ro.build.version.release; echo ---; getprop ro.product.model",
                    15_000
                )
            } catch (t: Throwable) {
                PrivilegeManager.ShellResult(
                    false,
                    -1,
                    "",
                    t.message ?: t.javaClass.simpleName,
                    PrivilegeManager.currentMode().name.lowercase()
                )
            }
            withContext(Dispatchers.Main) {
                val builder = StringBuilder()
                builder.append("MODE: ").append(result.mode.uppercase()).append('\n')
                builder.append("EXIT: ").append(result.exitCode)
                if (result.output.isNotBlank()) {
                    builder.append("\n\n").append(result.output)
                }
                if (!result.error.isNullOrBlank()) {
                    builder.append("\n\nERROR: ").append(result.error)
                }
                if (result.mode == "local") {
                    builder.append("\n\nNote: root/Shizuku not active, so this ran inside the app sandbox.")
                }
                binding.tvTestOutput.text = builder.toString()
                binding.btnTestRoot.isEnabled = true
                binding.btnTestRoot.alpha = 1f
            }
        }
    }

    private fun renderStatus() {
        if (!::binding.isInitialized) return
        val status = PrivilegeManager.status.value

        val accent = ContextCompat.getColor(this, R.color.status_green)
        val shizukuColor = ContextCompat.getColor(this, R.color.accent_sapphire)
        val muted = ContextCompat.getColor(this, R.color.text_muted)

        binding.tvModeBadge.text = status.modeLabel
        binding.tvModeBadge.setTextColor(
            when (status.mode) {
                PrivilegeManager.Mode.ROOT -> accent
                PrivilegeManager.Mode.SHIZUKU -> shizukuColor
                PrivilegeManager.Mode.LOCAL -> muted
            }
        )
        binding.tvModeTitle.text = "${status.modeLabel} MODE"
        binding.tvModeSummary.text = status.summary

        binding.tvRootStatus.text = when {
            status.rootGranted -> "Granted · uid 0"
            status.rootInstalled -> "Installed · not granted"
            else -> "Not detected"
        }
        binding.tvRootStatus.setTextColor(if (status.rootGranted) accent else muted)

        binding.tvShizukuStatus.text = when {
            !status.shizukuInstalled -> "Not installed"
            !status.shizukuRunning -> "Installed · not running"
            !status.shizukuPermissionGranted -> "Permission required"
            status.shizukuServiceBound -> "Connected · shell"
            else -> "Permission granted"
        }
        binding.tvShizukuStatus.setTextColor(
            if (status.shizukuRunning && status.shizukuPermissionGranted) shizukuColor else muted
        )

        binding.tvShellStatus.text = when (status.mode) {
            PrivilegeManager.Mode.ROOT -> "Root shell (uid 0)"
            PrivilegeManager.Mode.SHIZUKU -> "Shizuku shell (ADB)"
            PrivilegeManager.Mode.LOCAL -> "Local app sandbox"
        }
        binding.tvShellStatus.setTextColor(
            if (status.mode == PrivilegeManager.Mode.LOCAL) muted else accent
        )

        binding.tvShizukuHint.text = when {
            status.rootGranted ->
                "Root shell is active. JARVIS can run privileged commands with full uid 0 access."
            !status.shizukuInstalled ->
                "Root is optional. Install Shizuku (or Sui via Magisk) to unlock privileged commands without root."
            !status.shizukuRunning ->
                "Shizuku is installed but not running. Open Shizuku and start it via root or wireless debugging."
            !status.shizukuPermissionGranted ->
                "Shizuku is running. Tap Grant Shizuku to allow JARVIS to use its shell."
            else ->
                "Shizuku is connected. Commands run with ADB shell identity even without root."
        }

        binding.btnGrantShizuku.text = when {
            status.shizukuRunning && status.shizukuPermissionGranted -> "Refresh Access"
            status.shizukuRunning -> "Grant Shizuku"
            else -> "Open Shizuku"
        }
    }
}
