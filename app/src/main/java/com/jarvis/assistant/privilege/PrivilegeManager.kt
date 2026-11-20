package com.jarvis.assistant.privilege

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Single source of truth for root and Shizuku availability.
 *
 * Detection is passive (su paths, manager packages, build tags) plus a single
 * active `su -c id` probe. While the app is in the foreground the probe is
 * repeated at a low frequency, so enabling/disabling root access in Magisk,
 * KernelSU or APatch is reflected without the user refreshing anything.
 * Shizuku state is updated from binder/permission listeners and a light poll.
 */
object PrivilegeManager {

    private const val TAG = "PrivilegeManager"

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE_SHIZUKU = 4201

    private const val FOREGROUND_POLL_MS = 4_000L
    private const val ROOT_ACTIVE_PROBE_INTERVAL_MS = 15_000L
    private const val ROOT_PROBE_TIMEOUT_MS = 4_000

    enum class Mode { ROOT, SHIZUKU, LOCAL }

    data class Status(
        val rootInstalled: Boolean = false,
        val rootGranted: Boolean = false,
        val shizukuInstalled: Boolean = false,
        val shizukuRunning: Boolean = false,
        val shizukuPermissionGranted: Boolean = false,
        val shizukuServiceBound: Boolean = false,
        val mode: Mode = Mode.LOCAL,
        val lastCheckedAt: Long = 0L
    ) {
        val modeLabel: String
            get() = when (mode) {
                Mode.ROOT -> "ROOT"
                Mode.SHIZUKU -> "SHIZUKU"
                Mode.LOCAL -> "LOCAL"
            }

        val summary: String
            get() = when (mode) {
                Mode.ROOT -> "Root shell active (uid 0)"
                Mode.SHIZUKU -> "Shizuku shell active (ADB identity)"
                Mode.LOCAL -> "App sandbox only"
            }
    }

