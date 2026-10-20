package com.synapse.localai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.FileObserver
import android.os.SystemClock
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground-only, opt-in event observation. It reports event types and relative paths,
 * never file contents, Wi-Fi credentials, packet captures, or Bluetooth device details.
 */
object SystemEventMonitor {

    const val PREFS_NAME = "atlas_system_watchers"
    const val PREF_ENABLED = "watchers_enabled"
    private const val WORKSPACE_PATH = "/sdcard/atlas-ai"
    private const val MAX_WATCHED_DIRECTORIES = 256
    private const val MAX_DEPTH = 8
    private const val EVENT_THROTTLE_MS = 1_000L

    private val observers = CopyOnWriteArrayList<FileObserver>()
    private val lastEventAt = AtomicLong(0L)
    @Volatile private var registeredContext: Context? = null
    @Volatile private var receiver: BroadcastReceiver? = null
    @Volatile private var eventSink: ((String) -> Unit)? = null

    @Synchronized
    fun start(context: Context, onEvent: (String) -> Unit) {
        stop()
        val appContext = context.applicationContext
        if (!appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, false)
        ) return

        eventSink = onEvent
        registeredContext = appContext
        watchWorkspace(File(WORKSPACE_PATH))
        registerSystemReceiver(appContext)
    }

    @Synchronized
    fun stop() {
        observers.forEach(FileObserver::stopWatching)
        observers.clear()
        receiver?.let { current ->
            registeredContext?.unregisterReceiver(current)
        }
        receiver = null
        registeredContext = null
        eventSink = null
    }

    @Synchronized
    private fun watchWorkspace(root: File) {
        if (!root.isDirectory || !root.canRead()) return
        data class PendingDirectory(val file: File, val depth: Int)
        val pending = ArrayDeque<PendingDirectory>()
        pending.add(PendingDirectory(root, 0))

        while (pending.isNotEmpty() && observers.size < MAX_WATCHED_DIRECTORIES) {
            val (directory, depth) = pending.removeFirst()
            val observer = object : FileObserver(
                directory.absolutePath,
                FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE or
                    FileObserver.DELETE or FileObserver.ATTRIB or FileObserver.MOVED_FROM
            ) {
                override fun onEvent(event: Int, name: String?) {
                    if (name != null &&
                        event and (FileObserver.CREATE or FileObserver.MOVED_TO) != 0 &&
                        depth < MAX_DEPTH
                    ) {
                        val child = File(directory, name)
                        if (child.isDirectory && observers.size < MAX_WATCHED_DIRECTORIES) {
                            watchWorkspace(child)
                        }
                    }
                    if (event and (FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE or
                            FileObserver.DELETE or FileObserver.ATTRIB or FileObserver.MOVED_FROM) != 0
                    ) {
                        emitThrottled("workspace_changed")
                    }
                }
            }
            observer.startWatching()
            observers.add(observer)
            if (depth < MAX_DEPTH) {
                directory.listFiles()
                    ?.asSequence()
                    ?.filter { it.isDirectory && !it.isSymbolicLink() && it.canRead() }
                    ?.take(MAX_WATCHED_DIRECTORIES - observers.size)
                    ?.forEach { pending.add(PendingDirectory(it, depth + 1)) }
            }
        }
    }

    private fun registerSystemReceiver(context: Context) {
        val systemReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                val event = when (action) {
                    ConnectivityManager.CONNECTIVITY_ACTION -> "network_connectivity_changed"
                    Intent.ACTION_POWER_CONNECTED -> "power_connected"
                    Intent.ACTION_POWER_DISCONNECTED -> "power_disconnected"
                    Intent.ACTION_BATTERY_LOW -> "battery_low"
                    else -> return
                }
                emitThrottled(event)
            }
        }
        val filter = IntentFilter().apply {
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(systemReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(systemReceiver, filter)
        }
        receiver = systemReceiver
    }

    private fun emitThrottled(event: String) {
        val now = SystemClock.elapsedRealtime()
        while (true) {
            val previous = lastEventAt.get()
            if (now - previous < EVENT_THROTTLE_MS) return
            if (lastEventAt.compareAndSet(previous, now)) break
        }
        eventSink?.invoke(event)
    }

    private fun File.isSymbolicLink(): Boolean =
        try {
            canonicalFile != absoluteFile
        } catch (_: Exception) {
            true
        }
}
