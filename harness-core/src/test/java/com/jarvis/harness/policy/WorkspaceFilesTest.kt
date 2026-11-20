package com.jarvis.harness.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkspaceFilesTest {

    private fun tempRoot(): File = File.createTempFile("wsfiles", "").let { it.delete(); it.mkdirs(); it }

    @Test
    fun countsOnlyNonEmptyFilesAndSkipsExcludedDirs() {
        val root = tempRoot()
        try {
            File(root, "index.html").writeText("<h1>hi</h1>")
            File(root, "empty.txt").writeText("")
            File(root, "node_modules").mkdirs()
            File(root, "node_modules/dep.js").writeText("x")
            File(root, "sub").mkdirs()
            File(root, "sub/page.html").writeText("<h1>page</h1>")
            assertEquals(2, WorkspaceFiles.count(root))
            assertTrue(WorkspaceFiles.list(root).contains("sub/page.html"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun entryFilePrefersShallowestIndex() {
        val root = tempRoot()
        try {
            File(root, "sub").mkdirs()
            File(root, "sub/index.html").writeText("deep")
            File(root, "index.html").writeText("root")
            val entry = WorkspaceFiles.findEntryFile(root)
            assertEquals("index.html", entry?.name)
            assertEquals(root.canonicalPath, entry?.parentFile?.canonicalPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun entryFileFallsBackToAnyHtmlWithinDepth() {
        val root = tempRoot()
        try {
            File(root, "site").mkdirs()
            File(root, "site/work.html").writeText("<html></html>")
            val entry = WorkspaceFiles.findEntryFile(root)
            assertEquals("work.html", entry?.name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingEntryFileReturnsNull() {
        val root = tempRoot()
        try {
            File(root, "style.css").writeText("body{}")
            assertNull(WorkspaceFiles.findEntryFile(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun describeSearchMentionsPathAndCount() {
        val root = tempRoot()
        try {
            val text = WorkspaceFiles.describeSearch(root, 4)
            assertTrue(text.contains(root.absolutePath))
            assertTrue(text.contains("4 files"))
        } finally {
            root.deleteRecursively()
        }
    }
}
