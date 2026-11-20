package com.jarvis.assistant.ui.settings

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.BottomSheetThemeSelectionBinding
import com.jarvis.assistant.ui.home.HomeFragment
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.util.OrbStyleRegistry
import com.jarvis.assistant.util.ThemeRegistry

class ThemeSelectionBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetThemeSelectionBinding? = null
    private val binding get() = _binding!!
    private val preferences by lazy { JarvisApp.instance.preferences }

    private val themeRows = mutableListOf<Pair<String, ImageView>>()
    private val orbRows = mutableListOf<Pair<String, ImageView>>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetThemeSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        buildThemeGrid()
        buildOrbStyles()
        updateCheckmarks(preferences.appTheme)
        updateOrbCheckmarks(preferences.orbAnimation)

        val customHex = preferences.customThemeColor
        binding.etCustomHex.setText(customHex)
        try {
            binding.viewCustomPreview.backgroundTintList = ColorStateList.valueOf(Color.parseColor(customHex))
        } catch (_: Exception) {}

        binding.btnApplyCustom.setOnClickListener {
            val hex = binding.etCustomHex.text.toString().trim()
            applyCustomColor(hex)
        }

        binding.swatch1.setOnClickListener { applyCustomColor("#000000") }
        binding.swatch2.setOnClickListener { applyCustomColor("#1E1B4B") }
        binding.swatch3.setOnClickListener { applyCustomColor("#042F2E") }
        binding.swatch4.setOnClickListener { applyCustomColor("#451A03") }
        binding.swatch5.setOnClickListener { applyCustomColor("#F0FDF4") }
        binding.swatch6.setOnClickListener { applyCustomColor("#FAF5FF") }
    }

    private fun buildThemeGrid() {
        val grid = binding.containerThemeGrid
        val accent = MaterialColors.getColor(grid, R.attr.themeAccent)
        val rowBg = ContextCompat.getDrawable(requireContext(), R.drawable.bg_jarvis_card)
        val chipBg = ContextCompat.getDrawable(requireContext(), R.drawable.bg_jarvis_chip)

        ThemeRegistry.ALL.forEach { theme ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = rowBg
                setPadding(dp(16), dp(16), dp(16), dp(16))
                setOnClickListener { selectTheme(theme.key, theme.name) }
            }

            val swatch = View(requireContext()).apply {
                background = chipBg
                backgroundTintList = try {
                    ColorStateList.valueOf(Color.parseColor(theme.bgColor))
                } catch (_: Exception) {
                    null
                }
            }
            row.addView(swatch, LinearLayout.LayoutParams(dp(28), dp(28)))

            val textCol = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
            }
            textCol.addView(TextView(requireContext()).apply {
                text = theme.name
                setTextColor(MaterialColors.getColor(grid, R.attr.themeTextPrimary))
                textSize = 15f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            })
            textCol.addView(TextView(requireContext()).apply {
                text = theme.subtitle
                setTextColor(MaterialColors.getColor(grid, R.attr.themeTextMuted))
                textSize = 12f
            })
            row.addView(
                textCol,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(14)
                }
            )

            val check = ImageView(requireContext()).apply {
                setImageResource(R.drawable.ic_checklist_tasks)
                imageTintList = ColorStateList.valueOf(accent)
            }
            row.addView(check, LinearLayout.LayoutParams(dp(20), dp(20)))
            themeRows.add(theme.key to check)
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }
    }

    private fun buildOrbStyles() {
        val container = binding.containerOrbStyles
        val accent = MaterialColors.getColor(container, R.attr.themeAccent)
        val rowBg = ContextCompat.getDrawable(requireContext(), R.drawable.bg_jarvis_card)

        OrbStyleRegistry.ALL.forEach { style ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = rowBg
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener { selectOrbStyle(style.key) }
            }

            val dot = View(requireContext()).apply {
                background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_jarvis_chip)
                backgroundTintList = ColorStateList.valueOf(accent)
            }
            row.addView(dot, LinearLayout.LayoutParams(dp(20), dp(20)))

            val textCol = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
            }
            textCol.addView(TextView(requireContext()).apply {
                text = style.name
                setTextColor(MaterialColors.getColor(container, R.attr.themeTextPrimary))
                textSize = 14f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            })
            textCol.addView(TextView(requireContext()).apply {
                text = style.desc
                setTextColor(MaterialColors.getColor(container, R.attr.themeTextMuted))
                textSize = 11f
            })
            row.addView(
                textCol,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12)
                }
            )

            val check = ImageView(requireContext()).apply {
                setImageResource(R.drawable.ic_checklist_tasks)
                imageTintList = ColorStateList.valueOf(accent)
            }
            row.addView(check, LinearLayout.LayoutParams(dp(18), dp(18)))
            orbRows.add(style.key to check)
            container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun applyCustomColor(hexInput: String) {
        val hex = if (hexInput.startsWith("#")) hexInput else "#$hexInput"
        try {
            Color.parseColor(hex)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Invalid HEX color format (e.g. #1E1B4B)", Toast.LENGTH_SHORT).show()
            return
        }

        preferences.customThemeColor = hex
        preferences.appTheme = "custom"
        updateCheckmarks("custom")

        Toast.makeText(requireContext(), "Custom theme set to $hex", Toast.LENGTH_SHORT).show()

        applyThemeImmediately()
        homeFragment()?.setOrbTheme("custom", hex)

        dismiss()
        activity?.recreate()
    }

    private fun selectTheme(themeKey: String, themeName: String) {
        preferences.appTheme = themeKey
        updateCheckmarks(themeKey)
        Toast.makeText(requireContext(), "Theme set to $themeName", Toast.LENGTH_SHORT).show()
        applyThemeImmediately()
        homeFragment()?.setOrbTheme(themeKey, null)
        dismiss()
        activity?.recreate()
    }

    private fun selectOrbStyle(styleKey: String) {
        preferences.orbAnimation = styleKey
        updateOrbCheckmarks(styleKey)
        val style = OrbStyleRegistry.byKey(styleKey)
        Toast.makeText(requireContext(), "Orb style set to ${style?.name ?: styleKey}", Toast.LENGTH_SHORT).show()
        homeFragment()?.setOrbStyle(styleKey)
        dismiss()
        activity?.recreate()
    }

    /**
     * Applies the newly selected theme immediately (before the activity recreate), so the
     * change is visible even if the recreate is delayed or interrupted.
     */
    private fun applyThemeImmediately() {
        val host = activity as? MainActivity ?: return
        runCatching {
            com.jarvis.assistant.util.ThemeManager.applyTheme(host)
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(host.findViewById(android.R.id.content))
        }
    }

    private fun homeFragment(): HomeFragment? {
        val mainActivity = activity as? MainActivity
        return mainActivity?.supportFragmentManager?.fragments
            ?.filterIsInstance<HomeFragment>()
            ?.firstOrNull()
    }

    private fun updateCheckmarks(activeTheme: String) {
        binding.ivCheckCustom.visibility = if (activeTheme == "custom") View.VISIBLE else View.GONE
        themeRows.forEach { (key, check) ->
            check.visibility = if (key == activeTheme) View.VISIBLE else View.GONE
        }
    }

    private fun updateOrbCheckmarks(activeStyle: String) {
        orbRows.forEach { (key, check) ->
            check.visibility = if (key == activeStyle) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        themeRows.clear()
        orbRows.clear()
        _binding = null
    }

    companion object {
        const val TAG = "ThemeSelectionBottomSheet"
        fun newInstance() = ThemeSelectionBottomSheet()
    }
}
