package com.jarvis.harness

/**
 * Build/configuration values for the harness engine.
 *
 * The module intentionally does not depend on the host application's generated
 * `BuildConfig`, so it can be dropped into any app without build-script plumbing.
 * Defaults point at the public Mobile Harness runtime release host; a host app can
 * point at its own mirror before first use, e.g.
 *
 * ```
 * HarnessBuildConfig.runtimeReleaseBaseUrl = "https://example.com/harness/runtime-2026.09.4"
 * ```
 */
object HarnessBuildConfig {
    /**
     * True when runtime bundles ship inside the APK. Jarvis keeps this false:
     * the Linux rootfs and toolchains are always downloaded on demand.
     */
    @Volatile
    var offlineRuntimeBundles: Boolean = false

    /** Base URL of the runtime bundle release; each bundle file is appended as `/<file>`. */
    @Volatile
    var runtimeReleaseBaseUrl: String =
        "https://github.com/techjarves/Mobile-Harness/releases/download/runtime-2026.09.4"

    /** Host application version name, stamped into workspace backup metadata. */
    @Volatile
    var versionName: String = "0"

    /** Host application version code, stamped into workspace backup metadata. */
    @Volatile
    var versionCode: Int = 0

    /**
     * Manifest used by the bundled [com.jarvis.harness.update.AppUpdater]. Blank by
     * default: the host application owns its own update flow and must opt in here.
     */
    @Volatile
    var appUpdateManifestUrl: String = ""

    /** Artifact key looked up inside the update manifest. */
    @Volatile
    var appVariant: String = "online"

    /** True in host debug builds; gates debug-only diagnostics. */
    @Volatile
    var debug: Boolean = false

    /** Optional provider key seeded on debug installs. Empty in production. */
    @Volatile
    var testOpenrouterApiKey: String = ""

    /**
     * When true (upstream behaviour) an empty install starts downloading the Linux
     * runtime as soon as the engine boots. Host apps that must only install on an
     * explicit user action set this to false before creating [HarnessEngine].
     */
    @Volatile
    var autoInstallOnFirstRun: Boolean = true
}
