package com.jarvis.assistant.data.repository

import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChatRepository(private val preferences: AppPreferences) {

    private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val turns: StateFlow<List<ChatTurn>> = _turns.asStateFlow()

    init {
        _turns.value = preferences.loadChatHistory()
    }

    @Synchronized
    fun addTurn(userText: String, jarvisText: String) {
        val cleanUser = userText.trim()
        val cleanJarvis = jarvisText.trim()

        if (cleanUser.isEmpty() && cleanJarvis.isEmpty()) return

        val currentList = _turns.value.toMutableList()

        if (currentList.isNotEmpty()) {
            val last = currentList.last()
            if (last.userTranscript == cleanUser && cleanJarvis.isNotEmpty() && last.jarvisResponse.isBlank()) {
                currentList[currentList.lastIndex] = last.copy(jarvisResponse = cleanJarvis)
                _turns.value = currentList
                preferences.saveChatHistory(currentList)
                return
            }
            if (last.userTranscript == cleanUser && last.jarvisResponse == cleanJarvis) {
                return
            }
        }

        val newTurn = ChatTurn(
            userTranscript = cleanUser.ifEmpty { "(Audio query)" },
            jarvisResponse = cleanJarvis
        )

        currentList.add(newTurn)
        if (currentList.size > 100) {
            currentList.removeAt(0)
        }

        _turns.value = currentList
        preferences.saveChatHistory(currentList)
    }

    @Synchronized
    fun clearHistory() {
        _turns.value = emptyList()
        preferences.clearChatHistory()
    }

    @Synchronized
    fun replaceHistory(turns: List<ChatTurn>) {
        val clean = turns.takeLast(100)
        _turns.value = clean
        preferences.saveChatHistory(clean)
    }
}
