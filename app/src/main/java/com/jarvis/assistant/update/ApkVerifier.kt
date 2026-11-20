package com.jarvis.assistant.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.jarvis.assistant.security.AppIdentityVerifier
import java.io.File
import java.security.MessageDigest

/**
 * Verifies a downloaded APK before it is ever handed to the installer. All three checks must
 * pass, otherwise the file is rejected (and the caller deletes it):
 *  1. SHA-256 matches the manifest;
 *  2. declared package name equals the installed app's package;
 *  3. signing certificate matches this app's signing certificate (reuses [AppIdentityVerifier]).
 */
object ApkVerifier {

    sealed class Result {
        object Ok : Result()
        data class Failure(val reason: String) : Result()
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } > 0) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verify(context: Context, apk: File, manifest: UpdateManifest): Result {
        if (!apk.isFile || apk.length() <= 0L) return Result.Failure("APK file missing")
        if (manifest.apkSizeBytes > 0L && apk.length() != manifest.apkSizeBytes) {
            return Result.Failure("Size mismatch (${apk.length()} != ${manifest.apkSizeBytes})")
        }
        val actualHash = sha256(apk)
        if (!actualHash.equals(manifest.apkSha256, ignoreCase = true)) {
            return Result.Failure("SHA-256 mismatch")
        }
        val archive = packageArchiveInfo(context, apk) ?: return Result.Failure("APK not readable")
        val packageName = archive.packageName
        if (packageName != context.packageName) {
            return Result.Failure("Package mismatch ($packageName)")
        }
        val apkSigners = archiveSignerSha256(archive)
        val installedSigners = AppIdentityVerifier.currentSignerSha256(context)
            .map { it.uppercase() }
            .toSet()
        if (apkSigners.isEmpty() || installedSigners.isEmpty()) {
            return Result.Failure("Signing certificate unavailable")
        }
        val matches = apkSigners.any { it.uppercase() in installedSigners }
        if (!matches) return Result.Failure("Signing certificate mismatch")
        return Result.Ok
    }

    private fun packageArchiveInfo(context: Context, apk: File): PackageInfo? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
    }.getOrNull()

    private fun archiveSignerSha256(info: PackageInfo): List<String> = runCatching {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty()
        }
        signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { byte -> "%02X".format(byte) }
        }.distinct().sorted()
    }.getOrDefault(emptyList())
}
