package com.jarvis.assistant.ui.settings

import android.os.StatFs
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.databinding.FragmentSettingsBinding
import com.jarvis.assistant.harness.JarvisHarnessBridge
import com.jarvis.harness.HarnessAgentStatus
import com.jarvis.harness.HarnessInstallState
import com.jarvis.harness.HarnessProjectType
import com.jarvis.harness.network.ConnectionValidation
import com.jarvis.harness.runtime.RuntimeSetupStatus
import com.jarvis.harness.model.AgentKind
import com.jarvis.harness.model.ProviderKind
import com.jarvis.harness.model.providersForAgent
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Wires the "Coding Engine" card in Settings.
 *
 * The card is a thin view over [JarvisHarnessBridge]/`:harness-core`; all real work
 * (install, resume, retry, task state) lives in the module. Nothing here touches
 * Jarvis's existing voice, accessibility, overlay or API-connection features.
 */
class HarnessSettingsController(
    private val fragment: Fragment,
    private val binding: FragmentSettingsBinding,
) {

    private enum class ApiChoice(val label: String, val kind: ProviderKind, val dshApi: String?) {
        ANTHROPIC("Anthropic API", ProviderKind.ANTHROPIC, null),
        OPENROUTER("OpenRouter", ProviderKind.LLM_ROUTER, null),
        OPENAI("OpenAI-compatible", ProviderKind.CUSTOM, "openai-completions"),
        CUSTOM("Anthropic-compatible / Custom", ProviderKind.CUSTOM, "anthropic-messages"),
    }

    private var choice: ApiChoice = ApiChoice.ANTHROPIC

    fun bind() {
        val engine = JarvisHarnessBridge.engine(fragment.requireContext())

        binding.tvHarnessProviderValue.text = choice.label
        binding.etHarnessEndpoint.setText(choice.kind.defaultBaseUrl)

        binding.rowHarnessInstall.setOnClickListener { startInstall() }
        binding.rowHarnessUpdateAll.setOnClickListener {
            engine.updateAll()
            toast("Update started. Agents and packages refresh in the background.")
        }
        binding.rowHarnessProvider.setOnClickListener { chooseProvider() }
        renderRecentTasks()
        binding.rowHarnessSaveApi.setOnClickListener { saveApi() }
        binding.rowHarnessTestApi.setOnClickListener { testConnection() }
        // Discover per-agent updates for the catalog (no-op if a check is already running).
        engine.checkForUpdates()

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { engine.installState.collect { render(it) } }
                launch { engine.agentStatuses.collect { renderAgents(it) } }
            }
        }
    }

    /**
     * Builds one row per built-in agent: install / update / remove are always
     * separate, plus a Provider action that targets that agent. The catalog is
     * driven by [AgentKind] so a new agent needs no layout change.
     */
    private fun renderAgents(statuses: List<HarnessAgentStatus>) {
        val context = fragment.requireContext()
        val container = binding.layoutHarnessAgents
        container.removeAllViews()
        statuses.forEachIndexed { index, status ->
            container.addView(agentRow(context, status, if (index == 0) 0 else 8))
        }
    }

    private fun agentRow(context: android.content.Context, status: HarnessAgentStatus, topMarginDp: Int): View {
        val engine = JarvisHarnessBridge.engine(context)
        val row = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = context.getDrawable(com.jarvis.assistant.R.drawable.bg_ref_row)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(topMarginDp) }
        }

        val header = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        header.addView(
            android.widget.TextView(context).apply {
                text = status.kind.title
                setTextColor(themeColor(context, com.jarvis.assistant.R.attr.themeTextPrimary))
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        header.addView(
            android.widget.TextView(context).apply {
                val label = when {
                    status.busy -> "WORKING…"
                    status.latestVersion != null -> "v${status.version} → v${status.latestVersion}"
                    status.installed && status.version.isNotBlank() -> "v${status.version}"
                    status.installed -> "INSTALLED"
                    else -> "NOT INSTALLED"
                }
                text = label
                setTextColor(themeColor(context, com.jarvis.assistant.R.attr.themeAccent))
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
        )
        row.addView(header)

        val providers = providersForAgent(status.kind)
        row.addView(
            android.widget.TextView(context).apply {
                val providerText = if (providers.isEmpty()) "Google account sign-in" else providers.joinToString(", ") { it.title }
                text = "${status.kind.subtitle}\nProviders: $providerText"
                setTextColor(android.graphics.Color.parseColor("#8A8F98"))
                textSize = 11f
                setPadding(0, dp(4), 0, 0)
            },
        )

        val actions = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        if (!status.installed) {
            actions.addView(action(context, "INSTALL") {
                engine.installAgent(status.kind)
                toast("${status.kind.title} install started.")
            })
        } else {
            actions.addView(action(context, "REMOVE") { confirmRemove(status.kind) })
        }
        if (status.latestVersion != null) {
            actions.addView(action(context, "UPDATE") {
                engine.updateAgent(status.kind)
                toast("${status.kind.title} update started.")
            })
        }
        if (providers.isNotEmpty()) {
            actions.addView(action(context, "PROVIDER") {
                engine.selectAgent(status.kind)
                chooseProvider()
            })
        }
        row.addView(actions)
        return row
    }

    private fun action(context: android.content.Context, label: String, onClick: () -> Unit): View =
        android.widget.TextView(context).apply {
            text = label
            setTextColor(themeColor(context, com.jarvis.assistant.R.attr.themeAccent))
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isClickable = true
            isFocusable = true
            setPadding(0, dp(6), dp(18), dp(6))
            setOnClickListener { onClick() }
        }

    private fun confirmRemove(kind: AgentKind) {
        val context = fragment.requireContext()
        MaterialAlertDialogBuilder(context)
            .setTitle("Remove ${kind.title}")
            .setMessage("${kind.title} runtime files hat jayenge. Projects, chats aur sign-in safe rahenge.")
            .setPositiveButton("Remove") { _, _ ->
                JarvisHarnessBridge.engine(context).removeAgent(kind)
                toast("${kind.title} remove started.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * fragment.resources.displayMetrics.density).toInt()

    private fun themeColor(context: android.content.Context, attr: Int): Int {
        val typed = context.obtainStyledAttributes(intArrayOf(attr))
        val color = typed.getColor(0, android.graphics.Color.WHITE)
        typed.recycle()
        return color
    }

    private fun render(state: HarnessInstallState) {
        val engine = JarvisHarnessBridge.engine(fragment.requireContext())
        val installed = engine.isInstalled()
        val progress = (state.progress.coerceIn(0f, 1f) * 100).toInt()

        binding.tvHarnessBadge.text = when {
            !engine.isSupportedDevice() -> "UNSUPPORTED"
            installed -> "READY"
            state.status == RuntimeSetupStatus.RUNNING -> "INSTALLING ${progress}%"
            state.status == RuntimeSetupStatus.ERROR -> "FAILED"
            else -> "NOT INSTALLED"
        }

        binding.tvHarnessStatus.text = when {
            !engine.isSupportedDevice() ->
                "Ye phone 64-bit ARM nahi hai, isliye coding engine yahan nahi chal sakta."
            state.status == RuntimeSetupStatus.RUNNING ->
                state.message.ifBlank { "Installing…" }
            state.status == RuntimeSetupStatus.ERROR ->
                state.errorMessage ?: "Install failed. Tap Install to retry."
            installed && state.status == RuntimeSetupStatus.COMPLETE ->
                "Ready. Voice se bolo: \"Jarvis mere liye ek app banao\"."
            installed -> "Ready."
            else -> "DeepSeek Harness agent + Ubuntu runtime install karein."
        }

        val active = state.status == RuntimeSetupStatus.RUNNING
        binding.pbHarnessInstall.visibility = if (active || state.indeterminate) View.VISIBLE else View.GONE
        binding.pbHarnessInstall.isIndeterminate = state.indeterminate || state.progress <= 0f
        if (!binding.pbHarnessInstall.isIndeterminate) binding.pbHarnessInstall.progress = progress

        val bytes = state.downloadedBytes
        val total = state.totalBytes
        binding.tvHarnessBytes.visibility = if (bytes != null && total != null) View.VISIBLE else View.GONE
        if (bytes != null && total != null && total > 0) {
            binding.tvHarnessBytes.text = String.format(
                Locale.US,
                "%.1f / %.1f MB",
                bytes / 1_048_576.0,
                total / 1_048_576.0,
            )
        }
        binding.rowHarnessInstall.alpha = if (active) 0.6f else 1f
    }

    private fun startInstall() {
        val context = fragment.requireContext()
        val engine = JarvisHarnessBridge.engine(context)
        if (!engine.isSupportedDevice()) {
            toast("Ye phone coding engine support nahi karta (64-bit ARM chahiye).")
            return
        }
        val freeBytes = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
        if (freeBytes in 1 until MIN_FREE_BYTES) {
            toast(
                String.format(
                    Locale.US,
                    "Storage kam hai. Kam se kam %.1f GB free chahiye.",
                    MIN_FREE_BYTES / 1_073_741_824.0,
                ),
            )
            return
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("Install coding engine")
            .setMessage(
                "Ubuntu runtime, Node.js, npm, Git, saare toolchains (Web, Python, Android, C/C++, PHP) " +
                    "aur DeepSeek Harness agent download honge. Wi-Fi par karne ki salah hai. " +
                    "Download beech me ruk jaye to Install dobara tap karein — wahi se resume hoga.",
            )
            .setPositiveButton("Install") { _, _ ->
                engine.install(
                    setOf(
                        HarnessProjectType.WEB,
                        HarnessProjectType.PYTHON,
                        HarnessProjectType.ANDROID,
                        HarnessProjectType.CPP,
                        HarnessProjectType.PHP,
                    ),
                )
                toast("Install started. Aap background me voice use kar sakte hain.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseProvider() {
        val labels = ApiChoice.entries.map { it.label }.toTypedArray()
        val checked = ApiChoice.entries.indexOf(choice)
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle("Coding model provider")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                choice = ApiChoice.entries[which]
                binding.tvHarnessProviderValue.text = choice.label
                binding.etHarnessEndpoint.setText(choice.kind.defaultBaseUrl)
                binding.etHarnessModel.setText(choice.kind.defaultModel)
                dialog.dismiss()
            }
            .show()
    }

    private fun saveApi() {
        val context = fragment.requireContext()
        val engine = JarvisHarnessBridge.engine(context)
        val endpoint = binding.etHarnessEndpoint.text?.toString().orEmpty().trim()
        val model = binding.etHarnessModel.text?.toString().orEmpty().trim()
        val key = binding.etHarnessKey.text?.toString().orEmpty().trim()

        val effectiveKey = key.ifBlank { engine.storedApiKey(choice.kind) }
        if (effectiveKey.isBlank()) {
            apiStatus("API key daalein.")
            return
        }
        if (model.isBlank()) {
            apiStatus("Model name daalein.")
            return
        }
        engine.configureProvider(
            kind = choice.kind,
            baseUrl = endpoint,
            model = model,
            apiKey = effectiveKey,
            dshApi = choice.dshApi,
        )
        binding.etHarnessKey.setText("")
        apiStatus("Saved. Key encrypted storage me hai (Jarvis ki Gemini key se alag).")
    }

    private fun testConnection() {
        val context = fragment.requireContext()
        val engine = JarvisHarnessBridge.engine(context)
        val endpoint = binding.etHarnessEndpoint.text?.toString().orEmpty().trim()
        val model = binding.etHarnessModel.text?.toString().orEmpty().trim()
        val key = binding.etHarnessKey.text?.toString().orEmpty().trim()
            .ifBlank { engine.storedApiKey(choice.kind) }
        if (key.isBlank()) {
            apiStatus("Test se pehle API key daalein.")
            return
        }
        apiStatus("Testing…")
        binding.rowHarnessTestApi.isEnabled = false
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                engine.testConnection(choice.kind, endpoint, model, key)
            }
            binding.rowHarnessTestApi.isEnabled = true
            result.fold(
                onSuccess = { validation ->
                    when (validation) {
                        is ConnectionValidation.Success ->
                            apiStatus("✓ ${choice.kind.title} · ${validation.message}".trim())
                        is ConnectionValidation.Failure -> {
                            // Never a bare "Connection failed": provider, label, action and the
                            // provider's own words are all shown.
                            val providerNote = validation.providerMessage
                                ?.takeIf { it.isNotBlank() }
                                ?.let { " · provider said: ${it.take(140)}" }
                                .orEmpty()
                            apiStatus(
                                "✕ ${choice.kind.title} · ${validation.label}: ${validation.message.trim()}$providerNote",
                            )
                        }
                    }
                },
                onFailure = {
                    val error = com.jarvis.harness.HarnessError.classify(
                        it.message ?: it.toString(),
                        choice.kind.title,
                    )
                    apiStatus("✕ ${error.spoken}")
                },
            )
        }
    }

    private fun renderRecentTasks() {
        val context = fragment.requireContext()
        val container = binding.layoutHarnessRecent
        container.removeAllViews()
        val tasks = runCatching { JarvisHarnessBridge.recentTasks(8) }.getOrDefault(emptyList())
        binding.tvHarnessRecentEmpty.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
        tasks.forEach { task ->
            val row = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, 14, 0, 14)
                isClickable = true
                setOnClickListener { showTaskDetail(task) }
            }
            val title = android.widget.TextView(context).apply {
                text = "${task.projectName ?: "Task"} · ${task.state.name.lowercase()}${if (task.verified) " · verified" else ""}"
                setTextColor(context.obtainStyledAttributes(intArrayOf(com.jarvis.assistant.R.attr.themeTextPrimary)).let {
                    val color = it.getColor(0, android.graphics.Color.WHITE); it.recycle(); color
                })
                textSize = 13f
            }
            val subtitle = android.widget.TextView(context).apply {
                val whenText = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(task.updatedAtMillis))
                val stepText = if (task.steps > 0) " · ${task.steps} steps · ${task.files} files" else ""
                text = "$whenText$stepText${task.error?.let { " · " + it.headline }.orEmpty()}"
                setTextColor(android.graphics.Color.parseColor("#8A8F98"))
                textSize = 11f
            }
            row.addView(title)
            row.addView(subtitle)
            container.addView(row)
        }
    }

    private fun showTaskDetail(task: com.jarvis.harness.HarnessTaskStatus) {
        val context = fragment.requireContext()
        val proof = task.proof
        val body = buildString {
            append("State: ").append(task.state.name.lowercase()).append('\n')
            if (task.provider != null) append("Provider: ").append(task.provider).append('\n')
            append("Steps: ").append(task.steps).append(" · files: ").append(task.files).append('\n')
            task.error?.let { append("Error: ").append(it.headline).append('\n').append(it.hint).append('\n') }
            task.deliverableLocation?.let { append("Location: ").append(it).append('\n') }
            if (proof != null) {
                append('\n').append("Proof (").append(proof.files.size).append(" files):\n")
                proof.files.take(10).forEach { append("• ").append(it).append('\n') }
                proof.buildTail?.takeIf { it.isNotBlank() }?.let {
                    append('\n').append("Build output:\n").append(it.lines().takeLast(6).joinToString("\n"))
                }
            }
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(task.projectName ?: "Task")
            .setMessage(body)
            .setPositiveButton("Close", null)
        if (task.state == com.jarvis.harness.HarnessTaskState.WAITING_RETRY ||
            task.state == com.jarvis.harness.HarnessTaskState.FAILED ||
            task.state == com.jarvis.harness.HarnessTaskState.INTERRUPTED
        ) {
            dialog.setNeutralButton("Retry / resume") { _, _ ->
                JarvisHarnessBridge.retryTask(task.id)
                toast("Task retry queued.")
            }
        }
        dialog.show()
    }

    private fun apiStatus(text: String) {
        binding.tvHarnessApiStatus.visibility = View.VISIBLE
        binding.tvHarnessApiStatus.text = text
    }

    private fun toast(message: String) {
        Toast.makeText(fragment.requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val MIN_FREE_BYTES = 1_500_000_000L
    }
}