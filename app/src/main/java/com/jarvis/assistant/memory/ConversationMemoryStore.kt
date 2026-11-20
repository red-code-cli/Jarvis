package com.jarvis.assistant.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Durable conversation memory for the live voice session.
 *
 * Gemini Live sessions are renewed every few minutes and can drop at any time. The
 * server forgets the old turns, so Jarvis persists every finished turn here and
 * injects a rolling summary + the last few turns into the system prompt of every
 * new or reconnected session.
 */
class ConversationMemoryStore(context: Context) {

    private val file = File(context.filesDir, "conversation_memory.json")

    data class Turn(val user: String, val assistant: String, val atMillis: Long, val persona: String = "")

    @Synchronized
    fun recordTurn(user: String, assistant: String, persona: String = "") {
        val cleanUser = user.trim()
        val cleanAssistant = assistant.trim()
        if (cleanUser.isBlank() || cleanAssistant.isBlank()) return
        val turns = load().toMutableList()
        turns.add(
            Turn(
                user = cleanUser.take(500),
                assistant = cleanAssistant.take(800),
                atMillis = System.currentTimeMillis(),
                persona = persona,
            ),
        )
        while (turns.size > MAX_TURNS) turns.removeAt(0)
        save(turns)
    }

    @Synchronized
    fun turns(): List<Turn> = load()

    /**
     * Text block for the system prompt.
     *
     * Split by design (see §7): the older summary is SHARED across every persona, so
     * facts (name, preferences, plans, projects) are never lost when the persona
     * changes. The verbatim recent turns are PERSONA-SCOPED, so each character keeps
     * its own tone, rapport and in-jokes and one persona's style never leaks into another.
     */
    @Synchronized
    fun contextBlock(persona: String = ""): String {
        val turns = load()
        if (turns.isEmpty()) return ""
        val mine = if (persona.isBlank()) turns else turns.filter { it.persona == persona || it.persona.isBlank() }
        val verbatim = mine.takeLast(VERBATIM_TURNS)
        val shared = turns.dropLast(VERBATIM_TURNS)
        return buildString {
            appendLine("CONVERSATION MEMORY (your real history with the user; use it, never contradict it):")
            if (shared.isNotEmpty()) {
                appendLine("Shared facts from earlier conversations (same for every persona):")
                shared.takeLast(12).forEach { turn ->
                    append("- User asked: ").append(turn.user.take(140))
                    append(" → answered about: ").append(turn.assistant.take(120)).appendLine()
                }
            }
            if (verbatim.isNotEmpty()) {
                appendLine("Your recent turns as this persona (keep this tone and rapport):")
                verbatim.forEach { turn ->
                    append("User: ").append(turn.user.take(300)).appendLine()
                    append("You: ").append(turn.assistant.take(300)).appendLine()
                }
            }
        }
    }

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }

    private fun load(): List<Turn> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("turns") ?: JSONArray()
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                Turn(
                    user = item.optString("user"),
                    assistant = item.optString("assistant"),
                    atMillis = item.optLong("at"),
                    persona = item.optString("persona"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(turns: List<Turn>) {
        runCatching {
            val array = JSONArray()
            turns.forEach { turn ->
                array.put(
                    JSONObject()
                        .put("user", turn.user)
                        .put("assistant", turn.assistant)
                        .put("at", turn.atMillis)
                        .put("persona", turn.persona),
                )
            }
            val payload = JSONObject().put("version", 1).put("turns", array).toString()
            val staged = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(staged).use { out ->
                out.write(payload.toByteArray())
                out.flush()
                out.fd.sync()
            }
            if (!staged.renameTo(file)) {
                file.delete()
                staged.renameTo(file)
            }
        }
    }

    companion object {
        private const val MAX_TURNS = 60
        private const val VERBATIM_TURNS = 6
    }
}
