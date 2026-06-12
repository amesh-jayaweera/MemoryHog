package com.test.memoryhog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * One worker per process. Each worker owns its own slice of native memory,
 * persists its current chunk list to per-process SharedPreferences, and
 * auto-restores those chunks on (re)creation — so when lmkd kills a worker
 * to satisfy the target app, START_STICKY brings it back and it re-allocates
 * the same chunks immediately. Splitting allocations across 8 processes also
 * means the kernel can only reclaim ~1/8 of total pressure per kill cycle.
 */
abstract class HogWorker : Service() {

    protected abstract val workerIndex: Int

    private val nativeMemory = NativeMemory()
    private val chunks = ArrayDeque<Int>()
    private var foregroundStarted = false

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("worker_$workerIndex", Context.MODE_PRIVATE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            startInForeground()
            foregroundStarted = true
            restoreFromPrefs()
        }

        when (intent?.action) {
            ACTION_ALLOCATE -> {
                val mb = intent.getIntExtra(EXTRA_MB, 0)
                if (mb > 0) allocateInternal(mb)
            }
            ACTION_RELEASE_LAST -> {
                val mb = intent.getIntExtra(EXTRA_MB, 0)
                if (mb > 0) releaseLastInternal(mb)
            }
            ACTION_RELEASE_ALL -> releaseAllInternal()
            ACTION_STOP -> {
                releaseAllInternal()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        broadcastStats()
        updateNotification()
        return START_STICKY
    }

    private fun allocateInternal(mb: Int) {
        val result = nativeMemory.allocateMB(mb)
        if (result > 0) {
            chunks.addLast(mb)
            persistChunks()
        }
    }

    private fun releaseLastInternal(mb: Int) {
        var freedMb = 0
        while (chunks.isNotEmpty() && freedMb < mb) {
            freedMb += chunks.removeLast()
        }
        nativeMemory.releaseLastMB(mb)
        persistChunks()
    }

    private fun releaseAllInternal() {
        chunks.clear()
        nativeMemory.releaseAll()
        persistChunks()
    }

    private fun restoreFromPrefs() {
        val stored = prefs.getString(KEY_CHUNKS, null)
            ?.split(",")
            ?.mapNotNull { it.toIntOrNull() }
            ?.filter { it > 0 }
            ?: return

        var anyFailed = false
        for (mb in stored) {
            val result = nativeMemory.allocateMB(mb)
            if (result > 0) {
                chunks.addLast(mb)
            } else {
                anyFailed = true
            }
        }
        if (anyFailed) persistChunks()
    }

    private fun persistChunks() {
        prefs.edit()
            .putString(KEY_CHUNKS, chunks.joinToString(","))
            .apply()
    }

    private fun broadcastStats() {
        val intent = Intent(ACTION_STATS_UPDATE)
            .setPackage(packageName)
            .putExtra(EXTRA_WORKER_INDEX, workerIndex)
            .putExtra(EXTRA_BYTES, nativeMemory.getTotalAllocatedBytes())
        sendBroadcast(intent)
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(notificationId, buildNotification())
    }

    private fun buildNotification(): Notification {
        val mb = nativeMemory.getTotalAllocatedBytes() / (1024 * 1024)

        val openIntent = PendingIntent.getActivity(
            this, workerIndex,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RAM Hog [W$workerIndex]")
            .setContentText("Holding $mb MB")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .setGroup(GROUP_KEY)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "RAM Hog", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps allocated memory resident in the background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        nativeMemory.releaseAll()
        super.onDestroy()
    }

    private val notificationId: Int get() = 1000 + workerIndex

    companion object {
        const val ACTION_ALLOCATE = "com.test.memoryhog.WORKER_ALLOCATE"
        const val ACTION_RELEASE_LAST = "com.test.memoryhog.WORKER_RELEASE_LAST"
        const val ACTION_RELEASE_ALL = "com.test.memoryhog.WORKER_RELEASE_ALL"
        const val ACTION_STOP = "com.test.memoryhog.WORKER_STOP"
        const val EXTRA_MB = "mb"

        const val ACTION_STATS_UPDATE = "com.test.memoryhog.STATS_UPDATE"
        const val EXTRA_WORKER_INDEX = "worker_index"
        const val EXTRA_BYTES = "bytes"

        const val NUM_WORKERS = 8

        private const val CHANNEL_ID = "memhog"
        private const val GROUP_KEY = "memhog_group"
        private const val KEY_CHUNKS = "chunks"

        val workerClasses: List<Class<out HogWorker>> = listOf(
            HogWorker0::class.java,
            HogWorker1::class.java,
            HogWorker2::class.java,
            HogWorker3::class.java,
            HogWorker4::class.java,
            HogWorker5::class.java,
            HogWorker6::class.java,
            HogWorker7::class.java
        )
    }
}

class HogWorker0 : HogWorker() { override val workerIndex = 0 }
class HogWorker1 : HogWorker() { override val workerIndex = 1 }
class HogWorker2 : HogWorker() { override val workerIndex = 2 }
class HogWorker3 : HogWorker() { override val workerIndex = 3 }
class HogWorker4 : HogWorker() { override val workerIndex = 4 }
class HogWorker5 : HogWorker() { override val workerIndex = 5 }
class HogWorker6 : HogWorker() { override val workerIndex = 6 }
class HogWorker7 : HogWorker() { override val workerIndex = 7 }
