package com.jarvis.assistant.ui.settings

import android.content.ClipData
import android.graphics.Color
import android.os.Bundle
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.R
import com.jarvis.assistant.harness.AgentGatewayController
import com.jarvis.assistant.harness.CustomProvider
import com.jarvis.assistant.harness.ProviderCatalog
import com.jarvis.assistant.harness.ProviderModelRefresh
import com.jarvis.assistant.harness.ProviderPriorityStore
import com.jarvis.assistant.util.ThemeManager
import com.jarvis.harness.data.ApiKeyVault
import com.jarvis.harness.gateway.AgentCapabilityTable
import com.jarvis.harness.gateway.GatewayPriorityOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "API Providers" and Settings → Coding for the coding engine.
 *
 * Shows the user-editable provider priority chain (long-press a row to drag it up or down),
 * multiple keys per provider (Keystore-backed), a custom provider, the daily free-model refresh,
 * and the per-agent capability table. Deliberately separate from Jarvis's own "AI Connection".
 */
class ProvidersActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var container: LinearLayout
    private lateinit var vault: ApiKeyVault
    private lateinit var store: ProviderPriorityStore
    private lateinit var gateway: AgentGatewayController

    /** Provider id currently being dragged in the priority list. */
    private var draggingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyTheme(this)
        setContentView(R.layout.activity_providers)
        container = findViewById(R.id.layoutProviders)
        vault = ApiKeyVault(applicationContext)
        store = ProviderPriorityStore(applicationContext)
        gateway = AgentGatewayController.get(applicationContext)

        findViewById<View>(R.id.btnProvidersBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnProvidersUpdate).setOnClickListener { refreshRemote() }
        findViewById<View>(R.id.btnProvidersCustom).setOnClickListener { promptCustomProvider() }
        render()
        scope.launch { ProviderModelRefresh.refreshIfDue(applicationContext) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private fun render() {
        container.removeAllViews()
        val catalog = ProviderCatalog.load(this)
        val customs = store.customProviders()
        val byId = (catalog + customs.map { it.asCatalogProvider() }).associateBy { it.id }
        val withKeys = byId.keys.filter { runCatching { vault.list(it).isNotEmpty() }.getOrDefault(false) }
        store.reconcile(withKeys)

        sectionHeader("PRIORITY & FALLBACK")
        hint("Long-press a row and drop it where you want it. The local gateway tries providers top-to-bottom and switches on 429 / quota / auth / 5xx / timeout.")
        val ordered = store.order.mapNotNull { byId[it] }
        if (ordered.isEmpty()) {
            hint("Add a key below to start the priority chain.")
        } else {
            ordered.forEachIndexed { index, provider -> container.addView(priorityRow(index + 1, provider)) }
        }

        sectionHeader("ADD A PROVIDER")
        hint("Tap to add a key (Keystore-encrypted). Multiple keys per provider are tried in order.")
        (catalog + customs.map { it.asCatalogProvider() }).forEach { provider ->
            container.addView(catalogRow(provider))
        }

        sectionHeader("CODING AGENTS")
        hint("Per-agent capability: who can use the local gateway and who must resume from a checkpoint.")
        AgentCapabilityTable.all().forEach { capability ->
            container.addView(capabilityRow(capability))
        }

        // Apply the new order/keys to the live gateway.
        runCatching { gateway.start() }
    }

    private fun priorityRow(position: Int, provider: ProviderCatalog.CatalogProvider): View {
        val row = baseRow()
        row.addView(TextView(this).apply {
            text = position.toString()
            gravity = Gravity.CENTER
            setTextColor(themeColor(R.attr.themeAccent, Color.parseColor("#2F6FED")))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        row.addView(avatar(provider))
        row.addView(texts(provider, showModel = true))
        row.addView(TextView(this).apply {
            text = "≡"
            setTextColor(themeColor(R.attr.themeTextMuted, Color.GRAY))
            textSize = 20f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
        })

        row.setOnClickListener { manageKeys(provider) }
        row.setOnLongClickListener {
            draggingId = provider.id
            row.startDragAndDrop(
                ClipData.newPlainText("provider", provider.id),
                View.DragShadowBuilder(row),
                null,
                0,
            )
            true
        }
        row.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DROP -> {
                    moveDraggedTo(provider.id)
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    draggingId = null
                    render()
                    true
                }
                else -> true
            }
        }
        return row
    }

    private fun moveDraggedTo(targetId: String) {
        val id = draggingId ?: return
        if (id == targetId) return
        val order = store.order
        if (id !in order || targetId !in order) return
        store.order = GatewayPriorityOrder.move(order, order.indexOf(id), order.indexOf(targetId))
    }

    private fun catalogRow(provider: ProviderCatalog.CatalogProvider): View {
        val row = baseRow()
        row.addView(avatar(provider))
        row.addView(texts(provider, showModel = false))
        val keyCount = runCatching { vault.list(provider.id).size }.getOrDefault(0)
        row.addView(TextView(this).apply {
            text = if (keyCount > 0) "✓ $keyCount" else "+"
            setTextColor(if (keyCount > 0) Color.parseColor("#39D98A") else themeColor(R.attr.themeAccent, Color.parseColor("#2F6FED")))
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(28))
        })
        row.setOnClickListener { promptForKey(provider) }
        return row
    }

    private fun capabilityRow(capability: com.jarvis.harness.gateway.AgentCapabilities): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_ref_row)
            setPadding(dp(13), dp(11), dp(13), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(9) }
        }
        box.addView(TextView(this).apply {
            text = capability.agent.title
            setTextColor(themeColor(R.attr.themeTextPrimary, Color.BLACK))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = buildString {
                append("gateway: ").append(if (capability.customBaseUrl) "yes" else "no")
                append(" · keys: ").append(if (capability.apiKeys) "yes" else "no (OAuth)")
                append(" · multi-key failover: ").append(if (capability.multiKeyFailover) "yes" else "no")
                append(" · resume from checkpoint: ").append(if (capability.resumableFromCheckpoint) "yes" else "no")
            }
            setTextColor(themeColor(R.attr.themeTextMuted, Color.GRAY))
            textSize = 11f
        })
        box.addView(TextView(this).apply {
            text = capability.note
            setTextColor(themeColor(R.attr.themeTextSecondary, Color.DKGRAY))
            textSize = 11f
            setPadding(0, dp(4), 0, 0)
        })
        return box
    }

    private fun baseRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_ref_row)
        setPadding(dp(13), dp(13), dp(13), dp(13))
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(9) }
    }

    private fun avatar(provider: ProviderCatalog.CatalogProvider): TextView = TextView(this).apply {
        text = provider.letterAvatar
        gravity = Gravity.CENTER
        setTextColor(themeColor(R.attr.themeAccent, Color.parseColor("#2F6FED")))
        textSize = 17f
        setBackgroundResource(R.drawable.bg_letter_avatar)
        layoutParams = LinearLayout.LayoutParams(dp(43), dp(43))
    }

    private fun texts(provider: ProviderCatalog.CatalogProvider, showModel: Boolean): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
            addView(TextView(this@ProvidersActivity).apply {
                text = provider.name
                setTextColor(themeColor(R.attr.themeTextPrimary, Color.BLACK))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            val keyCount = runCatching { vault.list(provider.id).size }.getOrDefault(0)
            addView(TextView(this@ProvidersActivity).apply {
                text = buildString {
                    append(provider.protocol.uppercase())
                    append(" · ")
                    append(provider.baseUrl)
                    if (keyCount > 0) append(" · ").append(keyCount).append(" key")
                    if (showModel) {
                        val model = store.selectedModel(provider.id)
                        if (model.isNotBlank()) append(" · ").append(model)
                    }
                }
                setTextColor(themeColor(R.attr.themeTextMuted, Color.GRAY))
                textSize = 11f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }

    private fun sectionHeader(text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            setTextColor(themeColor(R.attr.themeAccent, Color.parseColor("#2F6FED")))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(4))
        })
    }

    private fun hint(text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            setTextColor(themeColor(R.attr.themeTextMuted, Color.GRAY))
            textSize = 11f
            setPadding(0, 0, 0, dp(6))
        })
    }

    // ── Key management ───────────────────────────────────────────────────────

    private fun manageKeys(provider: ProviderCatalog.CatalogProvider) {
        val keys = runCatching { vault.list(provider.id) }.getOrDefault(emptyList())
        val labels = keys.map { "${if (it.isActive) "● " else "○ "}${it.name}" }.toMutableList()
        labels += "＋ Add a key"
        MaterialAlertDialogBuilder(this)
            .setTitle(provider.name)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == keys.size) { promptForKey(provider); return@setItems }
                val key = keys[which]
                MaterialAlertDialogBuilder(this)
                    .setTitle(key.name)
                    .setItems(arrayOf("Make active", "Remove")) { _, action ->
                        if (action == 0) {
                            runCatching { vault.activate(provider.id, key.id) }
                        } else {
                            runCatching { vault.remove(provider.id, key.id) }
                        }
                        render()
                    }
                    .show()
            }
            .show()
    }

    private fun promptForKey(provider: ProviderCatalog.CatalogProvider) {
        val input = EditText(this).apply {
            hint = "Paste ${provider.name} API key"
            setSingleLine(true)
        }
        val title = buildString {
            append(provider.name)
            if (provider.keyUrl.isNotBlank()) append("\n\n").append(provider.keyUrl)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("SAVE") { _, _ ->
                val key = input.text?.toString()?.trim().orEmpty()
                if (key.isBlank()) return@setPositiveButton
                runCatching { vault.add(provider.id, "Key ${vault.list(provider.id).size + 1}", key) }
                    .onSuccess {
                        Toast.makeText(this, "${provider.name} key saved (encrypted).", Toast.LENGTH_SHORT).show()
                        render()
                        scope.launch {
                            withContext(Dispatchers.IO) { ProviderModelRefresh.refreshIfDue(applicationContext, force = true) }
                            render()
                        }
                    }
                    .onFailure {
                        Toast.makeText(this, "Could not save key: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun promptCustomProvider() {
        val nameInput = EditText(this).apply { hint = "Name (e.g. My llama.cpp)"; setSingleLine(true) }
        val urlInput = EditText(this).apply { hint = "Base URL (e.g. http://192.168.1.5:8080/v1)"; setSingleLine(true) }
        val modelInput = EditText(this).apply { hint = "Model (optional, auto-fetched)"; setSingleLine(true) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(nameInput)
            addView(urlInput)
            addView(modelInput)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Add a custom provider")
            .setView(box)
            .setPositiveButton("SAVE") { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty()
                val url = urlInput.text?.toString()?.trim().orEmpty()
                if (name.isBlank() || url.isBlank()) return@setPositiveButton
                val protocol = if (url.contains("/anthropic") || url.contains("anthropic.com")) "anthropic" else "openai"
                val id = ProviderPriorityStore.CUSTOM_PREFIX + name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                val provider = CustomProvider(
                    id = id,
                    name = name,
                    baseUrl = url,
                    protocol = protocol,
                    model = modelInput.text?.toString()?.trim().orEmpty(),
                )
                store.addCustomProvider(provider)
                if (provider.model.isNotBlank()) store.setSelectedModel(id, provider.model)
                Toast.makeText(this, "Custom provider added. Add its key next.", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun refreshRemote() {
        Toast.makeText(this, "Checking for catalog update...", Toast.LENGTH_SHORT).show()
        scope.launch {
            val updated = withContext(Dispatchers.IO) { ProviderCatalog.refreshRemote(applicationContext) }
            if (updated) {
                Toast.makeText(this@ProvidersActivity, "Provider catalog updated.", Toast.LENGTH_SHORT).show()
                render()
            } else {
                Toast.makeText(this@ProvidersActivity, "No update available. Using bundled catalog.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val typed = theme.obtainStyledAttributes(intArrayOf(attr))
        val color = typed.getColor(0, fallback)
        typed.recycle()
        return color
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
