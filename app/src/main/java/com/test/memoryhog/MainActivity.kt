package com.test.memoryhog

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.test.memoryhog.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    private var service: MemoryHogService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? MemoryHogService.LocalBinder ?: return
            service = localBinder.getService()
            bound = true
            updateStats()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensureNotificationPermission()
        startAndBindService()
        setupButtons()
        startMemoryUpdates()
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

    private fun startAndBindService() {
        val intent = Intent(this, MemoryHogService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun setupButtons() {
        binding.btnAlloc1.setOnClickListener  { allocate(256) }
        binding.btnAlloc2.setOnClickListener  { allocate(384) }
        binding.btnAlloc3.setOnClickListener  { allocate(448) }
        binding.btnAlloc4.setOnClickListener  { allocate(500) }
        binding.btnAlloc5.setOnClickListener  { allocate(510) }
        binding.btnAlloc6.setOnClickListener  { allocate(550) }

        binding.btnFree1.setOnClickListener   { releaseLast(256) }
        binding.btnFree2.setOnClickListener   { releaseLast(384) }
        binding.btnFree3.setOnClickListener   { releaseLast(448) }
        binding.btnFree4.setOnClickListener   { releaseLast(500) }
        binding.btnFree5.setOnClickListener   { releaseLast(510) }
        binding.btnFreeAll.setOnClickListener { releaseAll() }
    }

    private fun allocate(mb: Int) {
        val svc = service
        if (svc != null) {
            val result = svc.allocateMB(mb)
            binding.tvStatus.text = if (result < 0) {
                "mmap FAILED for ${mb}MB — out of memory?"
            } else {
                "Allocated ${mb}MB via mmap ✓"
            }
        } else {
            sendCommand(MemoryHogService.ACTION_ALLOCATE, mb)
            binding.tvStatus.text = "Queued ${mb}MB allocate (service starting)"
        }
        updateStats()
    }

    private fun releaseLast(mb: Int) {
        val svc = service
        if (svc != null) {
            svc.releaseLastMB(mb)
        } else {
            sendCommand(MemoryHogService.ACTION_RELEASE_LAST, mb)
        }
        binding.tvStatus.text = "Released ~${mb}MB"
        updateStats()
    }

    private fun releaseAll() {
        val svc = service
        if (svc != null) {
            svc.releaseAll()
        } else {
            sendCommand(MemoryHogService.ACTION_RELEASE_ALL)
        }
        binding.tvStatus.text = "Released all allocations"
        updateStats()
    }

    private fun sendCommand(action: String, mb: Int? = null) {
        val intent = Intent(this, MemoryHogService::class.java).setAction(action)
        if (mb != null) intent.putExtra(MemoryHogService.EXTRA_MB, mb)
        ContextCompat.startForegroundService(this, intent)
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
        val ourAlloc   = (service?.getTotalAllocatedBytes() ?: 0L).toMB()
        val isLowMem   = mi.lowMemory

        binding.tvRamTotal.text    = "Total RAM:       ${totalRam} MB"
        binding.tvRamFree.text     = "Available RAM:   ${freeRam} MB"
        binding.tvRamUsed.text     = "Used RAM:        ${usedRam} MB"
        binding.tvOurAlloc.text    = "Our mmap alloc:  ${ourAlloc} MB"
        binding.tvLowMemory.text   = "Low memory flag: ${if (isLowMem) "⚠️ YES" else "NO"}"
        binding.tvLowMemThreshold.text = "Low mem threshold: ${mi.threshold.toMB()} MB"

        binding.progressRam.progress = ((usedRam.toFloat() / totalRam) * 100).toInt()
    }

    private fun Long.toMB(): Long = this / (1024 * 1024)

    override fun onDestroy() {
        super.onDestroy()
        updateRunnable?.let { handler.removeCallbacks(it) }
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 1001
    }
}
