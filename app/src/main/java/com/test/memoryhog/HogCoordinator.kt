package com.test.memoryhog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Lives in the main process. Owns a [BroadcastReceiver] that listens for
 * stats broadcasts coming back from each worker process and maintains a
 * local mirror of "bytes held per worker". Dispatches commands to the
 * worker with the smallest (allocate) or largest (release) current load
 * so memory pressure is distributed evenly across the 8 hog processes.
 */
class HogCoordinator(private val context: Context) {

    private val workerBytes = LongArray(HogWorker.NUM_WORKERS)
    private var listener: (() -> Unit)? = null
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != HogWorker.ACTION_STATS_UPDATE) return
            val idx = intent.getIntExtra(HogWorker.EXTRA_WORKER_INDEX, -1)
            val bytes = intent.getLongExtra(HogWorker.EXTRA_BYTES, 0L)
            if (idx in workerBytes.indices) {
                workerBytes[idx] = bytes
                listener?.invoke()
            }
        }
    }

    fun setOnStatsChangedListener(l: (() -> Unit)?) {
        listener = l
    }

    fun start() {
        if (started) return
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(HogWorker.ACTION_STATS_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        for (cls in HogWorker.workerClasses) {
            ContextCompat.startForegroundService(context, Intent(context, cls))
        }
        started = true
    }

    fun stop() {
        if (!started) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // receiver wasn't registered for some reason
        }
        started = false
    }

    fun allocate(mb: Int) {
        val target = smallestWorker()
        workerBytes[target] += mb.toLong() * 1024 * 1024
        send(target, HogWorker.ACTION_ALLOCATE, mb)
        listener?.invoke()
    }

    fun releaseLast(mb: Int) {
        val target = largestWorker()
        if (workerBytes[target] == 0L) return
        val freed = minOf(workerBytes[target], mb.toLong() * 1024 * 1024)
        workerBytes[target] -= freed
        send(target, HogWorker.ACTION_RELEASE_LAST, mb)
        listener?.invoke()
    }

    fun releaseAll() {
        workerBytes.fill(0L)
        for (cls in HogWorker.workerClasses) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, cls).setAction(HogWorker.ACTION_RELEASE_ALL)
            )
        }
        listener?.invoke()
    }

    fun getTotalAllocatedBytes(): Long = workerBytes.sum()

    fun getPerWorkerBytes(): LongArray = workerBytes.copyOf()

    private fun smallestWorker(): Int {
        var idx = 0
        var min = workerBytes[0]
        for (i in 1 until workerBytes.size) {
            if (workerBytes[i] < min) {
                min = workerBytes[i]
                idx = i
            }
        }
        return idx
    }

    private fun largestWorker(): Int {
        var idx = 0
        var max = workerBytes[0]
        for (i in 1 until workerBytes.size) {
            if (workerBytes[i] > max) {
                max = workerBytes[i]
                idx = i
            }
        }
        return idx
    }

    private fun send(workerIdx: Int, action: String, mb: Int) {
        val intent = Intent(context, HogWorker.workerClasses[workerIdx])
            .setAction(action)
            .putExtra(HogWorker.EXTRA_MB, mb)
        ContextCompat.startForegroundService(context, intent)
    }
}
