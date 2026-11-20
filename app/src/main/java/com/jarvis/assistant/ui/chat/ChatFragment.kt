package com.jarvis.assistant.ui.chat

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.databinding.FragmentChatBinding
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.ui.home.MainViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel get() = (requireActivity() as MainActivity).viewModel

    private val chatAdapter = JarvisChatAdapter(
        onEditUserMessage = { text ->
            binding.etMessageInput.setText(text)
            binding.etMessageInput.setSelection(text.length)
            binding.etMessageInput.requestFocus()
        }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        binding.rvChatMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvChatMessages.adapter = chatAdapter

        binding.btnSendMessage.setOnClickListener { sendMessage() }
        binding.etMessageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }

        binding.btnChatNew.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Nayi chat")
                .setMessage("Current chat history clear karke nayi conversation shuru karein?")
                .setPositiveButton("NEW CHAT") { _, _ ->
                    JarvisApp.instance.chatRepository.clearHistory()
                }
                .setNegativeButton("CANCEL", null)
                .show()
        }

        binding.btnAttach.setOnClickListener {
            (activity as? MainActivity)?.toggleScreenShare()
        }

        observeChatTurns()
        observeStatus()
    }

    private fun sendMessage() {
        val text = binding.etMessageInput.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        binding.etMessageInput.text?.clear()
        (activity as? MainActivity)?.sendTextToJarvis(text)
    }

    private fun observeChatTurns() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.chatTurns.collect { turns ->
                    chatAdapter.submitList(turns) {
                        binding.emptyChatState.visibility = if (turns.isEmpty()) View.VISIBLE else View.GONE
                        if (turns.isNotEmpty()) {
                            binding.rvChatMessages.smoothScrollToPosition(turns.size - 1)
                        }
                    }
                }
            }
        }
    }

    private fun observeStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        viewModel.isSessionOn,
                        viewModel.connectionStatus,
                        viewModel.conversationState,
                        JarvisConversationService.isStandby
                    ) { isOn, connection, state, standby ->
                        ChatState(isOn, connection, state, standby)
                    }.collect { state ->
                        binding.tvChatSubtitle.text = when {
                            !state.isOn -> "Gemini Live • OFF"
                            state.standby -> "Gemini Live • STANDBY"
                            state.connection != "LIVE" -> "Gemini Live • ${state.connection}"
                            state.state == ConversationState.LISTENING -> "Gemini Live • LISTENING"
                            state.state == ConversationState.SPEAKING -> "Gemini Live • SPEAKING"
                            state.state == ConversationState.THINKING -> "Gemini Live • THINKING"
                            else -> "Gemini Live • LIVE"
                        }
                        binding.tvChatSubtitle.setTextColor(
                            if (state.isOn && state.connection == "LIVE") ContextCompat.getColor(requireContext(), R.color.status_green)
                            else themeColor(R.attr.themeTextMuted, ContextCompat.getColor(requireContext(), R.color.text_muted))
                        )
                    }
                }

                launch {
                    val capture = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance()
                    combine(capture.isSharing, capture.hasReceivedFrame) { sharing, hasFrame -> sharing to hasFrame }
                        .collect { (sharing, hasFrame) ->
                            binding.btnAttach.setImageResource(
                                if (sharing) R.drawable.ic_screen_share else R.drawable.ic_attach
                            )
                            binding.btnAttach.imageTintList = ColorStateList.valueOf(
                                if (sharing && hasFrame) ContextCompat.getColor(requireContext(), R.color.status_green)
                                else themeColor(R.attr.themeTextPrimary, ContextCompat.getColor(requireContext(), R.color.text_primary))
                            )
                            binding.btnAttach.contentDescription =
                                if (sharing) "Stop sharing screen" else "Share screen"
                        }
                }
            }
        }
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val value = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, value, true)
        return if (value.data != 0) value.data else fallback
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private data class ChatState(
    val isOn: Boolean,
    val connection: String,
    val state: ConversationState,
    val standby: Boolean
)
