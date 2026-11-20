package com.jarvis.assistant.harness

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jarvis.assistant.R
import com.jarvis.harness.HarnessLogEvent
import com.jarvis.harness.HarnessLogKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Realtime log feed for a coding task, opened from the Home "Agent Live" card.
 *
 * It follows the engine's append-only event log live (file writes/edits with ±,
 * bash commands with exit codes, command output tails, errors and retries),
 * auto-scrolls and offers "Show more" for the older history.
 */
object HarnessLogSheet {

    private const val PAGE_SIZE = 120

    fun show(context: Context, fragmentManager: androidx.fragment.app.FragmentManager, taskId: String) {
        val engine = JarvisHarnessBridge.engine(context)
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_harness_log, null)
        val title = view.findViewById<TextView>(R.id.tvHarnessLogTitle)
        val subtitle = view.findViewById<TextView>(R.id.tvHarnessLogSubtitle)
        val container = view.findViewById<LinearLayout>(R.id.layoutHarnessLog)
        val scroll = view.findViewById<ScrollView>(R.id.scrollHarnessLog)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        var limit = PAGE_SIZE
        var lastSeq = 0L

        fun renderAll() {
            container.removeAllViews()
            val events = engine.taskLog(taskId, limit = limit)
            lastSeq = events.lastOrNull()?.seq ?: 0L
            if (events.size >= limit) {
                container.addView(showMoreRow(context, dialog) {
                    limit += PAGE_SIZE
                    renderAll()
                })
            }
            events.forEach { event -> container.addView(renderRow(context, event, timeFormat)) }
        }

        fun updateHeader() {
            val status = engine.taskStatus(taskId)
            title.text = status?.projectName ?: "Task activity"
            subtitle.text = buildString {
                append(status?.state?.name?.lowercase() ?: "")
                append(" · ").append(engine.logCounts(taskId))
                status?.provider?.let { append(" · ").append(it) }
                status?.error?.let { append("\n").append(it.headline).append(" — ").append(it.hint) }
            }
        }

        renderAll()
        updateHeader()

        val collector = scope.launch {
            runCatching {
                engine.liveStatus.collect { live ->
                    if (live.taskId != taskId) return@collect
                    val status = engine.taskStatus(taskId)
                    if (status == null) return@collect
                    updateHeader()
                    val newest = engine.taskLog(taskId, limit = limit)
                    newest.filter { it.seq > lastSeq }.forEach { event ->
                        container.addView(renderRow(context, event, timeFormat))
                        lastSeq = event.seq
                    }
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }

        view.findViewById<TextView>(R.id.btnHarnessLogClose).setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            collector.cancel()
            scope.cancel()
        }
        dialog.setContentView(view)
        dialog.show()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showMoreRow(context: Context, dialog: BottomSheetDialog, onClick: () -> Unit): View =
        TextView(context).apply {
            text = "Show more ↑"
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#7FE7FF"))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 16, 0, 16)
            isClickable = true
            setOnClickListener {
                onClick()
                dialog.findViewById<ScrollView>(R.id.scrollHarnessLog)?.post {
                    dialog.findViewById<ScrollView>(R.id.scrollHarnessLog)?.fullScroll(View.FOCUS_DOWN)
                }
            }
        }

    private fun renderRow(context: Context, event: HarnessLogEvent, timeFormat: SimpleDateFormat): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 9, 0, 9)
        }
        val header = TextView(context).apply {
            text = buildString {
                append(timeFormat.format(Date(event.atMillis)))
                append("  ")
                append(glyph(event.kind))
                append("  ")
                append(event.title.take(120))
                when {
                    event.additions > 0 || event.deletions > 0 -> append("  +${event.additions} -${event.deletions}")
                    event.exitCode != null -> append("  exit ${event.exitCode}")
                }
            }
            setTextColor(colorFor(event.kind))
            textSize = 12f
            setTypeface(typeface, if (event.kind == HarnessLogKind.ERROR) Typeface.BOLD else Typeface.NORMAL)
        }
        row.addView(header)

        val detail = event.detail.ifBlank { event.command.orEmpty() }
        val output = event.outputTail.orEmpty()
        val body = listOf(detail, if (output.isNotBlank()) "↳ $output" else "").filter { it.isNotBlank() }.joinToString("\n")
        if (body.isNotBlank()) {
            val detailView = TextView(context).apply {
                text = body.take(4_000)
                setTextColor(Color.parseColor("#8A8F98"))
                textSize = 11f
                maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            row.addView(detailView)
            if (body.length > 120) {
                detailView.setOnClickListener {
                    if (detailView.maxLines == 4) {
                        detailView.maxLines = Int.MAX_VALUE
                        detailView.ellipsize = null
                        detailView.setTextColor(Color.parseColor("#C9CDD4"))
                    } else {
                        detailView.maxLines = 4
                        detailView.ellipsize = android.text.TextUtils.TruncateAt.END
                        detailView.setTextColor(Color.parseColor("#8A8F98"))
                    }
                }
            }
        }
        return row
    }

    private fun glyph(kind: HarnessLogKind): String = when (kind) {
        HarnessLogKind.FILE_WRITE -> "✎"
        HarnessLogKind.FILE_EDIT -> "✱"
        HarnessLogKind.BASH_COMMAND -> "$"
        HarnessLogKind.STEP_DONE -> "✓"
        HarnessLogKind.ERROR -> "✕"
        HarnessLogKind.RETRY -> "↻"
        HarnessLogKind.PAUSED -> "❚❚"
        HarnessLogKind.RESUMED -> "▶"
        HarnessLogKind.COMPLETED -> "★"
        HarnessLogKind.STATE, HarnessLogKind.THINKING -> "·"
    }

    private fun colorFor(kind: HarnessLogKind): Int = when (kind) {
        HarnessLogKind.ERROR -> Color.parseColor("#FF6B6B")
        HarnessLogKind.RETRY -> Color.parseColor("#FFB020")
        HarnessLogKind.COMPLETED -> Color.parseColor("#39D98A")
        HarnessLogKind.FILE_WRITE, HarnessLogKind.FILE_EDIT -> Color.parseColor("#7FE7FF")
        HarnessLogKind.BASH_COMMAND -> Color.parseColor("#C8B6FF")
        else -> Color.parseColor("#D5D8DE")
    }

}
