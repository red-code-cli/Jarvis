package com.jarvis.assistant.ui.apps

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.databinding.ActivityAppDrawerBinding
import com.jarvis.assistant.databinding.ItemAppGridBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen launcher app grid with instant search. All installed launcher apps
 * are shown on one screen; tap opens an app, long-press opens its App Info.
 */
class AppDrawerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppDrawerBinding
    private val allApps = mutableListOf<AppEntry>()
    private lateinit var adapter: AppGridAdapter
    private var appsLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppDrawerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = AppGridAdapter { entry -> launchApp(entry) }
        binding.rvApps.layoutManager = GridLayoutManager(this, gridSpanCount())
        binding.rvApps.adapter = adapter
        binding.rvApps.setHasFixedSize(true)

        binding.btnAppsBack.setOnClickListener { finish() }
        binding.etAppSearch.doAfterTextChanged { filterApps(it?.toString().orEmpty()) }
        binding.etAppSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else {
                false
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        binding.tvAppsCount.text = "Loading apps…"
        loadApps()
    }

    private fun gridSpanCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return (widthDp / 76).coerceIn(4, 6)
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { loadInstalledApps() }
            if (isFinishing || isDestroyed) return@launch
            allApps.clear()
            allApps.addAll(apps)
            appsLoaded = true
            filterApps(binding.etAppSearch.text?.toString().orEmpty())
        }
    }

    private fun loadInstalledApps(): List<AppEntry> {
        val result = mutableListOf<AppEntry>()
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, 0)
            }
            val seen = mutableSetOf<String>()
            for (info in resolved) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == packageName || !seen.add(pkg)) continue
                try {
                    val icon = loadGridIcon(info.loadIcon(packageManager))
                    result.add(
                        AppEntry(
                            label = info.loadLabel(packageManager).toString().ifBlank { pkg },
                            packageName = pkg,
                            icon = icon
                        )
                    )
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
        return result.sortedBy { it.label.lowercase() }
    }

    private fun loadGridIcon(source: Drawable): Drawable {
        return try {
            val size = (64 * resources.displayMetrics.density).toInt().coerceAtLeast(64)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            source.setBounds(0, 0, size, size)
            source.draw(canvas)
            BitmapDrawable(resources, bitmap)
        } catch (_: Throwable) {
            source
        }
    }

    private fun filterApps(query: String) {
        if (!appsLoaded) return
        val clean = query.trim().lowercase()
        val filtered = if (clean.isEmpty()) {
            allApps.toList()
        } else {
            allApps.filter {
                it.label.lowercase().contains(clean) ||
                    it.packageName.lowercase().contains(clean)
            }
        }
        adapter.submit(filtered)
        binding.tvAppsCount.text = if (clean.isEmpty()) {
            "${allApps.size} apps • long-press for app info"
        } else {
            "${filtered.size} of ${allApps.size} apps"
        }
        binding.tvAppsEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun launchApp(entry: AppEntry) {
        val launch = packageManager.getLaunchIntentForPackage(entry.packageName)
        if (launch == null) {
            Toast.makeText(this, "${entry.label} launch nahi ho paya.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(launch)
            finish()
        } catch (t: Throwable) {
            Toast.makeText(
                this,
                "${entry.label} launch failed: ${t.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun openAppInfo(entry: AppEntry) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${entry.packageName}")
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(binding.etAppSearch.windowToken, 0)
        } catch (_: Throwable) {
        }
    }

    private data class AppEntry(
        val label: String,
        val packageName: String,
        val icon: Drawable
    )

    private inner class AppGridAdapter(
        private val onClick: (AppEntry) -> Unit
    ) : RecyclerView.Adapter<AppGridAdapter.Holder>() {

        private val items = mutableListOf<AppEntry>()

        fun submit(newItems: List<AppEntry>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val itemBinding = ItemAppGridBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return Holder(itemBinding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.binding.ivAppIcon.setImageDrawable(entry.icon)
            holder.binding.tvAppLabel.text = entry.label
            holder.binding.root.setOnClickListener { onClick(entry) }
            holder.binding.root.setOnLongClickListener {
                openAppInfo(entry)
                true
            }
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(val binding: ItemAppGridBinding) :
            RecyclerView.ViewHolder(binding.root)
    }
}
