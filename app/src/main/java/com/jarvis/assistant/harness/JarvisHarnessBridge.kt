package com.jarvis.assistant.harness

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.harness.HarnessCommandRisk
import com.jarvis.harness.HarnessError
import com.jarvis.harness.HarnessEngine
import com.jarvis.harness.HarnessProjectSnapshot
import com.jarvis.harness.HarnessProjectType
import com.jarvis.harness.HarnessTaskState
import com.jarvis.harness.HarnessTaskStatus
import com.jarvis.harness.HarnessHost
import com.jarvis.harness.ProjectLookup
import com.jarvis.harness.model.AgentKind
import com.jarvis.harness.model.ProviderKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Glue between Jarvis and the reusable :harness-core module.
 *
 * This is the ONLY place that knows about both sides: it initialises the engine with
 * Jarvis's build identity, implements [HarnessHost] (deliverables, notifications,
 * spoken announcements) and turns Gemini Live tool calls into engine calls.
 *
 * The module itself never references Jarvis.
 */
object JarvisHarnessBridge : HarnessHost {

    private const val TAG = "JarvisHarness"
    private const val CHANNEL_ID = "jarvis_coding_engine"
    private const val NOTIFICATION_ID = 8107

    @Volatile
    private var engineRef: HarnessEngine? = null

    fun engine(context: Context): HarnessEngine =
        engineRef ?: synchronized(this) {
            engineRef ?: HarnessEngine.initialize(
                context = context.applicationContext,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                // Jarvis must never download the Linux runtime on its own; install starts
                // from Settings → Coding Engine or the install_coding_engine voice tool.
                autoInstallOnFirstRun = false,
            ).also { it.host = this }.also { engineRef = it }
        }

    /** Called once from JarvisApp so the engine is ready before the first tool call. */
    fun warmUp(context: Context) {
        runCatching {
            val engine = engine(context)
            // Bring up the local provider gateway before any task so agent bridges can route
            // through it and fail over between providers/keys mid-request.
            runCatching { AgentGatewayController.get(context.applicationContext).start() }
            // A running task may have been killed with the process; resume it from the
            // last checkpoint and keep the engine in its own foreground service.
            if (engine.isInstalled() && engine.storedApiKey(engine.providerKind()).isNotBlank()) {
                val interrupted = engine.resumeInterrupted()
                if (interrupted.isNotEmpty()) HarnessRunService.start(context)
            }
        }.onFailure { Log.w(TAG, "Harness engine warm-up failed: ${it.message}") }
    }

    /** Live line/state for the orb. Cheap to collect. */
    val liveStatus: kotlinx.coroutines.flow.StateFlow<com.jarvis.harness.HarnessLiveStatus>
        get() = engine(JarvisApp.instance).liveStatus

    fun recentTasks(limit: Int = 10) = engine(JarvisApp.instance).recentTasks(limit)

    fun retryTask(taskId: String) = engine(JarvisApp.instance).retryNow(taskId)

    // ── Host callbacks ───────────────────────────────────────────────────────

    override suspend fun onTaskCompleted(status: HarnessTaskStatus) {
        val context = JarvisApp.instance
        val projectDir = status.projectId?.let { engine(context).projectLocation(it) }?.let(::File)
        val published = projectDir?.let { dir ->
            HarnessDeliveries.publish(context, status.projectName ?: "project", File(status.deliverableLocation ?: dir.path) )
        }

        val name = status.projectName ?: "project"
        val where = published ?: status.deliverableLocation ?: "the private workspace"
        val memory = engine(context).projectMemory(status.id)
        val preview = memory?.previewUrl?.takeIf { it.isNotBlank() }

        // Web project finished: open the real preview in Jarvis Browser and say so.
        val previewOpened = preview != null && openPreview(context, preview)
        val summary = buildString {
            if (status.verified) append("Coding task finished: $name. Files are in $where.")
            else append("Coding task finished: $name, but I could not verify the output file. It is in $where.")
            if (previewOpened) append(" Preview khol diya.") else if (preview != null) append(" Preview is at $preview.")
            val apk = engine(context).latestApkPath()
            if (status.projectType == HarnessProjectType.ANDROID && apk != null) {
                append(" APK is ready at ").append(apk).append("; say install karo to install it.")
            }
        }
        announce(context, summary, name)
        if (!engine(context).isTaskActive()) HarnessRunService.stop(context)
    }

    override suspend fun onTaskCompletedWithWarnings(status: HarnessTaskStatus, reason: String) {
        val context = JarvisApp.instance
        val memory = engine(context).projectMemory(status.id)
        val preview = memory?.previewUrl?.takeIf { it.isNotBlank() }
        val previewOpened = preview != null && openPreview(context, preview)
        val name = status.projectName ?: "project"
        val summary = buildString {
            append("Coding task $name finished with warnings. ")
            append(reason.take(200))
            when {
                previewOpened -> append(" Preview khol diya.")
                preview != null -> append(" Preview is at $preview.")
            }
        }
        announce(context, summary, name)
        if (!engine(context).isTaskActive()) HarnessRunService.stop(context)
    }

