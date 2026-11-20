package com.jarvis.harness

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Durable task state for [HarnessEngine].
 *
 * Rewritten on every transition (staged + renamed), so a kill mid-write cannot corrupt
 * it. The append-only event log in [HarnessEventLog] holds the detailed history; this
 * file holds the current state so status answers survive a restart.
 */
internal class HarnessTaskStore(context: Context) {

    private val file = File(context.filesDir, "harness/tasks.json").apply { parentFile?.mkdirs() }

    @Synchronized
    fun load(): MutableList<HarnessTaskRecord> {
        if (!file.isFile) return mutableListOf()
        return runCatching {
            val root = JSONObject(file.readText())
            val tasks = root.optJSONArray("tasks") ?: JSONArray()
            MutableList(tasks.length()) { index ->
                val item = tasks.getJSONObject(index)
                HarnessTaskRecord(
                    id = item.getString("id"),
                    projectName = item.optString("projectName"),
                    prompt = item.getString("prompt"),
                    projectType = runCatching {
                        HarnessProjectType.valueOf(item.optString("projectType"))
                    }.getOrDefault(HarnessProjectType.GENERAL),
                    state = runCatching {
                        HarnessTaskState.valueOf(item.optString("state"))
                    }.getOrDefault(HarnessTaskState.QUEUED),
                    projectId = item.optString("projectId").takeIf(String::isNotBlank),
                    createdAtMillis = item.optLong("createdAtMillis"),
                    updatedAtMillis = item.optLong("updatedAtMillis"),
                    lastMessage = item.optString("lastMessage").takeIf(String::isNotBlank),
                    changedFiles = item.optInt("changedFiles"),
                    deliverableLocation = item.optString("deliverableLocation").takeIf(String::isNotBlank),
                    runtimeReady = item.optBoolean("runtimeReady"),
                    verified = item.optBoolean("verified"),
                    attempts = item.optInt("attempts"),
                    provider = item.optString("provider").takeIf(String::isNotBlank),
                    model = item.optString("model").takeIf(String::isNotBlank),
                    httpStatus = item.optInt("httpStatus").takeIf { item.has("httpStatus") && !item.isNull("httpStatus") },
                    errorHeadline = item.optString("errorHeadline").takeIf(String::isNotBlank),
                    errorKind = item.optString("errorKind").takeIf(String::isNotBlank),
                    errorHint = item.optString("errorHint").takeIf(String::isNotBlank),
                    errorRaw = item.optString("errorRaw").takeIf(String::isNotBlank),
                    liveLine = item.optString("liveLine").takeIf(String::isNotBlank),
                    stepCount = item.optInt("stepCount"),
                    fileCount = item.optInt("fileCount"),
                    tier = item.optString("tier").takeIf(String::isNotBlank),
                    tierReason = item.optString("tierReason").takeIf(String::isNotBlank),
                    budgetLabel = item.optString("budgetLabel").takeIf(String::isNotBlank),
                    visualTest = item.optString("visualTest").takeIf(String::isNotBlank),
                    sessionId = item.optString("sessionId").takeIf(String::isNotBlank),
                    proofFiles = item.optJSONArray("proofFiles")?.let { array ->
                        List(array.length()) { array.optString(it) }
                    }.orEmpty(),
                    proofBuildTail = item.optString("proofBuildTail").takeIf(String::isNotBlank),
                )
            }
        }.getOrDefault(mutableListOf())
    }

