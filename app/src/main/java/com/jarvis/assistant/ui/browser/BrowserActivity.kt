package com.jarvis.assistant.ui.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.assistant.databinding.ActivityBrowserBinding
import com.jarvis.assistant.util.ThemeManager
import java.net.URLEncoder

class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var lastLoadFailed = false
    private var openedByJarvis = false

    private val closeHandler = Handler(Looper.getMainLooper())
    private val closeRunnable = Runnable { finishAndUnload() }

    /**
     * Jarvis opens this screen only when the user needs to see a page or rendered
     * HTML/result. When the task ends, the service broadcasts this action so the
     * WebView is unloaded instead of staying alive in the back stack.
     * A browser opened manually by the user ignores this signal.
     */
    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!openedByJarvis) return
            closeHandler.removeCallbacks(closeRunnable)
            closeHandler.postDelayed(closeRunnable, 1_200L)
        }
    }

    private data class PendingDownload(
        val url: String,
        val userAgent: String,
        val contentDisposition: String,
        val mimeType: String
    )

    private var pendingDownload: PendingDownload? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileChooserCallback
        fileChooserCallback = null
        if (callback == null) return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }
        val data = result.data
        val uris = when {
            data?.clipData != null -> {
                val clip = data.clipData!!
                Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
            }
            data?.data != null -> arrayOf(data.data!!)
            else -> emptyArray()
        }
        callback.onReceiveValue(uris)
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingDownload
        pendingDownload = null
        if (granted && pending != null) {
            enqueueDownload(pending)
        } else if (!granted) {
            Toast.makeText(this, "Storage permission is required to save the file.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        openedByJarvis = intent.getBooleanExtra(EXTRA_OPENED_BY_JARVIS, false)
        try {
            ContextCompat.registerReceiver(
                this,
                closeReceiver,
                IntentFilter(ACTION_CLOSE_JARVIS_BROWSER),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Throwable) {
        }

        configureWebView()

        binding.btnClose.setOnClickListener { finish() }
        binding.btnWebBack.setOnClickListener { if (binding.webView.canGoBack()) binding.webView.goBack() }
        binding.btnWebForward.setOnClickListener { if (binding.webView.canGoForward()) binding.webView.goForward() }
        binding.btnRefresh.setOnClickListener {
            lastLoadFailed = false
            binding.webView.reload()
        }

        binding.etAddress.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                loadFromAddressBar()
                true
            } else {
                false
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    binding.webView.canGoBack() -> binding.webView.goBack()
                    else -> finish()
                }
            }
        })

        val initialUrl = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        val target = if (initialUrl.isEmpty()) HOME_URL else normalizeUrl(initialUrl)
        binding.etAddress.setText(target)
        binding.webView.loadUrl(target)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(com.jarvis.assistant.BuildConfig.DEBUG)

        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString JarvisBrowser/1.0"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, true)
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url ?: return false
                return handleUri(uri)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                lastLoadFailed = false
                binding.progressBar.visibility = View.VISIBLE
                binding.etAddress.setText(url)
                updateNavigationButtons()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
                if (!lastLoadFailed) {
                    binding.etAddress.setText(url)
                }
                updateNavigationButtons()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (!view.hasFocus()) return
                binding.etAddress.setText(url)
                updateNavigationButtons()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (!request.isForMainFrame) return
                lastLoadFailed = true
                binding.progressBar.visibility = View.GONE
                Toast.makeText(
                    this@BrowserActivity,
                    "Page could not load: ${error.description}",
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: android.net.http.SslError
            ) {
                AlertDialog.Builder(this@BrowserActivity)
                    .setTitle("Security warning")
                    .setMessage("The certificate for this site is not trusted. Continue only if you understand the risk.")
                    .setPositiveButton("Continue") { _, _ -> handler.proceed() }
                    .setNegativeButton("Cancel") { _, _ -> handler.cancel() }
                    .show()
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                return try {
                    val intent = fileChooserParams.createIntent()
                    fileChooserLauncher.launch(intent)
                    true
                } catch (t: Throwable) {
                    fileChooserCallback = null
                    Toast.makeText(this@BrowserActivity, "File picker unavailable.", Toast.LENGTH_SHORT).show()
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val granted = request.resources.filter { permission ->
                    when (permission) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                            ContextCompat.checkSelfPermission(
                                this@BrowserActivity,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                            ContextCompat.checkSelfPermission(
                                this@BrowserActivity,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        else -> false
                    }
                }.toTypedArray()
                if (granted.isNotEmpty()) request.grant(granted) else request.deny()
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                binding.fullscreenContainer.addView(view)
                binding.fullscreenContainer.visibility = View.VISIBLE
                binding.webView.visibility = View.INVISIBLE
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }

        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("blob:")) {
                Toast.makeText(this, "This download is not supported.", Toast.LENGTH_SHORT).show()
                return@setDownloadListener
            }
            if (url.startsWith("blob:")) {
                Toast.makeText(this, "Blob downloads are not supported in this browser.", Toast.LENGTH_SHORT).show()
                return@setDownloadListener
            }
            val pending = PendingDownload(url, userAgent, contentDisposition, mimeType)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingDownload = pending
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                enqueueDownload(pending)
            }
        }
    }

    private fun enqueueDownload(download: PendingDownload) {
        try {
            val fileName = URLUtil.guessFileName(download.url, download.contentDisposition, download.mimeType)
            val request = DownloadManager.Request(Uri.parse(download.url)).apply {
                setMimeType(download.mimeType)
                addRequestHeader("User-Agent", download.userAgent)
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, "Download started: $fileName", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "Download failed: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideCustomView() {
        val view = customView ?: return
        binding.fullscreenContainer.removeView(view)
        binding.fullscreenContainer.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    private fun finishAndUnload() {
        if (isFinishing || isDestroyed) return
        try {
            binding.webView.stopLoading()
            binding.webView.loadUrl("about:blank")
            binding.webView.clearHistory()
        } catch (_: Throwable) {
        }
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        closeHandler.removeCallbacks(closeRunnable)
        openedByJarvis = intent.getBooleanExtra(EXTRA_OPENED_BY_JARVIS, openedByJarvis)
        val incoming = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        if (incoming.isEmpty()) return
        val target = normalizeUrl(incoming)
        binding.etAddress.setText(target)
        binding.webView.loadUrl(target)
    }

    private fun handleUri(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase().orEmpty()
        return when (scheme) {
            "http", "https" -> false
            "javascript" -> false
            else -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (t: Throwable) {
                    Toast.makeText(this, "No app can open this link.", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
    }

    private fun loadFromAddressBar() {
        val raw = binding.etAddress.text?.toString().orEmpty()
        val normalized = normalizeUrl(raw)
        binding.etAddress.setText(normalized)
        binding.webView.loadUrl(normalized)
        binding.etAddress.clearFocus()
    }

    private fun normalizeUrl(raw: String): String {
        val input = raw.trim()
        if (input.isEmpty()) return HOME_URL
        val lower = input.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("about:") || lower.startsWith("data:")
        ) {
            return input
        }
        if (input.contains("://")) return input
        if (!input.contains(" ") && input.contains(".") && !input.startsWith(".")) {
            return "https://$input"
        }
        val query = try {
            URLEncoder.encode(input, "UTF-8")
        } catch (_: Throwable) {
            input
        }
        return "$SEARCH_URL$query"
    }

    private fun updateNavigationButtons() {
        val web = binding.webView
        binding.btnWebBack.alpha = if (web.canGoBack()) 1f else 0.35f
        binding.btnWebForward.alpha = if (web.canGoForward()) 1f else 0.35f
        binding.btnWebBack.isEnabled = web.canGoBack()
        binding.btnWebForward.isEnabled = web.canGoForward()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        binding.webView.resumeTimers()
    }

    override fun onPause() {
        binding.webView.onPause()
        binding.webView.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        closeHandler.removeCallbacks(closeRunnable)
        try {
            unregisterReceiver(closeReceiver)
        } catch (_: Throwable) {
        }
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        try {
            binding.fullscreenContainer.removeAllViews()
            binding.webView.loadUrl("about:blank")
            binding.webView.stopLoading()
            binding.webView.removeAllViews()
            binding.webView.destroy()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_OPENED_BY_JARVIS = "extra_opened_by_jarvis"
        const val ACTION_CLOSE_JARVIS_BROWSER = "com.jarvis.assistant.action.CLOSE_JARVIS_BROWSER"
        private const val HOME_URL = "https://www.google.com"
        private const val SEARCH_URL = "https://www.google.com/search?q="
    }
}
