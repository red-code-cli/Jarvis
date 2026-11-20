package com.jarvis.harness

import android.app.Application
import android.content.Context
import com.jarvis.harness.data.ApiKeyInfo
import com.jarvis.harness.model.AndroidTemplate
import com.jarvis.harness.model.AgentKind
import com.jarvis.harness.model.DevStack
import com.jarvis.harness.model.Project
import com.jarvis.harness.model.ProjectType
import com.jarvis.harness.model.ProviderKind
import com.jarvis.harness.model.ProviderProfile
import com.jarvis.harness.network.ConnectionValidation
import com.jarvis.harness.network.DiscoveredModel
import com.jarvis.harness.network.ModelDiscoveryResult
import com.jarvis.harness.runtime.InstalledRuntime
import com.jarvis.harness.runtime.RuntimeInstaller
import com.jarvis.harness.runtime.RuntimeSetupController
import com.jarvis.harness.runtime.RuntimeSetupStatus
import com.jarvis.harness.runtime.supportsArm64Runtime
import com.jarvis.harness.policy.AgentPolicyConfig
import com.jarvis.harness.policy.AgentPromptPolicy
import com.jarvis.harness.policy.CapabilityProbeCache
import com.jarvis.harness.policy.FeedText
import com.jarvis.harness.policy.HeadlessBrowserProbe
import com.jarvis.harness.policy.LoopWatchdog
import com.jarvis.harness.policy.StaticPreviewServer
import com.jarvis.harness.policy.StepCategory
import com.jarvis.harness.policy.StepClassifier
import com.jarvis.harness.policy.TaskClassifier
import com.jarvis.harness.policy.TaskPolicyState
import com.jarvis.harness.policy.TaskTier
import com.jarvis.harness.policy.VisualTestResult
import com.jarvis.harness.policy.WatchdogDecision
import com.jarvis.harness.policy.WorkspaceFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

// ─────────────────────────────────────────────────────────────────────────────
// Public types
// ─────────────────────────────────────────────────────────────────────────────

enum class HarnessProjectType {
    GENERAL,
    WEB,
    PYTHON,
    ANDROID,
    CPP,
    PHP,
    ;

    internal val stack: DevStack?
        get() = when (this) {
            WEB -> DevStack.WEB
            PYTHON -> DevStack.PYTHON
            ANDROID -> DevStack.ANDROID
            CPP -> DevStack.CPP
            PHP -> DevStack.PHP
            GENERAL -> null
        }
}

data class HarnessInstallState(
    val status: RuntimeSetupStatus = RuntimeSetupStatus.IDLE,
    val message: String = "",
    val progress: Float = 0f,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val indeterminate: Boolean = false,
    val errorMessage: String? = null,
    val logs: List<String> = emptyList(),
) {
    val isReady: Boolean get() = status == RuntimeSetupStatus.COMPLETE
}

data class HarnessProjectInfo(
    val id: String,
    val name: String,
    val slug: String,
    val android: Boolean,
    val updatedAtMillis: Long,
    val location: String,
)

/**
 * Everything Jarvis needs to answer "wo project kahan tak bana / continue karo" from the
 * durable session record, without re-deriving anything from the UI.
 */
data class HarnessProjectSnapshot(
    val projectId: String,
    val taskId: String,
    val name: String,
    val projectType: String,
    val status: String,
    val checkpoint: String,
    val summary: String,
    val workspacePath: String,
    val projectRoot: String,
    val agentType: String,
    val agentSessionId: String,
    val provider: String,
    val model: String,
    val tier: String?,
    val milestones: List<String>,
    val fileTree: List<String>,
    val files: List<String>,
    val previewUrl: String?,
    val previewPort: Int?,
    val stepsDone: Int,
    val lastActivityMillis: Long,
    val canResume: Boolean,
    val nativeResume: Boolean,
)

/** Result of looking a project up by name/id. Ambiguous means "ask one question". */
sealed interface ProjectLookup {
    data class Found(val project: HarnessProjectSnapshot) : ProjectLookup
    data class Ambiguous(val candidates: List<HarnessProjectSnapshot>) : ProjectLookup
    object None : ProjectLookup
}

/** Result of [HarnessEngine.resumeProject]; [ambiguous] asks the user to choose. */
data class ProjectResumeOutcome(
    val success: Boolean,
    val ambiguous: Boolean = false,
    val question: String = "",
    val candidates: List<String> = emptyList(),
    val message: String = "",
    val projectId: String? = null,
    val taskId: String? = null,
    val name: String = "",
    val mode: String = "",
)

/** Per-agent install state for the Settings catalog. One row per built-in agent. */
data class HarnessAgentStatus(
    val kind: AgentKind,
    val installed: Boolean,
    val version: String,
    val latestVersion: String?,
    val busy: Boolean,
)

enum class HarnessTaskState {
    QUEUED,
    RUNNING,
    WAITING_RETRY,
    PAUSED,
    COMPLETED,
    /** Finished, but the supervisor had to stop a loop/budget overrun instead of a clean verify. */
    COMPLETED_WITH_WARNINGS,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

/** Everything needed to prove a task actually produced something. */
data class HarnessTaskProof(
    val files: List<String> = emptyList(),
    val buildTail: String? = null,
    val locations: List<String> = emptyList(),
)

data class HarnessTaskStatus(
    val id: String,
    val state: HarnessTaskState,
    val projectId: String?,
    val projectName: String?,
    val prompt: String,
    val projectType: HarnessProjectType,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastMessage: String?,
    val changedFiles: Int,
    val deliverableLocation: String?,
    val verified: Boolean,
    val runtimeReady: Boolean,
    val queued: Boolean,
    val queuePosition: Int,
    val attempts: Int = 0,
    val provider: String? = null,
    val model: String? = null,
    val httpStatus: Int? = null,
    val liveLine: String? = null,
    val steps: Int = 0,
    val files: Int = 0,
    val error: HarnessError? = null,
    val proof: HarnessTaskProof? = null,
    val tier: String = "",
    val tierReason: String = "",
    val budget: String = "",
    val visualTest: String = "",
)

data class HarnessCommandResult(
    val command: String,
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val rejected: Boolean = false,
    val rejectionReason: String? = null,
) {
    val ok: Boolean get() = !rejected && !timedOut && !cancelled && exitCode == 0
}

enum class HarnessCommandRisk { SAFE, CONFIRM, BLOCK }

/** One rendered line of the Home "Agent Live" feed. */
data class HarnessLiveLine(
    val atMillis: Long,
    val kind: String,
    val text: String,
    val isError: Boolean = false,
)

/** Home "Agent Live" status. Throttled; safe to render at 4-5 Hz. */
data class HarnessLiveStatus(
    val taskId: String? = null,
    val phase: Phase = Phase.IDLE,
    val line: String = "",
    val step: Int = 0,
    val files: Int = 0,
    val retryInSeconds: Int = 0,
    val errorSummary: String? = null,
    val updatedAtMillis: Long = 0L,
    val state: String = "",
    val projectName: String = "",
    val progress: Float = 0f,
    val queued: Int = 0,
    val feed: List<HarnessLiveLine> = emptyList(),
    val previewUrl: String? = null,
    val paused: Boolean = false,
    /** B2: complexity tier, why it was chosen, and its budget label. */
    val tier: String = "",
    val tierReason: String = "",
    val budget: String = "",
    /** B2.3: build/edit/verify/test step counts, rendered for the telemetry feed. */
    val breakdown: String = "",
    /** B3: human summary of a supervisor finalize, when the task ended with warnings. */
    val warnings: String? = null,
) {
    enum class Phase { IDLE, WORKING, WAITING_RETRY, ERROR, SUCCESS, PAUSED }
}

sealed interface HarnessEvent {
    val taskId: String

    data class Progress(override val taskId: String, val message: String, val fraction: Float? = null) : HarnessEvent
    data class Completed(
        override val taskId: String,
        val message: String,
        val deliverableLocation: String?,
        val changedFiles: Int,
    ) : HarnessEvent
    data class Failed(override val taskId: String, val message: String, val error: HarnessError? = null) : HarnessEvent
    data class Retrying(override val taskId: String, val inSeconds: Int, val error: HarnessError) : HarnessEvent
}

interface HarnessHost {
    suspend fun onTaskCompleted(status: HarnessTaskStatus) {}
    suspend fun onTaskFailed(status: HarnessTaskStatus, reason: String) {}
    suspend fun onTaskRetrying(status: HarnessTaskStatus, error: HarnessError, inSeconds: Int) {}
    /** True when a key switch happened; lets the host toast/announce it. */
    suspend fun onApiKeySwitched(from: ApiKeyInfo?, to: ApiKeyInfo) {}

    /** A dev/static server became reachable; the host can open it for the user. */
    suspend fun onPreviewStarted(status: HarnessTaskStatus?, url: String) {}

    /** The supervisor finalized a task that ended with warnings (B3.4). Host announces it. */
    suspend fun onTaskCompletedWithWarnings(status: HarnessTaskStatus, reason: String) {}
}

/**
 * UI-free facade over the Mobile Harness engine.
 *
 * Status answers come from a durable event log ([taskLog]), never from guesses.
 * A task is only reported COMPLETED after [verify] finds real output on disk.
 */
class HarnessEngine private constructor(private val application: Application) {

