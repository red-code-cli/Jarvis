package com.jarvis.harness

import androidx.core.content.FileProvider

/**
 * Dedicated FileProvider so the module never collides with the host app's own
 * `androidx.core.content.FileProvider` declaration.
 *
 * The manifest merger keys providers by class name, so reusing
 * `androidx.core.content.FileProvider` here would clash with any host provider that
 * uses the same class. This subclass keeps the two declarations independent.
 *
 * Authority: `${applicationId}.harness.files` (declared in the module manifest).
 */
class HarnessFileProvider : FileProvider() {
    companion object {
        fun authority(context: android.content.Context): String =
            "${context.packageName}.harness.files"
    }
}