    data class ShellResult(
        val success: Boolean,
        val exitCode: Int,
        val output: String,
        val error: String?,
        val mode: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("success", success)
            put("exit_code", exitCode)
            put("output", output)
            put("mode", mode)
            if (!error.isNullOrBlank()) put("error", error)
        }
    }

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var initialized = false
    @Volatile private var userService: IPrivilegedService? = null
    @Volatile private var lastRootProbeAt = 0L
    @Volatile private var bindDeferred: CompletableDeferred<IPrivilegedService?>? = null
    private val bindLock = Any()

    private var pollJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                val remote = IPrivilegedService.Stub.asInterface(service)
                userService = remote
                updateStatus(shizukuServiceBound = remote != null)
                Log.i(TAG, "Shizuku user service connected")
                completeBind(remote)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to attach Shizuku user service", t)
                completeBind(null)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Shizuku user service disconnected")
            userService = null
            updateStatus(shizukuServiceBound = false)
            completeBind(null)
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        refreshShizukuState()
        scope.launch { maybeBindUserService() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died")
        userService = null
        updateStatus(
            shizukuRunning = false,
            shizukuPermissionGranted = false,
            shizukuServiceBound = false
        )
        completeBind(null)
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE_SHIZUKU) {
            Log.i(TAG, "Shizuku permission result: $grantResult")
            refreshShizukuState()
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                scope.launch { maybeBindUserService() }
            }
        }
    }

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            initialized = true
        }

        try {
            rikka.sui.Sui.init(appContext.packageName)
        } catch (t: Throwable) {
            Log.d(TAG, "Sui not available: ${t.message}")
        }

        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not register Shizuku listeners", t)
        }

        scope.launch {
            detectRoot(activeProbe = true)
            refreshShizukuState()
            maybeBindUserService()
        }

        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(FOREGROUND_POLL_MS)
                try {
                    val foreground = try {
                        JarvisApp.isAppForeground.value
                    } catch (_: Throwable) {
                        true
                    }
                    if (!foreground) continue

                    detectRoot(
                        activeProbe = !_status.value.rootGranted &&
                            System.currentTimeMillis() - lastRootProbeAt >= ROOT_ACTIVE_PROBE_INTERVAL_MS
                    )
                    refreshShizukuState()
                    if (_status.value.shizukuPermissionGranted && userService == null) {
                        maybeBindUserService()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Privilege poll failed: ${t.message}")
                }
            }
        }
    }

    /** Force a fresh root + Shizuku check. Safe to call from UI. */
    fun refresh() {
        if (!initialized) return
        scope.launch {
            detectRoot(activeProbe = true)
            refreshShizukuState()
            maybeBindUserService()
        }
    }

    fun requestShizukuPermission(requestCode: Int = REQUEST_CODE_SHIZUKU) {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refreshShizukuState()
                scope.launch { maybeBindUserService() }
                return
            }
            Shizuku.requestPermission(requestCode)
        } catch (t: Throwable) {
            Log.e(TAG, "Shizuku permission request failed", t)
        }
    }

    fun openShizukuManager() {
        try {
            val launch = appContext.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(launch)
            } else {
                appContext.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not open Shizuku manager", t)
        }
    }

    /**
     * Runs a command with the best available identity:
     * root shell -> Shizuku user service -> local app sandbox.
     */
    suspend fun runShell(command: String, timeoutMs: Int = 15_000): ShellResult {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return ShellResult(false, -1, "", "Command is empty", "none")
        }
        val safeTimeout = timeoutMs.coerceIn(1_000, 55_000)

        if (!initialized) {
            return ShellResult(false, -1, "", "PrivilegeManager is not initialized", "none")
        }

        if (_status.value.rootGranted) {
            try {
                val result = runProcess(arrayOf("su", "-c", trimmed), safeTimeout, "root")
                if (result.exitCode == 0 || !looksLikePermissionDenied(result.output)) {
                    return result
                }
                Log.w(TAG, "Root command denied; falling back. ${result.output.take(120)}")
                updateStatus(rootGranted = false)
            } catch (t: Throwable) {
                Log.w(TAG, "Root shell unavailable: ${t.message}")
                updateStatus(rootGranted = false)
            }
        }

        if (hasShizukuPermission()) {
            val remote = ensureUserService(4_000)
            if (remote != null) {
                try {
                    val raw = remote.runCommand(trimmed, safeTimeout)
                    val parsed = parseServiceResult(raw, "shizuku")
                    if (parsed != null) return parsed
                } catch (t: Throwable) {
                    Log.w(TAG, "Shizuku shell failed: ${t.message}")
                    userService = null
                    updateStatus(shizukuServiceBound = false)
                }
            }
        }

        return try {
            runProcess(arrayOf("/system/bin/sh", "-c", trimmed), safeTimeout, "local")
        } catch (t: Throwable) {
            ShellResult(false, -1, "", t.message ?: t.javaClass.simpleName, "local")
        }
    }

    /** Best identity currently available, without blocking on Shizuku binding. */
    fun currentMode(): Mode = _status.value.mode

    private suspend fun maybeBindUserService() {
        if (!hasShizukuPermission()) return
        val remote = ensureUserService(6_000)
        if (remote != null) {
            updateStatus(shizukuServiceBound = true)
        }
    }

    private suspend fun ensureUserService(timeoutMs: Long): IPrivilegedService? {
        userService?.let { existing ->
            return try {
                if (existing.asBinder().pingBinder()) existing else null
            } catch (_: Throwable) {
                null
            }
        }
        if (!hasShizukuPermission()) return null

        val (deferred, shouldBind) = synchronized(bindLock) {
            val existing = bindDeferred
            if (existing != null) {
                existing to false
            } else {
                CompletableDeferred<IPrivilegedService?>().also { bindDeferred = it } to true
            }
        }

        if (shouldBind) {
            withContext(Dispatchers.Main.immediate) {
                try {
                    Shizuku.bindUserService(userServiceArgs(), serviceConnection)
                } catch (t: Throwable) {
                    Log.e(TAG, "bindUserService failed", t)
                    completeBind(null)
                }
            }
        }

        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } catch (t: Throwable) {
            Log.w(TAG, "Waiting for Shizuku user service failed: ${t.message}")
            null
        }
    }

    private fun completeBind(service: IPrivilegedService?) {
        val pending = synchronized(bindLock) {
            val current = bindDeferred
            bindDeferred = null
            current
        }
        pending?.complete(service)
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(appContext, PrivilegedUserService::class.java))
            .daemon(false)
            .processNameSuffix("privilege")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    private fun hasShizukuPermission(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    private fun refreshShizukuState() {
        val installed = isPackageInstalled(SHIZUKU_PACKAGE)
        val running = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        val granted = if (running) {
            try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
        } else false

        val bound = userService?.let {
            try {
                it.asBinder().pingBinder()
            } catch (_: Throwable) {
                false
            }
        } ?: false

        updateStatus(
            shizukuInstalled = installed,
            shizukuRunning = running,
            shizukuPermissionGranted = granted,
            shizukuServiceBound = bound
        )
    }

    private suspend fun detectRoot(activeProbe: Boolean) {
        val hints = collectRootHints()
        var granted = _status.value.rootGranted

        if (activeProbe && hints.installed && System.currentTimeMillis() - lastRootProbeAt >= 2_000L) {
            lastRootProbeAt = System.currentTimeMillis()
            val probe = try {
                runProcess(arrayOf("su", "-c", "id"), ROOT_PROBE_TIMEOUT_MS, "root")
            } catch (_: Throwable) {
                null
            }
            granted = probe != null && probe.success && probe.output.contains("uid=0")
        } else if (!hints.installed) {
            granted = false
        }

        updateStatus(
            rootInstalled = hints.installed,
            rootGranted = granted,
            lastCheckedAt = System.currentTimeMillis()
        )
        Log.d(TAG, "Root check: installed=${hints.installed} granted=$granted hints=${hints.detail}")
    }

    private data class RootHints(val installed: Boolean, val detail: String)

    private fun collectRootHints(): RootHints {
        val details = mutableListOf<String>()

        val suPaths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/sbin/su", "/vendor/bin/su", "/debug_ramdisk/su",
            "/data/adb/magisk", "/data/adb/ksu", "/data/adb/ap"
        )
        suPaths.forEach { path ->
            try {
                if (File(path).exists()) details += path
            } catch (_: Throwable) {
            }
        }

        val managerPackages = listOf(
            "com.topjohnwu.magisk",
            "me.weishu.kernelsu",
            "com.rifsxd.ksunext",
            "me.bmax.apatch",
            "eu.chainfire.supersu",
            "com.kingroot.kinguser",
            "com.kingo.root"
        )
        managerPackages.forEach { pkg ->
            if (isPackageInstalled(pkg)) details += pkg
        }

        if (Build.TAGS?.contains("test-keys") == true) {
            details += "test-keys"
        }

        try {
            val which = ProcessBuilder("/system/bin/sh", "-c", "command -v su 2>/dev/null")
                .redirectErrorStream(true)
                .start()
            val path = which.inputStream.bufferedReader().use { it.readText() }.trim()
            if (which.waitFor(1, TimeUnit.SECONDS) && path.isNotEmpty()) {
                details += "path:$path"
            } else {
                which.destroyForcibly()
            }
        } catch (_: Throwable) {
        }

        val unique = details.distinct()
        return RootHints(unique.isNotEmpty(), unique.joinToString(","))
    }

    private fun parseServiceResult(raw: String?, mode: String): ShellResult? {
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
            ShellResult(
                success = json.optBoolean("success", false),
                exitCode = json.optInt("exit_code", -1),
                output = json.optString("output", ""),
                error = json.optString("error", "").ifBlank { null },
                mode = mode
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Could not parse user service result: ${t.message}")
            null
        }
    }

    private fun runProcess(cmd: Array<String>, timeoutMs: Int, mode: String): ShellResult {
        val process = ProcessBuilder(*cmd)
            .redirectErrorStream(true)
            .start()

        val output = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.append(buffer, 0, count)
                        if (output.length > MAX_OUTPUT_CHARS) break
                    }
                }
            } catch (_: Throwable) {
            }
        }
        reader.isDaemon = true
        reader.start()

        val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(500)
            return ShellResult(
                false,
                -1,
                output.toString().trim().take(MAX_OUTPUT_CHARS),
                "Command timed out after ${timeoutMs / 1000}s",
                mode
            )
        }
        reader.join(1_000)

        val exitCode = try {
            process.exitValue()
        } catch (_: Throwable) {
            -1
        }
        val text = output.toString().trim().take(MAX_OUTPUT_CHARS)
        return ShellResult(
            success = exitCode == 0,
            exitCode = exitCode,
            output = text,
            error = if (exitCode == 0) null else text.ifBlank { "Command failed" },
            mode = mode
        )
    }

    private fun looksLikePermissionDenied(output: String): Boolean {
        val lower = output.lowercase()
        return lower.contains("permission denied") ||
            lower.contains("not allowed") ||
            lower.contains("su: request rejected") ||
            lower.contains("cannot open") && lower.contains("su")
    }

    private fun isPackageInstalled(packageName: String): Boolean = try {
        appContext.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: Throwable) {
        false
    }

    private fun updateStatus(
        rootInstalled: Boolean = _status.value.rootInstalled,
        rootGranted: Boolean = _status.value.rootGranted,
        shizukuInstalled: Boolean = _status.value.shizukuInstalled,
        shizukuRunning: Boolean = _status.value.shizukuRunning,
        shizukuPermissionGranted: Boolean = _status.value.shizukuPermissionGranted,
        shizukuServiceBound: Boolean = _status.value.shizukuServiceBound,
        lastCheckedAt: Long = _status.value.lastCheckedAt
    ) {
        val mode = when {
            rootGranted -> Mode.ROOT
            shizukuRunning && shizukuPermissionGranted -> Mode.SHIZUKU
            else -> Mode.LOCAL
        }
        _status.value = Status(
            rootInstalled = rootInstalled,
            rootGranted = rootGranted,
            shizukuInstalled = shizukuInstalled,
            shizukuRunning = shizukuRunning,
            shizukuPermissionGranted = shizukuPermissionGranted,
            shizukuServiceBound = shizukuServiceBound,
            mode = mode,
            lastCheckedAt = lastCheckedAt
        )
    }

    private const val MAX_OUTPUT_CHARS = 200_000
}
