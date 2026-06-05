package com.test.memoryhog

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.test.memoryhog.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val nativeMemory = NativeMemory()
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
        startMemoryUpdates()
    }

    private fun setupButtons() {
        binding.btnAlloc1.setOnClickListener  { allocate(256) }
        binding.btnAlloc2.setOnClickListener  { allocate(384) }
        binding.btnAlloc3.setOnClickListener { allocate(448) }
        binding.btnAlloc4.setOnClickListener  { allocate(500) }
        binding.btnAlloc5.setOnClickListener  { allocate(510) }
        binding.btnAlloc6.setOnClickListener { allocate(550) }

        binding.btnFree1.setOnClickListener   { nativeMemory.releaseLastMB(256);  updateStats() }
        binding.btnFree2.setOnClickListener   { nativeMemory.releaseLastMB(384);  updateStats() }
        binding.btnFree3.setOnClickListener   { nativeMemory.releaseLastMB(448);         updateStats() }
        binding.btnFree4.setOnClickListener   { nativeMemory.releaseLastMB(500);  updateStats() }
        binding.btnFree5.setOnClickListener   { nativeMemory.releaseLastMB(510);  updateStats() }
        binding.btnFreeAll.setOnClickListener   { nativeMemory.releaseAll();         updateStats() }
    }

    private fun allocate(mb: Int) {
        val result = nativeMemory.allocateMB(mb)
        if (result < 0) {
            binding.tvStatus.text = "mmap FAILED for ${mb}MB — out of memory?"
        } else {
            binding.tvStatus.text = "Allocated ${mb}MB via mmap ✓"
        }
        updateStats()
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
        val ourAlloc   = nativeMemory.getTotalAllocatedBytes().toMB()
        val isLowMem   = mi.lowMemory

        binding.tvRamTotal.text    = "Total RAM:       ${totalRam} MB"
        binding.tvRamFree.text     = "Available RAM:   ${freeRam} MB"
        binding.tvRamUsed.text     = "Used RAM:        ${usedRam} MB"
        binding.tvOurAlloc.text    = "Our mmap alloc:  ${ourAlloc} MB"
        binding.tvLowMemory.text   = "Low memory flag: ${if (isLowMem) "⚠️ YES" else "NO"}"
        binding.tvLowMemThreshold.text = "Low mem threshold: ${mi.threshold.toMB()} MB"

        // Update progress bar (% of RAM used)
        binding.progressRam.progress = ((usedRam.toFloat() / totalRam) * 100).toInt()
    }

    private fun Long.toMB(): Long = this / (1024 * 1024)

    override fun onDestroy() {
        super.onDestroy()
        updateRunnable?.let { handler.removeCallbacks(it) }
        nativeMemory.releaseAll()
    }
}
