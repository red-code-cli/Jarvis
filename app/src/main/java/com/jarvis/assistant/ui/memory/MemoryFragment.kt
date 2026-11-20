package com.jarvis.assistant.ui.memory

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.memory.MemoryEntity
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.databinding.FragmentMemoryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MemoryFragment : Fragment() {

    private var _binding: FragmentMemoryBinding? = null
    private val binding get() = _binding!!
    private val memoryRepo by lazy { JarvisApp.instance.memoryRepository }
    private val chatRepository by lazy { JarvisApp.instance.chatRepository }
    private val preferences by lazy { JarvisApp.instance.preferences }

    private lateinit var adapter: MemoryAdapter

    private val createBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) writeBackup(uri)
    }

    private val restoreBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) readBackup(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMemoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        adapter = MemoryAdapter(
            onEdit = { memory -> showEditMemoryDialog(memory) },
            onDelete = { memory -> confirmDeleteMemory(memory) }
        )
        binding.rvMemories.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMemories.adapter = adapter

        binding.btnAddMemory.setOnClickListener { showAddMemoryDialog() }
        binding.btnBackupRestore.setOnClickListener { showBackupDialog() }

        observeMemories()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        }
    }

    private fun observeMemories() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                memoryRepo.getAllMemoriesFlow().collect { memories ->
                    adapter.submitList(memories)
                    binding.tvMemoryCount.text = when (memories.size) {
                        0 -> "Abhi koi yaad save nahi hai"
                        1 -> "1 yaad save hai"
                        else -> "${memories.size} yaadein save hain"
                    }
                    val empty = memories.isEmpty()
                    binding.tvNoMemories.visibility = if (empty) View.VISIBLE else View.GONE
                    binding.rvMemories.visibility = if (empty) View.INVISIBLE else View.VISIBLE
                }
            }
        }
    }

    private fun showAddMemoryDialog() {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(2))
        }
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                MemoryEntity.ALL_CATEGORIES
            )
            setBackgroundResource(R.drawable.bg_input_field)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val editText = EditText(context).apply {
            hint = "e.g. Meri favourite song Believer hai"
            setTextColor(themeColor(R.attr.themeTextPrimary, ContextCompat.getColor(context, R.color.text_primary)))
            setHintTextColor(themeColor(R.attr.themeTextMuted, ContextCompat.getColor(context, R.color.text_muted)))
            setBackgroundResource(R.drawable.bg_input_field)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        layout.addView(spinner)
        layout.addView(editText)

        MaterialAlertDialogBuilder(context)
            .setTitle("Nayi yaad")
            .setView(layout)
            .setPositiveButton("SAVE") { _, _ ->
                val fact = editText.text.toString().trim()
                val category = spinner.selectedItem.toString()
                if (fact.isNotBlank()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        memoryRepo.saveMemory(
                            rawText = fact,
                            category = category,
                            key = fact.take(30).trim(),
                            value = fact
                        )
                        Toast.makeText(context, "Yaad save ho gayi", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showEditMemoryDialog(memory: MemoryEntity) {
        val context = requireContext()
        val editText = EditText(context).apply {
            setText(memory.rawText)
            setTextColor(themeColor(R.attr.themeTextPrimary, ContextCompat.getColor(context, R.color.text_primary)))
            setBackgroundResource(R.drawable.bg_input_field)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val container = LinearLayout(context).apply {
            setPadding(dp(20), dp(6), dp(20), dp(2))
            addView(editText)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("Yaad edit karo")
            .setView(container)
            .setPositiveButton("UPDATE") { _, _ ->
                val newFact = editText.text.toString().trim()
                if (newFact.isNotBlank()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        memoryRepo.updateMemory(
                            memory.copy(
                                rawText = newFact,
                                value = newFact,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                        Toast.makeText(context, "Yaad update ho gayi", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun confirmDeleteMemory(memory: MemoryEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Yaad delete karo?")
            .setMessage("Jarvis ye baat bhool jayega:\n\n“${memory.rawText}”")
            .setPositiveButton("DELETE") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    memoryRepo.deleteMemoryById(memory.id)
                    Toast.makeText(requireContext(), "Yaad delete ho gayi", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    // ---------------- Backup & restore ----------------

    private fun showBackupDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Backup & restore")
            .setItems(
                arrayOf(
                    "Backup banao (.json)",
                    "Backup restore karo",
                    "Saari yaadein clear karo"
                )
            ) { _, which ->
                when (which) {
                    0 -> {
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                        createBackupLauncher.launch("jarvis-yaadein-$stamp.json")
                    }
                    1 -> restoreBackupLauncher.launch(
                        arrayOf("application/json", "text/plain", "application/octet-stream")
                    )
                    2 -> confirmClearAll()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Saari yaadein clear karo?")
            .setMessage("Ye action undo nahi hoga. Pehle backup bana lena.")
            .setPositiveButton("CLEAR") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    memoryRepo.clearAll()
                    Toast.makeText(requireContext(), "Saari yaadein clear ho gayi", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun writeBackup(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val payload = buildBackupJson()
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(payload.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("Backup file open nahi hui")
                }
                Toast.makeText(requireContext(), "Backup save ho gaya", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Backup fail: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun buildBackupJson(): String {
        val memories = memoryRepo.getAllMemories()
        val turns = chatRepository.turns.value
        val root = JSONObject()
        root.put("app", "jarvis")
        root.put("format", 1)
        root.put("exportedAt", System.currentTimeMillis())

        val memoryArray = JSONArray()
        memories.forEach { memory ->
            memoryArray.put(
                JSONObject()
                    .put("category", memory.category)
                    .put("key", memory.key)
                    .put("value", memory.value)
                    .put("rawText", memory.rawText)
                    .put("timestamp", memory.timestamp)
            )
        }
        root.put("memories", memoryArray)

        val chatArray = JSONArray()
        turns.forEach { turn ->
            chatArray.put(
                JSONObject()
                    .put("id", turn.id)
                    .put("userTranscript", turn.userTranscript)
                    .put("jarvisResponse", turn.jarvisResponse)
                    .put("timestamp", turn.timestamp)
            )
        }
        root.put("chats", chatArray)
        return root.toString(2)
    }

    private fun readBackup(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("Backup file read nahi hui")
                }
                val data = withContext(Dispatchers.Default) { parseBackup(text) }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Backup restore karo?")
                    .setMessage(
                        "Backup me ${data.memories.size} yaadein aur ${data.turns.size} chats hain.\n\n" +
                            "Restore karne par is phone ki current yaadein backup se replace hongi."
                    )
                    .setPositiveButton("RESTORE") { _, _ -> restoreBackup(data) }
                    .setNegativeButton("CANCEL", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Backup padha nahi ja saka: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun parseBackup(text: String): BackupData {
        val root = JSONObject(text)
        if (root.optString("app") != "jarvis") {
            throw IllegalArgumentException("Ye Jarvis ka backup nahi lagta")
        }
        val categories = MemoryEntity.ALL_CATEGORIES.toSet()
        val memoryArray = root.optJSONArray("memories") ?: JSONArray()
        val memories = ArrayList<MemoryEntity>(memoryArray.length())
        for (i in 0 until memoryArray.length()) {
            val item = memoryArray.optJSONObject(i) ?: continue
            val raw = item.optString("rawText").ifBlank { item.optString("value") }.trim()
            if (raw.isBlank()) continue
            val category = item.optString("category").takeIf { it in categories } ?: MemoryEntity.CATEGORY_NOTES
            memories.add(
                MemoryEntity(
                    category = category,
                    key = item.optString("key").ifBlank { raw.take(30).trim() },
                    value = item.optString("value").ifBlank { raw },
                    rawText = raw,
                    timestamp = item.optLong("timestamp", System.currentTimeMillis())
                )
            )
        }

        val chatArray = root.optJSONArray("chats") ?: JSONArray()
        val turns = ArrayList<ChatTurn>(chatArray.length())
        for (i in 0 until chatArray.length()) {
            val item = chatArray.optJSONObject(i) ?: continue
            val user = item.optString("userTranscript")
            val jarvis = item.optString("jarvisResponse")
            if (user.isBlank() && jarvis.isBlank()) continue
            turns.add(
                ChatTurn(
                    id = item.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                    userTranscript = user,
                    jarvisResponse = jarvis,
                    timestamp = item.optLong("timestamp", System.currentTimeMillis())
                )
            )
        }
        return BackupData(memories, turns)
    }

    private fun restoreBackup(data: BackupData) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    memoryRepo.clearAll()
                    data.memories.forEach { memoryRepo.insertImportedMemory(it) }
                }
                chatRepository.replaceHistory(data.turns)
                Toast.makeText(
                    requireContext(),
                    "${data.memories.size} yaadein aur ${data.turns.size} chats restore ho gayi",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Restore fail: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val value = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, value, true)
        return if (value.data != 0) value.data else fallback
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private data class BackupData(
    val memories: List<MemoryEntity>,
    val turns: List<ChatTurn>
)
