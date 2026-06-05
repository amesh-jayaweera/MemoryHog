package com.test.memoryhog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.app.Service
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that owns the [NativeMemory] instance. Running as a
 * foreground service keeps the process alive (and our mmap'd pages resident)
 * when the user navigates away from the activity.
 */
class MemoryHogService : Service() {

    private val nativeMemory = NativeMemory()
    private val binder = LocalBinder()
    private var started = false

    inner class LocalBinder : Binder() {
        fun getService(): MemoryHogService = this@MemoryHogService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) {
            startInForeground()
            started = true
        }

        when (intent?.action) {
            ACTION_ALLOCATE -> {
                val mb = intent.getIntExtra(EXTRA_MB, 256)
                nativeMemory.allocateMB(mb)
                updateNotification()
            }
            ACTION_RELEASE_LAST -> {
                val mb = intent.getIntExtra(EXTRA_MB, 256)
                nativeMemory.releaseLastMB(mb)
                updateNotification()
            }
            ACTION_RELEASE_ALL -> {
                nativeMemory.releaseAll()
                updateNotification()
            }
            ACTION_STOP -> {
                nativeMemory.releaseAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        return START_STICKY
    }

    fun allocateMB(mb: Int): Long {
        val result = nativeMemory.allocateMB(mb)
        updateNotification()
        return result
    }

    fun releaseLastMB(mb: Int) {
        nativeMemory.releaseLastMB(mb)
        updateNotification()
    }

    fun releaseAll() {
        nativeMemory.releaseAll()
        updateNotification()
    }

    fun getTotalAllocatedBytes(): Long = nativeMemory.getTotalAllocatedBytes()

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RAM Hog",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps allocated memory resident in the background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val mb = nativeMemory.getTotalAllocatedBytes() / (1024 * 1024)

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MemoryHogService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RAM Hog active")
            .setContentText("Holding $mb MB of RAM")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "Release all", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        nativeMemory.releaseAll()
        super.onDestroy()
    }

    companion object {
        const val ACTION_ALLOCATE = "com.test.memoryhog.ACTION_ALLOCATE"
        const val ACTION_RELEASE_LAST = "com.test.memoryhog.ACTION_RELEASE_LAST"
        const val ACTION_RELEASE_ALL = "com.test.memoryhog.ACTION_RELEASE_ALL"
        const val ACTION_STOP = "com.test.memoryhog.ACTION_STOP"
        const val EXTRA_MB = "mb"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "memhog"
    }
}
