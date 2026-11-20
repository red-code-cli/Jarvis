package com.jarvis.assistant.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.BatteryManager
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.databinding.FragmentHomeBinding
import com.jarvis.assistant.util.LocationWeatherHelper
import com.jarvis.assistant.util.PermissionHelper
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel get() = (requireActivity() as MainActivity).viewModel
    private val preferences by lazy { JarvisApp.instance.preferences }

    private var isPageLoaded = false
    private var pendingOrbState: String? = null
    private var pendingThemeKey: String? = null
    private var pendingCustomHex: String? = null
    private var pendingPersonaKey: String? = null
    private var pendingOrbStyle: String? = null
    private var lastAudioPushTime = 0L
    private var taskFallbackPanel: android.widget.LinearLayout? = null
    private var taskFallbackText: android.widget.TextView? = null
    private var batteryRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateBatteryCard(intent)
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (_binding == null) return@registerForActivityResult
        if (fineGranted || coarseGranted) {
            fetchLiveWeather()
        } else {
            binding.tvWeatherCondition.text = "Location permission"
            binding.tvWeatherDetails.text = "Tap here to allow weather"
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        createTaskFallbackPanel()
        setupOrbWebView()
        setupActions()
        updateGreeting()
        updateDateCard()
        observeViewModel()
        checkLocationAndFetchWeather()
    }

    private fun setupActions() {
        binding.btnHomeAttach.setOnClickListener {
            (activity as? MainActivity)?.toggleScreenShare()
        }
        binding.btnHomeSend.setOnClickListener { sendHomeInput() }
        binding.etHomeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendHomeInput()
                true
            } else {
                false
            }
        }

        binding.btnDismissLiveError.setOnClickListener {
            hideLiveErrorOverlay {
                viewModel.clearLiveError()
            }
        }

        binding.cardWeather.setOnClickListener {
            if (!PermissionHelper.hasLocationPermission(requireContext())) {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            } else {
                fetchLiveWeather()
            }
        }

        binding.cardDate.setOnClickListener { openTodayInCalendar() }
        binding.cardBattery.setOnClickListener { openBatteryUsage() }

        setupAgentLiveCard()
    }

    // ── Agent Live card ──────────────────────────────────────────────────────

    private fun setupAgentLiveCard() {
        binding.cardAgentLive.setOnClickListener {
            val id = harnessLive?.taskId ?: return@setOnClickListener
            com.jarvis.assistant.harness.HarnessLogSheet.show(
                requireContext(),
                parentFragmentManager,
                id,
            )
        }
    }

    private fun sendHomeInput() {
        val text = binding.etHomeInput.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        binding.etHomeInput.text?.clear()
        (activity as? MainActivity)?.sendTextToJarvis(text)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupOrbWebView() {
        binding.orbWebView.apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isPageLoaded = true

                    val currentTheme = pendingThemeKey ?: preferences.appTheme
                    val customHex = pendingCustomHex ?: preferences.customThemeColor
                    setOrbTheme(currentTheme, customHex)
                    setOrbPersona(pendingPersonaKey ?: preferences.personality)
                    setOrbStyle(pendingOrbStyle ?: preferences.orbAnimation)

                    val state = pendingOrbState ?: when (viewModel.conversationState.value) {
                        ConversationState.LISTENING -> "listening"
                        ConversationState.SPEAKING -> "speaking"
                        ConversationState.THINKING -> "thinking"
                        ConversationState.IDLE -> "idle"
                    }
                    setOrbState(state)
                }
            }

            // Single tap triggers mic/conversation without interrupting 3D rotation drag
            val gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    (activity as? MainActivity)?.handleMicOrOrbClick()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    (activity as? MainActivity)?.handleHoldMicOrOrb()
                }
            })

            setOnTouchListener { v, event ->
                gestureDetector.onTouchEvent(event)
                v.onTouchEvent(event)
            }

            loadUrl("file:///android_asset/orb.html?embedded=1")
        }
    }

    fun setOrbState(state: String) {
        pendingOrbState = state
        if (!isPageLoaded) return
        activity?.runOnUiThread {
            _binding?.orbWebView?.evaluateJavascript(
                "window.JarvisOrb && window.JarvisOrb.setState('$state');",
                null
            )
        }
    }

    fun setOrbTheme(themeKey: String, customHex: String?) {
        pendingThemeKey = themeKey
        pendingCustomHex = customHex
        if (!isPageLoaded) return
        activity?.runOnUiThread {
            val webView = _binding?.orbWebView ?: return@runOnUiThread
            if (themeKey == com.jarvis.assistant.util.ThemeRegistry.CUSTOM_KEY && !customHex.isNullOrBlank()) {
                webView.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setBackground('$customHex');", null)
            } else {
                val mapped = com.jarvis.assistant.util.ThemeRegistry.byKey(themeKey)?.orbTheme ?: "light"
                webView.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setTheme('$mapped');", null)
            }
        }
    }

    fun setOrbPersona(personaKey: String) {
        pendingPersonaKey = personaKey
        val normalized = com.jarvis.assistant.data.model.GeminiConstants.normalizePersonality(personaKey)
        val displayName = when (normalized) {
            com.jarvis.assistant.data.model.GeminiConstants.PERSONALITY_VIRUS -> "V.I.R.U.S"
            com.jarvis.assistant.data.model.GeminiConstants.PERSONALITY_GIRLFRIEND -> "I.S.H.A"
            else -> "J.A.R.V.I.S"
        }
        val mapped = when (normalized) {
            com.jarvis.assistant.data.model.GeminiConstants.PERSONALITY_VIRUS -> "virus"
            com.jarvis.assistant.data.model.GeminiConstants.PERSONALITY_GIRLFRIEND -> "isha"
            else -> "jarvis"
        }
        activity?.runOnUiThread {
            _binding?.tvOrbName?.text = displayName
        }
        if (!isPageLoaded) return
        activity?.runOnUiThread {
            _binding?.orbWebView?.evaluateJavascript(
                "window.JarvisOrb && window.JarvisOrb.setPersona('$mapped');",
                null
            )
        }
    }

    fun setOrbStyle(styleKey: String?) {
        val key = com.jarvis.assistant.util.OrbStyleRegistry.byKey(styleKey)?.key ?: com.jarvis.assistant.util.OrbStyleRegistry.DEFAULT_STYLE
        pendingOrbStyle = key
        if (!isPageLoaded) return
        activity?.runOnUiThread {
            _binding?.orbWebView?.evaluateJavascript(
                "window.JarvisOrb && window.JarvisOrb.setStyle('$key');",
                null
            )
        }
    }

    private fun pushExternalAudio(amp: Float, bass: Float, high: Float) {
        if (!isPageLoaded) return
        val now = System.currentTimeMillis()
        if (now - lastAudioPushTime < 16L) return
        lastAudioPushTime = now
        activity?.runOnUiThread {
            _binding?.orbWebView?.evaluateJavascript(
                "window.JarvisOrb && window.JarvisOrb.setExternalAudio($amp, $bass, $high);",
                null
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (!batteryRegistered) {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ContextCompat.registerReceiver(
                requireContext(),
                batteryReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            batteryRegistered = true
            updateBatteryCard(requireContext().registerReceiver(null, filter))
        }
    }

    override fun onStop() {
        if (batteryRegistered) {
            try {
                requireContext().unregisterReceiver(batteryReceiver)
            } catch (_: Exception) {
            }
            batteryRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null) return
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        binding.orbWebView.onResume()
        binding.orbWebView.resumeTimers()
        renderTaskFallback(com.jarvis.assistant.automation.agent.AgentTaskEngine.taskState.value)
        setOrbTheme(preferences.appTheme, preferences.customThemeColor)
        setOrbPersona(preferences.personality)
        setOrbStyle(preferences.orbAnimation)
        updateGreeting()
        updateDateCard()
        updateBatteryCard(requireContext().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
    }

    override fun onPause() {
        super.onPause()
        if (_binding != null) {
            binding.orbWebView.onPause()
            binding.orbWebView.pauseTimers()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (_binding == null) return
        if (!hidden) {
            binding.orbWebView.onResume()
            binding.orbWebView.resumeTimers()
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            setOrbTheme(preferences.appTheme, preferences.customThemeColor)
            setOrbPersona(preferences.personality)
            setOrbStyle(preferences.orbAnimation)
            updateGreeting()
            updateDateCard()
        } else {
            binding.orbWebView.onPause()
            binding.orbWebView.pauseTimers()
        }
    }

    /** Freeze the orb while a full-screen overlay (drawer / app grid) covers it. */
    fun pauseOrbRendering() {
        if (_binding == null) return
        binding.orbWebView.onPause()
        binding.orbWebView.pauseTimers()
    }

    fun resumeOrbRendering() {
        if (_binding == null) return
        binding.orbWebView.onResume()
        binding.orbWebView.resumeTimers()
    }

    private fun updateGreeting() {
        if (_binding == null) return
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeGreeting = when (hour) {
            in 4..11 -> "Good morning,"
            in 12..16 -> "Good afternoon,"
            in 17..21 -> "Good evening,"
            else -> "Welcome back,"
        }
        val configured = preferences.userName.trim()
        val displayName = if (configured.isBlank() || configured == "TechGPT X") "dost" else configured
        binding.tvGreetingPrefix.text = timeGreeting
        binding.tvGreetingName.text = displayName
    }

    private fun updateDateCard() {
        if (_binding == null) return
        val now = Calendar.getInstance()
        binding.tvDateDay.text = now.get(Calendar.DAY_OF_MONTH).toString()
        binding.tvDateMeta.text = SimpleDateFormat("EEE, MMM", Locale.getDefault()).format(now.time)
        binding.tvDateMonth.text = "${now.get(Calendar.YEAR)} • Week ${now.get(Calendar.WEEK_OF_YEAR)}"
    }

    private fun updateBatteryCard(intent: Intent?) {
        if (_binding == null) return
        val context = context ?: return
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = when {
            level >= 0 && scale > 0 -> level * 100 / scale
            else -> batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        }
        binding.tvBatteryPercent.text = if (percent in 0..100) "$percent%" else "--%"

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        binding.tvBatteryStatus.text = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "On battery"
        }

        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> null
        }
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        val healthText = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Health good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
            BatteryManager.BATTERY_HEALTH_COLD -> "Too cold"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Check battery"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            else -> "Phone ready"
        }
        binding.tvBatteryHint.text = if (source != null) "$source • $healthText" else healthText
    }

    private fun checkLocationAndFetchWeather() {
        if (PermissionHelper.hasLocationPermission(requireContext())) {
            fetchLiveWeather()
        } else {
            binding.tvTemperature.text = "--°C"
            binding.tvWeatherCondition.text = "Location permission"
            binding.tvWeatherDetails.text = "Tap to allow weather"
        }
    }

    private fun fetchLiveWeather() {
        binding.tvWeatherCondition.text = "Locating • Fetching weather…"
        viewLifecycleOwner.lifecycleScope.launch {
            val weather = LocationWeatherHelper.fetchWeather(requireContext())
            if (_binding != null && weather != null) {
                binding.tvTemperature.text = weather.temperature
                binding.tvWeatherCondition.text = weather.condition
                binding.tvWeatherDetails.text = weather.details
                binding.ivWeatherIcon.setImageResource(weather.iconResId)
                preferences.userLocation = weather.cityName
            } else if (_binding != null) {
                binding.tvWeatherCondition.text = "Live weather • Tap to retry"
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    com.jarvis.assistant.automation.agent.AgentTaskEngine.taskState.collect { snapshot ->
                        renderTaskFallback(snapshot)
                    }
                }
                launch {
                    com.jarvis.assistant.harness.JarvisHarnessBridge.liveStatus.collect { live ->
                        harnessLive = live
                        renderAgentLiveCard()
                    }
                }
                launch {
                    val screenCapture = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance()
                    combine(
                        screenCapture.isSharing,
                        screenCapture.hasReceivedFrame
                    ) { sharing, hasFrame -> sharing to hasFrame }
                        .collect { (sharing, hasFrame) ->
                            val icon = if (sharing && hasFrame) R.drawable.ic_screen_share else R.drawable.ic_attach
                            binding.btnHomeAttach.setImageResource(icon)
                            binding.btnHomeAttach.imageTintList = ColorStateList.valueOf(
                                if (sharing && hasFrame) ContextCompat.getColor(requireContext(), R.color.status_green)
                                else themeColor(R.attr.themeTextPrimary, Color.parseColor("#0C1B3A"))
                            )
                            binding.btnHomeAttach.contentDescription =
                                if (sharing) "Stop sharing screen with Jarvis" else "Share screen with Jarvis"
                        }
                }

                launch {
                    combine(
                        viewModel.isSessionOn,
                        viewModel.isMicMuted,
                        viewModel.conversationState,
                        JarvisConversationService.isStandby,
                        viewModel.connectionStatus
                    ) { isOn, isMuted, state, isStandby, connection ->
                        HomeStatusState(isOn, isMuted, state, isStandby, connection)
                    }.collect { status ->
                        renderHomeStatus(status)
                    }
                }

                launch {
                    viewModel.audioFrequency.collect { freq ->
                        pushExternalAudio(freq.amplitude, freq.bass, freq.high)
                    }
                }

                launch {
                    viewModel.liveError.collect { error ->
                        if (error == null) {
                            hideLiveErrorOverlay()
                        } else {
                            showLiveErrorOverlay()
                            binding.tvLiveErrorTitle.text = error.title
                            binding.tvLiveErrorMessage.text = error.message
                            binding.btnFixLiveError.text = error.actionText

                            when (error.errorCode) {
                                "API_KEY_INVALID", "API_KEY_MISSING" -> {
                                    binding.ivLiveErrorIcon.setImageResource(R.drawable.ic_warning)
                                    binding.ivLiveErrorIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                                    binding.tvLiveErrorTitle.setTextColor(Color.parseColor("#EF4444"))
                                    binding.btnFixLiveError.setOnClickListener {
                                        startActivity(Intent(requireContext(), com.jarvis.assistant.ui.settings.ApiConnectionActivity::class.java))
                                    }
                                }
                                "ACCESSIBILITY_DISABLED" -> {
                                    binding.ivLiveErrorIcon.setImageResource(R.drawable.ic_settings_shield)
                                    binding.ivLiveErrorIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                                    binding.tvLiveErrorTitle.setTextColor(Color.parseColor("#F59E0B"))
                                    binding.btnFixLiveError.setOnClickListener {
                                        PermissionHelper.openAccessibilitySettings(requireContext())
                                    }
                                }
                                "PERMISSIONS_MISSING" -> {
                                    binding.ivLiveErrorIcon.setImageResource(R.drawable.ic_settings_shield)
                                    binding.ivLiveErrorIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                                    binding.tvLiveErrorTitle.setTextColor(Color.parseColor("#F59E0B"))
                                    binding.btnFixLiveError.setOnClickListener {
                                        startActivity(Intent(requireContext(), com.jarvis.assistant.ui.permission.PermissionActivity::class.java))
                                    }
                                }
                                else -> {
                                    binding.ivLiveErrorIcon.setImageResource(R.drawable.ic_warning)
                                    binding.ivLiveErrorIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                                    binding.tvLiveErrorTitle.setTextColor(Color.parseColor("#EF4444"))
                                    binding.btnFixLiveError.setOnClickListener {
                                        (activity as? MainActivity)?.handleMicOrOrbClick()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Volatile
    private var harnessLive: com.jarvis.harness.HarnessLiveStatus? = null

    private fun renderAgentLiveCard() {
        val b = _binding ?: return
        val live = harnessLive
        val active = live != null &&
            live.taskId != null &&
            live.phase != com.jarvis.harness.HarnessLiveStatus.Phase.IDLE

        if (!active) {
            b.tvAgentLiveProject.text = "Koi kaam nahi chal raha"
            b.tvAgentLiveMeta.text = "Bolo: Jarvis ek app banao"
            b.tvAgentLiveChip.visibility = View.GONE
            b.tvAgentLiveQueued.visibility = View.GONE
            b.pbAgentLive.visibility = View.GONE
            return
        }

        val phase = live!!.phase
        val (label, color) = when (phase) {
            com.jarvis.harness.HarnessLiveStatus.Phase.WORKING -> "RUNNING" to "#7FE7FF"
            com.jarvis.harness.HarnessLiveStatus.Phase.PAUSED -> "PAUSED" to "#FFB020"
            com.jarvis.harness.HarnessLiveStatus.Phase.WAITING_RETRY -> "WAITING_RETRY" to "#FFB020"
            com.jarvis.harness.HarnessLiveStatus.Phase.ERROR -> "FAILED" to "#FF6B6B"
            com.jarvis.harness.HarnessLiveStatus.Phase.SUCCESS -> "DONE" to "#39D98A"
            else -> "IDLE" to "#9AA1AC"
        }
        b.tvAgentLiveProject.text = live.projectName.ifBlank { "Project" }
        b.tvAgentLiveMeta.text = buildString {
            if (live.tier.isNotBlank()) append(live.tier).append(" · ")
            append("Step ").append(live.step)
            append(" · ").append(live.files).append(" files")
            if (live.budget.isNotBlank()) append(" · ").append(live.budget)
        }
        b.tvAgentLiveChip.text = label
        b.tvAgentLiveChip.setTextColor(Color.parseColor(color))
        b.tvAgentLiveChip.visibility = View.VISIBLE
        b.tvAgentLiveQueued.visibility = if (live.queued > 0) View.VISIBLE else View.GONE
        b.tvAgentLiveQueued.text = "+${live.queued} queued"

        val progress = (live.progress * 100).toInt().coerceIn(0, 100)
        // Indeterminate unless we truly know (100% on success). No fake percent.
        if (progress <= 0) {
            b.pbAgentLive.isIndeterminate = true
            b.pbAgentLive.visibility =
                if (phase == com.jarvis.harness.HarnessLiveStatus.Phase.ERROR) View.GONE else View.VISIBLE
        } else {
            b.pbAgentLive.isIndeterminate = false
            b.pbAgentLive.progress = progress
            b.pbAgentLive.visibility = View.VISIBLE
        }
    }

    private fun renderHomeStatus(status: HomeStatusState) {
        if (_binding == null) return
        when {
            status.isStandby -> {
                setOrbState("idle")
                binding.tvOrbStatus.text = "STANDBY"
            }
            !status.isOn -> {
                setOrbState("idle")
                binding.tvOrbStatus.text = "TAP TO START"
            }
            status.isMuted -> {
                setOrbState("idle")
                binding.tvOrbStatus.text = "MIC MUTED"
            }
            status.connection != "LIVE" -> {
                setOrbState("thinking")
                binding.tvOrbStatus.text = "CONNECTING"
            }
            else -> when (status.state) {
                ConversationState.LISTENING -> {
                    setOrbState("listening")
                    binding.tvOrbStatus.text = "LISTENING"
                }
                ConversationState.SPEAKING -> {
                    setOrbState("speaking")
                    binding.tvOrbStatus.text = "SPEAKING"
                }
                ConversationState.THINKING -> {
                    setOrbState("thinking")
                    binding.tvOrbStatus.text = "THINKING"
                }
                ConversationState.IDLE -> {
                    setOrbState("idle")
                    binding.tvOrbStatus.text = "READY"
                }
            }
        }
    }

    private fun openTodayInCalendar() {
        val intents = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR),
            Intent(Intent.ACTION_VIEW, android.provider.CalendarContract.CONTENT_URI)
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
                // Try the next calendar entry point.
            }
        }
        android.widget.Toast.makeText(
            requireContext(),
            "Koi calendar app nahi mila.",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun openBatteryUsage() {
        val intents = listOf(
            Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
            Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
                // Try the next battery settings screen.
            }
        }
        android.widget.Toast.makeText(
            requireContext(),
            "Battery settings nahi khul payi.",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun showLiveErrorOverlay() {
        if (_binding == null) return
        val card = binding.cardLiveError
        if (card.visibility == View.VISIBLE && card.alpha == 1f) return
        card.alpha = 0f
        card.scaleX = 0.85f
        card.scaleY = 0.85f
        card.visibility = View.VISIBLE
        card.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(240)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.15f))
            .start()
    }

    private fun hideLiveErrorOverlay(onEnd: (() -> Unit)? = null) {
        if (_binding == null) {
            onEnd?.invoke()
            return
        }
        val card = binding.cardLiveError
        if (card.visibility != View.VISIBLE) {
            onEnd?.invoke()
            return
        }
        card.animate()
            .alpha(0f)
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(180)
            .withEndAction {
                if (_binding != null) {
                    card.visibility = View.GONE
                }
                onEnd?.invoke()
            }
            .start()
    }

    private fun createTaskFallbackPanel() {
        val root = binding.root as? androidx.constraintlayout.widget.ConstraintLayout ?: return
        val primary = themeColor(R.attr.themeTextPrimary, Color.parseColor("#0C1B3A"))
        val accent = themeColor(R.attr.themeAccent, Color.parseColor("#2F8DF6"))
        val panel = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_ref_card)
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }
        panel.addView(android.widget.TextView(requireContext()).apply {
            text = "JARVIS AGENT PROGRESS"
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(accent)
        })
        val details = android.widget.TextView(requireContext()).apply {
            textSize = 12f
            setTextColor(primary)
            maxLines = 5
            setPadding(0, dp(5), 0, dp(4))
        }
        panel.addView(details)
        panel.addView(android.widget.Button(requireContext()).apply {
            text = "Enable floating overlay"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_ref_blue_button)
            setOnClickListener {
                try {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${requireContext().packageName}")
                        )
                    )
                } catch (_: Exception) {
                    android.widget.Toast.makeText(
                        requireContext(),
                        "Open Android Settings → Apps → JARVIS → Display over other apps.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }, android.widget.LinearLayout.LayoutParams(-2, dp(40)))
        val params = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(0, -2).apply {
            startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            // Below the greeting + orb block so it never covers the header.
            topMargin = dp(306)
            marginStart = dp(18)
            marginEnd = dp(18)
        }
        root.addView(panel, params)
        taskFallbackPanel = panel
        taskFallbackText = details
    }

    private fun renderTaskFallback(snapshot: com.jarvis.assistant.automation.agent.AgentTaskSnapshot?) {
        val panel = taskFallbackPanel ?: return
        if (snapshot == null || android.provider.Settings.canDrawOverlays(requireContext())) {
            panel.visibility = View.GONE
            return
        }
        panel.visibility = View.VISIBLE
        val activeStep = snapshot.steps.firstOrNull { it.stepId == snapshot.currentStepId }?.title ?: snapshot.currentStatus
        val checklist = snapshot.steps.take(4).joinToString("\n") { step ->
            val mark = if (step.status == com.jarvis.assistant.automation.agent.TaskStepStatus.COMPLETED) "✓" else "○"
            "$mark ${step.title}"
        }
        taskFallbackText?.text =
            "${snapshot.originalGoal}\n${snapshot.progressPercentage}% · ${snapshot.currentStatus.replace('_', ' ')}\nCurrent: $activeStep\n$checklist"
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val value = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, value, true)
        return if (value.data != 0) value.data else fallback
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private data class HomeStatusState(
    val isOn: Boolean,
    val isMuted: Boolean,
    val state: ConversationState,
    val isStandby: Boolean,
    val connection: String
)
