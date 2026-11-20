package com.jarvis.harness

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Event kinds written to a task's append-only log. */
enum class HarnessLogKind {
    STATE,
    THINKING,
    FILE_WRITE,
    FILE_EDIT,
    BASH_COMMAND,
    STEP_DONE,
    ERROR,
    RETRY,
    PAUSED,
    RESUMED,
    COMPLETED,
}

/** One immutable line in a task's history. */
data class HarnessLogEvent(
    val seq: Long,
    val taskId: String,
    val atMillis: Long,
    val kind: HarnessLogKind,
    val title: String,
    val detail: String = "",
    val path: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val command: String? = null,
    val exitCode: Int? = null,
    val outputTail: String? = null,
)

/**
 * Append-only, per-task event log (JSON Lines).
 *
 * This is the single source of truth for "kahan tak bana?". Jarvis answers status
 * questions from this log only — never from guesses. Every append is flushed to the
 * OS before returning, so a process kill loses at most the event in flight.
 */
internal class HarnessEventLog(context: Context) {

    private val dir = File(context.filesDir, "harness/logs").apply { mkdirs() }

    private fun file(taskId: String) = File(dir, "$taskId.jsonl")

    @Synchronized
    fun append(event: HarnessLogEvent): HarnessLogEvent {
        val target = file(event.taskId)
        runCatching {
            target.appendText(event.toJson().toString() + "\n")
            // Cheap durability: get the bytes to the filesystem even if the process dies.
            java.io.FileOutputStream(target, true).use { it.fd.sync() }
        }
        return event
    }

    @Synchronized
    fun append(
        taskId: String,
        seq: Long,
        kind: HarnessLogKind,
        title: String,
        detail: String = "",
        path: String? = null,
        additions: Int = 0,
        deletions: Int = 0,
        command: String? = null,
        exitCode: Int? = null,
        outputTail: String? = null,
    ): HarnessLogEvent = append(
        HarnessLogEvent(
            seq = seq,
            taskId = taskId,
            atMillis = System.currentTimeMillis(),
            kind = kind,
            title = title,
            detail = detail,
            path = path,
            additions = additions,
            deletions = deletions,
            command = command,
            exitCode = exitCode,
            outputTail = outputTail,
        ),
    )

    /** Reads the most recent [limit] events, oldest first. Tolerates a truncated last line. */
    @Synchronized
    fun read(taskId: String, limit: Int = 500): List<HarnessLogEvent> {
        val target = file(taskId)
        if (!target.isFile) return emptyList()
        return runCatching {
            target.useLines { lines ->
                lines.mapNotNull { parse(it) }.toList().takeLast(limit)
            }
        }.getOrDefault(emptyList())
    }

    fun counts(taskId: String): HarnessLogCounts {
        val events = read(taskId, limit = Int.MAX_VALUE / 2)
        return HarnessLogCounts(
            steps = events.count { it.kind == HarnessLogKind.STEP_DONE },
            files = events.filter { it.kind == HarnessLogKind.FILE_WRITE || it.kind == HarnessLogKind.FILE_EDIT }
                .mapNotNull { it.path }
                .distinct()
                .size,
            commands = events.count { it.kind == HarnessLogKind.BASH_COMMAND },
            errors = events.count { it.kind == HarnessLogKind.ERROR },
            retries = events.count { it.kind == HarnessLogKind.RETRY },
            lastEvent = events.lastOrNull(),
            lastError = events.lastOrNull { it.kind == HarnessLogKind.ERROR },
        )
    }

    @Synchronized
    fun delete(taskId: String) {
        file(taskId).delete()
    }

    private fun parse(line: String): HarnessLogEvent? = runCatching {
        val json = JSONObject(line)
        HarnessLogEvent(
            seq = json.optLong("seq"),
            taskId = json.getString("taskId"),
            atMillis = json.optLong("at"),
            kind = HarnessLogKind.valueOf(json.optString("kind")),
            title = json.optString("title"),
            detail = json.optString("detail"),
            path = json.optString("path").takeIf(String::isNotBlank),
            additions = json.optInt("add"),
            deletions = json.optInt("del"),
            command = json.optString("cmd").takeIf(String::isNotBlank),
            exitCode = if (json.has("code")) json.optInt("code") else null,
            outputTail = json.optString("out").takeIf(String::isNotBlank),
        )
    }.getOrNull()

    private fun HarnessLogEvent.toJson(): JSONObject = JSONObject().apply {
        put("seq", seq)
        put("taskId", taskId)
        put("at", atMillis)
        put("kind", kind.name)
        put("title", title)
        put("detail", detail)
        path?.let { put("path", it) }
        if (additions != 0) put("add", additions)
        if (deletions != 0) put("del", deletions)
        command?.let { put("cmd", it) }
        exitCode?.let { put("code", it) }
        outputTail?.let { put("out", it) }
    }
}

internal data class HarnessLogCounts(
    val steps: Int,
    val files: Int,
    val commands: Int,
    val errors: Int,
    val retries: Int,
    val lastEvent: HarnessLogEvent?,
    val lastError: HarnessLogEvent?,
)