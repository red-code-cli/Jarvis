package com.jarvis.assistant.ui.home

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.databinding.ActivityMainBinding
import com.jarvis.assistant.ui.browser.BrowserActivity
import com.jarvis.assistant.ui.chat.ChatFragment
import com.jarvis.assistant.ui.memory.MemoryFragment
import com.jarvis.assistant.ui.permission.PermissionActivity
import com.jarvis.assistant.ui.power.PowerActivity
import com.jarvis.assistant.ui.scan.ScanFragment
import com.jarvis.assistant.ui.settings.ApiConnectionActivity
import com.jarvis.assistant.ui.settings.HandsFreeActivity
import com.jarvis.assistant.ui.settings.OverlayAvatarActivity
import com.jarvis.assistant.ui.settings.PrivacyShieldActivity
import com.jarvis.assistant.ui.settings.SettingsFragment
import com.jarvis.assistant.ui.terminal.TerminalActivity
import com.jarvis.assistant.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAB_HOME = 0
        const val TAB_SCAN = 1
        const val TAB_CHAT = 2
        const val TAB_MEMORY = 3
        const val TAB_SETTINGS = 4
        const val TAB_UPDATES = 5

        /** Optional Settings category to jump to when opened by Jarvis's open_settings tool. */
        const val EXTRA_SETTINGS_CATEGORY = "open_settings_category"
    }

    private lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()
    private var activeSnackbar: Snackbar? = null
    private var forceUpdateObserver: kotlinx.coroutines.Job? = null

    private var currentPage = TAB_HOME
    private var lastDockPage = TAB_HOME
    private var isDrawerOpen = false

    private data class InstalledApp(
        val label: String,
        val packageName: String,
        val icon: Drawable
    )

    private var installedApps: List<InstalledApp>? = null
    private var appsLoading = false

    private val homeFragment by lazy { HomeFragment() }
    private val scanFragment by lazy { ScanFragment() }
    private val chatFragment by lazy { ChatFragment() }
    private val memoryFragment by lazy { MemoryFragment() }
    private val settingsFragment by lazy { SettingsFragment() }
    private val updatesFragment by lazy { UpdatesFragment() }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            checkPermissionsAndStart()
        } else {
            Toast.makeText(
                this,
                "Microphone permission is required for voice conversation.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            checkPermissionsAndStart()
        } else {
            Toast.makeText(
                this,
                "Notification permission is required to run in the background.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val mediaProjectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
    }

    val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            try {
                if (!JarvisConversationService.isSessionOn.value || JarvisConversationService.connectionStatus.value != "LIVE") {
                    Toast.makeText(this, "Start Jarvis and wait for LIVE before sharing your screen.", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                if (!JarvisConversationService.elevateToMediaProjection()) {
                    Toast.makeText(this, "Could not start Android's screen-capture service.", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                val projection = mediaProjectionManager.getMediaProjection(result.resultCode, result.data!!)
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                val started = JarvisConversationService.startScreenCapture(projection, metrics)
                if (!started) {
                    JarvisConversationService.demoteFromMediaProjection()
                    Toast.makeText(this, "Android did not start screen capture. Please try again.", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                Toast.makeText(this, "Checking screen capture…", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    delay(2500L)
                    val captureManager = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance()
                    if (!captureManager.isSharing.value) return@launch
                    if (captureManager.hasReceivedFrame.value) {
                        Toast.makeText(this@MainActivity, "Screen sharing is live. Jarvis is receiving screen frames.", Toast.LENGTH_LONG).show()
                    } else {
                        JarvisConversationService.stopScreenCapture()
                        Toast.makeText(this@MainActivity, "No screen frames arrived, so sharing was stopped. Please try again.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error starting MediaProjection: ${e.message}", e)
                JarvisConversationService.demoteFromMediaProjection()
                Toast.makeText(this, "Screen capture failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    private var appliedThemeKey: String = ""
    private var appliedCustomHex: String = ""

    fun toggleScreenShare() {
        if (com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().isSharing.value) {
            JarvisConversationService.stopScreenCapture()
            Toast.makeText(this, "Screen sharing stopped.", Toast.LENGTH_SHORT).show()
        } else {
            if (!JarvisConversationService.isSessionOn.value || JarvisConversationService.connectionStatus.value != "LIVE") {
                Toast.makeText(this, "Start Jarvis and wait for LIVE before sharing your screen.", Toast.LENGTH_LONG).show()
                return
            }
            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        val prefs = JarvisApp.instance.preferences
        appliedThemeKey = prefs.appTheme
        appliedCustomHex = prefs.customThemeColor
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!com.jarvis.assistant.security.AppIdentityVerifier.isTrustedRelease(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("App integrity check failed")
                .setMessage("This JARVIS build is not signed with the configured official release certificate. Sensitive features have been blocked.")
                .setCancelable(false)
                .setPositiveButton("Close") { _, _ -> finish() }
                .show()
            return
        }

        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.rootMainLayout)

        currentPage = savedInstanceState?.getInt("CURRENT_PAGE", TAB_HOME) ?: TAB_HOME
        if (currentPage !in TAB_HOME..TAB_UPDATES) currentPage = TAB_HOME
        lastDockPage = savedInstanceState?.getInt("LAST_DOCK_PAGE", TAB_HOME) ?: TAB_HOME
        if (lastDockPage !in TAB_HOME..TAB_MEMORY) lastDockPage = TAB_HOME

        // Clear any old restored fragments to prevent ghost overlapping
        val existingFragments = supportFragmentManager.fragments
        if (existingFragments.isNotEmpty()) {
            val trans = supportFragmentManager.beginTransaction()
            for (fragment in existingFragments) {
                trans.remove(fragment)
            }
            trans.commitNowAllowingStateLoss()
        }

        val trans = supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, homeFragment, "TAB_$TAB_HOME")
            .add(R.id.fragmentContainer, scanFragment, "TAB_$TAB_SCAN")
            .add(R.id.fragmentContainer, chatFragment, "TAB_$TAB_CHAT")
            .add(R.id.fragmentContainer, memoryFragment, "TAB_$TAB_MEMORY")
            .add(R.id.fragmentContainer, settingsFragment, "TAB_$TAB_SETTINGS")
            .add(R.id.fragmentContainer, updatesFragment, "TAB_$TAB_UPDATES")

        fragments().forEach { (page, frag) ->
            if (page == currentPage) trans.show(frag) else trans.hide(frag)
        }
        trans.commitNow()

        setupTopBar()
        setupNavigationDock()
        selectPage(currentPage, animate = false)
        observeViewModel()
        startForceUpdateObserver()

        // Jarvis's open_settings tool can ask for a specific category.
        intent?.getStringExtra(EXTRA_SETTINGS_CATEGORY)?.let { category ->
            if (category.isNotBlank() && currentPage != TAB_SETTINGS) {
                selectPage(TAB_SETTINGS)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    isDrawerOpen -> closeDrawer()
                    currentPage == TAB_SETTINGS || currentPage == TAB_UPDATES -> selectPage(lastDockPage)
                    currentPage != TAB_HOME -> selectPage(TAB_HOME)
                    isDefaultLauncher() -> {
                        // Never close the home screen when JARVIS is the user's launcher.
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("CURRENT_PAGE", currentPage)
        outState.putInt("LAST_DOCK_PAGE", lastDockPage)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSettings()
        val prefs = JarvisApp.instance.preferences
        if (appliedThemeKey != prefs.appTheme || appliedCustomHex != prefs.customThemeColor) {
            appliedThemeKey = prefs.appTheme
            appliedCustomHex = prefs.customThemeColor
            recreate()
            return
        }
        if (enforceForceUpdateIfNeeded()) return
        maybeCheckForUpdates()
    }

    private fun fragments(): List<Pair<Int, androidx.fragment.app.Fragment>> = listOf(
        TAB_HOME to homeFragment,
        TAB_SCAN to scanFragment,
        TAB_CHAT to chatFragment,
        TAB_MEMORY to memoryFragment,
        TAB_SETTINGS to settingsFragment,
        TAB_UPDATES to updatesFragment
    )

    private fun setupTopBar() {
        binding.btnTopMenu.setOnClickListener {
            when (currentPage) {
                TAB_SETTINGS, TAB_UPDATES -> selectPage(lastDockPage)
                else -> openDrawer()
            }
        }
        binding.btnTopBell.setOnClickListener {
            selectPage(TAB_UPDATES)
        }
        binding.btnTopApps.setOnClickListener {
            openAppDrawer()
        }
        binding.drawerScrim.setOnClickListener { closeDrawer() }
        updateBellDot()
    }

    private fun openAppDrawer() {
        try {
            startActivity(Intent(this, com.jarvis.assistant.ui.apps.AppDrawerActivity::class.java))
        } catch (t: Throwable) {
            showMessage("App drawer open nahi hua: ${t.message}")
        }
    }

    private fun setupNavigationDock() {
        binding.tabHome.setOnClickListener { selectPage(TAB_HOME) }
        binding.tabScan.setOnClickListener { selectPage(TAB_SCAN) }
        binding.tabChat.setOnClickListener { selectPage(TAB_CHAT) }
        binding.tabMemory.setOnClickListener { selectPage(TAB_MEMORY) }

        binding.tabMic.setOnClickListener {
            handleMicOrOrbClick()
        }

        binding.tabMic.setOnLongClickListener {
            handleHoldMicOrOrb()
            true
        }
    }

    fun selectTab(tab: Int) = selectPage(tab)

    fun selectPage(tab: Int, animate: Boolean = true) {
        if (tab !in TAB_HOME..TAB_UPDATES) return
        val previousPage = currentPage
        currentPage = tab
        if (tab <= TAB_MEMORY) lastDockPage = tab

        val trans = supportFragmentManager.beginTransaction()
        fragments().forEach { (page, frag) ->
            if (frag.isAdded) {
                if (page == tab) trans.show(frag) else trans.hide(frag)
            }
        }
        trans.commitNowAllowingStateLoss()

        fragments().forEach { (page, frag) ->
            val v = frag.view ?: return@forEach
            if (page == tab) {
                v.animate().cancel()
                if (animate && previousPage != tab) {
                    v.alpha = 0f
                    v.translationX = if (tab > previousPage) 18f else -18f
                    v.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .setDuration(190L)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                } else {
                    v.alpha = 1f
                    v.translationX = 0f
                }
            } else {
                v.animate().cancel()
            }
        }

        updateTopBar(tab)
        updateDockUI(tab)
        buildDrawerMenu()
        if (isDrawerOpen) closeDrawer()
        if (tab == TAB_UPDATES) markUpdatesSeen()
        hideKeyboard()
    }

    private fun updateTopBar(tab: Int) {
        binding.tvTopTitle.text = when (tab) {
            TAB_HOME -> "Jarvis"
            TAB_SCAN -> "Scanner"
            TAB_CHAT -> "Chat"
            TAB_MEMORY -> "Yaadein"
            TAB_SETTINGS -> "Settings"
            TAB_UPDATES -> "Updates"
            else -> "Jarvis"
        }
        binding.btnTopMenu.setImageResource(
            if (tab == TAB_SETTINGS || tab == TAB_UPDATES) R.drawable.ic_back else R.drawable.ic_menu
        )
        binding.btnTopMenu.contentDescription =
            if (tab == TAB_SETTINGS || tab == TAB_UPDATES) "Back" else "Menu"
    }

    private fun updateDockUI(tab: Int) {
        val inactiveColor = themeColor(R.attr.themeTextMuted, ContextCompat.getColor(this, R.color.text_muted))
        val activeColor = Color.WHITE

        val navItems = listOf(
            Triple(TAB_HOME, binding.pillTabHome, Pair(binding.ivTabHome, binding.tvTabHome)),
            Triple(TAB_SCAN, binding.pillTabScan, Pair(binding.ivTabScan, binding.tvTabScan)),
            Triple(TAB_MEMORY, binding.pillTabMemory, Pair(binding.ivTabMemory, binding.tvTabMemory)),
            Triple(TAB_CHAT, binding.pillTabChat, Pair(binding.ivTabChat, binding.tvTabChat))
        )

        navItems.forEach { (page, pill, views) ->
            val isSelected = page == tab
            val (iv, tv) = views
            if (isSelected) {
                pill.setBackgroundResource(R.drawable.bg_ref_dock_active)
                pill.alpha = 0f
                pill.scaleX = 0.88f
                pill.scaleY = 0.88f
                pill.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(240)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                    .start()
                iv.imageTintList = ColorStateList.valueOf(activeColor)
                tv.setTextColor(activeColor)
                tv.alpha = 1f
            } else {
                pill.animate().cancel()
                pill.setBackgroundResource(0)
                pill.alpha = 1f
                pill.scaleX = 1f
                pill.scaleY = 1f
                iv.imageTintList = ColorStateList.valueOf(inactiveColor)
                tv.setTextColor(inactiveColor)
                tv.alpha = 0.85f
            }
        }
    }

    // ---------------- Drawer ----------------

    private data class DrawerEntry(
        val title: String,
        val iconRes: Int,
        val page: Int? = null,
        val action: (() -> Unit)? = null
    )

    private data class DrawerSection(val label: String, val entries: List<DrawerEntry>)

    // Sidebar is navigation only. Every configuration entry point lives once, in Settings.
    private fun drawerSections(): List<DrawerSection> = listOf(
        DrawerSection(
            "HOME",
            listOf(
                DrawerEntry("Home", R.drawable.ic_nav_home, page = TAB_HOME),
                DrawerEntry("Chat", R.drawable.ic_nav_chat, page = TAB_CHAT),
                DrawerEntry("Scanner", R.drawable.ic_scan_frame, page = TAB_SCAN),
                DrawerEntry("Yaadein", R.drawable.ic_memory_head, page = TAB_MEMORY)
            )
        ),
        DrawerSection(
            "TOOLS",
            listOf(
                DrawerEntry("Jarvis Browser", R.drawable.ic_nav_browser) {
                    startActivity(
                        Intent(this, BrowserActivity::class.java)
                            .putExtra(BrowserActivity.EXTRA_OPENED_BY_JARVIS, false)
                    )
                },
                DrawerEntry("Terminal", R.drawable.ic_nav_terminal) {
                    startActivity(Intent(this, TerminalActivity::class.java))
                }
            )
        ),
        DrawerSection(
            "SYSTEM",
            listOf(
                DrawerEntry("Settings", R.drawable.ic_nav_settings, page = TAB_SETTINGS)
            )
        ),
        DrawerSection(
            "ABOUT",
            listOf(
                DrawerEntry("Privacy Policy", R.drawable.ic_settings_lock) {
                    startActivity(Intent(this, PrivacyShieldActivity::class.java))
                },
                DrawerEntry("About", R.drawable.ic_user_profile) { showAboutDialog() }
            )
        )
    )

    private fun buildDrawerMenu() {
        if (!::binding.isInitialized) return
        val container = binding.drawerMenuContainer
        container.removeAllViews()

        val primary = themeColor(R.attr.themeTextPrimary, ContextCompat.getColor(this, R.color.text_primary))
        val muted = themeColor(R.attr.themeTextMuted, ContextCompat.getColor(this, R.color.text_muted))
        val accent = themeColor(R.attr.themeAccent, ContextCompat.getColor(this, R.color.accent_sapphire))

        drawerSections().forEach { section ->
            val label = TextView(this).apply {
                text = section.label
                setTextColor(muted)
                textSize = 11f
                letterSpacing = 0.12f
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                setPadding(dp(10), dp(16), 0, dp(8))
            }
            container.addView(label)

            section.entries.forEach { entry ->
                val isActive = entry.page != null && entry.page == currentPage
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    background = ContextCompat.getDrawable(
                        this@MainActivity,
                        if (isActive) R.drawable.bg_ref_drawer_item_active else R.drawable.bg_ref_drawer_item
                    )
                    isClickable = true
                    isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(56)
                    ).apply { bottomMargin = dp(9) }
                }

                row.addView(ImageView(this).apply {
                    setImageResource(entry.iconRes)
                    imageTintList = ColorStateList.valueOf(if (isActive) Color.WHITE else accent)
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                })

                row.addView(TextView(this).apply {
                    text = entry.title
                    setTextColor(if (isActive) Color.WHITE else primary)
                    textSize = 16f
                    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                    setPadding(dp(15), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })

                if (isActive) {
                    row.addView(View(this).apply {
                        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_ref_dot_green)
                        layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
                    })
                }

                row.setOnClickListener {
                    if (entry.page != null) {
                        selectPage(entry.page)
                    } else {
                        closeDrawer()
                        row.postDelayed({ entry.action?.invoke() }, 190L)
                    }
                }
                container.addView(row)
            }
        }
        appendInstalledApps(container, primary, muted, accent)
    }

    private fun appendInstalledApps(
        container: LinearLayout,
        primary: Int,
        muted: Int,
        accent: Int
    ) {
        val label = TextView(this).apply {
            text = "APPS"
            setTextColor(muted)
            textSize = 11f
            letterSpacing = 0.12f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setPadding(dp(10), dp(16), 0, dp(8))
        }
        container.addView(label)

        val apps = installedApps
        if (apps == null) {
            val loading = TextView(this).apply {
                text = "Loading installed apps…"
                setTextColor(muted)
                textSize = 13f
                setPadding(dp(12), dp(10), 0, dp(10))
            }
            container.addView(loading)
            loadInstalledApps()
            return
        }

        val count = TextView(this).apply {
            text = "${apps.size} apps • long-press for app info"
            setTextColor(muted)
            textSize = 11f
            setPadding(dp(12), 0, 0, dp(6))
        }
        container.addView(count)

        apps.forEach { app ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_ref_drawer_item)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(52)
                ).apply { bottomMargin = dp(6) }
            }
            row.addView(ImageView(this).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            })
            row.addView(TextView(this).apply {
                text = app.label
                setTextColor(primary)
                textSize = 15f
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                setPadding(dp(14), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.setOnClickListener {
                closeDrawer()
                row.postDelayed({
                    val launch = packageManager.getLaunchIntentForPackage(app.packageName)
                    if (launch == null) {
                        Toast.makeText(
                            this,
                            "${app.label} launch nahi ho paya.",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        try {
                            startActivity(launch)
                        } catch (t: Throwable) {
                            Toast.makeText(
                                this,
                                "${app.label} launch failed: ${t.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }, 190L)
            }
            row.setOnLongClickListener {
                try {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.parse("package:${app.packageName}")
                        }
                    )
                } catch (_: Throwable) {
                }
                closeDrawer()
                true
            }
            container.addView(row)
        }
    }

    private fun loadInstalledApps() {
        if (appsLoading || installedApps != null) return
        appsLoading = true
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                try {
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    val resolved = packageManager.queryIntentActivities(intent, 0)
                    val seen = mutableSetOf<String>()
                    resolved.mapNotNull { info ->
                        val pkg = info.activityInfo.packageName
                        if (pkg == packageName || !seen.add(pkg)) return@mapNotNull null
                        try {
                            InstalledApp(
                                label = info.loadLabel(packageManager).toString(),
                                packageName = pkg,
                                icon = info.loadIcon(packageManager)
                            )
                        } catch (_: Throwable) {
                            null
                        }
                    }.sortedBy { it.label.lowercase() }
                } catch (_: Throwable) {
                    emptyList()
                }
            }
            installedApps = apps
            appsLoading = false
            if (isDrawerOpen) buildDrawerMenu()
        }
    }

    fun requestDefaultLauncher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    if (roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                        showMessage("JARVIS already default launcher hai.")
                        return
                    }
                    startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                    return
                }
            } catch (_: Throwable) {
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Throwable) {
            try {
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
                showMessage("Default launcher settings open nahi hui.")
            }
        }
    }

    private fun isDefaultLauncher(): Boolean {
        return try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveActivity(
                    homeIntent,
                    PackageManager.ResolveInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.resolveActivity(homeIntent, 0)
            }
            resolved?.activityInfo?.packageName == packageName
        } catch (_: Throwable) {
            false
        }
    }

    private fun openDrawer() {
        if (isDrawerOpen) return
        isDrawerOpen = true
        homeFragment.pauseOrbRendering()
        buildDrawerMenu()
        binding.drawerScroll.scrollTo(0, 0)
        binding.drawerScroll.post { binding.drawerScroll.scrollTo(0, 0) }
        val widthPx = dp(332)
        binding.drawerOverlay.visibility = View.VISIBLE
        binding.drawerScrim.alpha = 0f
        binding.drawerPanel.translationX = -widthPx.toFloat()
        binding.drawerPanel.animate()
            .translationX(0f)
            .setDuration(260L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        binding.drawerScrim.animate().alpha(1f).setDuration(240L).start()
    }

    private fun closeDrawer() {
        if (!isDrawerOpen) return
        isDrawerOpen = false
        if (currentPage == TAB_HOME) homeFragment.resumeOrbRendering()
        val widthPx = dp(332).toFloat()
        binding.drawerPanel.animate()
            .translationX(-widthPx)
            .setDuration(210L)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                if (!isDrawerOpen) binding.drawerOverlay.visibility = View.GONE
            }
            .start()
        binding.drawerScrim.animate().alpha(0f).setDuration(190L).start()
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Jarvis")
            .setMessage(
                "Version ${BuildConfig.VERSION_NAME}\n" +
                    "by ${BuildConfig.DEVELOPER_NAME}\n\n" +
                    "Gemini Live voice assistant with Android automation, saved memories, " +
                    "agent tasks and opt-in screen vision."
            )
            .setPositiveButton("Close", null)
            .setNeutralButton("App identity") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("App identity")
                    .setMessage(com.jarvis.assistant.security.AppIdentityVerifier.report(this))
                    .setPositiveButton("Close", null)
                    .show()
            }
            .show()
    }

    private fun updateBellDot() {
        val seen = getSharedPreferences("jarvis_shell", MODE_PRIVATE)
            .getBoolean("updates_seen", false)
        binding.topBellDot.visibility = if (seen) View.GONE else View.VISIBLE
    }

    private fun markUpdatesSeen() {
        getSharedPreferences("jarvis_shell", MODE_PRIVATE)
            .edit().putBoolean("updates_seen", true).apply()
        binding.topBellDot.visibility = View.GONE
    }

    // ---------------- Voice / session ----------------

    fun handleMicOrOrbClick() {
        if (!JarvisConversationService.isSessionOn.value) {
            checkPermissionsAndStart()
        } else if (viewModel.isMicMuted.value) {
            viewModel.toggleMicMute()
        } else if (JarvisConversationService.conversationState.value == ConversationState.SPEAKING) {
            JarvisConversationService.interruptPlayback()
        } else {
            viewModel.toggleMicMute()
        }
    }

    fun handleHoldMicOrOrb() {
        binding.tabMic.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        if (JarvisConversationService.isSessionOn.value || JarvisConversationService.isStandby.value) {
            viewModel.turnOffSession()
        } else {
            checkPermissionsAndStart()
        }
    }

    fun handleTurnOff() {
        handleHoldMicOrOrb()
    }

    fun handlePowerClick() {
        handleHoldMicOrOrb()
    }

    /**
     * Sends a typed message only when the real Gemini Live session is actually
     * ready. If Jarvis is off, it starts the session first and waits for LIVE,
     * so a message is never silently dropped.
     */
    fun sendTextToJarvis(text: String) {
        if (blockIfUpdateRequired()) return
        val clean = text.trim()
        if (clean.isEmpty()) return

        val prefs = JarvisApp.instance.preferences
        if (prefs.apiKey.isBlank()) {
            showMessage("Pehle Settings me Gemini API key set karo.")
            return
        }
        if (!PermissionHelper.hasAllRequiredPermissions(this)) {
            showMessage("Mic, notification aur battery permissions complete karo.")
            return
        }

        if (JarvisConversationService.isSessionOn.value &&
            JarvisConversationService.connectionStatus.value == "LIVE"
        ) {
            JarvisConversationService.sendTextMessage(clean)
            return
        }

        if (!JarvisConversationService.isSessionOn.value) {
            viewModel.toggleSession()
        }
        showMessage("Jarvis se connect kar raha hoon…")
        lifecycleScope.launch {
            val deadline = System.currentTimeMillis() + 20_000L
            while (System.currentTimeMillis() < deadline) {
                if (JarvisConversationService.isSessionOn.value &&
                    JarvisConversationService.connectionStatus.value == "LIVE"
                ) {
                    JarvisConversationService.sendTextMessage(clean)
                    return@launch
                }
                delay(400L)
            }
            showMessage("Jarvis LIVE nahi hua. API key aur network check karo.")
        }
    }

    private fun showMessage(message: String) {
        activeSnackbar?.dismiss()
        activeSnackbar = Snackbar.make(binding.rootMainLayout, message, Snackbar.LENGTH_SHORT).apply {
            setAnchorView(binding.bottomDock)
            show()
        }
    }

    /**
     * Mandatory-update gate laid on top of the existing start path. Returns true when the user
     * must update first, in which case the blocking update screen is shown and Jarvis must not
     * start. Fail-open: only true when a valid mandatory server response set the force flag.
     */
    private fun blockIfUpdateRequired(): Boolean {
        if (!com.jarvis.assistant.update.UpdateManager.isForceUpdateActive()) return false
        runCatching {
            startActivity(Intent(this, com.jarvis.assistant.update.UpdateActivity::class.java))
        }
        return true
    }

    private fun enforceForceUpdateIfNeeded(): Boolean = blockIfUpdateRequired()

    private fun startForceUpdateObserver() {
        if (forceUpdateObserver?.isActive == true) return
        forceUpdateObserver = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                com.jarvis.assistant.update.UpdateManager.forceActiveFlow.collect { active ->
                    if (active) blockIfUpdateRequired()
                }
            }
        }
    }

    private fun maybeCheckForUpdates() {
        if (!com.jarvis.assistant.update.UpdateCoordinator.shouldCheckOnResume()) return
        lifecycleScope.launch {
            runCatching { com.jarvis.assistant.update.UpdateCoordinator.checkNow(this@MainActivity) }
        }
    }

    private fun checkPermissionsAndStart() {
        if (blockIfUpdateRequired()) return
        if (!PermissionHelper.hasRecordAudioPermission(this)) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (!PermissionHelper.hasNotificationPermission(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        if (!PermissionHelper.hasBatteryOptimizationPermission(this)) {
            showBatteryPermissionDialog()
            return
        }

        viewModel.toggleSession()
    }

    private fun showBatteryPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Unrestricted Battery Required")
            .setMessage("Jarvis requires Unrestricted Battery access so it can stay active in the background and respond to wake words.\n\nPlease allow it or open Settings.")
            .setPositiveButton("ALLOW") { _, _ ->
                PermissionHelper.requestBatteryOptimization(this)
            }
            .setNeutralButton("OPEN SETTINGS") { _, _ ->
                selectPage(TAB_SETTINGS)
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.isMicMuted, viewModel.conversationState, viewModel.isSessionOn) { isMuted, state, isOn ->
                        Triple(isMuted, state, isOn)
                    }.collect { (isMuted, state, isOn) ->
                        val pill = binding.pillTabMic
                        when {
                            !isOn -> {
                                binding.ivTabMic.setImageResource(R.drawable.ic_mic_jarvis)
                                binding.ivTabMic.imageTintList = ColorStateList.valueOf(Color.parseColor("#DCE9F9"))
                                pill.setBackgroundResource(R.drawable.bg_ref_mic)
                                pill.animate().cancel()
                                pill.alpha = 0.72f
                                pill.scaleX = 1f
                                pill.scaleY = 1f
                            }
                            isMuted -> {
                                binding.ivTabMic.setImageResource(R.drawable.ic_mic_mute_jarvis)
                                binding.ivTabMic.imageTintList = ColorStateList.valueOf(Color.WHITE)
                                pill.setBackgroundResource(R.drawable.bg_ref_mic)
                                pill.animate().cancel()
                                pill.alpha = 1f
                                pill.scaleX = 1f
                                pill.scaleY = 1f
                            }
                            else -> {
                                binding.ivTabMic.setImageResource(R.drawable.ic_mic_jarvis)
                                binding.ivTabMic.imageTintList = ColorStateList.valueOf(Color.WHITE)
                                pill.setBackgroundResource(R.drawable.bg_ref_mic)
                                pill.alpha = 1f
                                if (state == ConversationState.LISTENING || state == ConversationState.SPEAKING) {
                                    pill.animate().cancel()
                                    pill.animate()
                                        .scaleX(1.08f)
                                        .scaleY(1.08f)
                                        .setDuration(300)
                                        .start()
                                } else {
                                    pill.animate().cancel()
                                    pill.scaleX = 1f
                                    pill.scaleY = 1f
                                }
                            }
                        }
                    }
                }

                launch {
                    viewModel.eventFlow.collect { message ->
                        showMessage(message)
                    }
                }
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(binding.rootMainLayout.windowToken, 0)
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return if (value.data != 0) value.data else fallback
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
