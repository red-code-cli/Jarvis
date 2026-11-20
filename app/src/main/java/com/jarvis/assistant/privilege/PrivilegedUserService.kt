package com.jarvis.assistant.privilege

import android.content.Context
import android.os.RemoteException
import android.system.Os
import android.util.Log
import androidx.annotation.Keep
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Runs inside a Shizuku UserService process with shell (ADB) or root identity.
 * It is intentionally small: one command runner whose output is returned as JSON.
 */
@Keep
class PrivilegedUserService : IPrivilegedService.Stub {

    @Keep
    constructor()

    @Keep
    constructor(context: Context) : this() {
        Log.i(TAG, "PrivilegedUserService created with context=${context.packageName}")
    }

    override fun destroy() {
        Log.i(TAG, "destroy requested")
        System.exit(0)
    }

    @Throws(RemoteException::class)
    override fun runCommand(command: String, timeoutMs: Int): String {
        return try {
            runCommandInternal(command, timeoutMs.coerceIn(1_000, 60_000))
        } catch (t: Throwable) {
            Log.e(TAG, "runCommand failed: ${t.message}", t)
            errorJson(t.message ?: t.javaClass.simpleName)
        }
    }

    override fun getIdentity(): String {
        return try {
            JSONObject()
                .put("success", true)
                .put("uid", Os.getuid())
                .put("pid", Os.getpid())
                .put("mode", if (Os.getuid() == 0) "root" else "shell")
                .toString()
        } catch (t: Throwable) {
            errorJson(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun runCommandInternal(command: String, timeoutMs: Int): String {
        if (command.isBlank()) return errorJson("Command is empty")

        val process = ProcessBuilder("/system/bin/sh", "-c", command)
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
                // The process may be force-killed while reading.
            }
        }
        reader.isDaemon = true
        reader.start()

        val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(500)
            return JSONObject()
                .put("success", false)
                .put("exit_code", -1)
                .put("output", output.toString().trim().take(MAX_OUTPUT_CHARS))
                .put("error", "Command timed out after ${timeoutMs / 1000}s")
                .toString()
        }

        reader.join(1_000)
        val exitCode = try {
            process.exitValue()
        } catch (_: Throwable) {
            -1
        }
        return JSONObject()
            .put("success", exitCode == 0)
            .put("exit_code", exitCode)
            .put("output", output.toString().trim().take(MAX_OUTPUT_CHARS))
            .toString()
    }

    private fun errorJson(message: String): String = JSONObject()
        .put("success", false)
        .put("exit_code", -1)
        .put("output", "")
        .put("error", message)
        .toString()

    private companion object {
        const val TAG = "PrivilegedUserService"
        const val MAX_OUTPUT_CHARS = 200_000
    }
}
