package com.jarvis.assistant.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.launch
import com.jarvis.assistant.databinding.FragmentUpdatesBinding

class UpdatesFragment : Fragment() {

    companion object {
        private const val UPDATE_CARD_TAG = "update_card"
    }

    private var _binding: FragmentUpdatesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUpdatesBinding.inflate(inflater, container, false)
        renderFeatureUpdates()
        renderUpdateCard()
        return binding.root
    }

    private data class FeatureNotice(val feature: String, val title: String, val details: String, val kind: String = "FEATURE")

    private fun renderFeatureUpdates() {
        val features = listOf(
            FeatureNotice("Interface", "Frosted ice-blue reference design", "Naya floating top bar, side drawer, five-item dock, HUD orb and rounded glass cards are now the default look. Dark, red, sunset and custom themes still work."),
            FeatureNotice("Home", "Real weather, date and battery", "The three status cards show live weather (with location permission), today's date and your actual battery state — no fake energy or mood values."),
            FeatureNotice("Yaadein", "Saved memories with backup & restore", "Every row is a real saved memory. Backup writes memories and chat history to a JSON file you choose; restore replaces them on this phone after confirmation."),
            FeatureNotice("Scanner", "Opt-in screen vision", "Jarvis only sees your screen while Android screen sharing is active. SNAP sends the current real frame; the preview is the actual captured screen."),
            FeatureNotice("Voice", "Gemini Live conversation", "Tap the mic or orb to start. Hold it to power Jarvis off. Typed messages wait for the live session before sending, so nothing is silently dropped."),
            FeatureNotice("Agent tasks", "Verified actions and recovery", "JARVIS observes the current screen, checks action results, tracks progress, and stops safely when the UI is unclear."),
            FeatureNotice("Silent execution", "Quiet task automation", "When enabled, task narration is muted while ordinary conversation stays audible."),
            FeatureNotice("Applications", "WhatsApp, calls and YouTube", "Contact lookup, permission-aware calling and YouTube search/playback checks use the real Android apps on your device."),
            FeatureNotice("Privacy", "App identity and permissions", "Sensitive features stay blocked for builds without the configured release signature. Permissions are shown per feature on the Permissions page.")
        )
        val list = binding.updatesList
        list.removeAllViews()
        features.forEach { feature ->
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                background = androidx.core.content.ContextCompat.getDrawable(requireContext(), com.jarvis.assistant.R.drawable.bg_ref_card)
                setPadding(dp(18), dp(16), dp(18), dp(16))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                }
            }
            card.addView(TextView(requireContext()).apply {
                text = feature.feature.uppercase()
                textSize = 10f
                letterSpacing = 0.10f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColor(com.jarvis.assistant.R.attr.themeAccent))
            })
            card.addView(TextView(requireContext()).apply {
                text = feature.title
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColor(com.jarvis.assistant.R.attr.themeTextPrimary))
                setPadding(0, dp(7), 0, dp(5))
            })
            card.addView(TextView(requireContext()).apply {
                text = feature.details
                textSize = 13f
                setTextColor(themeColor(com.jarvis.assistant.R.attr.themeTextMuted))
                setLineSpacing(dp(3).toFloat(), 1f)
            })
            list.addView(card)
        }
    }

    private fun themeColor(attribute: Int): Int {
        val value = TypedValue()
        requireContext().theme.resolveAttribute(attribute, value, true)
        return value.data
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /**
     * Adds the live in-app update card at the very top of the list, above the existing
     * "What's new" cards (which are left untouched). Shows the installed version, when we
     * last checked, the current status, release notes and a "Check now" button.
     */
    private fun renderUpdateCard() {
        val context = requireContext()
        val list = binding.updatesList
        (list.getChildAt(0)?.takeIf { it.tag == UPDATE_CARD_TAG })?.let { list.removeViewAt(0) }

        val manifest = com.jarvis.assistant.update.UpdateManager.cachedManifest()
        val action = com.jarvis.assistant.update.UpdateManager.currentAction()
        val forced = com.jarvis.assistant.update.UpdateManager.isForceUpdateActive()
        val lastCheck = com.jarvis.assistant.update.UpdateManager.lastCheckAt()

        val card = LinearLayout(context).apply {
            tag = UPDATE_CARD_TAG
            orientation = LinearLayout.VERTICAL
            background = androidx.core.content.ContextCompat.getDrawable(context, com.jarvis.assistant.R.drawable.bg_ref_card)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        card.addView(TextView(context).apply {
            text = "APP UPDATE"
            textSize = 10f
            letterSpacing = 0.10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(themeColor(com.jarvis.assistant.R.attr.themeAccent))
        })
        card.addView(TextView(context).apply {
            text = "Jarvis v${com.jarvis.assistant.BuildConfig.VERSION_NAME} (${com.jarvis.assistant.BuildConfig.VERSION_CODE})"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(themeColor(com.jarvis.assistant.R.attr.themeTextPrimary))
            setPadding(0, dp(7), 0, dp(5))
        })
        card.addView(TextView(context).apply {
            text = buildString {
                append(if (lastCheck > 0L) "Last checked: ${formatTime(lastCheck)}" else "Abhi tak check nahi hua")
                append("\n")
                append(
                    when {
                        forced -> "Update zaroori hai — update kiye bina Jarvis start nahi hoga."
                        action == com.jarvis.assistant.update.UpdateAction.OPTIONAL ->
                            "Naya update available: v${manifest?.latestVersionName} (${manifest?.latestVersionCode})."
                        manifest != null -> "Aap latest version par ho."
                        else -> "Status abhi pata nahi. 'Check now' dabao."
                    }
                )
            }
            textSize = 13f
            setTextColor(themeColor(com.jarvis.assistant.R.attr.themeTextMuted))
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        if (manifest != null && manifest.releaseNotes.isNotBlank()) {
            card.addView(TextView(context).apply {
                text = manifest.releaseNotes
                textSize = 12f
                setTextColor(themeColor(com.jarvis.assistant.R.attr.themeTextSecondary))
                setPadding(0, dp(8), 0, 0)
            })
        }
        card.addView(com.google.android.material.button.MaterialButton(context).apply {
            text = "Check now"
            textSize = 13f
            setOnClickListener {
                isEnabled = false
                text = "Checking..."
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching { com.jarvis.assistant.update.UpdateCoordinator.checkNow(context) }
                    if (_binding != null) renderUpdateCard()
                }
            }
        })
        if (action != com.jarvis.assistant.update.UpdateAction.NONE && manifest != null) {
            card.addView(com.google.android.material.button.MaterialButton(context).apply {
                text = if (forced) "Update karo" else "Update dekho"
                textSize = 13f
                setOnClickListener {
                    startActivity(android.content.Intent(context, com.jarvis.assistant.update.UpdateActivity::class.java))
                }
            })
        }
        list.addView(card, 0)
    }

    private fun formatTime(millis: Long): String =
        java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            renderUpdateCard()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            binding.updatesScroll.scrollTo(0, 0)
        } else if (hidden && _binding != null) {
            binding.root.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
