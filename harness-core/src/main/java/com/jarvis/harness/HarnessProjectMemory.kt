package com.jarvis.harness

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * One durable record per coding project, independent from Jarvis's Yaadein.
 *
 * The record answers "pichla project kaun sa tha / kahan tak bana" long after the
 * process that built it is gone. It is written on every meaningful event with a
 * staged-write + rename + fsync, so a crash or force-kill cannot corrupt it.
 */
data class HarnessProjectMemory(
    val id: String = "",
    /** Workspace project id (different from the task id in [id]); used to attach a session. */
    val projectId: String = "",
    val name: String = "",
    val projectType: String = "general",
    val originalRequest: String = "",
    val finalPrompt: String = "",
    val features: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val status: String = "queued",
    val checkpoint: String = "",
    val stepsDone: Int = 0,
    /** Complexity tier classified before the task started (B2), plus why it was chosen. */
    val tier: String = "",
    val tierReason: String = "",
    /** Human label of the step/time budget that goes with [tier]. */
    val budget: String = "",
    /** Cached headless-browser capability: "available" or "unavailable" (B3). */
    val visualTest: String = "",
    val files: List<String> = emptyList(),
    val outputLocation: String? = null,
    val previewUrl: String? = null,
    val previewPort: Int? = null,
    val lastError: String? = null,
    /** Absolute on-disk workspace the agent works in. */
    val workspacePath: String = "",
    /** Project root inside the workspace (blank when the workspace itself is the root). */
    val projectRoot: String = "",
    /** Agent stable id that last ran, e.g. "claude-code" / "deepseek-harness" / "antigravity". */
    val agentType: String = "",
    /** Runtime session id emitted by the agent bridge on its last run. */
    val agentSessionId: String = "",
    /** Chat the run belonged to, so a native agent session can be resumed in place. */
    val chatId: String = "",
    val provider: String = "",
    val model: String = "",
    /** Ordered progress milestones; doubles as the project todo list. */
    val milestones: List<String> = emptyList(),
    /** One-line human summary used by voice answers and the resume prompt. */
    val summary: String = "",
    /** Snapshot of the project's files at the last meaningful event. */
    val fileTree: List<String> = emptyList(),
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("projectId", projectId)
        put("name", name)
        put("projectType", projectType)
        put("originalRequest", originalRequest)
        put("finalPrompt", finalPrompt)
        put("features", JSONArray(features))
        put("decisions", JSONArray(decisions))
        put("status", status)
        put("checkpoint", checkpoint)
        put("stepsDone", stepsDone)
        put("tier", tier)
        put("tierReason", tierReason)
        put("budget", budget)
        put("visualTest", visualTest)
        put("files", JSONArray(files.takeLast(LAST_FILES)))
        put("outputLocation", outputLocation ?: "")
        put("previewUrl", previewUrl ?: "")
        put("previewPort", previewPort ?: JSONObject.NULL)
        put("lastError", lastError ?: "")
        put("workspacePath", workspacePath)
        put("projectRoot", projectRoot)
        put("agentType", agentType)
        put("agentSessionId", agentSessionId)
        put("chatId", chatId)
        put("provider", provider)
        put("model", model)
        put("milestones", JSONArray(milestones.takeLast(LAST_MILESTONES)))
        put("summary", summary)
        put("fileTree", JSONArray(fileTree.takeLast(LAST_FILE_TREE)))
        put("createdAtMillis", createdAtMillis)
        put("updatedAtMillis", updatedAtMillis)
    }

    /** Compact block injected into Jarvis's system prompt / tool answers. */
    fun toSummary(): JSONObject = JSONObject().apply {
        put("project_id", id)
        put("workspace_project_id", projectId)
        put("name", name)
        put("type", projectType)
        put("status", status)
        put("steps_done", stepsDone)
        put("tier", tier)
        put("tier_reason", tierReason)
        put("budget", budget)
        put("visual_test", visualTest)
        put("files", files.size)
        put("checkpoint", checkpoint)
        put("summary", summary)
        put("agent_type", agentType)
        put("agent_session_id", agentSessionId)
        put("provider", provider)
        put("model", model)
        put("workspace_path", workspacePath)
        put("project_root", projectRoot)
        put("preview_url", previewUrl.orEmpty())
        put("preview_port", previewPort ?: 0)
        put("milestones", JSONArray(milestones.takeLast(10)))
        put("file_tree_count", fileTree.size)
        put("output_location", outputLocation.orEmpty())
        put("last_error", lastError.orEmpty())
        put("updated_at", updatedAtMillis)
    }

    companion object {
        const val LAST_FILES = 60
        const val LAST_DECISIONS = 20
        const val LAST_MILESTONES = 40
        const val LAST_FILE_TREE = 200

        fun fromJson(item: JSONObject): HarnessProjectMemory = HarnessProjectMemory(
            id = item.optString("id"),
            projectId = item.optString("projectId"),
            name = item.optString("name"),
            projectType = item.optString("projectType", "general"),
            originalRequest = item.optString("originalRequest"),
            finalPrompt = item.optString("finalPrompt"),
            features = item.optJSONArray("features")?.let { array ->
                List(array.length()) { array.optString(it) }
            }.orEmpty(),
            decisions = item.optJSONArray("decisions")?.let { array ->
                List(array.length()) { array.optString(it) }
            }.orEmpty(),
            status = item.optString("status", "queued"),
            checkpoint = item.optString("checkpoint"),
            stepsDone = item.optInt("stepsDone"),
            tier = item.optString("tier"),
            tierReason = item.optString("tierReason"),
            budget = item.optString("budget"),
            visualTest = item.optString("visualTest"),
            files = item.optJSONArray("files")?.let { array ->
                List(array.length()) { array.optString(it) }
            }.orEmpty(),
            outputLocation = item.optString("outputLocation").takeIf(String::isNotBlank),
            previewUrl = item.optString("previewUrl").takeIf(String::isNotBlank),
            previewPort = item.optInt("previewPort").takeIf { item.has("previewPort") && !item.isNull("previewPort") },
            lastError = item.optString("lastError").takeIf(String::isNotBlank),
            workspacePath = item.optString("workspacePath"),
            projectRoot = item.optString("projectRoot"),
            agentType = item.optString("agentType"),
            agentSessionId = item.optString("agentSessionId"),
            chatId = item.optString("chatId"),
            provider = item.optString("provider"),
            model = item.optString("model"),
            milestones = item.optJSONArray("milestones")?.let { array ->
                List(array.length()) { array.optString(it) }
            }.orEmpty(),
            summary = item.optString("summary"),
            fileTree = item.optJSONArray("fileTree")?.let { array ->
                List(array.length()) { array.optString(it) }
            }.orEmpty(),
            createdAtMillis = item.optLong("createdAtMillis"),
            updatedAtMillis = item.optLong("updatedAtMillis"),
        )
    }
}

