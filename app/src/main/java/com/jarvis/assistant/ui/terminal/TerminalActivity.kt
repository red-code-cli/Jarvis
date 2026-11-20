package com.jarvis.assistant.ui.terminal

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.ActivityTerminalBinding
import com.jarvis.assistant.privilege.PrivilegeManager
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TerminalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalBinding
    private val transcript = StringBuilder()
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnRun.setOnClickListener { submitCurrentCommand() }
        binding.etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_GO) {
                submitCurrentCommand()
                true
            } else {
                false
            }
        }

        binding.btnClear.setOnClickListener {
            transcript.setLength(0)
            binding.tvOutput.text = ""
            appendLine(headerText())
        }

        binding.chipIdentity.setOnClickListener {
            binding.etCommand.setText("id")
            binding.etCommand.setSelection(binding.etCommand.text.length)
        }
        binding.chipBattery.setOnClickListener {
            binding.etCommand.setText("dumpsys battery | grep -E 'level|status|temperature'")
            binding.etCommand.setSelection(binding.etCommand.text.length)
        }
        binding.chipPackages.setOnClickListener {
            binding.etCommand.setText("pm list packages | head -30")
            binding.etCommand.setSelection(binding.etCommand.text.length)
        }
        binding.chipProps.setOnClickListener {
            binding.etCommand.setText("getprop ro.build.version.release; getprop ro.product.model")
            binding.etCommand.setSelection(binding.etCommand.text.length)
        }

        lifecycleScope.launch {
            PrivilegeManager.status.collect { status ->
                binding.tvModeBadge.text = status.modeLabel
                binding.tvModeBadge.setTextColor(
                    when (status.mode) {
                        PrivilegeManager.Mode.ROOT -> ContextCompat.getColor(this@TerminalActivity, R.color.status_green)
                        PrivilegeManager.Mode.SHIZUKU -> ContextCompat.getColor(this@TerminalActivity, R.color.accent_sapphire)
                        PrivilegeManager.Mode.LOCAL -> ContextCompat.getColor(this@TerminalActivity, R.color.text_muted)
                    }
                )
                ViewCompat.setBackgroundTintList(
                    binding.statusDot,
                    ColorStateList.valueOf(
                        when (status.mode) {
                            PrivilegeManager.Mode.ROOT -> ContextCompat.getColor(this@TerminalActivity, R.color.status_green)
                            PrivilegeManager.Mode.SHIZUKU -> ContextCompat.getColor(this@TerminalActivity, R.color.accent_sapphire)
                            PrivilegeManager.Mode.LOCAL -> ContextCompat.getColor(this@TerminalActivity, R.color.text_muted)
                        }
                    )
                )
            }
        }

        appendLine(headerText())

        val startupCommand = intent.getStringExtra(EXTRA_COMMAND)?.trim().orEmpty()
        if (startupCommand.isNotEmpty()) {
            binding.etCommand.setText(startupCommand)
            binding.etCommand.post { submitCurrentCommand() }
        }
    }

    private fun headerText(): String {
        val status = PrivilegeManager.status.value
        return buildString {
            append("JARVIS Terminal — real shell output\n")
            append("Identity: ").append(status.summary).append('\n')
            if (status.mode == PrivilegeManager.Mode.LOCAL) {
                append("Tip: grant root or start Shizuku for privileged commands.\n")
            }
            append('\n')
        }
    }

    private fun submitCurrentCommand() {
        val command = binding.etCommand.text?.toString()?.trim().orEmpty()
        if (command.isEmpty() || running) return
        binding.etCommand.setText("")
        execute(command)
    }

    private fun execute(command: String) {
        running = true
        binding.btnRun.isEnabled = false
        binding.btnRun.alpha = 0.5f
        appendLine("$ $command\n")

        lifecycleScope.launch {
            val result = try {
                PrivilegeManager.runShell(command, 20_000)
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
                if (result.output.isNotBlank()) {
                    appendLine(result.output)
                    if (!result.output.endsWith("\n")) appendLine("")
                }
                if (!result.error.isNullOrBlank() && result.error != result.output) {
                    appendLine("error: ${result.error}\n")
                }
                appendLine("[${result.mode} · exit ${result.exitCode}]\n")

                running = false
                binding.btnRun.isEnabled = true
                binding.btnRun.alpha = 1f
            }
        }
    }

    private fun appendLine(text: String) {
        transcript.append(text)
        if (transcript.length > MAX_TRANSCRIPT_CHARS) {
            transcript.delete(0, transcript.length - MAX_TRANSCRIPT_CHARS)
        }
        binding.tvOutput.text = transcript.toString()
        binding.scrollOutput.post {
            binding.scrollOutput.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    companion object {
        const val EXTRA_COMMAND = "extra_command"
        private const val MAX_TRANSCRIPT_CHARS = 120_000
    }
}
