package com.jarvis.harness.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedTextTest {

    @Test
    fun detailAlreadyInTitleIsNotDuplicated() {
        val line = FeedText.compose("Wrote index.html", "index.html")
        assertEquals("Wrote index.html", line)
        assertFalse(line.contains("index.htmlindex.html"))
    }

    @Test
    fun fileNameDetailIsNotDuplicatedWithFullPath() {
        val line = FeedText.compose("Wrote index.html", "src/index.html")
        assertEquals("Wrote index.html", line)
    }

    @Test
    fun unrelatedDetailIsAppended() {
        val line = FeedText.compose("Running", "npm run build")
        assertTrue(line.startsWith("Running"))
        assertTrue(line.contains("npm run build"))
    }

    @Test
    fun truncationHappensOnWordBoundary() {
        val long = "a".repeat(50) + " " + "b".repeat(100)
        val line = FeedText.truncate(long, 120)
        assertEquals("a".repeat(50) + "...", line)
        assertFalse(line.contains("b"))
    }

    @Test
    fun truncationKeepsShortTextUntouched() {
        assertEquals("hello world", FeedText.truncate("hello world", 40))
    }

    @Test
    fun onlyFirstLineIsKept() {
        assertEquals("first line", FeedText.truncate("first line\nsecond line"))
    }
}