    private val viewModel = MainViewModel(application)
    private val installer = RuntimeInstaller(application)
    private val store = HarnessTaskStore(application)
    private val log = HarnessEventLog(application)
    private val projectMemoryStore = HarnessProjectMemoryStore(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Every supervisor policy number lives in one config object (B2/B3), never inline. */
    private val policyConfig = AgentPolicyConfig.DEFAULT
    private val capabilityProbe = CapabilityProbeCache()
    private val headlessProbe = HeadlessBrowserProbe(policyConfig.capability)

    /** Per-task policy state: tier, budget, probe verdict, watchdog and step breakdown. */
    private val taskPolicy = mutableMapOf<String, TaskPolicyState>()

    /** Tasks the supervisor is finalizing, until the stopped run settles. */
    private val supervisedFinalize = mutableMapOf<String, String>()
    private val finalizeInFlight = mutableSetOf<String>()
    private val supervisedCompleted = mutableSetOf<String>()

    /** Ground-truth file count per task, taken from the workspace walk (B1). */
    private val workspaceFileCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Static preview server used as the finalize fallback for a finished web project. */
    private var previewServer: StaticPreviewServer? = null
    private var previewServerRoot: String? = null

    private val tasks: MutableList<HarnessTaskRecord> = store.load()
    private val queue = ArrayDeque<String>()

    /** Sequence counters per task, so the log is strictly ordered. */
    private val seq = mutableMapOf<String, Long>()

    /**
     * In-memory counters rebuilt once per task (from the log) and then incremented,
     * so appending an event never re-reads the whole history.
     */
    private class Counters {
        var steps = 0
        var commands = 0
        var errors = 0
        var retries = 0
        val files = mutableSetOf<String>()
    }

    private val counters = mutableMapOf<String, Counters>()

    /** Last rendered feed lines per task, for the Home card and the log sheet. */
    private val feed = mutableMapOf<String, MutableList<HarnessLiveLine>>()

    private fun feedFor(taskId: String): MutableList<HarnessLiveLine> =
        feed.getOrPut(taskId) { mutableListOf() }

    private fun countersFor(taskId: String): Counters = counters.getOrPut(taskId) {
        val fresh = Counters()
        log.read(taskId, Int.MAX_VALUE / 2).forEach { event ->
            when (event.kind) {
                HarnessLogKind.STEP_DONE -> fresh.steps++
                HarnessLogKind.BASH_COMMAND -> fresh.commands++
                HarnessLogKind.ERROR -> fresh.errors++
                HarnessLogKind.RETRY -> fresh.retries++
                HarnessLogKind.FILE_WRITE, HarnessLogKind.FILE_EDIT -> event.path?.let(fresh.files::add)
                else -> Unit
            }
        }
        fresh
    }

    private val _events = MutableSharedFlow<HarnessEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<HarnessEvent> = _events

    private val _commandOutput = MutableSharedFlow<String>(extraBufferCapacity = 128)
    val commandOutput: SharedFlow<String> = _commandOutput

    private val _liveStatus = MutableStateFlow(HarnessLiveStatus())
    val liveStatus: StateFlow<HarnessLiveStatus> = _liveStatus.asStateFlow()

    @Volatile
    var host: HarnessHost? = null

    val installState: StateFlow<HarnessInstallState> = RuntimeSetupController.snapshot
        .map { snapshot ->
            HarnessInstallState(
                status = snapshot.status,
                message = snapshot.message,
                progress = snapshot.progress,
                downloadedBytes = snapshot.downloadedBytes,
                totalBytes = snapshot.totalBytes,
                indeterminate = snapshot.indeterminate,
                errorMessage = snapshot.errorMessage,
                logs = snapshot.logs,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, HarnessInstallState())

    private var monitorJob: Job? = null
    private var eventJob: Job? = null
    private var retryJob: Job? = null
    private var activeTaskId: String? = null
    private var wasRunning = false
    private var pendingFailure: String? = null
    private var lastLiveEmitMillis = 0L
    private val failedKeyIds = mutableSetOf<String>()

    /** Tasks explicitly asked to resume, so the queued run sends the resume prompt. */
    private val resumeRequested = mutableSetOf<String>()

    init {
        RuntimeSetupController.restore(application)
        tasks.filter { it.state == HarnessTaskState.QUEUED }.forEach { queue.addLast(it.id) }
        // Anything that was RUNNING when the process died is interrupted, not magically done.
        for (index in tasks.indices) {
            val record = tasks[index]
            if (record.state == HarnessTaskState.RUNNING) {
                tasks[index] = record.copy(
                    state = HarnessTaskState.INTERRUPTED,
                    updatedAtMillis = System.currentTimeMillis(),
                    liveLine = null,
                    lastMessage = "Interrupted (app process stopped). Resume to continue.",
                )
            }
        }
        persist()
        tasks.forEach { task -> seq[task.id] = (log.read(task.id, 1).lastOrNull()?.seq ?: 0L) }
        observeRuntime()
        observeCompletion()
        restoreCard()
    }

    /** Rebuild the Home card's feed from the durable log after a process restart. */
    private fun restoreCard() {
        val record = tasks.filter {
            it.state == HarnessTaskState.PAUSED ||
                it.state == HarnessTaskState.INTERRUPTED ||
                it.state == HarnessTaskState.WAITING_RETRY ||
                it.state == HarnessTaskState.FAILED
        }.maxByOrNull { it.updatedAtMillis } ?: return
        feedFor(record.id).clear()
        log.read(record.id, 40).forEach { event ->
            feedFor(record.id).add(
                HarnessLiveLine(
                    atMillis = event.atMillis,
                    kind = event.kind.name,
                    // FeedText.compose dedupes (no "Wrote index.htmlindex.html") and truncates
                    // on a word boundary instead of mid-token.
                    text = FeedText.compose(event.title, event.detail),
                    isError = event.kind == HarnessLogKind.ERROR,
                ),
            )
        }
        val phase = when (record.state) {
            HarnessTaskState.PAUSED -> HarnessLiveStatus.Phase.PAUSED
            HarnessTaskState.INTERRUPTED -> HarnessLiveStatus.Phase.PAUSED
            HarnessTaskState.WAITING_RETRY -> HarnessLiveStatus.Phase.WAITING_RETRY
            else -> HarnessLiveStatus.Phase.ERROR
        }
        policyFor(record)
        val memory = projectMemoryStore.get(record.id)
        setLive(
            HarnessLiveStatus(
                taskId = record.id,
                phase = phase,
                line = record.lastMessage ?: record.state.name.lowercase(),
                paused = record.state != HarnessTaskState.WAITING_RETRY,
                errorSummary = record.errorHeadline,
                tier = memory?.tier.orEmpty(),
                tierReason = memory?.tierReason.orEmpty(),
                budget = memory?.budget.orEmpty(),
            ),
        )
    }

    // ── Installation ─────────────────────────────────────────────────────────

    fun isSupportedDevice(): Boolean = runCatching {
        supportsArm64Runtime(android.os.Build.SUPPORTED_ABIS, System.getProperty("os.arch").orEmpty())
    }.getOrDefault(false)

    fun isInstalled(): Boolean = runCatching { installer.isInstalled() }.getOrDefault(false)

    fun installedAgentVersions(): Map<AgentKind, String> =
        runCatching { installer.installedAgentVersions() }.getOrDefault(emptyMap())

    fun installedStacks(): Set<DevStack> = runCatching { installer.installedStacks() }.getOrDefault(emptySet())

    fun install(projectTypes: Set<HarnessProjectType> = DEFAULT_TOOLCHAINS) {
        if (!isInstalled()) viewModel.selectAgent(AgentKind.DEEPSEEK_HARNESS)
        projectTypes.mapNotNull { it.stack }
            .filterNot { it in viewModel.state.value.selectedDevStacks }
            .forEach { viewModel.toggleDevStack(it) }
        viewModel.startRuntimeSetup()
    }

    fun retryInstall() {
        if (isInstalled()) viewModel.retryStartup() else viewModel.startRuntimeSetup()
    }

    fun installAgent(kind: AgentKind) {
        viewModel.selectAgent(kind)
        viewModel.installAgent(kind)
    }

    fun updateAll() = viewModel.updateAllPackages()

    fun checkForUpdates() = viewModel.checkAgentUpdates()

    /** Selects the active agent; Settings uses this so the Provider picker targets the tapped agent. */
    fun selectAgent(kind: AgentKind) = viewModel.selectAgent(kind)

    /** Updates one installed agent to [HarnessAgentStatus.latestVersion]. */
    fun updateAgent(kind: AgentKind) = viewModel.updateAgent(kind)

    /** Removes one installed agent's runtime files. Projects and chats are untouched. */
    fun removeAgent(kind: AgentKind) = viewModel.removeAgent(kind)

    fun isAgentInstalled(kind: AgentKind): Boolean =
        runCatching { installer.isAgentInstalled(kind) }.getOrDefault(false)

    /** Live install state for every built-in agent, for the Settings catalog. */
    val agentStatuses: StateFlow<List<HarnessAgentStatus>> = viewModel.state
        .map(::agentStatusesOf)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Snapshot of [agentStatuses] without waiting for a flow emission (voice tool path). */
    fun agentStatusNow(): List<HarnessAgentStatus> = agentStatusesOf(viewModel.state.value)

    private fun agentStatusesOf(state: AppUiState): List<HarnessAgentStatus> = AgentKind.entries.map { kind ->
        HarnessAgentStatus(
            kind = kind,
            installed = state.installedAgentVersions.containsKey(kind),
            version = state.installedAgentVersions[kind].orEmpty(),
            latestVersion = state.agentUpdates[kind]?.latestVersion,
            busy = state.agentInstalling == kind || state.agentUpdating == kind || state.agentRemoving == kind,
        )
    }

    fun installStack(type: HarnessProjectType) {
        type.stack?.let { viewModel.installDevStack(it) }
    }

    // ── Provider / API configuration ─────────────────────────────────────────

    private val apiKeyVault by lazy { com.jarvis.harness.data.ApiKeyVault(application) }

    fun configureProvider(
        kind: ProviderKind,
        baseUrl: String,
        model: String,
        apiKey: String,
        dshApi: String? = null,
    ) {
        val profile = ProviderProfile(
            kind = kind,
            baseUrl = baseUrl.ifBlank { kind.defaultBaseUrl },
            model = model.ifBlank { kind.defaultModel },
            dshApi = dshApi ?: com.jarvis.harness.model.defaultDshApiForProvider(kind),
        )
        viewModel.updateProvider(profile, apiKey)
    }

    fun providerKind(): ProviderKind = viewModel.state.value.provider.kind

    fun providerName(): String = providerKind().title

    /** Model the active provider profile currently points at (for the task state line). */
    fun providerModel(): String = runCatching { viewModel.state.value.provider.model }.getOrDefault("")

    /**
     * Records a gateway failover as a first-class event: it lands in the task's event log and
     * shows as one short line on the Agent Live card, and the task state's provider/model is
     * updated to the target that actually answered.
     */
    fun recordGatewaySwitch(
        fromLabel: String,
        toLabel: String,
        reason: String,
        providerName: String = "",
        model: String = "",
    ) {
        com.jarvis.harness.gateway.LocalGatewayRegistry.lastSwitchLine = toLabel
        val id = activeTaskId ?: tasks.maxByOrNull { it.updatedAtMillis }?.id
        if (id != null) {
            note(
                id,
                HarnessLogKind.RETRY,
                "Switched: ${toLabel.take(80)}",
                "${fromLabel.take(80)} → ${reason.take(120)}",
            )
            feedFor(id).add(
                HarnessLiveLine(System.currentTimeMillis(), "SWITCH", "Switched: ${toLabel.take(120)}", false),
            )
            setLive(
                HarnessLiveStatus(
                    taskId = id,
                    phase = HarnessLiveStatus.Phase.WORKING,
                    line = "Switched: ${toLabel.take(60)}",
                ),
            )
        }
        if (providerName.isNotBlank() || model.isNotBlank()) {
            id?.let { taskId ->
                update(taskId, null, provider = providerName.ifBlank { null }, model = model.ifBlank { null })
            }
        }
    }

    /** Marks the current task as actively using [provider]/[model] (they answered successfully). */
    fun noteActiveProvider(provider: String, model: String) {
        val id = activeTaskId ?: return
        update(id, null, provider = provider.ifBlank { null }, model = model.ifBlank { null })
    }

    /** Run id of the active task, if any, so a module-level gateway can attribute switches. */
    fun activeTask(): String? = activeTaskId

    fun storedApiKey(kind: ProviderKind): String = runCatching { apiKeyVault.get(kind.name).orEmpty() }.getOrDefault("")

    fun storedApiKeys(kind: ProviderKind): List<ApiKeyInfo> = runCatching { apiKeyVault.list(kind.name) }.getOrDefault(emptyList())

    fun addApiKey(kind: ProviderKind, name: String, secret: String): List<ApiKeyInfo> =
        viewModel.addApiKey(kind, name, secret)

    fun activateApiKey(kind: ProviderKind, keyId: String): List<ApiKeyInfo> =
        viewModel.activateApiKey(kind, keyId)

    fun removeApiKey(kind: ProviderKind, keyId: String): List<ApiKeyInfo> =
        viewModel.removeApiKey(kind, keyId)

    /**
     * "Test connection". Returns the raw validation verdict; callers should render
     * [ConnectionValidation.Failure.label] + provider message instead of a generic line.
     */
    suspend fun testConnection(
        kind: ProviderKind,
        baseUrl: String,
        model: String,
        apiKey: String,
    ): ConnectionValidation {
        val profile = ProviderProfile(
            kind = kind,
            baseUrl = baseUrl.ifBlank { kind.defaultBaseUrl },
            model = model.ifBlank { kind.defaultModel },
        )
        val models: List<DiscoveredModel> =
            (runCatching { viewModel.discoverModels(profile, apiKey) }.getOrNull() as? ModelDiscoveryResult.Success)
                ?.models
                .orEmpty()
        return viewModel.validateProvider(profile, apiKey, models)
    }

    // ── Tasks ────────────────────────────────────────────────────────────────

    fun startTask(
        prompt: String,
        projectType: HarnessProjectType = HarnessProjectType.GENERAL,
        projectName: String? = null,
    ): String {
        val classification = TaskClassifier.classify(prompt.trim(), projectType.name.lowercase(), policyConfig)
        val budget = policyConfig.budgetFor(classification.tier)
        val record = HarnessTaskRecord(
            id = store.newId(),
            projectName = projectName?.takeIf(String::isNotBlank) ?: deriveProjectName(prompt, projectType),
            prompt = prompt.trim(),
            projectType = projectType,
            state = HarnessTaskState.QUEUED,
            projectId = null,
            createdAtMillis = System.currentTimeMillis(),
            updatedAtMillis = System.currentTimeMillis(),
            lastMessage = "Queued.",
            changedFiles = 0,
            deliverableLocation = null,
            runtimeReady = isInstalled(),
            provider = providerName(),
            model = providerModel(),
            tier = classification.tier.name,
            tierReason = classification.reason,
            budgetLabel = budget.label,
        )
        taskPolicy[record.id] = TaskPolicyState(
            tier = classification.tier,
            reason = classification.reason,
            budget = budget,
            visualTest = VisualTestResult.unavailable("not probed yet"),
            watchdog = LoopWatchdog(budget, policyConfig.watchdog),
        )
        tasks += record
        seq[record.id] = 0L
        log.append(record.id, nextSeq(record.id), HarnessLogKind.STATE, "Queued", prompt.take(200))
        log.append(
            record.id, nextSeq(record.id), HarnessLogKind.THINKING,
            "Tier ${classification.tier.name} · ${budget.label}", classification.reason,
        )
        projectMemoryStore.update(
            id = record.id,
            name = record.projectName,
            projectType = record.projectType.name.lowercase(),
            originalRequest = record.prompt,
            finalPrompt = record.prompt,
            status = "queued",
            checkpoint = "Queued",
agentType = currentAgentKind().stableId,
            provider = record.provider,
            model = record.model,
            addMilestone = "Queued",
            tier = classification.tier.name,
            tierReason = classification.reason,
            budget = budget.label,
        )
        feedFor(record.id).add(HarnessLiveLine(System.currentTimeMillis(), "STATE", "Queued", false))
        feedFor(record.id).add(
            HarnessLiveLine(System.currentTimeMillis(), "TIER", "Tier ${classification.tier.name} · ${budget.label}", false),
        )
        queue.addLast(record.id)
        persist()
        pump()
        return record.id
    }

    fun taskStatus(id: String): HarnessTaskStatus? {
        val record = tasks.firstOrNull { it.id == id } ?: return null
        val position = queue.indexOf(id)
        return record.toStatus(position + 1, queued = position >= 0 && record.state == HarnessTaskState.QUEUED)
    }

    fun allTasks(): List<HarnessTaskStatus> = tasks.mapIndexed { index, record ->
        record.toStatus(index + 1, queued = queue.contains(record.id))
    }

    fun recentTasks(limit: Int = 10): List<HarnessTaskStatus> =
        tasks.sortedByDescending { it.updatedAtMillis }.take(limit).map { it.toStatus(0, false) }

    fun interruptedTasks(): List<HarnessTaskStatus> =
        tasks.filter { it.state == HarnessTaskState.INTERRUPTED }
            .map { it.toStatus(0, false) }

    /** Interrupted or paused work that can be resumed from its checkpoint. */
    fun resumableTasks(): List<HarnessTaskStatus> =
        tasks.filter { it.state == HarnessTaskState.INTERRUPTED || it.state == HarnessTaskState.PAUSED }
            .sortedByDescending { it.updatedAtMillis }
            .map { it.toStatus(0, false) }

    /** True while the engine is actually executing something in this process. */
    fun isTaskActive(): Boolean = activeTaskId != null || queue.isNotEmpty()

    /**
     * Called once on app start: a task found RUNNING was already converted to
     * INTERRUPTED by init, so re-queue it to resume from its checkpoint.
     */
    fun resumeInterrupted(): List<HarnessTaskStatus> {
        val interrupted = tasks.filter { it.state == HarnessTaskState.INTERRUPTED }
        if (interrupted.isEmpty()) return emptyList()
        interrupted.forEach { record ->
            queue.remove(record.id)
            queue.addLast(record.id)
            resumeRequested += record.id
            update(record.id, HarnessTaskState.QUEUED, message = "Resuming after restart.")
            log.append(record.id, nextSeq(record.id), HarnessLogKind.RESUMED, "Resume after restart")
            projectMemoryStore.update(record.id, status = "queued", checkpoint = "Resumed after restart")
            feedFor(record.id).add(
                HarnessLiveLine(System.currentTimeMillis(), "RESUMED", "Resuming after restart", false),
            )
        }
        pump()
        return interrupted.map { it.toStatus(0, false) }
    }

    // ── Project memory (independent from Yaadein) ────────────────────────────

    fun projectMemory(id: String): HarnessProjectMemory? = projectMemoryStore.get(id)

    fun recentProjectMemories(limit: Int = 10): List<HarnessProjectMemory> =
        projectMemoryStore.list().take(limit)

    fun projectMemorySummaryBlock(limit: Int = 5): String = projectMemoryStore.summaryBlock(limit)

    fun latestProjectMemory(): HarnessProjectMemory? = projectMemoryStore.list().firstOrNull()

    fun previewUrlFor(taskId: String): String? =
        projectMemoryStore.get(taskId)?.previewUrl ?: activePreviewUrl()

    // ── Project sessions / resume ────────────────────────────────────────────

    /** Agent currently selected; decides whether a native session can be resumed. */
    fun currentAgentKind(): AgentKind =
        runCatching { viewModel.state.value.agentKind }.getOrDefault(AgentKind.ANTIGRAVITY)

    /** Only the projects that still need work, for the Gemini Live system prompt. */
    fun unfinishedProjectsBlock(limit: Int = 5): String = projectMemoryStore.unfinishedBlock(limit)

    /** Recent project sessions, deduped per workspace project, newest first. */
    fun projectSnapshots(limit: Int = 20): List<HarnessProjectSnapshot> =
        projectMemoryStore.list()
            .sortedByDescending { it.updatedAtMillis }
            .distinctBy { it.projectId.ifBlank { it.displayName().lowercase() } }
            .take(limit)
            .map(::snapshotOf)

    /** Look up one project for get_project_status. Ambiguous means ask one question. */
    fun projectStatus(query: String?): ProjectLookup =
        when (val result = ProjectResolver.resolve(query.orEmpty(), projectMemoryStore.list(), System.currentTimeMillis())) {
            is ProjectResolver.Result.Match -> ProjectLookup.Found(snapshotOf(result.memory))
            is ProjectResolver.Result.Ambiguous -> ProjectLookup.Ambiguous(result.candidates.map(::snapshotOf))
            ProjectResolver.Result.None -> ProjectLookup.None
        }

    /**
     * Continue a saved project. A paused/interrupted/failed task resumes from its checkpoint;
     * otherwise a new session starts with the saved summary, file tree, todo and checkpoint.
     * Returns [ProjectResumeOutcome.ambiguous] when more than one project could match.
     */
    fun resumeProject(query: String?): ProjectResumeOutcome {
        val result = ProjectResolver.resolve(query.orEmpty(), projectMemoryStore.list(), System.currentTimeMillis())
        return when (result) {
            ProjectResolver.Result.None -> ProjectResumeOutcome(
                success = false,
                message = "Koi saved project nahi mila. Pehle kuch banwao, phir continue karunga.",
            )
            is ProjectResolver.Result.Ambiguous -> ProjectResumeOutcome(
                success = false,
                ambiguous = true,
                question = ProjectResolver.question(result.candidates),
                candidates = result.candidates.take(5).map { it.displayName() },
                message = "More than one project could match. Ask the user which one before resuming.",
            )
            is ProjectResolver.Result.Match -> resumeMatchedProject(result.memory)
        }
    }

    private fun resumeMatchedProject(memory: HarnessProjectMemory): ProjectResumeOutcome {
        val project = viewModel.state.value.projects.firstOrNull { it.id == memory.projectId }
        val task = tasks.firstOrNull { it.id == memory.id }
            ?: memory.projectId.takeIf(String::isNotBlank)?.let { pid -> tasks.lastOrNull { it.projectId == pid } }
        val native = currentAgentKind() == AgentKind.ANTIGRAVITY &&
            viewModel.hasAgentConversation(memory.projectId, memory.chatId, AgentKind.ANTIGRAVITY)
        val mode = if (native) "native" else "new_session"

        if (task != null) {
            when (task.state) {
                HarnessTaskState.RUNNING -> return ProjectResumeOutcome(
                    success = true,
                    message = "${memory.displayName()} is already running.",
                    projectId = memory.projectId, taskId = task.id, name = memory.displayName(), mode = mode,
                )
                HarnessTaskState.QUEUED -> return ProjectResumeOutcome(
                    success = true,
                    message = "${memory.displayName()} is already queued.",
                    projectId = memory.projectId, taskId = task.id, name = memory.displayName(), mode = mode,
                )
                HarnessTaskState.PAUSED,
                HarnessTaskState.INTERRUPTED,
                HarnessTaskState.FAILED,
                HarnessTaskState.WAITING_RETRY,
                -> {
                    val ok = resumeTask(task.id)
                    return ProjectResumeOutcome(
                        success = ok,
                        message = if (ok) "Resuming ${memory.displayName()} from its last checkpoint."
                        else "I could not resume ${memory.displayName()}.",
                        projectId = memory.projectId, taskId = task.id, name = memory.displayName(), mode = mode,
                    )
                }
                HarnessTaskState.COMPLETED,
                HarnessTaskState.COMPLETED_WITH_WARNINGS,
                HarnessTaskState.CANCELLED,
                -> Unit
            }
        }

        val name = project?.name ?: memory.displayName()
        val newTaskId = startTask(buildResumePrompt(memory), parseProjectType(memory.projectType), name)
        return ProjectResumeOutcome(
            success = true,
            message = "Starting a new session for $name with the saved summary, files and todo.",
            projectId = memory.projectId, taskId = newTaskId, name = name, mode = "new_session",
        )
    }

    private fun snapshotOf(memory: HarnessProjectMemory): HarnessProjectSnapshot {
        val task = tasks.firstOrNull { it.id == memory.id }
        val native = currentAgentKind() == AgentKind.ANTIGRAVITY &&
            viewModel.hasAgentConversation(memory.projectId, memory.chatId, AgentKind.ANTIGRAVITY)
        return HarnessProjectSnapshot(
            projectId = memory.projectId,
            taskId = memory.id,
            name = memory.displayName(),
            projectType = memory.projectType,
            status = task?.state?.name?.lowercase() ?: memory.status,
            checkpoint = memory.checkpoint,
            summary = memory.summary,
            workspacePath = memory.workspacePath,
            projectRoot = memory.projectRoot,
            agentType = memory.agentType,
            agentSessionId = memory.agentSessionId,
            provider = memory.provider,
            model = memory.model,
            tier = memory.tier,
            milestones = memory.milestones,
            fileTree = memory.fileTree,
            files = memory.files,
            previewUrl = memory.previewUrl,
            previewPort = memory.previewPort,
            stepsDone = memory.stepsDone,
            lastActivityMillis = maxOf(memory.updatedAtMillis, task?.updatedAtMillis ?: 0L),
            canResume = task?.state != HarnessTaskState.RUNNING,
            nativeResume = native,
        )
    }

    private fun resumePromptFor(taskId: String): String =
        runCatching { buildResumePrompt(projectMemoryStore.get(taskId)) }.getOrDefault(RESUME_PROMPT)

    private fun parseProjectType(raw: String): HarnessProjectType =
        HarnessProjectType.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: HarnessProjectType.GENERAL

    /** Records workspace, agent and chat context so a later resume can find the session. */
    private fun recordProjectContext(record: HarnessTaskRecord, project: Project) {
        val root = runCatching { viewModel.projectWorkspaceLocation(project) }.getOrNull() ?: return
        projectMemoryStore.update(
            id = record.id,
            projectId = project.id,
            name = record.projectName,
            projectType = record.projectType.name.lowercase(),
            originalRequest = record.prompt,
            workspacePath = root.absolutePath,
            projectRoot = project.rootPath,
            agentType = currentAgentKind().stableId,
            chatId = viewModel.state.value.activeChatId,
            provider = record.provider ?: providerName(),
            model = record.model ?: providerModel(),
            fileTree = snapshotTree(root),
            addMilestone = "Workspace ready: ${root.name}",
        )
    }

    private fun refreshFileTree(taskId: String) {
        val record = tasks.firstOrNull { it.id == taskId } ?: return
        val project = record.projectId?.let { pid -> viewModel.state.value.projects.firstOrNull { it.id == pid } }
            ?: viewModel.state.value.activeProject
            ?: return
        val root = runCatching { viewModel.projectWorkspaceLocation(project) }.getOrNull() ?: return
        projectMemoryStore.update(taskId, fileTree = snapshotTree(root))
    }

    private fun snapshotTree(root: File): List<String> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.length() > 0 }
            .filterNot { file -> EXCLUDED_DIRS.any { dir -> file.path.contains("/$dir/") } }
            .map { file -> file.relativeTo(root).path.replace(File.separatorChar, '/') }
            .take(MAX_FILE_TREE)
            .sorted()
            .toList()
    }

