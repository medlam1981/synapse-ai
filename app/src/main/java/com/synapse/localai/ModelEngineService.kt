package com.synapse.localai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File

class ModelEngineService : Service() {

    companion object {
        private const val TAG = "ModelEngineService"
        private const val CHANNEL_ID = "medlam_engine_channel"
        private const val NOTIFICATION_ID = 8088
        const val ACTION_START = "com.synapse.localai.START_SERVER"
        const val ACTION_STOP = "com.synapse.localai.STOP_SERVER"
        const val EXTRA_ARGS = "extra_args"

        @Volatile
        var isServerThreadRunning = false
    }

    private var serverThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        startEngineForeground()
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MedlamAI::EngineWakeLock"
            )
            wakeLock?.acquire()
            Log.d(TAG, "WakeLock acquired: CPU will stay awake for the Neural Engine.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock: ${e.message}")
        }
    }

    private fun startEngineForeground() {
        val channelName = "MEDLAM AI Neural Engine"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Running local on-device neural inference server"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MEDLAM AI Engine")
            .setContentText("Neural Core is active in background")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallback startForeground: ${e.message}")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopLlamaServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val args = intent.getStringArrayExtra(EXTRA_ARGS)
                if (args != null && args.isNotEmpty()) {
                    launchServerProcess(args)
                }
            }
        }
        return START_STICKY
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "App swiped away, but Engine Service remains active.")
    }

    private fun launchServerProcess(args: Array<String>) {
        if (isServerThreadRunning) {
            stopLlamaServer()
        }

        if (!LlamaServerJNI.load()) {
            Log.e(TAG, "Failed to load LlamaServerJNI in engine process")
            return
        }

        val logDir = File("/sdcard/Medlam-Ai")
        if (!logDir.exists()) logDir.mkdirs()
        val logFile = File(logDir, "native_log.txt")
        try { logFile.delete() } catch (_: Exception) {}

        serverThread = Thread {
            isServerThreadRunning = true
            try {
                val fd = Os.open(logFile.absolutePath, OsConstants.O_CREAT or OsConstants.O_WRONLY or OsConstants.O_TRUNC, 438)
                Os.dup2(fd, OsConstants.STDOUT_FILENO)
                Os.dup2(fd, OsConstants.STDERR_FILENO)

                Log.d(TAG, "Starting JNI llama-server in isolated process: ${args.joinToString(" ")}")
                val result = LlamaServerJNI.safeStart(args)
                Log.d(TAG, "JNI llama-server stopped with exit code: $result")
            } catch (e: Exception) {
                Log.e(TAG, "Engine execution error: ${e.message}")
            } finally {
                isServerThreadRunning = false
            }
        }.apply {
            name = "llama-engine-isolated"
            isDaemon = true
            start()
        }
    }

    private fun stopLlamaServer() {
        LlamaServerJNI.safeStop()
        serverThread?.interrupt()
        serverThread = null
        isServerThreadRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopLlamaServer()
        releaseWakeLock()
        super.onDestroy()
    }
}