    override suspend fun onTaskRetrying(status: HarnessTaskStatus, error: HarnessError, inSeconds: Int) {
        val context = JarvisApp.instance
        announce(
            context,
            "${error.headline}. Retrying in ${inSeconds} seconds. ${error.hint}",
            status.projectName,
        )
    }

    override suspend fun onApiKeySwitched(from: com.jarvis.harness.data.ApiKeyInfo?, to: com.jarvis.harness.data.ApiKeyInfo) {
        val context = JarvisApp.instance
        announce(context, "Switched to API key ${to.name}. Continuing.", null)
    }

    override suspend fun onPreviewStarted(status: HarnessTaskStatus?, url: String) {
        val context = JarvisApp.instance
        // Web preview is ready: open it in Jarvis Browser and say so once.
        if (!openPreview(context, url)) return
        announce(context, "Preview khol diya.", status?.projectName)
    }

    override suspend fun onTaskFailed(status: HarnessTaskStatus, reason: String) {
        val context = JarvisApp.instance
        announce(context, "Coding task ${status.projectName ?: ""} did not finish. $reason", status.projectName)
        if (!engine(context).isTaskActive()) HarnessRunService.stop(context)
    }

    // ── Preview ──────────────────────────────────────────────────────────────

    /** Opens a project preview (or the engine's live dev-server URL) in Jarvis Browser. */
    fun openPreview(context: Context, url: String): Boolean {
        val clean = url.trim()
        if (clean.isBlank()) return false
        return runCatching {
            val target = if (clean.startsWith("http://") || clean.startsWith("https://")) clean else "http://$clean"
            context.startActivity(
                Intent(context, com.jarvis.assistant.ui.browser.BrowserActivity::class.java)
                    .putExtra(com.jarvis.assistant.ui.browser.BrowserActivity.EXTRA_URL, target)
                    .putExtra(com.jarvis.assistant.ui.browser.BrowserActivity.EXTRA_OPENED_BY_JARVIS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.onFailure { Log.w(TAG, "Could not open preview: ${it.message}") }.getOrDefault(false)
    }

    /** Permanent cancel from the Home card — always asks once, never silent. */
    fun requestCancel(context: Context) {
        val engine = runCatching { engine(context) }.getOrNull() ?: return
        val id = engine.liveStatus.value.taskId ?: latestTaskId(context) ?: return
        runCatching {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle("Cancel this task?")
                .setMessage("The task will be marked cancelled permanently. To continue later instead, keep it paused.")
                .setPositiveButton("Cancel task") { _, _ ->
                    engine.cancelTask(id)
                    if (!engine.isTaskActive()) HarnessRunService.stop(context)
                }
                .setNegativeButton("Keep paused") { _, _ -> engine.pauseTask(id) }
                .show()
        }
    }

    /** One-line brief Jarvis speaks when a new/reconnected live session starts. */
    fun sessionStartBriefing(context: Context): String {
        val engine = runCatching { engine(context) }.getOrNull() ?: return ""
        val resumable = engine.resumableTasks()
        if (resumable.isNotEmpty()) {
            val task = resumable.first()
            return "Ek kaam chal raha tha: ${task.projectName ?: "project"}, " +
                "${task.steps} steps aur ${task.files} files done. " +
                "Bolo 'continue' to wahin se shuru karun."
        }
        val unfinished = engine.projectSnapshots(10).firstOrNull { it.status !in setOf("completed", "cancelled") }
        if (unfinished != null && unfinished.stepsDone > 0) {
            return "Ek project adhura hai: ${unfinished.name}, ${unfinished.stepsDone} steps done. " +
                "Bolo 'continue' to wahin se shuru karun."
        }
        val recent = engine.latestProjectMemory() ?: return ""
        return "Pichla project ${recent.name} tha, status ${recent.status}, ${recent.stepsDone} steps done."
    }

    private fun announce(context: Context, message: String, projectName: String?) {
        val silent = runCatching { (context as JarvisApp).preferences.isSilentExecutionMode }.getOrDefault(false)
        val sessionOn = runCatching { JarvisConversationService.isServiceRunning() }.getOrDefault(false)
        if (sessionOn && !silent) {
            // The live session is up: let Jarvis speak the result in its own voice/persona.
            runCatching {
                JarvisConversationService.sendTextMessage(
                    "[coding-engine update] $message Tell the user this in one short sentence in their language. Do not invent details.",
                )
            }
        }
        postNotification(context, projectName?.let { "Coding engine · $it" } ?: "Coding engine", message)
    }

    private fun postNotification(context: Context, title: String, text: String) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Coding engine", NotificationManager.IMPORTANCE_DEFAULT),
            )
            val intent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(intent)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "Could not post coding-engine notification: ${it.message}") }
    }

    // ── Gemini Live tool dispatch ────────────────────────────────────────────

    /**
     * Runs a harness tool. Returns null when [name] is not a harness tool, so the
     * caller can fall through to Jarvis's existing tool handling untouched.
     */
    suspend fun execute(name: String, args: JSONObject): JSONObject? {
        if (name !in TOOL_NAMES) return null
        val context = JarvisApp.instance
        return try {
            when (name) {
                "install_coding_engine" -> installEngine(context)
                "coding_engine_status" -> engineStatus(context)
                "start_build_task" -> startBuildTask(context, args)
                "get_task_status" -> getTaskStatus(context, args)
                "get_task_log" -> getTaskLog(context, args)
                "get_recent_tasks" -> getRecentTasks(context)
                "pause_task" -> pauseTask(context, args)
                "resume_task" -> resumeTask(context, args)
                "cancel_task" -> cancelTask(context, args)
                "send_followup" -> sendFollowUp(context, args)
                "list_projects" -> listProjects(context)
                "resume_project" -> resumeProject(context, args)
                "get_project_status" -> getProjectStatus(context, args)
                "get_project_memory" -> getProjectMemory(context, args)
                "get_project_location" -> getProjectLocation(context, args)
                "open_preview" -> openPreviewTool(context, args)
                "run_terminal_command" -> runTerminalCommand(context, args)
                "get_app_state" -> getAppState(context)
                "list_providers" -> listProviders(context)
                "list_agents" -> listAgents(context)
                "set_setting" -> setSetting(context, args)
                "open_settings" -> openSettings(context, args)
                else -> null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Harness tool '$name' failed: ${t.message}", t)
            result(false, "The coding engine hit an error: ${t.message?.take(200)}")
        }
    }

    val toolNames: Set<String> get() = TOOL_NAMES

    private fun installEngine(context: Context): JSONObject {
        val engine = engine(context)
        if (!engine.isSupportedDevice()) {
            return result(false, "This phone cannot run the coding engine. It needs a 64-bit ARM device.")
        }
        engine.install(setOf(HarnessProjectType.WEB))
        return result(true, "Coding engine install started. Ubuntu, Node.js, npm and Git are downloading in the background. You can keep talking; ask me for progress anytime.")
    }

    private fun engineStatus(context: Context): JSONObject {
        val engine = engine(context)
        val state = engine.installState.value
        val agents = engine.installedAgentVersions()
        return JSONObject()
            .put("success", true)
            .put("installed", engine.isInstalled())
            .put("supported", engine.isSupportedDevice())
            .put("status", state.status.name)
            .put("message", state.message)
            .put("progress", state.progress)
            .put("agents", JSONObject().apply { agents.forEach { (k, v) -> put(k.stableId, v) } })
            .put("stacks", JSONArray(engine.installedStacks().map { it.name }))
    }

    private fun startBuildTask(context: Context, args: JSONObject): JSONObject {
        val engine = engine(context)
        val description = args.optString("description").trim()
        if (description.isBlank()) return result(false, "I need a description of what to build.")
        if (!engine.isInstalled()) {
            return JSONObject()
                .put("success", false)
                .put("install_required", true)
                .put("message", "The coding engine is not installed yet. Ask the user whether to install it, then call install_coding_engine.")
        }
        if (engine.storedApiKey(engine.providerKind()).isBlank()) {
            return JSONObject()
                .put("success", false)
                .put("provider_required", true)
                .put("message", "No coding model API key is saved. Ask the user to open Settings, Coding Engine, Custom API and add a provider key before building.")
        }
        val type = parseProjectType(args.optString("project_type"))
        val name = args.optString("project_name").trim().ifBlank { null }
        runCatching { AgentGatewayController.get(context.applicationContext).start() }
        val id = engine.startTask(description, type, name)
        HarnessRunService.start(context)
        return JSONObject()
            .put("success", true)
            .put("task_id", id)
            .put("project_type", type.name.lowercase())
            .put("message", "Build task queued. It runs in the background; ask me 'kahan tak bana' or call get_task_status for real progress.")
    }

    private fun getTaskStatus(context: Context, args: JSONObject): JSONObject {
        val engine = engine(context)
        val id = args.optString("task_id").trim()
        val status = if (id.isBlank()) {
            engine.allTasks().lastOrNull()
        } else {
            engine.taskStatus(id)
        } ?: return result(false, "I have no record of that task.")

        val output = JSONObject()
            .put("success", true)
            .put("task_id", status.id)
            .put("state", status.state.name.lowercase())
            .put("project_name", status.projectName.orEmpty())
            .put("project_type", status.projectType.name.lowercase())
            .put("progress_note", status.lastMessage.orEmpty())
            .put("changed_files", status.changedFiles)
            .put("verified", status.verified)
            .put("deliverable_location", status.deliverableLocation.orEmpty())
            .put("queued", status.queued)
            .put("preview_url", engine(context).activePreviewUrl().orEmpty())
            .put("apk_path", engine(context).latestApkPath().orEmpty())
            .put("provider", status.provider.orEmpty())
            .put("model", status.model.orEmpty())
            .put("attempts", status.attempts)
            .put("steps", status.steps)
            .put("files", status.files)
            .put("error_headline", status.error?.headline.orEmpty())
            .put("error_hint", status.error?.hint.orEmpty())
            .put("proof_files", JSONArray(status.proof?.files.orEmpty().take(15)))
        when (status.state) {
            HarnessTaskState.RUNNING -> output.put("message", "Still running. ${status.lastMessage.orEmpty()}".trim())
            HarnessTaskState.QUEUED -> output.put("message", "Waiting in the queue.")
            HarnessTaskState.WAITING_RETRY -> output.put(
                "message",
                "Provider error: ${status.error?.headline.orEmpty()}. Retry attempt ${status.attempts} is pending. ${status.error?.hint.orEmpty()}".trim(),
            )
            HarnessTaskState.PAUSED -> output.put("message", "Paused. Say 'resume karo' to continue.")
            HarnessTaskState.INTERRUPTED -> output.put("message", "The app stopped while this task was running. Say 'resume karo' to continue from the last checkpoint.")
            HarnessTaskState.COMPLETED -> output.put(
                "message",
                if (status.verified) "Finished and verified. Output is at ${status.deliverableLocation}."
                else "Marked finished but the output could not be verified.",
            )
            HarnessTaskState.COMPLETED_WITH_WARNINGS -> output.put(
                "message",
                "Finished with warnings: ${status.lastMessage.orEmpty()}".trim(),
            )
            HarnessTaskState.FAILED -> output.put("message", "Failed: ${status.error?.spoken ?: status.lastMessage.orEmpty()}")
            HarnessTaskState.CANCELLED -> output.put("message", "Cancelled.")
        }
        return output
    }

    private fun getTaskLog(context: Context, args: JSONObject): JSONObject {
        val engine = engine(context)
        val id = args.optString("task_id").trim().ifBlank { latestTaskId(context) }
            ?: return result(false, "There is no task log yet.")
        val limit = args.optInt("limit", 40).coerceIn(1, 200)
        val events = engine.taskLog(id, limit)
        if (events.isEmpty()) return result(false, "No events recorded for that task yet.")
        val array = JSONArray()
        events.forEach { event ->
            array.put(
                JSONObject()
                    .put("at", event.atMillis)
                    .put("kind", event.kind.name.lowercase())
                    .put("title", event.title)
                    .put("detail", event.detail)
                    .put("path", event.path.orEmpty())
                    .put("add", event.additions)
                    .put("del", event.deletions)
                    .put("cmd", event.command.orEmpty())
                    .put("exit", event.exitCode ?: -1),
            )
        }
        return JSONObject()
            .put("success", true)
            .put("task_id", id)
            .put("counts", engine.logCounts(id))
            .put("events", array)
    }

    private fun getRecentTasks(context: Context): JSONObject {
        val tasks = engine(context).recentTasks(10)
        val array = JSONArray()
        tasks.forEach { task ->
            array.put(
                JSONObject()
                    .put("task_id", task.id)
                    .put("name", task.projectName.orEmpty())
                    .put("state", task.state.name.lowercase())
                    .put("verified", task.verified)
                    .put("location", task.deliverableLocation.orEmpty())
                    .put("updated_at", task.updatedAtMillis),
            )
        }
        return JSONObject().put("success", true).put("count", tasks.size).put("tasks", array)
    }

    private fun pauseTask(context: Context, args: JSONObject): JSONObject {
        val id = args.optString("task_id").trim()
        val ok = if (id.isBlank()) latestTaskId(context)?.let { engine(context).pauseTask(it) } == true
        else engine(context).pauseTask(id)
        return result(ok, if (ok) "Paused." else "Nothing is running to pause.")
    }

    private fun resumeTask(context: Context, args: JSONObject): JSONObject {
        val id = args.optString("task_id").trim()
        val ok = if (id.isBlank()) latestTaskId(context)?.let { engine(context).resumeTask(it) } == true
        else engine(context).resumeTask(id)
        return result(ok, if (ok) "Resumed." else "I could not resume that task.")
    }

    private fun cancelTask(context: Context, args: JSONObject): JSONObject {
        val id = args.optString("task_id").trim()
        val ok = if (id.isBlank()) latestTaskId(context)?.let { engine(context).cancelTask(it) } == true
        else engine(context).cancelTask(id)
        if (ok && !engine(context).isTaskActive()) HarnessRunService.stop(context)
        return result(ok, if (ok) "Cancelled." else "Nothing to cancel.")
    }

    private fun sendFollowUp(context: Context, args: JSONObject): JSONObject {
        val text = args.optString("text").trim()
        if (text.isBlank()) return result(false, "I did not catch the extra instruction.")
        val id = args.optString("task_id").trim().ifBlank { latestTaskId(context) }
            ?: return result(false, "There is no task to add that to.")
        val ok = engine(context).sendFollowUp(id, text)
        return result(ok, if (ok) "Added to the task." else "That task cannot take more instructions.")
    }

    private fun listProjects(context: Context): JSONObject {
        val engine = engine(context)
        val projects = engine.listProjects()
        val snapshots = engine.projectSnapshots(100)
        val array = JSONArray()
        projects.take(50).forEach { project ->
            val snapshot = snapshots.firstOrNull { it.projectId == project.id }
                ?: snapshots.firstOrNull { it.name.equals(project.name, ignoreCase = true) }
            array.put(
                JSONObject()
                    .put("project_id", project.id)
                    .put("name", project.name)
                    .put("android", project.android)
                    .put("location", project.location)
                    .put("status", snapshot?.status.orEmpty())
                    .put("checkpoint", snapshot?.checkpoint.orEmpty())
                    .put("summary", snapshot?.summary.orEmpty())
                    .put("last_activity", snapshot?.lastActivityMillis ?: project.updatedAtMillis)
                    .put("steps", snapshot?.stepsDone ?: 0)
                    .put("files", snapshot?.fileTree?.size ?: 0)
                    .put("agent", snapshot?.agentType.orEmpty())
                    .put("provider", snapshot?.provider.orEmpty())
                    .put("model", snapshot?.model.orEmpty())
                    .put("can_resume", snapshot?.canResume ?: false)
                    .put("native_resume", snapshot?.nativeResume ?: false)
                    .put("preview_url", snapshot?.previewUrl.orEmpty())
                    .put("preview_port", snapshot?.previewPort ?: 0),
            )
        }
        return JSONObject()
            .put("success", true)
            .put("count", projects.size)
            .put("projects", array)
            .put("note", "Use resume_project or get_project_status with a project name or id to act on one.")
    }

    /**
     * Continues a saved project. `ambiguous=true` means more than one project could match:
     * the model must ask the single short `question` instead of guessing.
     */
    private fun resumeProject(context: Context, args: JSONObject): JSONObject {
        val query = args.optString("project").trim().ifBlank { args.optString("task_id").trim() }
        val outcome = engine(context).resumeProject(query)
        return JSONObject()
            .put("success", outcome.success)
            .put("ambiguous", outcome.ambiguous)
            .apply {
                if (outcome.ambiguous) {
                    put("question", outcome.question)
                    put("candidates", JSONArray(outcome.candidates))
                }
            }
            .put("mode", outcome.mode)
            .put("project_id", outcome.projectId.orEmpty())
            .put("task_id", outcome.taskId.orEmpty())
            .put("name", outcome.name)
            .put(
                "message",
                if (outcome.ambiguous) "Ask the user this ONE short question, then call resume_project again: ${outcome.question}"
                else outcome.message,
            )
    }

    /** Persisted session state for one project (status, checkpoint, todo, files, preview). */
    private fun getProjectStatus(context: Context, args: JSONObject): JSONObject {
        val query = args.optString("project").trim().ifBlank { args.optString("task_id").trim() }
        return when (val lookup = engine(context).projectStatus(query)) {
            is ProjectLookup.Found -> projectSnapshotJson(lookup.project)
                .put("success", true)
                .put(
                    "message",
                    "${lookup.project.name}: ${lookup.project.status}, ${lookup.project.stepsDone} steps, " +
                        "${lookup.project.fileTree.size} files. ${lookup.project.checkpoint}",
                )
            is ProjectLookup.Ambiguous -> JSONObject()
                .put("success", false)
                .put("ambiguous", true)
                .put("question", ambiguityQuestion(lookup.candidates))
                .put("candidates", JSONArray(lookup.candidates.take(5).map { it.name }))
                .put("message", "More than one project matches. Ask the user which project.")
            ProjectLookup.None -> result(false, "No saved project yet. Nothing has been built on this phone.")
        }
    }

    private fun projectSnapshotJson(project: HarnessProjectSnapshot): JSONObject = JSONObject()
        .put("project_id", project.projectId)
        .put("task_id", project.taskId)
        .put("name", project.name)
        .put("type", project.projectType)
        .put("status", project.status)
        .put("checkpoint", project.checkpoint)
        .put("summary", project.summary)
        .put("workspace_path", project.workspacePath)
        .put("project_root", project.projectRoot)
        .put("agent", project.agentType)
        .put("agent_session_id", project.agentSessionId)
        .put("provider", project.provider)
        .put("model", project.model)
        .put("tier", project.tier.orEmpty())
        .put("milestones", JSONArray(project.milestones.takeLast(12)))
        .put("file_tree", JSONArray(project.fileTree.take(60)))
        .put("file_tree_count", project.fileTree.size)
        .put("files", JSONArray(project.files.takeLast(20)))
        .put("preview_url", project.previewUrl.orEmpty())
        .put("preview_port", project.previewPort ?: 0)
        .put("steps_done", project.stepsDone)
        .put("last_activity", project.lastActivityMillis)
        .put("can_resume", project.canResume)
        .put("native_resume", project.nativeResume)

    private fun ambiguityQuestion(candidates: List<HarnessProjectSnapshot>): String {
        val names = candidates.take(3).joinToString(" ya ") { it.name.ifBlank { it.taskId } }
        return "Kaunsa project — $names?"
    }

    /**
     * Full persistent project memory (original request, features, decisions, checkpoint,
     * files, output, preview, last error). This is the only source for "pichla project
     * kahan tak bana"; it survives process death.
     */
    private fun getProjectMemory(context: Context, args: JSONObject): JSONObject {
        val engine = engine(context)
        val query = args.optString("project").trim().ifBlank { args.optString("task_id").trim() }
        val memory = when {
            query.isBlank() -> engine.latestProjectMemory()
            else -> engine.projectMemory(query)
                ?: engine.recentProjectMemories(100).firstOrNull { it.name.equals(query, ignoreCase = true) }
        } ?: return JSONObject()
            .put("success", false)
            .put("message", "No project memory yet. Nothing has been built on this phone.")

        val decisions = JSONArray().apply { memory.decisions.takeLast(10).forEach { put(it) } }
        val files = JSONArray().apply { memory.files.takeLast(20).forEach { put(it) } }
        return JSONObject()
            .put("success", true)
            .put("project_id", memory.id)
            .put("name", memory.name)
            .put("type", memory.projectType)
            .put("original_request", memory.originalRequest.take(1_500))
            .put("features", JSONArray(memory.features))
            .put("decisions", decisions)
            .put("status", memory.status)
            .put("checkpoint", memory.checkpoint)
            .put("steps_done", memory.stepsDone)
            .put("files_count", memory.files.size)
            .put("files", files)
            .put("output_location", memory.outputLocation.orEmpty())
            .put("preview_url", memory.previewUrl.orEmpty())
            .put("last_error", memory.lastError.orEmpty())
            .put("updated_at", memory.updatedAtMillis)
            .put("message", "${memory.name}: ${memory.status}, ${memory.stepsDone} steps, ${memory.files.size} files. ${memory.checkpoint}")
    }

    private fun openPreviewTool(context: Context, args: JSONObject): JSONObject {
        val engine = engine(context)
        val id = args.optString("task_id").trim().ifBlank { latestTaskId(context) }
        val url = args.optString("url").trim().ifBlank {
            id?.let { engine.previewUrlFor(it) }.orEmpty()
        }
        if (url.isBlank()) {
            return JSONObject()
                .put("success", false)
                .put("message", "No preview URL yet. It appears once the web project or its dev server is ready.")
        }
        val opened = openPreview(context, url)
        return JSONObject()
            .put("success", opened)
            .put("url", url)
            .put("message", if (opened) "Preview khol diya." else "I could not open the preview.")
    }

    private fun getProjectLocation(context: Context, args: JSONObject): JSONObject {
        val id = args.optString("project_id").trim()
        val location = engine(context).projectLocation(id)
            ?: return result(false, "I could not find that project.")
        return JSONObject().put("success", true).put("location", location)
    }

    private suspend fun runTerminalCommand(context: Context, args: JSONObject): JSONObject {
        val command = args.optString("cmd").trim()
        if (command.isBlank()) return result(false, "No command given.")
        val engine = engine(context)
        val risk = HarnessEngine.assessCommand(command)
        when (risk) {
            HarnessCommandRisk.BLOCK -> return JSONObject()
                .put("success", false)
                .put("blocked", true)
                .put("message", "That command is blocked by the sandbox safety policy. Only the Linux sandbox is allowed.")
            HarnessCommandRisk.CONFIRM -> {
                val confirmed = args.optBoolean("confirmed", false)
                if (!confirmed) {
                    return JSONObject()
                        .put("success", false)
                        .put("needs_confirmation", true)
                        .put("command", command)
                        .put("message", "This command can delete or change data. Ask the user to confirm out loud before running it.")
                }
            }
            HarnessCommandRisk.SAFE -> Unit
        }
        val result = engine.runCommand(command, timeoutMs = 18_000L, confirmed = true)
        return JSONObject()
            .put("success", result.ok)
            .put("exit_code", result.exitCode)
            .put("timed_out", result.timedOut)
            .put("rejected", result.rejected)
            .put("output", result.output.takeLast(4_000))
            .put(
                "message",
                when {
                    result.rejected -> result.rejectionReason ?: "Command rejected."
                    result.timedOut -> "Command timed out. It may still be running in the sandbox."
                    else -> "Exit code ${result.exitCode}."
                },
            )
    }

    private fun latestTaskId(context: Context): String? = engine(context).allTasks().lastOrNull()?.id

    // ── §8 App knowledge: Jarvis always knows its own state ──────────────────

    /**
     * Everything Jarvis needs to answer "kya installed hai / kaunsa model chal raha
     * hai / kya fail hua" truthfully, instead of guessing. API keys are never included.
     */
    private fun getAppState(context: Context): JSONObject {
        val engine = engine(context)
        val prefs = (context.applicationContext as? JarvisApp)?.preferences
        val agents = runCatching { engine.installedAgentVersions() }.getOrDefault(emptyMap())
        val provider = runCatching { engine.providerKind() }.getOrNull()
        val keyCount = provider?.let { kind -> runCatching { engine.storedApiKeys(kind).size }.getOrDefault(0) } ?: 0
        val resumable = runCatching { engine.resumableTasks() }.getOrDefault(emptyList())
        val live = runCatching { engine.liveStatus.value }.getOrNull()
        return JSONObject()
            .put("success", true)
            .put("engine_installed", engine.isInstalled())
            .put("engine_supported", engine.isSupportedDevice())
            .put("installed_agents", JSONArray(agents.keys.map { it.stableId }))
            .put("provider", provider?.name?.lowercase().orEmpty())
            .put("provider_key_count", keyCount)
            .put("provider_key_configured", keyCount > 0)
            .put("persona", prefs?.personality.orEmpty())
            .put("voice", prefs?.voice.orEmpty())
            .put("language", prefs?.language.orEmpty())
            .put("wake_sensitivity", prefs?.wakeSensitivity.orEmpty())
            .put("wake_words", prefs?.enabledWakeWords?.joinToString(",")?.lowercase().orEmpty())
            .put("interrupt_sensitivity", prefs?.interruptSensitivity.orEmpty())
            .put("owner_voice_enabled", prefs?.isOwnerVoiceEnabled ?: false)
            .put("owner_voice_enrolled", prefs?.hasOwnerVoiceProfile() ?: false)
            .put("active_task", live?.projectName.orEmpty())
            .put("active_task_state", live?.state.orEmpty())
            .put("resumable_task_count", resumable.size)
            .put("projects", runCatching { engine.listProjects().size }.getOrDefault(0))
    }

    private fun listProviders(context: Context): JSONObject {
        val engine = engine(context)
        val array = JSONArray()
        ProviderKind.values().forEach { kind ->
            val keys = runCatching { engine.storedApiKeys(kind) }.getOrDefault(emptyList())
            array.put(
                JSONObject()
                    .put("id", kind.name.lowercase())
                    .put("title", kind.title)
                    .put("protocol", kind.protocol.name)
                    .put("base_url", kind.defaultBaseUrl)
                    .put("default_model", kind.defaultModel)
                    .put("keys", keys.size)
                    .put("experimental", kind.experimental),
            )
        }
        // Full catalog (including providers not yet wired to the engine gateway).
        val catalog = runCatching { ProviderCatalog.load(context) }.getOrDefault(emptyList())
        val catalogArray = JSONArray()
        catalog.forEach { p ->
            catalogArray.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("protocol", p.protocol)
                    .put("base_url", p.baseUrl)
                    .put("local", p.isLocal),
            )
        }
        return JSONObject()
            .put("success", true)
            .put("count", ProviderKind.values().size)
            .put("providers", array)
            .put("catalog_count", catalog.size)
            .put("catalog", catalogArray)
    }

    private fun listAgents(context: Context): JSONObject {
        val engine = engine(context)
        val statuses = runCatching { engine.agentStatusNow() }.getOrDefault(emptyList())
        val array = JSONArray()
        AgentKind.values().forEach { kind ->
            val status = statuses.firstOrNull { it.kind == kind }
            val providers = JSONArray()
            com.jarvis.harness.model.providersForAgent(kind).forEach { providers.put(it.name) }
            array.put(
                JSONObject()
                    .put("id", kind.stableId)
                    .put("title", kind.title)
                    .put("subtitle", kind.subtitle)
                    .put("installed", status?.installed ?: false)
                    .put("version", status?.version.orEmpty())
                    .put("update_available", status?.latestVersion != null)
                    .put("latest_version", status?.latestVersion ?: JSONObject.NULL)
                    .put("busy", status?.busy == true)
                    .put("providers", providers)
                    .put("can_remove", status?.installed == true),
            )
        }
        return JSONObject()
            .put("success", true)
            .put("count", AgentKind.values().size)
            .put("agents", array)
            .put("note", "Each agent installs, updates and removes independently from Settings (or say 'install karo'). Nothing auto-installs.")
    }

    /**
     * Applies a whitelisted, safe setting. Security-sensitive settings require a spoken
     * confirmation first (the model must re-call with confirmed=true after the user says yes).
     */
    private fun setSetting(context: Context, args: JSONObject): JSONObject {
        val prefs = (context.applicationContext as? JarvisApp)?.preferences
            ?: return result(false, "Settings are unavailable right now.")
        val key = args.optString("key").trim().lowercase()
        val value = args.optString("value").trim()
        if (key.isBlank() || value.isBlank()) return result(false, "Mujhe setting ka naam aur value dono chahiye.")
        if (key in SENSITIVE_SETTINGS && !args.optBoolean("confirmed", false)) {
            return JSONObject()
                .put("success", false)
                .put("needs_confirmation", true)
                .put("message", "'$key' security se juda hai. User se zubaani confirm karwao, phir confirmed=true ke saath dobara call karo.")
        }
        val applied = when (key) {
            "persona", "personality" -> { prefs.personality = value; true }
            "voice" -> { prefs.voice = value; true }
            "language" -> { prefs.language = value; true }
            "wake_sensitivity" -> { prefs.wakeSensitivity = value; true }
            "wake_words" -> {
                val wanted = value.lowercase()
                    .split(',', ' ', '/')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                if (wanted.isNotEmpty() && wanted.all { it in setOf("jarvis", "isha", "virus") }) {
                    prefs.isWakeWordJarvisEnabled = "jarvis" in wanted
                    prefs.isWakeWordIshaEnabled = "isha" in wanted
                    prefs.isWakeWordVirusEnabled = "virus" in wanted
                    true
                } else {
                    return result(false, "Wake words sirf jarvis, isha aur virus me se ho sakte hain, jaise 'jarvis,isha'.")
                }
            }
            "interrupt_sensitivity" -> { prefs.interruptSensitivity = value; true }
            "silent_mode" -> { prefs.isSilentExecutionMode = value.toBooleanStrictOrNull() ?: prefs.isSilentExecutionMode; true }
            "noise_filter" -> { prefs.isNoiseFilterEnabled = value.toBooleanStrictOrNull() ?: prefs.isNoiseFilterEnabled; true }
            "owner_voice" -> { prefs.isOwnerVoiceEnabled = value.toBooleanStrictOrNull() ?: prefs.isOwnerVoiceEnabled; true }
            "owner_voice_threshold" -> { prefs.ownerVoiceThreshold = value.toFloatOrNull() ?: prefs.ownerVoiceThreshold; true }
            else -> false
        }
        return result(applied, if (applied) "Set $key to $value." else "Mere paas '$key' naam ki koi safe setting nahi hai.")
    }

    private fun openSettings(context: Context, args: JSONObject): JSONObject {
        val category = args.optString("category").trim()
        return runCatching {
            context.startActivity(
                Intent(context, com.jarvis.assistant.ui.home.MainActivity::class.java)
                    .putExtra(com.jarvis.assistant.ui.home.MainActivity.EXTRA_SETTINGS_CATEGORY, category)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            JSONObject()
                .put("success", true)
                .put("category", category)
                .put("message", if (category.isBlank()) "Settings khol diya." else "Settings ka '$category' section khol diya.")
        }.getOrElse { result(false, "Settings nahi khol paya: ${it.message}") }
    }

    private fun result(success: Boolean, message: String): JSONObject =
        JSONObject().put("success", success).put("message", message)

    private fun parseProjectType(raw: String): HarnessProjectType =
        when (raw.trim().lowercase()) {
            "android", "android app", "apk", "kotlin", "java" -> HarnessProjectType.ANDROID
            "web", "website", "web app", "react", "next", "html", "node" -> HarnessProjectType.WEB
            "python", "script", "py", "flask", "fastapi" -> HarnessProjectType.PYTHON
            "c", "cpp", "c++", "c/c++", "cmake" -> HarnessProjectType.CPP
            "php", "laravel" -> HarnessProjectType.PHP
            else -> HarnessProjectType.GENERAL
        }


    private val TOOL_NAMES = setOf(
        "install_coding_engine",
        "coding_engine_status",
        "start_build_task",
        "get_task_status",
        "get_task_log",
        "get_recent_tasks",
        "pause_task",
        "resume_task",
        "cancel_task",
        "send_followup",
        "list_projects",
        "resume_project",
        "get_project_status",
        "get_project_memory",
        "get_project_location",
        "open_preview",
        "run_terminal_command",
        "get_app_state",
        "list_providers",
        "list_agents",
        "set_setting",
        "open_settings",
    )

    /** Settings that always need an explicit spoken confirmation before they change. */
    private val SENSITIVE_SETTINGS = setOf("owner_voice", "owner_voice_threshold")
}
