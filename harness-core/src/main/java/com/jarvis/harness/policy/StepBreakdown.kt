package com.jarvis.harness.policy

/** The four work buckets the telemetry feed breaks steps into (B2.3). */
enum class StepCategory { BUILD, EDIT, VERIFY, TEST, OTHER }

/** Running tally of build/edit/verify/test steps for one task. */
data class StepBreakdown(
    val build: Int = 0,
    val edit: Int = 0,
    val verify: Int = 0,
    val test: Int = 0,
    val other: Int = 0,
) {
    fun plus(category: StepCategory): StepBreakdown = when (category) {
        StepCategory.BUILD -> copy(build = build + 1)
        StepCategory.EDIT -> copy(edit = edit + 1)
        StepCategory.VERIFY -> copy(verify = verify + 1)
        StepCategory.TEST -> copy(test = test + 1)
        StepCategory.OTHER -> copy(other = other + 1)
    }

    /** Compact form for the Agent Live card / telemetry feed. */
    fun render(): String = "build $build · edit $edit · verify $verify · test $test"
}

/**
 * Classifies a step into a [StepCategory] from a tool name and its command/detail text.
 * File writes/edits are treated as EDIT by the engine; this covers commands and other tools.
 */
object StepClassifier {

    private val BUILD_COMMANDS = listOf(
        "gradle", "gradlew", "make", "cmake", "ninja", "cargo build", "go build",
        "npm run build", "npm build", "yarn build", "pnpm build", "bun build",
        "vite build", "webpack", "tsc", "javac", "mvn", "gcc", "clang", "dotnet build",
        "pip install", "npm install", "npm i ", "yarn add", "pnpm add",
    )

    private val VERIFY_COMMANDS = listOf(
        "curl", "wget", "http://", "https://", "test -f", "test -d", "ls -", "ls ",
        "file ", "stat ", "readlink", "head ", "tail ", "python -m http.server",
        "http.server", "serve",
    )

    private val BUILD_TOOL_HINTS = listOf("build", "compile")
    private val VERIFY_TOOL_HINTS = listOf("verify", "check", "http", "curl", "fetch")
    private val EDIT_TOOL_HINTS = listOf("write", "edit", "create", "patch", "replace", "apply", "save")

    fun classify(toolName: String, detail: String, config: AgentPolicyConfig = AgentPolicyConfig.DEFAULT): StepCategory {
        val command = detail.lowercase()
        val name = toolName.lowercase()

        if (isTestingCommand(detail, config.testingPatterns)) return StepCategory.TEST

        if (EDIT_TOOL_HINTS.any { name.contains(it) }) return StepCategory.EDIT
        if (BUILD_TOOL_HINTS.any { name.contains(it) }) return StepCategory.BUILD
        if (VERIFY_TOOL_HINTS.any { name.contains(it) }) return StepCategory.VERIFY

        if (BUILD_COMMANDS.any { command.contains(it) }) return StepCategory.BUILD
        if (VERIFY_COMMANDS.any { command.contains(it) }) return StepCategory.VERIFY
        return StepCategory.OTHER
    }

    fun isTestingCommand(command: String, patterns: List<Regex> = AgentPolicyConfig.DEFAULT.testingPatterns): Boolean {
        val text = command.lowercase()
        return patterns.any { it.containsMatchIn(text) }
    }
}
