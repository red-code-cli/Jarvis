package com.jarvis.harness.runtime

import java.io.File

/** Failure-safe activation of a rebuilt Linux root while retaining the user's Linux home. */
internal object RootfsRepair {
    /**
     * Directories under the Linux root that are reused verbatim when the runtime is
     * rebuilt during an app update or a repair, so their contents are never downloaded
     * again:
     *
     *  * `root` — the Linux home. npm (`~/.npm`) and pip (`~/.cache/pip`) package caches,
     *    the Gradle/Maven caches, the Android SDK and the local Maven repository all
     *    live here because PRoot is started with `HOME=/root`.
     *  * `opt` — the bundled Gradle distribution and JDK installed by the Android
     *    toolchain overlay. Its version marker lives under `root`, so without preserving
     *    `opt` a rebuild would re-download the ~570 MB Android bundle even though the
     *    toolchain was already installed and unchanged.
     *  * `var/cache/apt` — the apt package archive cache.
     *  * `var/lib/apt/lists` — the apt package index cache.
     *
     * These paths only ever hold machine-generated caches and pinned toolchains; the
     * rebuilt root still overwrites them when a bundled version changes, because the
     * installers re-extract that directory as soon as the version marker no longer
     * matches.
     */
    internal val REUSABLE_ROOTFS_PATHS = listOf(
        "root",
        "opt",
        "var/cache/apt",
        "var/lib/apt/lists",
    )

    fun reconcileInterruptedSwap(
        rootfs: File,
        staging: File,
        backup: File,
        validate: (File) -> Boolean,
    ) {
        if (!backup.exists()) return

        if (!rootfs.exists()) {
            check(backup.renameTo(rootfs)) { "Could not restore the previous Linux environment" }
            staging.deleteRecursively()
            return
        }

        if (validate(rootfs)) {
            restoreReusable(backup, rootfs)
            check(validate(rootfs)) { "Repaired Linux environment failed validation" }
            check(backup.deleteRecursively()) { "Could not remove the previous Linux environment" }
            staging.deleteRecursively()
            return
        }

        rollback(rootfs, staging, backup)
    }

    fun activate(
        rootfs: File,
        staging: File,
        backup: File,
        validate: (File) -> Boolean,
    ) {
        require(staging.isDirectory && validate(staging)) {
            "Rebuilt Linux environment failed validation"
        }
        check(!backup.exists()) { "A previous Linux environment repair is still pending" }

        val hadRootfs = rootfs.exists()
        if (hadRootfs) {
            check(rootfs.renameTo(backup)) { "Could not preserve the previous Linux environment" }
        }

        try {
            check(staging.renameTo(rootfs)) { "Could not activate the rebuilt Linux environment" }
            if (hadRootfs) restoreReusable(backup, rootfs)
            check(validate(rootfs)) { "Rebuilt Linux environment failed validation after activation" }
            if (hadRootfs) {
                check(backup.deleteRecursively()) { "Could not remove the previous Linux environment" }
            }
        } catch (error: Throwable) {
            if (hadRootfs && backup.exists()) {
                runCatching { rollback(rootfs, staging, backup) }
                    .onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    /**
     * Moves the reusable directories from the preserved root into the rebuilt one. The
     * rebuilt copy is discarded first so the preserved caches and toolchains survive the
     * update instead of being replaced by an empty bundle directory.
     */
    private fun restoreReusable(backup: File, rootfs: File) {
        REUSABLE_ROOTFS_PATHS.forEach { relative ->
            val preserved = File(backup, relative)
            if (!preserved.exists()) return@forEach
            val fresh = File(rootfs, relative)
            if (fresh.exists()) {
                check(fresh.deleteRecursively()) { "Could not replace the rebuilt Linux $relative" }
            }
            fresh.parentFile?.mkdirs()
            check(preserved.renameTo(fresh)) { "Could not restore the Linux $relative" }
        }
    }

    private fun rollback(rootfs: File, staging: File, backup: File) {
        REUSABLE_ROOTFS_PATHS.forEach { relative ->
            val active = File(rootfs, relative)
            val preserved = File(backup, relative)
            if (!preserved.exists() && active.exists()) {
                preserved.parentFile?.mkdirs()
                check(active.renameTo(preserved)) { "Could not preserve the Linux $relative during rollback" }
            }
        }
        if (rootfs.exists()) {
            check(rootfs.deleteRecursively()) { "Could not remove the failed Linux environment" }
        }
        check(backup.renameTo(rootfs)) { "Could not restore the previous Linux environment" }
        staging.deleteRecursively()
    }
}
