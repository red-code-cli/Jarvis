package com.jarvis.harness

import java.io.File
import org.json.JSONObject

/**
 * Versioned on-disk cache for the project file-tree summary.
 *
 * [MainViewModel] counts the visible children of every workspace folder so the file tree
 * can show sizes and child counts. That walk used to be repeated after every restart. The
 * counts are stored under the app's private `filesDir`, which Android preserves across an
 * APK update because the applicationId never changes, so an update reuses the previous
 * summary instead of re-walking every folder.
 *
 * An entry is only trusted while the folder's modification time still matches the recorded
 * one, so a stale entry is recomputed rather than shown. A mismatched [VERSION] discards
 * the whole file and rebuilds it: that is the explicit migration for a change to this
 * format, and it keeps the file readable by any future app build.
 */
class ProjectTreeSummaryCache(private val file: File) {

    data class Entry(val modifiedAtMillis: Long, val childCount: Int)

    /** Reads the stored summary, or an empty map when the file is missing or from another version. */
    @Synchronized
    fun read(): Map<String, Entry> {
        if (!file.isFile) return emptyMap()
        return runCatching<Map<String, Entry>> {
            val root = JSONObject(file.readText())
            if (root.optInt(KEY_VERSION) != VERSION) return@runCatching emptyMap()
            val entries = root.optJSONObject(KEY_ENTRIES) ?: return@runCatching emptyMap()
            buildMap {
                entries.keys().forEach { key ->
                    val item = entries.optJSONObject(key) ?: return@forEach
                    put(key, Entry(item.optLong("modifiedAtMillis"), item.optInt("childCount")))
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** Replaces the stored summary with [entries]. The whole snapshot is written atomically. */
    @Synchronized
    fun write(entries: Map<String, Entry>) {
        val encoded = JSONObject()
        entries.forEach { (key, entry) ->
            encoded.put(
                key,
                JSONObject()
                    .put("modifiedAtMillis", entry.modifiedAtMillis)
                    .put("childCount", entry.childCount),
            )
        }
        val payload = JSONObject()
            .put(KEY_VERSION, VERSION)
            .put(KEY_ENTRIES, encoded)
        file.parentFile?.mkdirs()
        val staged = File(file.parentFile, "${file.name}.tmp")
        staged.writeText(payload.toString())
        file.delete()
        if (!staged.renameTo(file)) throw java.io.IOException("Could not replace ${file.name}")
    }

    companion object {
        /** Bump whenever the stored shape changes so older files are discarded, not misread. */
        const val VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_ENTRIES = "entries"
    }
}
