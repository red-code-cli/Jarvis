package com.jarvis.harness.policy

import java.io.File

/**
 * Ground-truth view of a project workspace on disk. The files counter and the entry-file
 * check both come from here, so a missing tool event can never make the count wrong.
 *
 * Pure (only [java.io.File]) and depth-limited, so it stays cheap even for large projects.
 */
object WorkspaceFiles {

    /** Relative paths of non-empty files, sorted, excluding build/dependency dirs. */
    fun list(
        root: File?,
        excludeDirs: Set<String> = AgentPolicyConfig.EXCLUDED_DIRS,
        maxFiles: Int = AgentPolicyConfig.MAX_FILE_TREE,
    ): List<String> {
        if (root == null || !root.isDirectory) return emptyList()
        val out = ArrayList<String>()
        val queue = ArrayDeque<File>()
        queue.addLast(root)
        while (queue.isNotEmpty() && out.size < maxFiles) {
            val dir = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (out.size >= maxFiles) break
                if (child.isDirectory) {
                    if (child.name !in excludeDirs && !child.name.startsWith(".")) queue.addLast(child)
                } else if (child.isFile && child.length() > 0L) {
                    out.add(child.relativeTo(root).invariantSeparatorsPath)
                }
            }
        }
        out.sort()
        return out
    }

    fun count(root: File?, excludeDirs: Set<String> = AgentPolicyConfig.EXCLUDED_DIRS): Int =
        list(root, excludeDirs, Int.MAX_VALUE / 2).size

    /**
     * The web entry file to serve. Prefers the shallowest `index.html` within [maxDepth],
     * then the shallowest `*.html`. Returns null when there is none in range.
     */
    fun findEntryFile(
        root: File?,
        maxDepth: Int = AgentPolicyConfig.ENTRY_FILE_MAX_DEPTH,
        excludeDirs: Set<String> = AgentPolicyConfig.EXCLUDED_DIRS,
    ): File? {
        if (root == null || !root.isDirectory) return null
        val files = ArrayList<Pair<Int, File>>()
        walk(root, maxDepth, excludeDirs) { file, depth -> files.add(depth to file) }
        return files
            .filter { it.second.name.equals("index.html", ignoreCase = true) }
            .minByOrNull { it.first }
            ?.second
            ?: files
                .filter {
                    it.second.extension.equals("html", ignoreCase = true) ||
                        it.second.extension.equals("htm", ignoreCase = true)
                }
                .minByOrNull { it.first }
                ?.second
    }

    /** Human-readable "checked <path> (N files, depth <= D)" line for a failed verification. */
    fun describeSearch(
        root: File?,
        fileCount: Int,
        maxDepth: Int = AgentPolicyConfig.ENTRY_FILE_MAX_DEPTH,
    ): String = "checked ${root?.absolutePath ?: "no workspace"} ($fileCount files, depth <= $maxDepth)"

    private fun walk(root: File, maxDepth: Int, excludeDirs: Set<String>, onFile: (File, Int) -> Unit) {
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.addLast(root to 0)
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    if (depth < maxDepth && child.name !in excludeDirs && !child.name.startsWith(".")) {
                        queue.addLast(child to depth + 1)
                    }
                } else if (child.isFile) {
                    onFile(child, depth + 1)
                }
            }
        }
    }
}
