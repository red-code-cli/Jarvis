package com.jarvis.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSessionResumeTest {

    private fun memory(
        id: String,
        name: String,
        status: String,
        updatedAtMillis: Long,
        projectId: String = "ws-$id",
    ) = HarnessProjectMemory(
        id = id,
        projectId = projectId,
        name = name,
        status = status,
        updatedAtMillis = updatedAtMillis,
        createdAtMillis = updatedAtMillis,
    )

    @Test
    fun projectSessionRoundTripKeepsEverythingNeededToResume() {
        val session = HarnessProjectMemory(
            id = "task-9",
            projectId = "ws-9",
            name = "Portfolio",
            projectType = "web",
            originalRequest = "make a portfolio",
            finalPrompt = "portfolio with dark theme",
            features = listOf("hero"),
            decisions = listOf("static html"),
            status = "paused",
            checkpoint = "Edited index.html",
            stepsDone = 5,
            files = listOf("index.html", "style.css"),
            outputLocation = "/data/portfolio",
            previewUrl = "http://127.0.0.1:5173",
            previewPort = 5173,
            lastError = null,
            workspacePath = "/data/workspaces/ws-9",
            projectRoot = "app",
            agentType = "antigravity",
            agentSessionId = "session-abc",
            chatId = "chat-1",
            provider = "ANTIGRAVITY",
            model = "gemini-3.8-flash",
            tier = "pro",
            milestones = listOf("Queued", "Wrote index.html"),
            summary = "Portfolio · paused · 5 steps",
            fileTree = listOf("index.html", "style.css", "app/main.js"),
            createdAtMillis = 1000L,
            updatedAtMillis = 2000L,
        )

        val restored = HarnessProjectMemory.fromJson(session.toJson())

        assertEquals(session.projectId, restored.projectId)
        assertEquals(session.workspacePath, restored.workspacePath)
        assertEquals(session.projectRoot, restored.projectRoot)
        assertEquals(session.agentType, restored.agentType)
        assertEquals(session.agentSessionId, restored.agentSessionId)
        assertEquals(session.chatId, restored.chatId)
        assertEquals(session.provider, restored.provider)
        assertEquals(session.model, restored.model)
        assertEquals(session.tier, restored.tier)
        assertEquals(session.previewPort, restored.previewPort)
        assertEquals(session.milestones, restored.milestones)
        assertEquals(session.summary, restored.summary)
        assertEquals(session.fileTree, restored.fileTree)
        assertEquals(session.checkpoint, restored.checkpoint)
        assertEquals(session.status, restored.status)
        assertEquals(session.updatedAtMillis, restored.updatedAtMillis)
    }

    @Test
    fun summaryExposesSessionFieldsForVoiceAnswers() {
        val session = memory("task-1", "Notes", "paused", 5000L).copy(
            agentType = "deepseek-harness",
            agentSessionId = "sess-1",
            provider = "DEEPSEEK",
            model = "deepseek-v4-flash",
            previewUrl = "http://127.0.0.1:8080",
            previewPort = 8080,
            summary = "Notes · paused",
        )

        val summary = session.toSummary()

        assertEquals("task-1", summary.getString("project_id"))
        assertEquals("ws-task-1", summary.getString("workspace_project_id"))
        assertEquals("deepseek-harness", summary.getString("agent_type"))
        assertEquals("sess-1", summary.getString("agent_session_id"))
        assertEquals("DEEPSEEK", summary.getString("provider"))
        assertEquals("deepseek-v4-flash", summary.getString("model"))
        assertEquals(8080, summary.getInt("preview_port"))
    }

    @Test
    fun fallbackResumePromptCarriesSummaryFilesTodoAndCheckpoint() {
        val session = memory("task-7", "Blog", "paused", 9000L).copy(
            summary = "Blog · paused · 3 steps",
            checkpoint = "Edited posts.html",
            milestones = listOf("Queued", "Wrote index.html", "Edited posts.html"),
            fileTree = listOf("index.html", "posts.html"),
        )

        val prompt = buildResumePrompt(session)

        assertTrue(prompt.contains("Blog"))
        assertTrue(prompt.contains("SUMMARY: Blog · paused · 3 steps"))
        assertTrue(prompt.contains("LAST CHECKPOINT: Edited posts.html"))
        assertTrue(prompt.contains("TODO / MILESTONES:"))
        assertTrue(prompt.contains("- Wrote index.html"))
        assertTrue(prompt.contains("EXISTING FILES:"))
        assertTrue(prompt.contains("- posts.html"))
        assertTrue(prompt.contains("Do not restart finished work."))
    }

    @Test
    fun fallbackResumePromptWithoutMemoryUsesThePlainResumePrompt() {
        assertEquals(HarnessEngine.RESUME_PROMPT, buildResumePrompt(null))
    }

    @Test
    fun ambiguousContinueAsksOneQuestionInsteadOfGuessing() {
        val now = 10_000_000L
        val result = ProjectResolver.resolve(
            query = "",
            memories = listOf(
                memory("a", "Alpha", "paused", now - 1_000L),
                memory("b", "Beta", "running", now - 2_000L),
            ),
            nowMillis = now,
        )

        assertTrue(result is ProjectResolver.Result.Ambiguous)
        val candidates = (result as ProjectResolver.Result.Ambiguous).candidates
        assertEquals(2, candidates.size)
        val question = ProjectResolver.question(candidates)
        assertTrue(question.startsWith("Kaunsa project continue karun"))
        assertTrue(question.contains("Alpha"))
        assertTrue(question.contains("Beta"))
    }

    @Test
    fun singleRecentProjectResolvesWithoutAsking() {
        val now = 10_000_000L
        val result = ProjectResolver.resolve(
            query = "",
            memories = listOf(
                memory("a", "Alpha", "paused", now - 1_000L),
                memory("b", "Beta", "completed", now - RECENT_PROJECT_WINDOW_MS - 5_000L),
            ),
            nowMillis = now,
        )

        assertTrue(result is ProjectResolver.Result.Match)
        assertEquals("Alpha", (result as ProjectResolver.Result.Match).memory.name)
    }

    @Test
    fun namedProjectResolvesByIdOrName() {
        val now = 10_000_000L
        val memories = listOf(
            memory("a", "Alpha Notes", "paused", now - 1_000L),
            memory("b", "Beta Site", "paused", now - 2_000L),
        )

        val byId = ProjectResolver.resolve("b", memories, now)
        assertTrue(byId is ProjectResolver.Result.Match)
        assertEquals("Beta Site", (byId as ProjectResolver.Result.Match).memory.name)

        val byName = ProjectResolver.resolve("Alpha Notes", memories, now)
        assertTrue(byName is ProjectResolver.Result.Match)
        assertEquals("a", (byName as ProjectResolver.Result.Match).memory.id)
    }

    @Test
    fun unknownQueryReturnsNoneInsteadOfGuessing() {
        val now = 10_000_000L
        val result = ProjectResolver.resolve(
            query = "does-not-exist",
            memories = listOf(memory("a", "Alpha", "paused", now - 1_000L)),
            nowMillis = now,
        )
        assertTrue(result is ProjectResolver.Result.None)
    }
}
