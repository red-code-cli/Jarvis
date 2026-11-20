package com.jarvis.harness.policy

import java.io.File

/** Whether headless browser ("visual") testing can run in this project + engine. */
data class VisualTestResult(val available: Boolean, val reason: String) {
    /** The exact word the task prompt tells the agent to use. */
    val label: String get() = if (available) "available" else "unavailable"

    companion object {
        val AVAILABLE = VisualTestResult(true, "headless browser (chrome + module) detected")

        fun unavailable(reason: String): VisualTestResult = VisualTestResult(false, reason)
    }
}

/**
 * Probes the runtime ONCE per project + engine version and caches the verdict (B3.1).
 *
 * Keying on the engine version means an app update that ships a new runtime re-probes,
 * while every step of an ordinary task reuses the cached answer instead of re-probing.
 */
class CapabilityProbeCache {
    private val cache = mutableMapOf<String, VisualTestResult>()

    fun key(projectId: String, engineVersion: String): String = "$projectId@$engineVersion"

    fun cached(projectId: String, engineVersion: String): VisualTestResult? =
        cache[key(projectId, engineVersion)]

    /** Runs [probe] at most once per key; later calls return the cached verdict. */
    fun probeOnce(projectId: String, engineVersion: String, probe: () -> VisualTestResult): VisualTestResult {
        val k = key(projectId, engineVersion)
        cache[k]?.let { return it }
        val result = runCatching(probe).getOrElse {
            VisualTestResult.unavailable("capability probe failed: ${it.message ?: "unknown error"}")
        }
        cache[k] = result
        return result
    }

    fun clear() = cache.clear()

    val size: Int get() = cache.size
}

/**
 * The actual check behind the cache: a chrome/chromium binary in the runtime rootfs AND a
 * usable browser module (puppeteer/playwright) in the project workspace.
 */
class HeadlessBrowserProbe(private val config: CapabilityProbeConfig = CapabilityProbeConfig()) {

    fun inspect(rootfsDir: File?, workspaceDir: File?): VisualTestResult {
        val chrome = config.chromeBinaryPaths
            .map { File(rootfsDir ?: File("/"), it) }
            .firstOrNull { it.isFile }
            ?: return VisualTestResult.unavailable("no headless browser binary found in the runtime")

        val module = config.browserModulePaths
            .map { File(workspaceDir ?: File("/"), it) }
            .firstOrNull { it.isDirectory }
            ?: return VisualTestResult.unavailable("no usable browser module (puppeteer/playwright) installed")

        return VisualTestResult(
            available = true,
            reason = "headless browser ready: ${chrome.name} + ${module.name}",
        )
    }
}