    /** The project root for a task: the recorded workspace path, else the active project. */
    private fun workspaceRootFor(taskId: String): File? {
        projectMemoryStore.get(taskId)
            ?.workspacePath?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isDirectory }
            ?.let { return it }
        val record = tasks.firstOrNull { it.id == taskId }
        val project = record?.projectId?.let { pid -> viewModel.state.value.projects.firstOrNull { it.id == pid } }
            ?: viewModel.state.value.activeProject
        return project?.let { runCatching { viewModel.projectWorkspaceLocation(it) }.getOrNull() }
    }

    /**
     * Ground-truth files counter (B1): the workspace walk wins over event-derived counts when
     * they disagree, so the card never shows "files=0" while files exist on disk.
     */
    private fun refreshWorkspaceFileCount(id: String) {
        val root = workspaceRootFor(id) ?: return
        val count = WorkspaceFiles.count(root)
        if (count <= 0) return
        workspaceFileCounts[id] = count
        val current = tasks.firstOrNull { it.id == id }?.fileCount ?: 0
        if (count > current) update(id, null, fileCount = count)
    }

    /** Starts (or reuses) the static preview server for [root] (finalize fallback). */
    private fun startStaticPreview(root: File?): String? {
        if (root == null || !root.isDirectory) return null
        val existing = previewServer
        if (existing != null && previewServerRoot == root.absolutePath) return existing.url
        existing?.close()
        return runCatching {
            val server = StaticPreviewServer(root)
            val url = server.start()
            previewServer = server
            previewServerRoot = root.absolutePath
            url
        }.getOrNull()
    }

