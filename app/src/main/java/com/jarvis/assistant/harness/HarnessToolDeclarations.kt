package com.jarvis.assistant.harness

import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini Live function declarations for the coding engine.
 *
 * Kept out of GeminiLiveWebSocket's already very large setup builder so the engine
 * surface is easy to find and change. Jarvis's existing declarations are untouched;
 * these are appended to the same `functionDeclarations` array.
 */
object HarnessToolDeclarations {

    fun appendTo(declarations: JSONArray) {
        declarations.put(
            tool(
                name = "start_build_task",
                description = "Starts an autonomous coding task on this phone. Returns immediately with a task_id so the conversation can continue. Use when the user asks to build an app, website, script, tool, or project. Build a detailed, self-contained prompt from what the user said (platform, features, constraints, folder name) before calling. Never claim it finished; use get_task_status for the truth.",
                properties = JSONObject()
                    .put("description", string("Detailed build brief: what to build, platform, features, UI, constraints, and any naming the user gave."))
                    .put("project_type", string("One of: web, android, python, cpp, php, general. Ask one short question if the user's platform is genuinely unclear."))
                    .put("project_name", string("Optional short folder/project name. Omit to derive it from the brief.")),
                required = arrayOf("description", "project_type"),
            ),
        )
        declarations.put(
            tool(
                name = "get_task_status",
                description = "Returns the real, current state of a coding task: running, queued, paused, completed, failed or cancelled, plus changed file count, verification result and output location. Always call this before telling the user how far the work got. Never guess.",
                properties = JSONObject()
                    .put("task_id", string("Task id from start_build_task. Omit for the most recent task.")),
                required = emptyArray(),
            ),
        )
        declarations.put(
            tool(
                name = "pause_task",
                description = "Pauses the running coding task after saving a checkpoint. Use when the user says stop / ruko / rok do / pause about the TASK. Say: 'Project roka. Continue bolo to wahin se shuru karunga.' Do not pause just because the user wants you to stop speaking; if it is ambiguous, ask one short question first.",
                properties = JSONObject().put("task_id", string("Task id. Omit for the most recent task.")),
                required = emptyArray(),
            ),
        )
        declarations.put(
            tool(
                name = "resume_task",
                description = "Resumes a paused or interrupted coding task from its last checkpoint. Use when the user says continue / aage badho / jaari rakho.",
                properties = JSONObject().put("task_id", string("Task id. Omit for the most recent task.")),
                required = emptyArray(),
            ),
        )
        declarations.put(
            tool(
                name = "cancel_task",
                description = "PERMANENTLY cancels and deletes a task. This is different from pause. Only call after the user used an explicit cancel phrase (cancel karo / delete karo / band karo) AND confirmed out loud. Never infer it from 'stop'.",
                properties = JSONObject().put("task_id", string("Task id. Omit for the most recent task.")),
                required = emptyArray(),
            ),
        )
        declarations.put(
            tool(
                name = "send_followup",
                description = "Adds more instructions to a running or paused coding task, e.g. 'ye feature bhi add karo'. Queues behind the current step if the agent is busy.",
                properties = JSONObject()
                    .put("text", string("The additional instruction, in the user's words."))
                    .put("task_id", string("Task id. Omit for the most recent task.")),
                required = arrayOf("text"),
            ),
        )
        declarations.put(
            tool(
                name = "list_projects",
                description = "Lists coding projects with persistent memory: status, last checkpoint, last activity, steps, files, preview URL and whether each can be resumed. Use when the user asks kaun se projects bane hain or pichla project kaun sa tha. Then use get_project_status or resume_project for one project.",
                properties = JSONObject(),
                required = emptyArray(),
            ),
        )
        declarations.put(
            tool(
                name = "resume_project",
                description = "Continues a saved project from where it stopped, reusing its workspace and agent session when possible, otherwise starting a new session with the saved summary, file tree, todo and checkpoint. Use for continue / aage badho on a PROJECT. If more than one project could match, the result has ambiguous=true with a question: ask that ONE short question and call again with the chosen project. Never guess which project.",
                properties = JSONObject()
                    .put("project", string("Project name or project id. Omit for the most recent project.")),
                required = emptyArray(),
            ),
        )
        declarations.put(
            tool(
                name = "get_project_status",
                description = "Returns one project's persisted session state: status, last checkpoint, summary, milestones/todo, file-tree snapshot, workspace path, agent, provider/model, preview URL/port and last activity. Use before answering kahan tak bana about a project. If more than one project could match it returns ambiguous=true with a question; ask that ONE short question.",
                properties = JSONObject()
                    .put("project", string("Project name or project id. Omit for the most recent project.")),
                required = emptyArray(),
            ),
        )
        declarations.put(
            tool(
                name = "get_project_memory",
                description = "Returns the persistent memory of one project: original request, features, decisions, status, checkpoint, steps done, files, output location, preview URL and last error. This memory is separate from Yaadein and survives restarts. ALWAYS call this before answering 'pichla project kahan tak bana' — never guess.",
                properties = JSONObject()
                    .put("project", string("Project name or task_id. Omit for the most recent project."))
                    .put("task_id", string("Optional task id.")),
                required = emptyArray(),
            ),
        )
        declarations.put(
            tool(
                name = "open_preview",
                description = "Opens the web project's preview (or the running dev server) in Jarvis Browser and says 'Preview khol diya'. Use only when a preview URL exists or the web project is ready.",
                properties = JSONObject()
                    .put("task_id", string("Task id. Omit for the most recent task."))
                    .put("url", string("Optional explicit preview URL.")),
                required = emptyArray(),
            ),
        )
        declarations.put(
            tool(
                name = "get_app_state",
                description = "Returns the REAL current state of the app: engine installed, installed agents, active provider and whether its key is set, current persona/voice/language, allowlisted settings, active and resumable tasks, project count. Call this before answering any question about what is installed, which model is running, or what failed. Never guess. API keys are never returned.",
                properties = JSONObject(),
                required = emptyArray(),
            ),
        )
        declarations.put(
            tool(
                name = "list_providers",
                description = "Lists the coding engine's API providers with protocol, base URL, default model, whether the user added a key, and whether the provider is experimental.",
                properties = JSONObject(),
                required = emptyArray(),
            ),
        )
        declarations.put(
            tool(
                name = "list_agents",
                description = "Lists the built-in coding agents (Claude Code, DeepSeek Harness, Antigravity CLI) with whether each one is installed, its version, whether an update is available, the providers each supports, and whether it can be removed. Each agent installs/updates/removes independently; never claim one is installed unless installed=true.",
                properties = JSONObject(),
                required = emptyArray(),
            ),
        )
        declarations.put(
            tool(
                name = "set_setting",
                description = "Changes one app setting by name. Only safe, allowlisted settings work. Security-sensitive ones (owner_voice, owner_voice_threshold) require the user to confirm out loud first, then call again with confirmed=true. Never set an API key through this tool.",
                properties = JSONObject()
                    .put("key", string("Setting name, e.g. persona, voice, language, wake_sensitivity, wake_words, interrupt_sensitivity, silent_mode, noise_filter, owner_voice, owner_voice_threshold."))
                    .put("value", string("New value."))
                    .put("confirmed", boolean("Set true only after the user confirmed a sensitive change out loud.")),
                required = arrayOf("key", "value"),
            ),
        )
        declarations.put(
            tool(
                name = "open_settings",
                description = "Opens the app's Settings screen, optionally at a named category (voice, coding, ai, memory, appearance, permissions, about). Use when the user asks to open settings or to change something you must do by hand.",
                properties = JSONObject()
                    .put("category", string("Optional category: voice, phone, coding, ai, memory, appearance, permissions, about.")),
                required = emptyArray(),
            ),
        )
        declarations.put(
            tool(
                name = "get_project_location",
                description = "Returns where a project lives, plus the Downloads path if a finished deliverable was published there.",
                properties = JSONObject().put("project_id", string("Project id from list_projects.")),
                required = arrayOf("project_id"),
            ),
        )
        declarations.put(
            tool(
                name = "run_terminal_command",
                description = "Runs a shell command inside the coding engine's private Linux sandbox only (mkdir, ls, cp, mv, cat, git, pip/npm/apt and similar). Never affects the Android system. Destructive commands come back with needs_confirmation=true; ask the user out loud and call again with confirmed=true only after they agree.",
                properties = JSONObject()
                    .put("cmd", string("The shell command to run inside the Linux sandbox."))
                    .put("confirmed", JSONObject().apply {
                        put("type", "BOOLEAN")
                        put("description", "Set true only after the user verbally confirmed a destructive command.")
                    }),
                required = arrayOf("cmd"),
            ),
        )
        declarations.put(
            tool(
                name = "install_coding_engine",
                description = "Starts installing the coding engine (Ubuntu, Node.js, npm, Git) plus the toolchains and the DeepSeek Harness agent. Only call after the user agrees. Returns immediately; progress is reported separately.",
                properties = JSONObject(),
                required = emptyArray(),
            ),
        )
        declarations.put(
            tool(
                name = "coding_engine_status",
                description = "Reports whether the coding engine is installed and supported, its install progress, and which agents and toolchains are present. Use before claiming anything about the engine.",
                properties = JSONObject(),
                required = emptyArray(),
            ),
        )
        declarations.put(
            tool(
                name = "get_task_log",
                description = "Returns the real, timestamped event log of a coding task: file writes/edits with +/-, bash commands with exit codes, steps, errors and retries. This is the only source of truth for 'kahan tak bana' — read it before answering, never guess.",
                properties = JSONObject()
                    .put("task_id", string("Task id. Omit for the most recent task."))
                    .put("limit", JSONObject().apply {
                        put("type", "INTEGER")
                        put("description", "How many recent events (1-200, default 40).")
                    }),
                required = emptyArray(),
            ),
        )
        declarations.put(
            tool(
                name = "get_recent_tasks",
                description = "Lists the most recent coding tasks with state, verification result and output location. Use on session start or when the user asks what happened recently.",
                properties = JSONObject(),
                required = emptyArray(),
            ),
        )
    }

    private fun tool(name: String, description: String, properties: JSONObject, required: Array<String>): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", properties)
                put("required", JSONArray(required.toList()))
            })
        }

    private fun string(description: String): JSONObject =
        JSONObject().put("type", "STRING").put("description", description)

    private fun boolean(description: String): JSONObject =
        JSONObject().put("type", "BOOLEAN").put("description", description)
}