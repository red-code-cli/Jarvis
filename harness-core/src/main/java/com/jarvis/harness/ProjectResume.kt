package com.jarvis.harness

/**
 * Pure helpers behind "wo project continue karo".
 *
 * Kept free of Android/Context so the resume and ambiguity rules can be unit-tested
 * directly. [HarnessEngine] wires them to the durable project memory and the agents.
 */
internal const val RECENT_PROJECT_WINDOW_MS: Long = 24L * 60L * 60L * 1000L

/** A project that has not reached a terminal state is still resumable. */
internal fun HarnessProjectMemory.isIncomplete(): Boolean {
    val normalized = status.trim().lowercase()
    return normalized.isNotEmpty() && normalized !in TERMINAL_PROJECT_STATUSES
}

private val TERMINAL_PROJECT_STATUSES = setOf("completed", "cancelled", "canceled")

internal fun HarnessProjectMemory.displayName(): String = name.ifBlank { id }

/** Short "2h ago" style label; kept deterministic so it can be asserted in tests. */
internal fun lastActivityLabel(updatedAtMillis: Long, nowMillis: Long): String {
    if (updatedAtMillis <= 0L) return "unknown"
    val diff = (nowMillis - updatedAtMillis).coerceAtLeast(0L)
    val minutes = diff / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "${minutes}m ago"
        hours < 24L -> "${hours}h ago"
        else -> "${days}d ago"
    }
}

/**
 * Decides which saved project the user means. When more than one project could match a
 * bare "continue" it returns [Result.Ambiguous] so Jarvis asks ONE short question
 * instead of guessing.
 */
internal object ProjectResolver {

    sealed interface Result {
        data class Match(val memory: HarnessProjectMemory) : Result
        data class Ambiguous(val candidates: List<HarnessProjectMemory>) : Result
        object None : Result
    }

    fun resolve(
        query: String,
        memories: List<HarnessProjectMemory>,
        nowMillis: Long,
    ): Result {
        val ordered = memories
            .filter { it.id.isNotBlank() || it.name.isNotBlank() }
            .sortedByDescending { it.updatedAtMillis }
        if (ordered.isEmpty()) return Result.None

        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) return resolveNamed(trimmed, ordered)
        return resolveUnnamed(ordered, nowMillis)
    }

    /** One short, speakable question listing at most three candidate names. */
    fun question(candidates: List<HarnessProjectMemory>): String {
        val names = candidates.take(3).joinToString(" ya ") { it.displayName() }
        return "Kaunsa project continue karun — $names?"
    }

    private fun resolveNamed(query: String, ordered: List<HarnessProjectMemory>): Result {
        val byId = ordered.filter { it.id.equals(query, true) || it.projectId.equals(query, true) }
        if (byId.size == 1) return Result.Match(byId.first())
        if (byId.size > 1) return Result.Ambiguous(byId)

        val exactName = ordered.filter { it.name.equals(query, true) }
        if (exactName.size == 1) return Result.Match(exactName.first())
        if (exactName.size > 1) return Result.Ambiguous(exactName)

        val fuzzy = ordered.filter {
            it.name.contains(query, ignoreCase = true) || query.contains(it.name, ignoreCase = true)
        }
        return when {
            fuzzy.size == 1 -> Result.Match(fuzzy.first())
            fuzzy.size > 1 -> Result.Ambiguous(fuzzy)
            else -> Result.None
        }
    }

    /**
     * A bare "wo project continue karo" only auto-resumes when exactly one project is
     * recent or unfinished. Anything else asks the user which one.
     */
    private fun resolveUnnamed(ordered: List<HarnessProjectMemory>, nowMillis: Long): Result {
        val recent = ordered.filter { nowMillis - it.updatedAtMillis <= RECENT_PROJECT_WINDOW_MS }
        val incomplete = ordered.filter { it.isIncomplete() }
        val candidates = (recent + incomplete)
            .distinctBy { it.id }
            .sortedByDescending { it.updatedAtMillis }
        return when {
            candidates.size == 1 -> Result.Match(candidates.first())
            candidates.size > 1 -> Result.Ambiguous(candidates)
            // Nothing recent or unfinished: only assume it when there is a single project.
            ordered.size == 1 -> Result.Match(ordered.first())
            else -> Result.Ambiguous(ordered)
        }
    }
}

/**
 * Prompt for a NEW session when the agent has no resumable native session: the project
 * summary, file tree, todo/milestones and last checkpoint, so work continues instead of
 * restarting. Falls back to [HarnessEngine.RESUME_PROMPT] when nothing was saved yet.
 */
internal fun buildResumePrompt(memory: HarnessProjectMemory?): String {
    if (memory == null) return HarnessEngine.RESUME_PROMPT
    val milestones = memory.milestones.takeLast(MAX_RESUME_MILESTONES)
    val tree = memory.fileTree.take(MAX_RESUME_TREE_FILES)
    return buildString {
        appendLine("Continue this project from where it stopped. Do not restart finished work.")
        appendLine("PROJECT: ${memory.displayName()}")
        if (memory.summary.isNotBlank()) appendLine("SUMMARY: ${memory.summary.take(600)}")
        if (memory.checkpoint.isNotBlank()) appendLine("LAST CHECKPOINT: ${memory.checkpoint.take(400)}")
        if (milestones.isNotEmpty()) {
            appendLine("TODO / MILESTONES:")
            milestones.forEach { appendLine("- ${it.take(200)}") }
        }
        if (tree.isNotEmpty()) {
            appendLine("EXISTING FILES:")
            tree.forEach { appendLine("- $it") }
        }
        append(
            "First check which files already exist, then finish the remaining work " +
                "without redoing what is already done.",
        )
    }.trim()
}

private const val MAX_RESUME_MILESTONES = 12
private const val MAX_RESUME_TREE_FILES = 40
