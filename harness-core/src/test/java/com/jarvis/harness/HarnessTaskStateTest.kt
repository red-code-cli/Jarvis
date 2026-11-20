package com.jarvis.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task-state mapping: the durable record is what survives an app restart, and the
 * status object is what the voice tools report back. They must agree exactly.
 */
class HarnessTaskStateTest {

    private fun record(
        state: HarnessTaskState,
        verified: Boolean = false,
        deliverable: String? = null,
        changed: Int = 0,
    ) = HarnessTaskRecord(
        id = "task-1",
        projectName = "To Do App",
        prompt = "build a todo android app",
        projectType = HarnessProjectType.ANDROID,
        state = state,
        projectId = "project-1",
        createdAtMillis = 1_000L,
        updatedAtMillis = 2_000L,
        lastMessage = "Compiling",
        changedFiles = changed,
        deliverableLocation = deliverable,
        runtimeReady = true,
        verified = verified,
    )

    @Test
    fun `queued status keeps the queue position`() {
        val status = record(HarnessTaskState.QUEUED).toStatus(position = 3, queued = true)
        assertEquals(HarnessTaskState.QUEUED, status.state)
        assertTrue(status.queued)
        assertEquals(3, status.queuePosition)
    }

    @Test
    fun `completed status carries verification and location`() {
        val status = record(
            HarnessTaskState.COMPLETED,
            verified = true,
            deliverable = "/data/user/0/app/files/app-debug.apk",
            changed = 7,
        ).toStatus(position = 0, queued = false)

        assertEquals(HarnessTaskState.COMPLETED, status.state)
        assertTrue(status.verified)
        assertEquals(7, status.changedFiles)
        assertEquals("/data/user/0/app/files/app-debug.apk", status.deliverableLocation)
        assertFalse(status.queued)
    }

    @Test
    fun `paused and failed states are reported verbatim`() {
        assertEquals(HarnessTaskState.PAUSED, record(HarnessTaskState.PAUSED).toStatus(0, false).state)
        assertEquals(HarnessTaskState.FAILED, record(HarnessTaskState.FAILED).toStatus(0, false).state)
        assertEquals(HarnessTaskState.CANCELLED, record(HarnessTaskState.CANCELLED).toStatus(0, false).state)
    }

    @Test
    fun `status never invents a deliverable`() {
        val status = record(HarnessTaskState.RUNNING).toStatus(0, false)
        assertEquals(null, status.deliverableLocation)
        assertFalse(status.verified)
    }

    @Test
    fun `install requirement text is explicit about the user action`() {
        assertTrue(HarnessEngine.INSTALL_REQUIRED_MESSAGE.contains("not installed", ignoreCase = true))
        assertTrue(HarnessEngine.INSTALL_REQUIRED_MESSAGE.contains("install", ignoreCase = true))
    }

    @Test
    fun `interrupted is its own state and never looks completed`() {
        val status = record(HarnessTaskState.INTERRUPTED).toStatus(position = 0, queued = false)
        assertEquals(HarnessTaskState.INTERRUPTED, status.state)
        assertFalse(status.state == HarnessTaskState.COMPLETED)
        assertFalse(status.verified)
        assertTrue("interrupted must be resumable", status.state != HarnessTaskState.CANCELLED)
    }

    @Test
    fun `waiting retry carries provider attempts and the classified error`() {
        val error = HarnessError.classify("HTTP 429 rate limit", "OpenRouter")
        val record = record(HarnessTaskState.WAITING_RETRY).copy(
            attempts = 2,
            provider = "OpenRouter",
            httpStatus = error.httpStatus,
            errorHeadline = error.headline,
            errorKind = error.kind.name,
            errorHint = error.hint,
            errorRaw = error.raw,
        )
        val status = record.toStatus(0, false)
        assertEquals(HarnessTaskState.WAITING_RETRY, status.state)
        assertEquals(2, status.attempts)
        assertEquals("OpenRouter", status.provider)
        assertEquals(HarnessErrorKind.RATE_LIMIT, status.error?.kind)
        assertTrue(status.error?.headline?.contains("429") == true)
    }

    @Test
    fun `completed status exposes proof and verification`() {
        val record = record(
            HarnessTaskState.COMPLETED,
            verified = true,
            deliverable = "/data/user/0/app/files/workspaces/todo/app-debug.apk",
        ).copy(
            proofFiles = listOf("app/src/main/MainActivity.kt (1200B)"),
            proofBuildTail = "BUILD SUCCESSFUL",
            stepCount = 9,
            fileCount = 6,
        )
        val status = record.toStatus(0, false)
        assertEquals(9, status.steps)
        assertEquals(6, status.files)
        assertEquals("BUILD SUCCESSFUL", status.proof?.buildTail)
        assertEquals(1, status.proof?.files?.size)
        assertTrue(status.verified)
    }

    @Test
    fun `default toolchains cover every shipped stack`() {
        assertEquals(
            setOf(
                HarnessProjectType.WEB,
                HarnessProjectType.PYTHON,
                HarnessProjectType.ANDROID,
                HarnessProjectType.CPP,
                HarnessProjectType.PHP,
            ),
            HarnessEngine.DEFAULT_TOOLCHAINS,
        )
    }
}