    private fun parsePreviewPort(url: String): Int? = runCatching {
        val normalized = url.trim()
        val withScheme = if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            normalized
        } else {
            "http://$normalized"
        }
        java.net.URI(withScheme).port.takeIf { it > 0 }
    }.getOrNull()

    /** Full history for one task, oldest first. This is the only status source. */
    fun taskLog(id: String, limit: Int = 300): List<HarnessLogEvent> = log.read(id, limit)

    fun logCounts(id: String): String {
        val counts = log.counts(id)
        return "steps=${counts.steps} files=${counts.files} commands=${counts.commands} errors=${counts.errors} retries=${counts.retries}"
    }

    fun pauseTask(id: String): Boolean {
        val record = tasks.firstOrNull { it.id == id } ?: return false
        if (id != activeTaskId) return record.state == HarnessTaskState.PAUSED
        retryJob?.cancel()
        queue.remove(id)
        viewModel.stopTask()
        log.append(id, nextSeq(id), HarnessLogKind.PAUSED, "Paused on request")
        update(record.id, HarnessTaskState.PAUSED, message = "Paused on request.")
        projectMemoryStore.update(
            id = id,
            status = "paused",
            checkpoint = "Paused on request at ${record.stepCount} steps, ${record.fileCount} files.",
            addDecision = "Paused at ${record.fileCount} files",
            addMilestone = "Paused (resumable)",
            agentType = currentAgentKind().stableId,
        )
        refreshFileTree(id)
        feedFor(id).add(HarnessLiveLine(System.currentTimeMillis(), "PAUSED", "Paused — checkpoint saved", false))
        setLive(
            HarnessLiveStatus(
                taskId = id,
                phase = HarnessLiveStatus.Phase.PAUSED,
                line = "Paused — checkpoint saved",
                paused = true,
            ),
        )
        return true
    }

    fun resumeTask(id: String): Boolean {
        val record = tasks.firstOrNull { it.id == id } ?: return false
        if (record.state == HarnessTaskState.COMPLETED || record.state == HarnessTaskState.CANCELLED) return false
        if (record.state == HarnessTaskState.COMPLETED_WITH_WARNINGS) return false
        if (activeTaskId == null) {
            queue.remove(id)
            queue.addFirst(id)
            resumeRequested += id
            update(id, HarnessTaskState.QUEUED, message = "Queued to resume.")
            projectMemoryStore.update(id, status = "queued", checkpoint = "Queued to resume")
            feedFor(id).add(HarnessLiveLine(System.currentTimeMillis(), "RESUMED", "Queued to resume", false))
            pump()
            return true
        }
        if (id == activeTaskId && !viewModel.state.value.isRunning) {
            log.append(id, nextSeq(id), HarnessLogKind.RESUMED, "Resumed on request")
            update(id, HarnessTaskState.RUNNING, message = "Resuming.")
            projectMemoryStore.update(
                id,
                status = "running",
                checkpoint = "Resumed",
                addMilestone = "Resumed",
            )
            feedFor(id).add(HarnessLiveLine(System.currentTimeMillis(), "RESUMED", "Resumed from checkpoint", false))
            viewModel.sendPrompt(resumePromptFor(record.id))
            return true
        }
        return false
    }

    /** Retry immediately instead of waiting for the next backoff tick. */
    fun retryNow(id: String): Boolean {
        val record = tasks.firstOrNull { it.id == id } ?: return false
        if (record.state != HarnessTaskState.WAITING_RETRY) return false
        retryJob?.cancel()
        retryJob = null
        log.append(id, nextSeq(id), HarnessLogKind.RETRY, "Retry now (user)")
        executeAttempt(record, isRetry = true)
        return true
    }

    fun cancelTask(id: String): Boolean {
        val record = tasks.firstOrNull { it.id == id } ?: return false
        retryJob?.cancel()
        retryJob = null
        queue.remove(id)
        if (id == activeTaskId) {
            viewModel.stopTask()
            activeTaskId = null
        }
        log.append(id, nextSeq(id), HarnessLogKind.STATE, "Cancelled")
        update(record.id, HarnessTaskState.CANCELLED, message = "Cancelled on request.")
        projectMemoryStore.update(id, status = "cancelled", checkpoint = "Cancelled on request")
        setLive(HarnessLiveStatus(taskId = id, phase = HarnessLiveStatus.Phase.IDLE, line = "Cancelled"))
        advanceIfIdle()
        return true
    }

    fun sendFollowUp(id: String, text: String): Boolean {
        val record = tasks.firstOrNull { it.id == id } ?: return false
        if (record.state == HarnessTaskState.CANCELLED) return false
        log.append(id, nextSeq(id), HarnessLogKind.STATE, "Follow-up", text.take(200))
        if (id != activeTaskId) {
            queue.remove(id)
            queue.addFirst(id)
            pump()
            return true
        }
        viewModel.sendPrompt(text)
        update(id, HarnessTaskState.RUNNING, message = text)
        return true
    }

    // ── Projects ─────────────────────────────────────────────────────────────

    fun listProjects(): List<HarnessProjectInfo> = viewModel.state.value.projects.map { project ->
        HarnessProjectInfo(
            id = project.id,
            name = project.name,
            slug = project.slug,
            android = project.type == ProjectType.ANDROID,
            updatedAtMillis = project.updatedAtMillis,
            location = project.rootPath,
        )
    }

    fun projectLocation(id: String): String? =
        viewModel.state.value.projects.firstOrNull { it.id == id }?.rootPath

    fun activePreviewUrl(): String? = viewModel.state.value.previewUrl

    fun latestApkPath(): String? = viewModel.state.value.androidBuildApkPath?.takeIf { File(it).isFile }

    fun openProject(id: String): Boolean {
        val project = viewModel.state.value.projects.firstOrNull { it.id == id } ?: return false
        viewModel.openProject(project)
        return true
    }

    // ── Terminal ─────────────────────────────────────────────────────────────

    suspend fun runCommand(
        command: String,
        timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        confirmed: Boolean = false,
    ): HarnessCommandResult {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return HarnessCommandResult(trimmed, -1, "", rejected = true, rejectionReason = "Empty command")
        }
        when (assessCommand(trimmed)) {
            HarnessCommandRisk.BLOCK -> return HarnessCommandResult(
                trimmed, -1, "", rejected = true,
                rejectionReason = "Command blocked by the sandbox safety policy.",
            )
            HarnessCommandRisk.CONFIRM -> if (!confirmed) {
                return HarnessCommandResult(
                    trimmed, -1, "", rejected = true,
                    rejectionReason = "This command can destroy data and needs confirmation.",
                )
            }
            HarnessCommandRisk.SAFE -> Unit
        }
        if (!isInstalled()) {
            return HarnessCommandResult(
                trimmed, -1, "", rejected = true,
                rejectionReason = "The coding engine is not installed yet.",
            )
        }

        val runtime: InstalledRuntime = installer.installedRuntime()
        val workspace = File(application.filesDir, "harness/shell").apply { mkdirs() }
        val outputFile = File(application.cacheDir, "harness-shell-${System.nanoTime()}.log")
        val process = installer.process(
            proot = runtime.proot,
            rootfs = runtime.rootfs,
            workspace = workspace,
            environment = emptyMap(),
            guestCommand = listOf("/bin/bash", "-lc", trimmed),
            outputFile = outputFile,
        )
        _commandOutput.tryEmit("$ $trimmed")
        runningCommandProcess = process
        val exitCode = withTimeoutOrNull(timeoutMs) {
            kotlinx.coroutines.withContext(Dispatchers.IO) { process.waitFor() }
        }
        runningCommandProcess = null
        val timedOut = exitCode == null
        if (timedOut) process.destroyForcibly()
        val output = runCatching { outputFile.readText() }.getOrDefault("")
        output.lineSequence().filter { it.isNotBlank() }.take(200).forEach { _commandOutput.tryEmit(it) }
        return HarnessCommandResult(trimmed, exitCode ?: -1, output, timedOut = timedOut)
    }

    suspend fun cancelRunningCommand() {
        runningCommandProcess?.destroyForcibly()
    }

    private var runningCommandProcess: Process? = null

    // ── Runtime observation ──────────────────────────────────────────────────

    private fun observeRuntime() {
        if (eventJob?.isActive == true) return
        eventJob = scope.launch {
            viewModel.runtimeEvents.collect { event ->
                val id = activeTaskId ?: return@collect
                when (event) {
                    is com.jarvis.harness.model.RuntimeEvent.SessionStarted -> {
                        pendingFailure = null
                        // Persist the agent session id immediately so a reconnect/crash can
                        // resume with the same session.
                        projectMemoryStore.update(
                            id = id,
                            status = "running",
                            checkpoint = "Session started",
                            agentType = currentAgentKind().stableId,
                            agentSessionId = event.sessionId,
                            chatId = viewModel.state.value.activeChatId,
                            provider = tasks.firstOrNull { it.id == id }?.provider ?: providerName(),
                            model = tasks.firstOrNull { it.id == id }?.model ?: providerModel(),
                            addMilestone = "Session started",
                        )
                        note(id, HarnessLogKind.STATE, "Session started", event.sessionId.take(8))
                    }
                    is com.jarvis.harness.model.RuntimeEvent.ToolStarted -> {
                        val (kind, title) = describeTool(event.toolName, event.detail)
                        // Pass a path for write/edit so the files counter rises from the
                        // normal tool stream, not only from FilesChanged events.
                        val countedPath = if (kind == HarnessLogKind.FILE_WRITE || kind == HarnessLogKind.FILE_EDIT) {
                            event.detail.trim().trim('"').substringAfterLast('/').substringBefore(' ').take(160).ifBlank { null }
                        } else null
                        note(id, kind, title, event.detail.take(300), path = countedPath)
                        observeStepPolicy(id, kind, event.toolName, event.detail)
                    }
                    is com.jarvis.harness.model.RuntimeEvent.ToolProgress -> {
                        note(id, HarnessLogKind.THINKING, event.detail.take(120))
                    }
                    is com.jarvis.harness.model.RuntimeEvent.RuntimeLog -> {
                        note(id, HarnessLogKind.THINKING, event.title, event.detail.take(300))
                    }
                    is com.jarvis.harness.model.RuntimeEvent.ToolCompleted -> {
                        note(id, HarnessLogKind.STEP_DONE, event.summary.take(120), event.toolName)
                        taskPolicy[id]?.let { policy ->
                            runWatchdog(id, policy.watchdog.onStep(countersFor(id).steps, policy.elapsedMinutes()))
                        }
                    }
                    is com.jarvis.harness.model.RuntimeEvent.FilesChanged -> {
                        event.changes.forEach { change ->
                            val kind = if (change.additions > 0 && change.deletions == 0) {
                                HarnessLogKind.FILE_WRITE
                            } else {
                                HarnessLogKind.FILE_EDIT
                            }
                            note(
                                id, kind,
                                title = if (kind == HarnessLogKind.FILE_WRITE) "Wrote ${change.path.substringAfterLast('/')}" else "Edited ${change.path.substringAfterLast('/')}",
                                detail = change.path,
                                path = change.path,
                                additions = change.additions,
                                deletions = change.deletions,
                            )
                            taskPolicy[id]?.let { policy ->
                                policy.breakdown = policy.breakdown.plus(StepCategory.EDIT)
                                if (kind == HarnessLogKind.FILE_WRITE) policy.watchdog.onProgress()
                                runWatchdog(id, policy.watchdog.onFileEdit(change.path))
                            }
                        }
                        // The file-tree snapshot is what makes a fallback resume concrete.
                        refreshFileTree(id)
                        // B1: files counter from the workspace watcher (ground truth).
                        refreshWorkspaceFileCount(id)
                        setLive(
                            HarnessLiveStatus(
                                taskId = id,
                                phase = HarnessLiveStatus.Phase.WORKING,
                                line = "Saved ${event.changes.size} file(s)",
                                files = workspaceFileCounts[id] ?: countersFor(id).files.size,
                            ),
                        )
                    }
                    is com.jarvis.harness.model.RuntimeEvent.PreviewStarted -> {
                        note(id, HarnessLogKind.STEP_DONE, "Preview ready", event.url)
taskPolicy[id]?.watchdog?.onProgress()
                        projectMemoryStore.update(
                            id,
                            previewUrl = event.url,
                            previewPort = parsePreviewPort(event.url),
                        )
                        setLive(
                            HarnessLiveStatus(
                                taskId = id,
                                phase = HarnessLiveStatus.Phase.WORKING,
                                line = "Preview ready",
                            ),
                        )
                        host?.onPreviewStarted(tasks.firstOrNull { it.id == id }?.toStatus(0, false), event.url)
                    }
                    is com.jarvis.harness.model.RuntimeEvent.SessionFailed -> {
                        pendingFailure = event.reason
                        note(id, HarnessLogKind.ERROR, "Session failed", HarnessError.scrub(event.reason))
                    }
                    is com.jarvis.harness.model.RuntimeEvent.SessionCompleted -> {
                        note(id, HarnessLogKind.STATE, "Session completed")
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * Feeds one tool invocation into the step breakdown (B2.3) and the loop watchdog (B3.4).
     */
    private fun observeStepPolicy(id: String, kind: HarnessLogKind, toolName: String, detail: String) {
        val policy = taskPolicy[id] ?: return
        val category = when (kind) {
            HarnessLogKind.FILE_WRITE, HarnessLogKind.FILE_EDIT -> StepCategory.EDIT
            else -> StepClassifier.classify(toolName, detail, policyConfig)
        }
        policy.breakdown = policy.breakdown.plus(category)
        if (category == StepCategory.TEST) {
            runWatchdog(id, policy.watchdog.onTestingCommand())
        }
        runWatchdog(id, policy.watchdog.onToolInvocation("${toolName}|${detail.take(120)}"))
    }

    private fun runWatchdog(id: String, decision: WatchdogDecision) {
        when (decision) {
            is WatchdogDecision.Correct -> injectCorrection(id, decision.reason)
            is WatchdogDecision.Finalize -> supervisorFinalize(id, decision.reason)
            WatchdogDecision.None -> Unit
        }
    }

    /** Injects "testing band karo, ab finalize karo" and records it on the feed. */
    private fun injectCorrection(id: String, reason: String) {
        note(id, HarnessLogKind.THINKING, "Supervisor: finalize karo", reason.take(160))
        feedFor(id).add(
            HarnessLiveLine(System.currentTimeMillis(), "SUPERVISOR", "Testing band karo — ab finalize karo", false),
        )
        // Only hand the instruction to the agent when it is idle; mid-run injection would
        // queue a follow-up after the current run, so the finalize path is the enforcement.
        if (!viewModel.state.value.isRunning && !viewModel.state.value.androidBuildRunning) {
            runCatching { viewModel.sendPrompt(CORRECTION_PROMPT) }
        }
    }

    /**
     * B3.4: the correction did not stop the loop, so the supervisor finalizes. It stops the
     * run and, once the run settles, completes with preview + a warnings summary.
     */
    private fun supervisorFinalize(id: String, reason: String) {
        if (supervisedFinalize.containsKey(id) || finalizeInFlight.contains(id)) return
        supervisedFinalize[id] = reason
        note(id, HarnessLogKind.STATE, "Supervisor finalized", reason.take(160))
        feedFor(id).add(HarnessLiveLine(System.currentTimeMillis(), "SUPERVISOR", "Finalize: ${reason.take(120)}", false))
        viewModel.stopTask()
        if (!viewModel.state.value.isRunning && !viewModel.state.value.androidBuildRunning) {
            scope.launch { finishSupervised(id) }
        }
    }

    private suspend fun finishSupervised(id: String) {
        val reason = synchronized(supervisedFinalize) { supervisedFinalize.remove(id) } ?: return
        completeWithWarnings(id, viewModel.state.value, reason)
    }

    /**
     * B3.5 finalize: preview start + open, then a short summary and the warnings status.
     * Never fails the task when a loop was cut short; it reports honestly instead.
     */
    private suspend fun completeWithWarnings(id: String, state: AppUiState, reason: String) {
        if (!finalizeInFlight.add(id)) return
        try {
            val record = tasks.firstOrNull { it.id == id } ?: return
            val project = state.projects.firstOrNull { it.id == record.projectId } ?: state.activeProject
            val verification = verify(record, state, project)
            activeTaskId = null

            val preview = state.previewUrl?.takeIf(String::isNotBlank)
                ?: activePreviewUrl()?.takeIf(String::isNotBlank)
                ?: projectMemoryStore.get(id)?.previewUrl?.takeIf(String::isNotBlank)
                ?: if (record.projectType == HarnessProjectType.WEB) startStaticPreview(verification.location?.let(::File)) else null
            val summary = "Kaam khatam (warnings ke saath): ${reason.take(140)}"

            log.append(id, nextSeq(id), HarnessLogKind.COMPLETED, "Completed with warnings", reason)
            update(
                id,
                HarnessTaskState.COMPLETED_WITH_WARNINGS,
                message = summary,
                changes = state.changes.size,
                deliverable = verification.location,
                verified = verification.ok,
                proof = verification.proof,
            )
            projectMemoryStore.update(
                id = id,
                status = "completed_with_warnings",
                checkpoint = reason.take(160),
                stepsDone = record.stepCount,
                outputLocation = verification.location,
                previewUrl = preview,
                addDecision = "Supervisor finalize: ${reason.take(120)}",
            )
            feedFor(id).add(HarnessLiveLine(System.currentTimeMillis(), "COMPLETED", "Ho gaya (warnings)", false))
            setLive(
                HarnessLiveStatus(
                    taskId = id,
                    phase = HarnessLiveStatus.Phase.SUCCESS,
                    line = "Ho gaya (warnings)",
                    step = record.stepCount,
                    files = verification.proof.files.size,
                    warnings = reason.take(160),
                ),
            )
            if (!preview.isNullOrBlank()) host?.onPreviewStarted(requireStatus(id), preview)
            _events.tryEmit(HarnessEvent.Completed(id, summary, verification.location, state.changes.size))
            host?.onTaskCompletedWithWarnings(requireStatus(id), reason)
            advanceIfIdle()
        } finally {
            supervisedCompleted.add(id)
            finalizeInFlight.remove(id)
            supervisedFinalize.remove(id)
        }
    }

    private fun describeTool(toolName: String, detail: String): Pair<HarnessLogKind, String> {
        val name = toolName.lowercase()
        val fileHint = detail.substringAfterLast('/').take(60)
        return when {
            "bash" in name || "shell" in name || "terminal" in name || "exec" in name ||
                "command" in name || "cmd" in name || "run_" in name ->
                HarnessLogKind.BASH_COMMAND to "Running: ${detail.take(60).ifBlank { toolName }}"
            "write" in name || "create" in name || "new_file" in name || "apply_patch" in name ||
                "create_file" in name || "save_file" in name ->
                HarnessLogKind.FILE_WRITE to "Writing ${fileHint.ifBlank { detail.take(50) }}"
            "edit" in name || "patch" in name || "replace" in name || "update" in name ||
                "str_replace" in name || "modify" in name ->
                HarnessLogKind.FILE_EDIT to "Editing ${fileHint.ifBlank { detail.take(50) }}"
            "read" in name || "list" in name || "grep" in name || "search" in name ||
                "view" in name || "glob" in name || "find" in name || "cat" in name ->
                HarnessLogKind.THINKING to "Reading ${detail.take(50)}"
            // Anything else is still shown honestly instead of being dropped.
            else -> HarnessLogKind.THINKING to toolName.ifBlank { "Working" }
        }
    }

    private fun observeCompletion() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            viewModel.state.collect { state ->
                val id = activeTaskId ?: return@collect
                val running = state.isRunning || state.androidBuildRunning
                if (running && !wasRunning) {
                    setLive(HarnessLiveStatus(taskId = id, phase = HarnessLiveStatus.Phase.WORKING, line = "Working"))
                }
                if (!running && wasRunning) {
                    onRunFinished(id, state)
                }
                wasRunning = running
            }
        }
    }

    private suspend fun onRunFinished(id: String, state: AppUiState) {
        // Already finalized by the supervisor; do not complete it a second time.
        if (id in supervisedCompleted) return
        // The supervisor cut a loop short; report that rather than a clean verification.
        val supervised = synchronized(supervisedFinalize) { supervisedFinalize.remove(id) }
        if (supervised != null || id in finalizeInFlight) {
            if (supervised != null) completeWithWarnings(id, state, supervised)
            return
        }
        val failure = pendingFailure
        pendingFailure = null
        if (failure != null) {
            handleFailure(id, failure)
            return
        }
        completeWithVerification(id, state)
    }

    // ── Failure handling: classify, switch key, retry, or fail ───────────────

    private suspend fun handleFailure(id: String, reason: String) {
        val record = tasks.firstOrNull { it.id == id } ?: return
        val error = HarnessError.classify(reason, record.provider ?: providerName())
        activeTaskId = null

        if (error.kind == HarnessErrorKind.INVALID_KEY && switchToNextKey(record, error)) return

        if (error.kind.retryable && record.attempts < MAX_ATTEMPTS) {
            noteOAuthCheckpointResume(record)
            scheduleRetry(record, error)
            return
        }
        failTask(record, error)
    }

    /**
     * OAuth-managed agents (Antigravity) cannot be pointed at the local gateway, so on a
     * retryable provider failure the task resumes from its checkpoint on the next attempt.
     * A key-based agent fails over inside the gateway instead, so it needs no extra note.
     */
    private fun noteOAuthCheckpointResume(record: HarnessTaskRecord) {
        val agent = runCatching { viewModel.state.value.agentKind }.getOrNull() ?: return
        val strategy = com.jarvis.harness.gateway.AgentRecovery.strategy(agent)
        if (strategy == com.jarvis.harness.gateway.RecoveryStrategy.RESUME_FROM_CHECKPOINT) {
            note(
                record.id,
                HarnessLogKind.RETRY,
                "Resuming from checkpoint",
                "${agent.title} cannot take a custom endpoint; continuing on the next available agent.",
            )
        }
    }

    private suspend fun switchToNextKey(record: HarnessTaskRecord, error: HarnessError): Boolean {
        val kind = providerKind()
        val keys = storedApiKeys(kind)
        val active = keys.firstOrNull { it.isActive }
        active?.let { failedKeyIds += it.id }
        val next = keys.firstOrNull { it.id !in failedKeyIds } ?: return false
        val switched = runCatching { activateApiKey(kind, next.id) }.isSuccess
        if (!switched) return false
        note(record.id, HarnessLogKind.RETRY, "Switched API key", "${active?.name ?: "previous"} → ${next.name}", command = error.headline)
        host?.onApiKeySwitched(active, next)
        executeAttempt(
            record.copy(attempts = record.attempts + 1, lastMessage = "Switched to key ${next.name}"),
            isRetry = true,
        )
        return true
    }

    private fun scheduleRetry(record: HarnessTaskRecord, error: HarnessError) {
        val attempt = record.attempts + 1
        val waitSeconds = RETRY_BACKOFF_SECONDS.getOrElse(attempt - 1) { RETRY_BACKOFF_SECONDS.last() }
        update(
            record.id,
            HarnessTaskState.WAITING_RETRY,
            message = "Waiting ${waitSeconds}s to retry (${error.headline}).",
            attempts = attempt,
            provider = error.provider,
            error = error,
        )
        note(record.id, HarnessLogKind.RETRY, "Retry in ${waitSeconds}s", error.headline)
        setLive(
            HarnessLiveStatus(
                taskId = record.id,
                phase = HarnessLiveStatus.Phase.WAITING_RETRY,
                line = "Retrying in ${waitSeconds}s",
                retryInSeconds = waitSeconds,
                errorSummary = error.headline,
            ),
        )
        scope.launch { _events.emit(HarnessEvent.Retrying(record.id, waitSeconds, error)) }
        scope.launch { host?.onTaskRetrying(requireStatus(record.id), error, waitSeconds) }

        retryJob?.cancel()
        retryJob = scope.launch {
            var remaining = waitSeconds
            while (remaining > 0) {
                delay(1_000)
                remaining--
                setLive(
                    HarnessLiveStatus(
                        taskId = record.id,
                        phase = HarnessLiveStatus.Phase.WAITING_RETRY,
                        line = "Retrying in ${remaining}s",
                        retryInSeconds = remaining,
                        errorSummary = error.headline,
                    ),
                )
            }
            val latest = tasks.firstOrNull { it.id == record.id } ?: return@launch
            if (latest.state != HarnessTaskState.WAITING_RETRY) return@launch
            executeAttempt(latest, isRetry = true)
        }
    }

    private suspend fun failTask(record: HarnessTaskRecord, error: HarnessError) {
        log.append(record.id, nextSeq(record.id), HarnessLogKind.ERROR, "Failed", error.spoken)
        update(record.id, HarnessTaskState.FAILED, message = error.spoken, error = error, provider = error.provider)
        projectMemoryStore.update(
            id = record.id,
            status = "failed",
            lastError = error.spoken,
            checkpoint = "Failed: ${error.headline}",
            addMilestone = "Failed: ${error.headline}",
            agentType = currentAgentKind().stableId,
        )
        refreshFileTree(record.id)
        feedFor(record.id).add(HarnessLiveLine(System.currentTimeMillis(), "ERROR", error.spoken, true))
        setLive(
            HarnessLiveStatus(
                taskId = record.id,
                phase = HarnessLiveStatus.Phase.ERROR,
                line = error.headline,
                errorSummary = error.hint,
            ),
        )
        _events.tryEmit(HarnessEvent.Failed(record.id, error.spoken, error))
        val status = requireStatus(record.id)
        activeTaskId = null
        host?.onTaskFailed(status, error.spoken)
        advanceIfIdle()
    }

    // ── Verification ─────────────────────────────────────────────────────────

    private suspend fun completeWithVerification(id: String, state: AppUiState) {
        val record = tasks.firstOrNull { it.id == id } ?: return
        val project = state.projects.firstOrNull { it.id == record.projectId } ?: state.activeProject
        val verification = verify(record, state, project)
        activeTaskId = null

        if (!verification.ok) {
            log.append(id, nextSeq(id), HarnessLogKind.ERROR, "Verification failed", verification.reason)
            update(
                id,
                HarnessTaskState.FAILED,
                message = verification.reason,
                error = HarnessError.classify(verification.reason, record.provider ?: providerName()),
                verified = false,
            )
            setLive(HarnessLiveStatus(taskId = id, phase = HarnessLiveStatus.Phase.ERROR, line = "Not verified", errorSummary = verification.reason))
            projectMemoryStore.update(
                id = id,
                status = "failed",
                lastError = verification.reason,
                checkpoint = "Output not verified: ${verification.reason.take(140)}",
                addMilestone = "Not verified (resumable)",
                agentType = currentAgentKind().stableId,
            )
            refreshFileTree(id)
            feedFor(id).add(HarnessLiveLine(System.currentTimeMillis(), "ERROR", "Not verified: ${verification.reason}", true))
            _events.tryEmit(HarnessEvent.Failed(id, verification.reason))
            host?.onTaskFailed(requireStatus(id), verification.reason)
            advanceIfIdle()
            return
        }

        val message = state.messages.lastOrNull { !it.fromUser }?.text?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }?.take(280) ?: "Finished."
        // Finalize always leaves a usable preview: reuse the agent's dev-server URL, else
        // start our own static server over the project root for a web project (B1).
        val preview = state.previewUrl?.takeIf(String::isNotBlank)
            ?: activePreviewUrl()
            ?: if (record.projectType == HarnessProjectType.WEB) startStaticPreview(verification.location?.let(::File)) else null
        refreshWorkspaceFileCount(id)
        log.append(id, nextSeq(id), HarnessLogKind.COMPLETED, "Completed", verification.summary)
        update(
            id,
            HarnessTaskState.COMPLETED,
            message = message,
            changes = state.changes.size,
            deliverable = verification.location,
            verified = true,
            proof = verification.proof,
        )
        projectMemoryStore.update(
            id = id,
            status = "completed",
            checkpoint = "Completed and verified",
            stepsDone = record.stepCount,
            outputLocation = verification.location,
            previewUrl = preview,
            previewPort = preview?.let(::parsePreviewPort),
            clearError = true,
            addMilestone = "Completed and verified",
            agentType = currentAgentKind().stableId,
        )
        refreshFileTree(id)
        feedFor(id).add(HarnessLiveLine(System.currentTimeMillis(), "COMPLETED", "Ho gaya", false))
        val status = requireStatus(id)
        setLive(
            HarnessLiveStatus(
                taskId = id,
                phase = HarnessLiveStatus.Phase.SUCCESS,
                line = "Ho gaya",
                step = record.stepCount,
                files = workspaceFileCounts[id] ?: verification.proof.files.size,
                previewUrl = preview,
            ),
        )
        _events.tryEmit(HarnessEvent.Completed(id, message, verification.location, state.changes.size))
        if (preview != null) host?.onPreviewStarted(status, preview)
        host?.onTaskCompleted(status)
        advanceIfIdle()
    }

    private data class Verification(
        val ok: Boolean,
        val reason: String,
        val summary: String = "",
        val location: String? = null,
        val proof: HarnessTaskProof = HarnessTaskProof(),
    )

    private fun verify(record: HarnessTaskRecord, state: AppUiState, project: Project?): Verification {
        // The agent writes inside the project's real workspace on disk
        // (filesDir/workspaces/<projectId>/<rootPath>), not the relative rootPath.
        // Resolving it correctly (and searching subfolders) is what stops false FAILED.
        // Prefer the root the agent actually wrote to (recorded in project memory from its
        // cwd/first write). Fall back to the project workspace base.
        val recordedRoot = projectMemoryStore.get(record.id)
            ?.workspacePath?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isDirectory }
        val projectBase = project?.let { File(application.filesDir, "workspaces/${it.id}") }
        val fallbackRoot = project?.let {
            val base = projectBase!!
            if (it.rootPath.isBlank()) base else File(base, it.rootPath)
        }?.takeIf { it.isDirectory } ?: projectBase?.takeIf { it.isDirectory }
        val root = recordedRoot ?: fallbackRoot
        val apk: File? = state.androidBuildApkPath?.let(::File)?.takeIf { it.isFile && it.length() > 0 }
            ?: root?.walkTopDown()?.firstOrNull { it.isFile && it.extension.equals("apk", true) && it.length() > 0 }
        val preview = state.previewUrl ?: activePreviewUrl()
        // Full tree first; only then cap the proof list. Capping before the entry-file
        // check hid index.html in any project with more than MAX_PROOF_FILES files.
        // Full list first; only then cap the proof. WorkspaceFiles also skips build/dependency
        // noise and is the same walk the files counter uses.
        val allFiles = WorkspaceFiles.list(root, maxFiles = Int.MAX_VALUE / 2)
        val sourceFiles = allFiles.take(AgentPolicyConfig.MAX_PROOF_FILES)
        val entryFile = WorkspaceFiles.findEntryFile(root)

        val ok: Boolean
        val reason: String
        when (record.projectType) {
            HarnessProjectType.ANDROID -> {
                ok = apk != null
                reason = if (ok) "" else "Build finished but no APK was produced. ${WorkspaceFiles.describeSearch(root, allFiles.size)}"
            }
            HarnessProjectType.WEB -> {
                // Depth-limited subfolder search: an entry file the agent created inside a
                // subfolder (or any *.html) counts, so this never false-negatives.
                ok = entryFile != null || preview != null
                reason = if (ok) "" else
                    "No web entry file (index.html) and no preview URL. ${WorkspaceFiles.describeSearch(root, allFiles.size)}"
            }
            else -> {
                ok = allFiles.isNotEmpty()
                reason = if (ok) "" else "No project files were created. ${WorkspaceFiles.describeSearch(root, allFiles.size)}"
            }
        }

        val proof = HarnessTaskProof(
            files = sourceFiles.map { relative ->
                val size = root?.let { File(it, relative) }?.length() ?: 0L
                "$relative (${size}B)"
            },
            buildTail = state.androidBuildLog.takeIf { it.isNotBlank() }?.lines()?.takeLast(40)?.joinToString("\n"),
            locations = listOfNotNull(root?.absolutePath, apk?.absolutePath),
        )
        return Verification(
            ok = ok,
            reason = reason,
            summary = "Verified ${sourceFiles.size} file(s)" + (apk?.let { ", APK ${it.name}" } ?: "") +
                (entryFile?.let { ", entry ${it.name}" } ?: ""),
            location = apk?.absolutePath ?: root?.absolutePath,
            proof = proof,
        )
    }

    // ── Execution ────────────────────────────────────────────────────────────

    private fun pump() {
        if (activeTaskId != null) return
        val next = queue.firstOrNull { qid ->
            tasks.any { it.id == qid && (it.state == HarnessTaskState.QUEUED || it.state == HarnessTaskState.INTERRUPTED || it.state == HarnessTaskState.PAUSED) }
        } ?: return
        queue.remove(next)
        val record = tasks.firstOrNull { it.id == next } ?: return
        executeAttempt(record, isRetry = false)
    }

    private fun executeAttempt(record: HarnessTaskRecord, isRetry: Boolean) {
        if (activeTaskId != null && activeTaskId != record.id) return
        activeTaskId = record.id
        failedKeyIds.clear()
val policy = policyFor(record)
        // A task queued by resumeTask/resumeInterrupted must continue from its checkpoint,
        // not replay its original brief.
        val resuming = isRetry || resumeRequested.remove(record.id)
        update(record.id, HarnessTaskState.RUNNING, message = if (resuming) "Resuming." else "Starting.")
        log.append(
            record.id,
            nextSeq(record.id),
            if (resuming) HarnessLogKind.RESUMED else HarnessLogKind.STATE,
            if (resuming) "Retrying" else "Started",
        )
        setLive(
            HarnessLiveStatus(
                taskId = record.id,
                phase = HarnessLiveStatus.Phase.WORKING,
                line = "Starting",
                tier = policy.tier.name,
                tierReason = policy.reason,
                budget = policy.budget.label,
                breakdown = policy.breakdown.render(),
            ),
        )
        scope.launch { launchTask(record, resuming) }
    }

    /**
     * Returns the task's policy state, re-seeding it from project memory (or a fresh
     * classification) after a restart so a resumed task keeps its original tier/budget.
     */
    private fun policyFor(record: HarnessTaskRecord): TaskPolicyState {
        taskPolicy[record.id]?.let { return it }
        val memory = projectMemoryStore.get(record.id)
        val tier = runCatching { TaskTier.valueOf(memory?.tier.orEmpty()) }.getOrNull()
            ?: TaskClassifier.classify(record.prompt, record.projectType.name.lowercase(), policyConfig).tier
        val budget = policyConfig.budgetFor(tier)
        val visual = memory?.visualTest
            ?.takeIf(String::isNotBlank)
            ?.let { VisualTestResult(it == "available", "cached: $it") }
            ?: VisualTestResult.unavailable("not probed yet")
        return TaskPolicyState(
            tier = tier,
            reason = memory?.tierReason.orEmpty(),
            budget = budget,
            visualTest = visual,
            watchdog = LoopWatchdog(budget, policyConfig.watchdog),
        ).also { taskPolicy[record.id] = it }
    }

    private suspend fun launchTask(record: HarnessTaskRecord, resuming: Boolean) {
        if (!isSupportedDevice()) {
            failTask(record, HarnessError.classify("This device is not arm64", record.provider ?: providerName()))
            return
        }
        if (!isInstalled()) {
            update(record.id, HarnessTaskState.PAUSED, message = INSTALL_REQUIRED_MESSAGE, runtimeReady = false)
            activeTaskId = null
            host?.onTaskFailed(requireStatus(record.id), INSTALL_REQUIRED_MESSAGE)
            return
        }
        var guard = 0
        while (viewModel.state.value.startupStage == StartupStage.INSTALLING && guard < 1_800) {
            delay(1_000)
            guard++
        }

        if (!resuming) {
            val project = openOrCreateProject(record)
            if (project == null) {
                failTask(record, HarnessError.classify("Could not create the project workspace", record.provider ?: providerName()))
                return
            }
            update(record.id, HarnessTaskState.RUNNING, projectId = project.id, message = "Working on it.")
            recordProjectContext(record, project)
            record.projectType.stack?.let { stack ->
                if (!installer.isStackInstalled(stack)) {
                    viewModel.installDevStack(stack)
                    var waited = 0
                    while (viewModel.state.value.devStackInstalling != null && waited < 3_600) {
                        delay(1_000)
                        waited++
                    }
                }
            }
            // B3.1: probe headless-browser testing ONCE per project + engine version, then
            // reuse the cached verdict for every step of this task.
            val policy = policyFor(record)
            val visual = probeVisualTesting(project)
            policy.visualTest = visual
            update(record.id, null, visualTest = visual.label)
            note(record.id, HarnessLogKind.THINKING, "Visual test: ${visual.label}", visual.reason)
            projectMemoryStore.update(id = record.id, visualTest = visual.label)
            viewModel.sendPrompt(
                record.prompt,
                agentInstructions = AgentPromptPolicy.efficiencyInstructions(policy.tier, policy.budget, visual),
            )
        } else {
            // Resume the same project workspace (agents that key sessions on cwd/native
            // conversation ids find their session here). If the native session is gone the
            // prompt below carries the project summary, file tree, todo and checkpoint.
            val project = record.projectId?.let { id -> viewModel.state.value.projects.firstOrNull { it.id == id } }
            if (project != null) {
                viewModel.openProject(project)
                projectMemoryStore.get(record.id)?.chatId?.takeIf(String::isNotBlank)?.let(viewModel::switchChat)
                recordProjectContext(record, project)
            }
            viewModel.sendPrompt(resumePromptFor(record.id))
        }
    }

    private fun probeVisualTesting(project: Project): VisualTestResult {
        val workspace = File(application.filesDir, "workspaces/${project.id}")
        val rootfs = runCatching { installer.installedRuntime().rootfs }.getOrNull()
        return capabilityProbe.probeOnce(project.id, HarnessBuildConfig.versionName) {
            headlessProbe.inspect(rootfs, workspace)
        }
    }

    private suspend fun openOrCreateProject(record: HarnessTaskRecord): Project? {
        viewModel.state.value.projects.firstOrNull { it.slug == com.jarvis.harness.model.projectSlug(record.projectName) }
            ?.let { existing ->
                viewModel.openProject(existing)
                return existing
            }
        return when (record.projectType) {
            HarnessProjectType.ANDROID -> {
                viewModel.createAndroidProject(record.projectName, AndroidTemplate.COMPOSE)
                viewModel.state.value.activeProject
            }
            else -> {
                viewModel.createProject(record.projectName)
                viewModel.state.value.activeProject
            }
        }
    }

    private fun advanceIfIdle() {
        if (activeTaskId == null) pump()
    }

    // ── Persistence helpers ──────────────────────────────────────────────────

    private fun nextSeq(taskId: String): Long {
        val next = (seq[taskId] ?: 0L) + 1
        seq[taskId] = next
        return next
    }

    private fun note(
        taskId: String,
        kind: HarnessLogKind,
        title: String,
        detail: String = "",
        path: String? = null,
        additions: Int = 0,
        deletions: Int = 0,
        command: String? = null,
    ) {
        log.append(taskId, nextSeq(taskId), kind, title, detail, path, additions, deletions, command)
        val counts = countersFor(taskId)
        when (kind) {
            HarnessLogKind.STEP_DONE -> counts.steps++
            HarnessLogKind.BASH_COMMAND -> counts.commands++
            HarnessLogKind.ERROR -> counts.errors++
            HarnessLogKind.RETRY -> counts.retries++
            HarnessLogKind.FILE_WRITE, HarnessLogKind.FILE_EDIT -> path?.let(counts.files::add)
            else -> Unit
        }
        if (kind != HarnessLogKind.THINKING) {
            update(
                taskId,
                null,
                stepCount = counts.steps,
                // The workspace watcher is ground truth; never let an event count pull it down.
                fileCount = maxOf(workspaceFileCounts[taskId] ?: 0, counts.files.size),
            )
        }
        val lineText = buildString {
            // Single implementation for dedupe + boundary truncation; the restore path uses
            // the same helper so a restart cannot reintroduce "Wrote index.htmlindex.html".
            append(FeedText.compose(title, detail))
            if (additions > 0 || deletions > 0) append("  +").append(additions).append(" -").append(deletions)
        }
        feedFor(taskId).add(
            HarnessLiveLine(
                atMillis = System.currentTimeMillis(),
                kind = kind.name,
                text = lineText,
                isError = kind == HarnessLogKind.ERROR,
            ),
        )
        while (feedFor(taskId).size > MAX_FEED_LINES) feedFor(taskId).removeAt(0)
        projectMemoryStore.update(
            id = taskId,
            status = when (kind) {
                HarnessLogKind.ERROR -> "failed"
                HarnessLogKind.RETRY -> "waiting_retry"
                HarnessLogKind.PAUSED -> "paused"
                else -> "running"
            },
            checkpoint = title.take(160),
            stepsDone = counts.steps,
            addFile = path,
            addMilestone = title.take(160).takeIf { kind != HarnessLogKind.THINKING },
            agentType = currentAgentKind().stableId,
        )
        // Publish on EVERY event so the Agent Live card and log sheet show the newest
        // line and live counters in real time, whatever the tool names are. Without
        // this, only bash/file-write events surfaced and a task looked frozen.
        val phase = when (kind) {
            HarnessLogKind.ERROR -> HarnessLiveStatus.Phase.ERROR
            HarnessLogKind.PAUSED -> HarnessLiveStatus.Phase.PAUSED
            HarnessLogKind.RETRY -> HarnessLiveStatus.Phase.WAITING_RETRY
            HarnessLogKind.COMPLETED -> HarnessLiveStatus.Phase.SUCCESS
            else -> _liveStatus.value.phase.takeIf { it != HarnessLiveStatus.Phase.IDLE }
                ?: HarnessLiveStatus.Phase.WORKING
        }
        setLive(
            HarnessLiveStatus(
                taskId = taskId,
                phase = phase,
                line = title,
                step = counts.steps,
                files = workspaceFileCounts[taskId] ?: counts.files.size,
            ),
        )
    }

    private fun setLive(status: HarnessLiveStatus) {
        val now = System.currentTimeMillis()
        val current = _liveStatus.value
        // Throttle to ~4-5 updates per second unless the phase or line changed.
        if (now - lastLiveEmitMillis < 200 && current.phase == status.phase && current.line == status.line) return
        lastLiveEmitMillis = now
        val id = status.taskId
        val record = id?.let { taskId -> tasks.firstOrNull { it.id == taskId } }
        // No fake percentage. Only a verified success is 100%; anything else is
        // "unknown" (0f) and the UI shows an indeterminate bar so we never lie.
        val progress = if (status.phase == HarnessLiveStatus.Phase.SUCCESS) 1f else 0f
        val policy = id?.let { taskPolicy[it] }
        val sameTask = status.taskId == current.taskId
        val enriched = status.copy(
            state = record?.state?.name ?: current.state,
            projectName = record?.projectName ?: current.projectName,
            progress = if (status.phase == HarnessLiveStatus.Phase.SUCCESS) 1f else progress,
            queued = queue.size,
            feed = id?.let { feedFor(it).toList().takeLast(MAX_FEED_LINES) }.orEmpty(),
            previewUrl = id?.let { projectMemoryStore.get(it)?.previewUrl },
            tier = policy?.tier?.name ?: if (sameTask) current.tier else "",
            tierReason = policy?.reason.orEmpty().ifBlank { if (sameTask) current.tierReason else "" },
            budget = policy?.budget?.label ?: if (sameTask) current.budget else "",
            breakdown = policy?.breakdown?.render() ?: if (sameTask) current.breakdown else "",
            warnings = if (sameTask) (status.warnings ?: current.warnings) else status.warnings,
            updatedAtMillis = now,
        )
        _liveStatus.value = enriched
    }

    private fun requireStatus(id: String): HarnessTaskStatus = taskStatus(id) ?: error("Unknown task $id")

    private fun update(
        id: String,
        state: HarnessTaskState?,
        message: String? = null,
        projectId: String? = null,
        changes: Int? = null,
        deliverable: String? = null,
        verified: Boolean? = null,
        runtimeReady: Boolean? = null,
        attempts: Int? = null,
        provider: String? = null,
        model: String? = null,
        error: HarnessError? = null,
        proof: HarnessTaskProof? = null,
        stepCount: Int? = null,
        fileCount: Int? = null,
        visualTest: String? = null,
    ) {
        val index = tasks.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = tasks[index]
        tasks[index] = current.copy(
            state = state ?: current.state,
            updatedAtMillis = System.currentTimeMillis(),
            lastMessage = message ?: current.lastMessage,
            projectId = projectId ?: current.projectId,
            changedFiles = changes ?: current.changedFiles,
            deliverableLocation = deliverable ?: current.deliverableLocation,
            verified = verified ?: current.verified,
            runtimeReady = runtimeReady ?: current.runtimeReady,
            attempts = attempts ?: current.attempts,
            provider = provider ?: current.provider,
            model = model ?: current.model,
            httpStatus = error?.httpStatus ?: current.httpStatus,
            errorHeadline = error?.headline ?: current.errorHeadline,
            errorKind = error?.kind?.name ?: current.errorKind,
            errorHint = error?.hint ?: current.errorHint,
            errorRaw = error?.raw ?: current.errorRaw,
            proofFiles = proof?.files ?: current.proofFiles,
            proofBuildTail = proof?.buildTail ?: current.proofBuildTail,
            stepCount = stepCount ?: current.stepCount,
            fileCount = fileCount ?: current.fileCount,
            visualTest = visualTest ?: current.visualTest,
        )
        persist()
    }

    private fun persist() {
        runCatching { store.save(tasks) }
    }

    private fun deriveProjectName(prompt: String, type: HarnessProjectType): String {
        val words = prompt.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() && it.length > 2 }
            .take(4)
        val base = words.joinToString(" ").ifBlank { "New project" }
        val suffix = when (type) {
            HarnessProjectType.ANDROID -> " app"
            HarnessProjectType.WEB -> " web app"
            HarnessProjectType.PYTHON -> " script"
            else -> ""
        }
        return (base + suffix).take(60).replaceFirstChar { it.uppercase() }
    }

    companion object {
        internal const val INSTALL_REQUIRED_MESSAGE =
            "The coding engine is not installed yet. Install it first, then say 'haan install karo'."
        internal const val RESUME_PROMPT =
            "Continue the previous task from where you stopped. Do not restart finished work. " +
                "First check which files already exist, then finish the remaining work."
        internal const val CORRECTION_PROMPT =
            "Testing band karo, ab finalize karo. Do not run more tests, installs or edits. " +
                "Start the preview/server if relevant, then give one short final summary."
        const val DEFAULT_COMMAND_TIMEOUT_MS = 120_000L
        private const val MAX_FEED_LINES = 60
        private const val MAX_ATTEMPTS = 5
        private const val MAX_PROOF_FILES = 30
        private const val MAX_FILE_TREE = 200
        private val RETRY_BACKOFF_SECONDS = listOf(30, 60, 120, 300, 600)
        private val EXCLUDED_DIRS = setOf("node_modules", ".git", "build", ".gradle", "__pycache__", ".venv")
        private val WEB_ENTRY_EXTENSIONS = setOf("html", "htm")

        val DEFAULT_TOOLCHAINS = setOf(
            HarnessProjectType.WEB,
            HarnessProjectType.PYTHON,
            HarnessProjectType.ANDROID,
            HarnessProjectType.CPP,
            HarnessProjectType.PHP,
        )

        @Volatile
        private var instance: HarnessEngine? = null

        fun initialize(
            context: Context,
            versionName: String,
            versionCode: Int,
            runtimeReleaseBaseUrl: String? = null,
            autoInstallOnFirstRun: Boolean = true,
        ): HarnessEngine {
            HarnessBuildConfig.versionName = versionName
            HarnessBuildConfig.versionCode = versionCode
            HarnessBuildConfig.autoInstallOnFirstRun = autoInstallOnFirstRun
            runtimeReleaseBaseUrl?.takeIf(String::isNotBlank)?.let {
                HarnessBuildConfig.runtimeReleaseBaseUrl = it.trimEnd('/')
            }
            return get(context)
        }

        fun get(context: Context): HarnessEngine =
            instance ?: synchronized(this) {
                instance ?: HarnessEngine(context.applicationContext as Application).also { instance = it }
            }

        fun assessCommand(command: String): HarnessCommandRisk {
            val normalized = command.lowercase().replace(Regex("\\s+"), " ")
            if (BLOCKED_PATTERNS.any { it.containsMatchIn(normalized) }) return HarnessCommandRisk.BLOCK
            if (CONFIRM_PATTERNS.any { it.containsMatchIn(normalized) }) return HarnessCommandRisk.CONFIRM
            return HarnessCommandRisk.SAFE
        }

        private val BLOCKED_PATTERNS = listOf(
            Regex("""\brm\s+(-[a-z]*\s+)*-?[rf]+\s*/(\s|$)"""),
            Regex("""\bmkfs(\.\w+)?\b"""),
            Regex("""\bdd\s+.*of=/dev/"""),
            Regex(""">\s*/dev/block/"""),
            Regex("""\b(shutdown|reboot|poweroff|halt)\b"""),
            Regex("""\bsu\b\s+-c"""),
            Regex("""\bpm\s+(uninstall|disable-user)\b"""),
            Regex("""/data/(data|user)/"""),
            Regex("""/storage/(emulated|[0-9a-f-]{36})/"""),
            Regex("""/sdcard/"""),
            Regex("""\bshizuku\b"""),
        )

        private val CONFIRM_PATTERNS = listOf(
            Regex("""\brm\s+-[a-z]*[rf]"""),
            Regex("""\brm\s+.*\*"""),
            Regex("""\bmv\s+.*\s+/dev/null"""),
            Regex("""\b(apt|apt-get|dpkg)\s+(remove|purge|autoremove|upgrade|dist-upgrade)\b"""),
            Regex("""\bpip3?\s+uninstall\b"""),
            Regex("""\bnpm\s+(uninstall|i|install)\s+-g\b"""),
            Regex("""\bgit\s+(reset\s+--hard|clean\s+-[a-z]*f)"""),
            Regex("""\btruncate\b|\b>\s*[^|]*\b(sh|bash)\b"""),
            Regex("""\b(chmod|chown)\s+-[a-z]*r\b"""),
        )
    }
}
