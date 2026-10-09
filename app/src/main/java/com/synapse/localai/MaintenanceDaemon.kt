package com.synapse.localai

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Autonomous Maintenance Daemon & Low-Power Background Optimizer.
 *
 * Responsibilities:
 * 1. Low-Power Adaptive Throttling: Monitors battery level and charging state;
 *    defers expensive disk operations and adjusts scheduler intervals during low battery (<20%).
 * 2. Automated System Checks: Diagnoses storage, RAM, swap, CPU thermal status, and background processes.
 * 3. Autonomous State Backup: Periodically snapshots agent mind, task history, and vector memory
 *    to /sdcard/atlas-ai/backups/.
 * 4. Scratch & Cache Hygiene: Purges ephemeral execution artifacts older than 48 hours to preserve flash storage.
 */
class MaintenanceDaemon(
    private val context: Context,
    private val taskMemory: TaskMemoryManager
) {

    companion object {
        private const val TAG = "MaintenanceDaemon"
        private const val BACKUP_DIR = "/sdcard/atlas-ai/backups"
        private const val SCRATCH_DIR = "/sdcard/atlas-ai/scratch"
        private const val LOGS_DIR = "/sdcard/atlas-ai/logs"
        private const val LOW_BATTERY_THRESHOLD = 20
    }

    data class PowerStatus(
        val batteryPct: Int,
        val isCharging: Boolean,
        val isPowerSaveMode: Boolean
    )

    /**
     * Inspects current battery and power supply state.
     */
    fun getPowerStatus(): PowerStatus {
        return try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, ifilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) ((level.toFloat() / scale.toFloat()) * 100).toInt() else 100
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            PowerStatus(
                batteryPct = pct,
                isCharging = isCharging,
                isPowerSaveMode = pct < LOW_BATTERY_THRESHOLD && !isCharging
            )
        } catch (e: Exception) {
            PowerStatus(100, true, false)
        }
    }

    /**
     * Executes automated periodic system check.
     */
    suspend fun runSystemCheck(): String = withContext(Dispatchers.IO) {
        val power = getPowerStatus()
        val memSummary = RootShellManager.execSingleLine("free -h 2>/dev/null | grep Mem: || free -m 2>/dev/null | grep Mem:") ?: "Mem: N/A"
        val swapSummary = RootShellManager.execSingleLine("free -h 2>/dev/null | grep Swap: || free -m 2>/dev/null | grep Swap:") ?: "Swap: N/A"
        val storageSummary = RootShellManager.execSingleLine("df -h /sdcard 2>/dev/null | tail -n 1") ?: "Storage: N/A"
        val uptime = RootShellManager.execSingleLine("uptime 2>/dev/null") ?: "Uptime: N/A"

        val report = buildString {
            appendLine("=== AUTONOMOUS SYSTEM HEALTH CHECK ===")
            appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("Power State: ${power.batteryPct}% battery (Charging: ${power.isCharging}, PowerSave: ${power.isPowerSaveMode})")
            appendLine("Memory: $memSummary")
            appendLine("Swap: $swapSummary")
            appendLine("Storage (/sdcard): $storageSummary")
            appendLine("Host: $uptime")
            appendLine("Active Tasks: ${taskMemory.getAllActiveTasks().size}")
        }

        Log.i(TAG, "Completed autonomous system check (Battery: ${power.batteryPct}%)")
        report
    }

    /**
     * Executes automated snapshot backup of agent identity, tasks, and memory.
     */
    suspend fun runAgentBackup(): String = withContext(Dispatchers.IO) {
        val power = getPowerStatus()
        if (power.isPowerSaveMode) {
            Log.w(TAG, "Skipping backup to preserve battery (${power.batteryPct}% uncharged)")
            return@withContext "Deferred: Low battery mode active (${power.batteryPct}%)"
        }

        try {
            val bDir = File(BACKUP_DIR)
            if (!bDir.exists()) bDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val backupFile = File(bDir, "atlas_backup_$timestamp.json")

            val backupRoot = JSONObject()
            backupRoot.put("version", "2.1")
            backupRoot.put("timestamp", System.currentTimeMillis())
            backupRoot.put("backupDate", Date().toString())

            // Backup active tasks and history summary
            val tasksArray = org.json.JSONArray()
            taskMemory.getAllActiveTasks().forEach { task ->
                tasksArray.put(JSONObject().apply {
                    put("id", task.id)
                    put("description", task.description)
                    put("command", task.command)
                    put("status", task.status.name)
                    put("executeAt", task.executeAt)
                })
            }
            backupRoot.put("activeTasks", tasksArray)

            // Backup mind file if exists
            val mindFile = listOf(
                File("/sdcard/atlas-ai/memory/agent_mind.json"),
                File("/sdcard/Medlam-Ai/memory/agent_mind.json")
            ).firstOrNull { it.exists() }
            mindFile?.let { backupRoot.put("agentMind", JSONObject(it.readText(StandardCharsets.UTF_8))) }

            backupFile.writeText(backupRoot.toString(2), StandardCharsets.UTF_8)
            val msg = "✔ Autonomous backup saved to ${backupFile.absolutePath} (${backupFile.length()} bytes)"
            Log.i(TAG, msg)

            // Keep only latest 10 backups to prevent storage clutter
            pruneBackups(bDir, keepCount = 10)
            msg
        } catch (e: Exception) {
            val err = "✖ Backup failed: ${e.message}"
            Log.e(TAG, err, e)
            err
        }
    }

    /**
     * Cleans up stale scratch and log files older than 48 hours.
     */
    suspend fun runCacheCleanup(): String = withContext(Dispatchers.IO) {
        var deletedCount = 0
        var freedBytes = 0L
        val maxAgeMs = 48 * 3600 * 1000L
        val cutoff = System.currentTimeMillis() - maxAgeMs

        listOf(File(SCRATCH_DIR), File(LOGS_DIR)).forEach { dir ->
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.lastModified() < cutoff) {
                        freedBytes += file.length()
                        if (file.delete()) deletedCount++
                    }
                }
            }
        }

        val summary = "Pruned $deletedCount temporary files, freed ${freedBytes / 1024} KB"
        Log.i(TAG, "Cache cleanup: $summary")
        summary
    }

    private fun pruneBackups(dir: File, keepCount: Int) {
        val backups = dir.listFiles { f -> f.isFile && f.name.startsWith("atlas_backup_") }
            ?.sortedByDescending { it.lastModified()} ?: return
        if (backups.size > keepCount) {
            backups.drop(keepCount).forEach { it.delete() }
        }
    }
}
