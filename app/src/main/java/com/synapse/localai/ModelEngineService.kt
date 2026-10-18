package com.synapse.localai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.synapse.localai.AtlasLog as Log
import androidx.core.app.NotificationCompat

/**
 * ModelEngineService — High-Priority Persistent Foreground Service for ATLAS-AI.
 *
 * Best-effort background execution support: foreground priority, a bounded wake lock,
 * ongoing status notification, and START_STICKY restart requests. Android can still
 * stop this process under resource pressure or after a user force-stop.
 */
class ModelEngineService : Service() {

    companion object {
        private const val TAG = "ModelEngineService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "synapse_model_engine_high"
        private const val CHANNEL_NAME = "Atlas-AI Autonomous Agent Core"

        const val ACTION_START = "com.synapse.localai.action.START_SERVICE"
        const val ACTION_UPDATE_STATUS = "com.synapse.localai.action.UPDATE_STATUS"
        const val ACTION_STOP = "com.synapse.localai.action.STOP_SERVICE"
        const val EXTRA_STATUS = "extra_status"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var currentStatus: String = "Neural Cognitive Matrix • Ready"
            private set

        fun start(context: Context, initialStatus: String? = null) {
            try {
                val intent = Intent(context, ModelEngineService::class.java).apply {
                    action = ACTION_START
                    if (initialStatus != null) putExtra(EXTRA_STATUS, initialStatus)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start ModelEngineService: ${e.message}")
            }
        }

        fun updateStatus(context: Context, status: String) {
            currentStatus = status
            try {
                val intent = Intent(context, ModelEngineService::class.java).apply {
                    action = ACTION_UPDATE_STATUS
                    putExtra(EXTRA_STATUS, status)
                }
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, ModelEngineService::class.java).apply {
                    action = ACTION_STOP
                }
                context.stopService(intent)
            } catch (_: Exception) {}
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ModelEngineService created as High-Priority Foreground Service")
        acquireWakeLock()
        startForegroundWithStatus(currentStatus)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val statusExtra = intent?.getStringExtra(EXTRA_STATUS)

        if (statusExtra != null) {
            currentStatus = statusExtra
        }

        when (action) {
            ACTION_STOP -> {
                Log.i(TAG, "Received ACTION_STOP, shutting down foreground service")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_STATUS -> {
                startForegroundWithStatus(currentStatus)
            }
            else -> {
                startForegroundWithStatus(currentStatus)
            }
        }

        return START_STICKY
    }

    private fun startForegroundWithStatus(statusText: String) {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            pendingIntentFlags
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ATLAS-AI Autonomous Agent Active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Keeps ATLAS-AI High-Priority Engine active during task execution"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AtlasAI:HighPriorityExecutionLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire(24 * 60 * 60 * 1000L) // Safe 24h cap
                }
                Log.i(TAG, "High-Priority Partial WakeLock acquired")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null
            Log.i(TAG, "WakeLock released")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WakeLock: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        Log.i(TAG, "ModelEngineService destroyed")
        isRunning = false
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }
}
