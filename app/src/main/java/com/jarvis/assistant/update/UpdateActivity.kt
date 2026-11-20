package com.jarvis.assistant.update

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.ActivityUpdateBinding
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Full-screen update surface. When the update is mandatory the back button is swallowed and
 * there is no "Later" button, so the user cannot bypass it. When it is optional the user can
 * dismiss it with "Baad me" (which remembers the skipped version).
 *
 * The screen only ever reads the already-verified cached state; it never locks the app when the
 * server is unreachable (fail-open is handled upstream in [UpdateCoordinator]).
 */
class UpdateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUpdateBinding
    private var manifest: UpdateManifest? = null
    private var mandatory = false
    private var busy = false
    private var awaitingPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        manifest = UpdateManager.cachedManifest()
        mandatory = UpdateManager.isForceUpdateActive()

        val current = manifest
        if (current == null) {
            // Nothing valid cached — never trap the user.
            finish()
            return
        }

        binding.tvUpdateTitle.text = if (mandatory) "Update zaroori hai" else "Naya update available"
        binding.tvUpdateVersionInfo.text = "Jarvis v${current.latestVersionName} (build ${current.latestVersionCode})"
        binding.tvUpdateSize.text = if (current.apkSizeBytes > 0L) formatSize(current.apkSizeBytes) else ""
        binding.tvUpdateNotes.text = current.releaseNotes.ifBlank { "Is version me performance aur stability sudhaari gayi hai." }
        binding.btnUpdatePrimary.text = if (mandatory) "Abhi update karo" else "Update karo"
        binding.btnUpdateLater.visibility = if (mandatory) View.GONE else View.VISIBLE

        binding.btnUpdatePrimary.setOnClickListener { onPrimaryClicked() }
        binding.btnUpdateLater.setOnClickListener {
            manifest?.let { UpdateManager.skipVersion(it.latestVersionCode) }
            finish()
        }

        if (mandatory) {
            // Swallow back so the update cannot be bypassed.
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { /* block */ }
            })
        }

        // If a fully verified APK is already on disk, offer to install straight away.
        if (UpdateDownloader.hasVerifiedApk(this, current)) {
            setStatus("Downloaded APK taiyaar hai. Install karo.")
            binding.btnUpdatePrimary.text = "Install karo"
        }
    }

    override fun onResume() {
        super.onResume()
        if (awaitingPermission && UpdateInstaller.canInstall(this)) {
            awaitingPermission = false
            startInstallFlow()
        }
    }

    private fun onPrimaryClicked() {
        if (busy) return
        val current = manifest ?: return
        if (!UpdateInstaller.canInstall(this)) {
            awaitingPermission = true
            setStatus("Pehle 'Install unknown apps' allow karo, phir wapas aao.")
            runCatching { startActivity(UpdateInstaller.unknownSourcesSettingsIntent(this)) }
                .onFailure { setStatus("Settings page nahi khul paya.") }
            return
        }
        if (UpdateDownloader.hasVerifiedApk(this, current)) {
            installVerifiedApk(UpdateDownloader.apkFile(this, current))
        } else {
            lifecycleScope.launch { downloadVerifyInstall(current) }
        }
    }

    private suspend fun downloadVerifyInstall(current: UpdateManifest) {
        busy = true
        setBusyUi(true)
        try {
            setStatus("Download ho raha hai...")
            val apk = UpdateDownloader.download(this, current) { done, total ->
                runOnUiThread { updateProgress(done, total) }
            }
            if (apk == null) {
                fail("Download fail ho gaya. Internet check karo aur dobara try karo.")
                return
            }
            setStatus("Verify ho raha hai...")
            when (val result = withContext(Dispatchers.IO) { ApkVerifier.verify(this, apk, current) }) {
                is ApkVerifier.Result.Ok -> installVerifiedApk(apk)
                is ApkVerifier.Result.Failure -> {
                    apk.delete()
                    fail("APK verify nahi hua (${result.reason}). File delete kar di.")
                }
            }
        } finally {
            busy = false
            setBusyUi(false)
        }
    }

    private fun installVerifiedApk(apk: File) {
        lifecycleScope.launch {
            busy = true
            setBusyUi(true)
            setStatus("Install shuru ho raha hai...")
            // Optional silent fast path when root/Shizuku is active.
            if (UpdateInstaller.silentInstallIfPossible(apk)) {
                setStatus("Update install ho gaya. App dobara khul raha hai...")
                busy = false
                return@launch
            }
            when (val result = UpdateInstaller.installWithUserAction(this@UpdateActivity, apk)) {
                is UpdateInstaller.Result.Started ->
                    setStatus("Confirm karo. Install ke baad app khud restart ho jayega.")
                UpdateInstaller.Result.NeedsUnknownSourcesPermission -> {
                    awaitingPermission = true
                    setStatus("'Install unknown apps' allow karo, phir wapas aao.")
                    runCatching { startActivity(UpdateInstaller.unknownSourcesSettingsIntent(this@UpdateActivity)) }
                }
                is UpdateInstaller.Result.Failure -> fail("Install fail hua: ${result.reason}")
            }
            busy = false
            setBusyUi(false)
        }
    }

    private fun updateProgress(done: Long, total: Long) {
        binding.progressUpdate.visibility = View.VISIBLE
        binding.tvUpdateProgress.visibility = View.VISIBLE
        if (total > 0L) {
            val percent = ((done * 100) / total).toInt().coerceIn(0, 100)
            binding.progressUpdate.progress = percent
            binding.tvUpdateProgress.text = "$percent%  (${formatSize(done)} / ${formatSize(total)})"
        } else {
            binding.progressUpdate.isIndeterminate = true
            binding.tvUpdateProgress.text = formatSize(done)
        }
    }

    private fun setBusyUi(busy: Boolean) {
        binding.btnUpdatePrimary.isEnabled = !busy
        binding.btnUpdateLater.isEnabled = !busy
    }

    private fun setStatus(message: String) {
        binding.tvUpdateStatus.visibility = View.VISIBLE
        binding.tvUpdateStatus.text = message
    }

    private fun fail(message: String) {
        binding.progressUpdate.visibility = View.GONE
        binding.tvUpdateProgress.visibility = View.GONE
        setStatus(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) String.format(Locale.US, "%.1f MB", mb)
        else String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }
}
