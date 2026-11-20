package com.jarvis.harness

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTreeSummaryCacheTest {

    private fun withCacheFile(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("mh-tree-cache-test").toFile()
        try {
            block(directory.resolve("project-tree-summary.json"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun roundTripsFolderCounts() = withCacheFile { file ->
        val cache = ProjectTreeSummaryCache(file)
        val entries = mapOf(
            "project:src" to ProjectTreeSummaryCache.Entry(modifiedAtMillis = 1_700_000_000_000, childCount = 7),
            "project:" to ProjectTreeSummaryCache.Entry(modifiedAtMillis = 1_700_000_000_001, childCount = 3),
        )

        cache.write(entries)

        assertEquals(entries, ProjectTreeSummaryCache(file).read())
    }

    @Test
    fun missingFileReadsAsEmpty() = withCacheFile { file ->
        assertEquals(emptyMap<String, ProjectTreeSummaryCache.Entry>(), ProjectTreeSummaryCache(file).read())
    }

    @Test
    fun anotherFormatVersionIsDiscardedAndRebuilt() = withCacheFile { file ->
        file.writeText(
            """{"version":${ProjectTreeSummaryCache.VERSION + 1},"entries":{"project:src":{"modifiedAtMillis":1,"childCount":2}}}""",
        )

        val cache = ProjectTreeSummaryCache(file)
        assertTrue(cache.read().isEmpty())

        cache.write(mapOf("project:src" to ProjectTreeSummaryCache.Entry(2, 4)))
        assertEquals(4, ProjectTreeSummaryCache(file).read().getValue("project:src").childCount)
    }

    @Test
    fun unreadableFileReadsAsEmptyInsteadOfThrowing() = withCacheFile { file ->
        file.writeText("{not-json")
        assertTrue(ProjectTreeSummaryCache(file).read().isEmpty())
    }

    @Test
    fun rewritingReplacesPreviousEntries() = withCacheFile { file ->
        val cache = ProjectTreeSummaryCache(file)
        cache.write(mapOf("project:a" to ProjectTreeSummaryCache.Entry(1, 1)))
        cache.write(mapOf("project:b" to ProjectTreeSummaryCache.Entry(2, 2)))

        val read = ProjectTreeSummaryCache(file).read()
        assertEquals(setOf("project:b"), read.keys)
    }
}
