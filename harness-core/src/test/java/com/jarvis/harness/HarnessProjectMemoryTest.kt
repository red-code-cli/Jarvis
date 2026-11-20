package com.jarvis.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessProjectMemoryTest {

    @Test
    fun roundTripKeepsEverythingNeededToAnswerPichlaProject() {
        val memory = HarnessProjectMemory(
            id = "task-1",
            name = "Portfolio Website",
            projectType = "web",
            originalRequest = "make me a portfolio site",
            finalPrompt = "portfolio with dark theme",
            features = listOf("hero", "projects"),
            decisions = listOf("used static html"),
            status = "paused",
            checkpoint = "Edited index.html",
            stepsDone = 4,
            files = listOf("index.html", "style.css"),
            outputLocation = "/data/projects/portfolio",
            previewUrl = "http://127.0.0.1:5173",
            lastError = null,
            createdAtMillis = 1000L,
            updatedAtMillis = 2000L,
        )

        val restored = HarnessProjectMemory.fromJson(memory.toJson())

        assertEquals(memory.id, restored.id)
        assertEquals(memory.name, restored.name)
        assertEquals(memory.projectType, restored.projectType)
        assertEquals(memory.originalRequest, restored.originalRequest)
        assertEquals(memory.features, restored.features)
        assertEquals(memory.decisions, restored.decisions)
        assertEquals(memory.status, restored.status)
        assertEquals(memory.checkpoint, restored.checkpoint)
        assertEquals(memory.stepsDone, restored.stepsDone)
        assertEquals(memory.files, restored.files)
        assertEquals(memory.outputLocation, restored.outputLocation)
        assertEquals(memory.previewUrl, restored.previewUrl)
        assertEquals(memory.updatedAtMillis, restored.updatedAtMillis)
    }

    @Test
    fun summaryExposesTheFieldsJarvisAnswersFrom() {
        val memory = HarnessProjectMemory(
            id = "t2",
            name = "Notes App",
            status = "completed",
            stepsDone = 9,
            files = listOf("a.kt", "b.kt"),
            outputLocation = "/data/projects/notes",
        )

        val summary = memory.toSummary()
        assertEquals("t2", summary.getString("project_id"))
        assertEquals("completed", summary.getString("status"))
        assertEquals(9, summary.getInt("steps_done"))
        assertEquals(2, summary.getInt("files"))
        assertEquals("/data/projects/notes", summary.getString("output_location"))
        assertTrue(summary.getString("checkpoint").isEmpty())
    }
}