    @Synchronized
    fun save(tasks: List<HarnessTaskRecord>) {
        val array = JSONArray()
        tasks.forEach { task ->
            array.put(
                JSONObject()
                    .put("id", task.id)
                    .put("projectName", task.projectName)
                    .put("prompt", task.prompt)
                    .put("projectType", task.projectType.name)
                    .put("state", task.state.name)
                    .put("projectId", task.projectId ?: "")
                    .put("createdAtMillis", task.createdAtMillis)
                    .put("updatedAtMillis", task.updatedAtMillis)
                    .put("lastMessage", task.lastMessage ?: "")
                    .put("changedFiles", task.changedFiles)
                    .put("deliverableLocation", task.deliverableLocation ?: "")
                    .put("runtimeReady", task.runtimeReady)
                    .put("verified", task.verified)
                    .put("attempts", task.attempts)
                    .put("provider", task.provider ?: "")
                    .put("model", task.model ?: "")
                    .put("httpStatus", task.httpStatus ?: JSONObject.NULL)
                    .put("errorHeadline", task.errorHeadline ?: "")
                    .put("errorKind", task.errorKind ?: "")
                    .put("errorHint", task.errorHint ?: "")
                    .put("errorRaw", task.errorRaw ?: "")
                    .put("liveLine", task.liveLine ?: "")
                    .put("stepCount", task.stepCount)
                    .put("fileCount", task.fileCount)
                    .put("tier", task.tier ?: "")
                    .put("tierReason", task.tierReason ?: "")
                    .put("budgetLabel", task.budgetLabel ?: "")
                    .put("visualTest", task.visualTest ?: "")
                    .put("sessionId", task.sessionId ?: "")
                    .put("proofFiles", JSONArray(task.proofFiles))
                    .put("proofBuildTail", task.proofBuildTail ?: ""),
            )
        }
        val payload = JSONObject().put("version", 2).put("tasks", array)
        val staged = File(file.parentFile, "${file.name}.tmp")
        staged.writeText(payload.toString())
        file.delete()
        staged.renameTo(file)
    }

    fun newId(): String = UUID.randomUUID().toString()
}

/** Mutable, serializable form of a task. Converted to [HarnessTaskStatus] for callers. */
internal data class HarnessTaskRecord(
    val id: String,
    val projectName: String,
    val prompt: String,
    val projectType: HarnessProjectType,
    val state: HarnessTaskState,
    val projectId: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastMessage: String?,
    val changedFiles: Int,
    val deliverableLocation: String?,
    val runtimeReady: Boolean,
    val verified: Boolean = false,
    val attempts: Int = 0,
    val provider: String? = null,
    val model: String? = null,
    val httpStatus: Int? = null,
    val errorHeadline: String? = null,
    val errorKind: String? = null,
    val errorHint: String? = null,
    val errorRaw: String? = null,
    val liveLine: String? = null,
    val stepCount: Int = 0,
    val fileCount: Int = 0,
    val tier: String? = null,
    val tierReason: String? = null,
    val budgetLabel: String? = null,
    val visualTest: String? = null,
    val sessionId: String? = null,
    val proofFiles: List<String> = emptyList(),
    val proofBuildTail: String? = null,
) {
    fun toStatus(position: Int, queued: Boolean): HarnessTaskStatus = HarnessTaskStatus(
        id = id,
        state = state,
        projectId = projectId,
        projectName = projectName,
        prompt = prompt,
        projectType = projectType,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        lastMessage = lastMessage,
        changedFiles = changedFiles,
        deliverableLocation = deliverableLocation,
        verified = verified,
        runtimeReady = runtimeReady,
        queued = queued,
        queuePosition = position,
        attempts = attempts,
        provider = provider,
        model = model,
        httpStatus = httpStatus,
        liveLine = liveLine,
        steps = stepCount,
        files = fileCount,
        tier = tier.orEmpty(),
        tierReason = tierReason.orEmpty(),
        budget = budgetLabel.orEmpty(),
        visualTest = visualTest.orEmpty(),
        error = errorHeadline?.let {
            HarnessError(
                kind = runCatching { HarnessErrorKind.valueOf(errorKind.orEmpty()) }
                    .getOrDefault(HarnessErrorKind.UNKNOWN),
                provider = provider.orEmpty(),
                httpStatus = httpStatus,
                raw = errorRaw.orEmpty(),
                hint = errorHint.orEmpty(),
            )
        },
        proof = if (proofFiles.isEmpty() && proofBuildTail == null) {
            null
        } else {
            HarnessTaskProof(files = proofFiles, buildTail = proofBuildTail, locations = listOfNotNull(deliverableLocation))
        },
    )
}
