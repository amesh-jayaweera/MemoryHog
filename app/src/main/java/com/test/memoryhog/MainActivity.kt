package com.test.memoryhog

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.test.memoryhog.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    private lateinit var coordinator: HogCoordinator

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensureNotificationPermission()

        coordinator = HogCoordinator(this).apply {
            setOnStatsChangedListener { updateStats() }
            start()
        }

        setupButtons()
        startMemoryUpdates()
        refreshLaunchButtonLabel()
    }

    override fun onResume() {
        super.onResume()
        refreshLaunchButtonLabel()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_POST_NOTIFICATIONS
                )
            }
        }
    }

    private fun setupButtons() {
        binding.btnAlloc1.setOnClickListener  { allocate(8) }
        binding.btnAlloc2.setOnClickListener  { allocate(16) }
        binding.btnAlloc3.setOnClickListener  { allocate(32) }
        binding.btnAlloc4.setOnClickListener  { allocate(64) }
        binding.btnAlloc5.setOnClickListener  { allocate(128) }
        binding.btnAlloc6.setOnClickListener  { allocate(256) }

        binding.btnFree1.setOnClickListener   { releaseLast(8) }
        binding.btnFree2.setOnClickListener   { releaseLast(16) }
        binding.btnFree3.setOnClickListener   { releaseLast(32) }
        binding.btnFree4.setOnClickListener   { releaseLast(64) }
        binding.btnFree5.setOnClickListener   { releaseLast(128) }
        binding.btnFreeAll.setOnClickListener { releaseAll() }

        binding.btnLaunchApp.setOnClickListener { launchTargetApp() }
        binding.btnConfigureApp.setOnClickListener { showConfigureDialog() }
    }

    private fun allocate(mb: Int) {
        coordinator.allocate(mb)
        binding.tvStatus.text = "Allocated ${mb}MB"
        updateStats()
    }

    private fun releaseLast(mb: Int) {
        coordinator.releaseLast(mb)
        binding.tvStatus.text = "Released ~${mb}MB"
        updateStats()
    }

    private fun releaseAll() {
        coordinator.releaseAll()
        binding.tvStatus.text = "Released all allocations"
        updateStats()
    }

    private fun getTargetPackage(): String? =
        prefs.getString(KEY_TARGET_PACKAGE, null)?.takeIf { it.isNotBlank() }

    private fun refreshLaunchButtonLabel() {
        val pkg = getTargetPackage()
        binding.btnLaunchApp.text = if (pkg != null) "Open $pkg" else "Open app (not set)"
    }

    private fun launchTargetApp() {
        val pkg = getTargetPackage()
        if (pkg == null) {
            showConfigureDialog()
            return
        }

        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Toast.makeText(this, "App not installed: $pkg", Toast.LENGTH_LONG).show()
            return
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to launch: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showConfigureDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "com.example.myapp"
            setText(getTargetPackage().orEmpty())
            setSelection(text.length)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (resources.displayMetrics.density * 20).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            gravity = Gravity.CENTER_VERTICAL
        }

        AlertDialog.Builder(this)
            .setTitle("Target app package")
            .setMessage("Enter the package name of the app to launch.")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val pkg = input.text.toString().trim()
                prefs.edit().putString(KEY_TARGET_PACKAGE, pkg).apply()
                refreshLaunchButtonLabel()
                binding.tvStatus.text = if (pkg.isEmpty()) {
                    "Target package cleared"
                } else {
                    "Target set: $pkg"
                }
            }
            .setNeutralButton("Clear") { _, _ ->
                prefs.edit().remove(KEY_TARGET_PACKAGE).apply()
                refreshLaunchButtonLabel()
                binding.tvStatus.text = "Target package cleared"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startMemoryUpdates() {
        updateRunnable = object : Runnable {
            override fun run() {
                updateStats()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun updateStats() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        val totalRam   = mi.totalMem.toMB()
        val freeRam    = mi.availMem.toMB()
        val usedRam    = totalRam - freeRam
        val ourAlloc   = coordinator.getTotalAllocatedBytes().toMB()
        val isLowMem   = mi.lowMemory

        binding.tvRamTotal.text    = "Total RAM:       ${totalRam} MB"
        binding.tvRamFree.text     = "Available RAM:   ${freeRam} MB"
        binding.tvRamUsed.text     = "Used RAM:        ${usedRam} MB"
        binding.tvOurAlloc.text    = "Our mmap alloc:  ${ourAlloc} MB (${workerSummary()})"
        binding.tvLowMemory.text   = "Low memory flag: ${if (isLowMem) "⚠️ YES" else "NO"}"
        binding.tvLowMemThreshold.text = "Low mem threshold: ${mi.threshold.toMB()} MB"

        binding.progressRam.progress = ((usedRam.toFloat() / totalRam) * 100).toInt()
    }

    private fun workerSummary(): String {
        val per = coordinator.getPerWorkerBytes()
        return per.joinToString("|") { (it / (1024 * 1024)).toString() }
    }

    private fun Long.toMB(): Long = this / (1024 * 1024)

    override fun onDestroy() {
        super.onDestroy()
        updateRunnable?.let { handler.removeCallbacks(it) }
        coordinator.setOnStatsChangedListener(null)
        coordinator.stop()
    }

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 1001
        private const val PREFS_NAME = "memoryhog_prefs"
        private const val KEY_TARGET_PACKAGE = "target_package"
    }
}