/**
 * Atomic per-project JSON store under `filesDir/harness/projects/<id>.json`.
 *
 * Reads are cheap and synchronous (small files); writes are staged and fsynced so a
 * force stop keeps either the previous or the new record, never a truncated one.
 */
internal class HarnessProjectMemoryStore(context: Context) {

    private val dir = File(context.filesDir, "harness/projects").apply { mkdirs() }

    private fun file(id: String) = File(dir, "${id.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.json")

    @Synchronized
    fun get(id: String): HarnessProjectMemory? {
        if (id.isBlank()) return null
        val target = file(id)
        if (!target.isFile) return null
        return runCatching { HarnessProjectMemory.fromJson(JSONObject(target.readText())) }.getOrNull()
    }

    @Synchronized
    fun list(): List<HarnessProjectMemory> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f -> runCatching { HarnessProjectMemory.fromJson(JSONObject(f.readText())) }.getOrNull() }
            ?.sortedByDescending { it.updatedAtMillis }
            .orEmpty()

    @Synchronized
    fun upsert(memory: HarnessProjectMemory): HarnessProjectMemory {
        if (memory.id.isBlank()) return memory
        val target = file(memory.id)
        val staged = File(target.parentFile, "${target.name}.tmp")
        runCatching {
            FileOutputStream(staged).use { out ->
                out.write(memory.toJson().toString().toByteArray())
                out.flush()
                out.fd.sync()
            }
            if (!staged.renameTo(target)) {
                target.delete()
                staged.renameTo(target)
            }
        }
        return memory
    }

    /**
     * Merge-update helper: keeps existing fields unless explicitly replaced and always
     * bumps [HarnessProjectMemory.updatedAtMillis].
     */
    @Synchronized
    fun update(
        id: String,
        name: String? = null,
        projectType: String? = null,
        originalRequest: String? = null,
        finalPrompt: String? = null,
        addFeature: String? = null,
        addDecision: String? = null,
        status: String? = null,
        checkpoint: String? = null,
        stepsDone: Int? = null,
        tier: String? = null,
        tierReason: String? = null,
        budget: String? = null,
        visualTest: String? = null,
        addFile: String? = null,
        outputLocation: String? = null,
        previewUrl: String? = null,
        lastError: String? = null,
        clearError: Boolean = false,
        projectId: String? = null,
        workspacePath: String? = null,
        projectRoot: String? = null,
        agentType: String? = null,
        agentSessionId: String? = null,
        clearAgentSession: Boolean = false,
        chatId: String? = null,
        provider: String? = null,
        model: String? = null,
        summary: String? = null,
        addMilestone: String? = null,
        fileTree: List<String>? = null,
        previewPort: Int? = null,
    ): HarnessProjectMemory {
        val now = System.currentTimeMillis()
        val current = get(id) ?: HarnessProjectMemory(id = id, createdAtMillis = now)
        val features = current.features.toMutableList()
        addFeature?.takeIf { it.isNotBlank() && it !in features }?.let(features::add)
        val decisions = current.decisions.toMutableList()
        addDecision?.takeIf { it.isNotBlank() }?.let { line ->
            decisions.add(line.take(300))
            while (decisions.size > HarnessProjectMemory.LAST_DECISIONS) decisions.removeAt(0)
        }
        val files = current.files.toMutableList()
        addFile?.takeIf { it.isNotBlank() }?.let { path ->
            files.remove(path)
            files.add(path)
            while (files.size > HarnessProjectMemory.LAST_FILES) files.removeAt(0)
        }
        val milestones = current.milestones.toMutableList()
        addMilestone?.takeIf { it.isNotBlank() }?.let { line ->
            val clipped = line.take(200)
            if (milestones.lastOrNull() != clipped) milestones.add(clipped)
            while (milestones.size > HarnessProjectMemory.LAST_MILESTONES) milestones.removeAt(0)
        }
        val merged = current.copy(
            projectId = projectId ?: current.projectId,
            name = name ?: current.name,
            projectType = projectType ?: current.projectType,
            originalRequest = originalRequest ?: current.originalRequest,
            finalPrompt = finalPrompt ?: current.finalPrompt,
            features = features,
            decisions = decisions,
            status = status ?: current.status,
            checkpoint = checkpoint ?: current.checkpoint,
            stepsDone = stepsDone ?: current.stepsDone,
            tier = tier ?: current.tier,
            tierReason = tierReason ?: current.tierReason,
            budget = budget ?: current.budget,
            visualTest = visualTest ?: current.visualTest,
            files = files,
            outputLocation = outputLocation ?: current.outputLocation,
            previewUrl = previewUrl ?: current.previewUrl,
            previewPort = previewPort ?: current.previewPort,
            lastError = if (clearError) null else (lastError ?: current.lastError),
            workspacePath = workspacePath ?: current.workspacePath,
            projectRoot = projectRoot ?: current.projectRoot,
            agentType = agentType ?: current.agentType,
            agentSessionId = if (clearAgentSession) "" else (agentSessionId ?: current.agentSessionId),
            chatId = chatId ?: current.chatId,
            provider = provider ?: current.provider,
            model = model ?: current.model,
            milestones = milestones,
            summary = summary ?: current.summary,
            fileTree = fileTree ?: current.fileTree,
            updatedAtMillis = now,
        )
        // Keep the one-line summary fresh from the merged record unless a caller forced it.
        return upsert(if (summary == null) merged.copy(summary = deriveSummary(merged)) else merged)
    }

    private fun deriveSummary(memory: HarnessProjectMemory): String = buildString {
        append(memory.name.ifBlank { memory.id })
        if (memory.status.isNotBlank()) append(" · ").append(memory.status)
        if (memory.stepsDone > 0) append(" · ").append(memory.stepsDone).append(" steps")
        if (memory.files.isNotEmpty()) append(" · ").append(memory.files.size).append(" files")
        if (memory.checkpoint.isNotBlank()) append(" · ").append(memory.checkpoint.take(160))
    }

    /** Recent projects, newest first, as a compact text block for the system prompt. */
    fun summaryBlock(limit: Int = 5): String {
        val recent = list().take(limit)
        if (recent.isEmpty()) return ""
        val now = System.currentTimeMillis()
        return buildString {
            appendLine("PROJECT MEMORY (persistent, survives restarts):")
            recent.forEach { project ->
                append("- ").append(project.name.ifBlank { project.id })
                append(" [").append(project.status).append("]")
if (project.isIncomplete()) append(" · unfinished")
                if (project.tier.isNotBlank()) append(" tier=").append(project.tier)
                if (project.checkpoint.isNotBlank()) append(" · ").append(project.checkpoint.take(120))
                append(" · steps=").append(project.stepsDone).append(" files=").append(project.files.size)
                append(" · ").append(lastActivityLabel(project.updatedAtMillis, now))
                project.outputLocation?.let { append(" · out=").append(it) }
                project.previewUrl?.let { append(" · preview=").append(it) }
                project.lastError?.let { append(" · error=").append(it.take(120)) }
                appendLine()
            }
        }
    }

    /** Only the projects that still need work, newest first. */
    fun unfinishedBlock(limit: Int = 5): String {
        val unfinished = list().filter { it.isIncomplete() }.take(limit)
        if (unfinished.isEmpty()) return ""
        val now = System.currentTimeMillis()
        return buildString {
            appendLine("UNFINISHED PROJECTS (resumable, survive restarts):")
            unfinished.forEach { project ->
                append("- ").append(project.displayName())
                append(" [").append(project.status).append("]")
                if (project.checkpoint.isNotBlank()) append(" · ").append(project.checkpoint.take(120))
                append(" · ").append(project.stepsDone).append(" steps · ")
                append(project.files.size).append(" files · ")
                append(lastActivityLabel(project.updatedAtMillis, now))
                appendLine()
            }
        }
    }
}
