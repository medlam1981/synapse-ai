package com.synapse.localai

import android.content.Context
import android.os.Process
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ZramOptimizer {
    private const val TAG = "ZramOptimizer"

    /**
     * Completely immune the current process and all child threads from Linux OOM killer
     * and Android low-memory killer daemon (lmkd).
     * By writing -1000 to /proc/$PID/oom_score_adj, the kernel considers this process
     * unkillable (OOM_SCORE_ADJ_MIN).
     */
    suspend fun disableOomKiller(): Boolean = withContext(Dispatchers.IO) {
        val pid = Process.myPid()
        val commands = listOf(
            "echo -1000 > /proc/$pid/oom_score_adj",
            "echo -17 > /proc/$pid/oom_adj 2>/dev/null",
            "for p in \$(pgrep -f com.synapse.localai); do echo -1000 > /proc/\$p/oom_score_adj 2>/dev/null; done",
            "for p in \$(pgrep llama-server); do echo -1000 > /proc/\$p/oom_score_adj 2>/dev/null; done"
        )

        val cmd = commands.joinToString(" && ")
        val success = executeSuperuserCommand(cmd)
        if (success) {
            Log.i(TAG, "✓ OOM Killer disabled successfully for PID $pid (oom_score_adj=-1000)")
        } else {
            Log.w(TAG, "⚠️ Failed to disable OOM killer (Root permission required)")
        }
        success
    }

    /**
     * Configures aggressive ZRAM compression, tunes virtual memory subsystem,
     * reclaims cached memory buffers, and defragments RAM to bypass low-memory limits.
     */
    suspend fun optimizeMemoryAndZram(context: Context): Boolean = withContext(Dispatchers.IO) {
        val pid = Process.myPid()
        val script = StringBuilder().apply {
            append("echo 1 > /proc/sys/vm/drop_caches 2>/dev/null; ")
            append("echo 1 > /proc/sys/vm/compact_memory 2>/dev/null; ")
            append("echo 60 > /proc/sys/vm/swappiness 2>/dev/null; ")
            append("echo 10 > /proc/sys/vm/watermark_scale_factor 2>/dev/null; ")
            append("echo 50 > /proc/sys/vm/vfs_cache_pressure 2>/dev/null; ")
            append("echo 1 > /proc/sys/vm/overcommit_memory 2>/dev/null; ")
            append("if [ -f /sys/block/zram0/disksize ]; then ")
            append("  if ! grep -q zram /proc/swaps; then ")
            append("    echo 4294967296 > /sys/block/zram0/disksize 2>/dev/null; ")
            append("    mkswap /dev/block/zram0 2>/dev/null; ")
            append("    swapon -p 32767 /dev/block/zram0 2>/dev/null; ")
            append("  fi; ")
            append("fi; ")
            append("echo -1000 > /proc/$pid/oom_score_adj; ")
            append("echo -17 > /proc/$pid/oom_adj 2>/dev/null")
        }.toString()

        val success = executeSuperuserCommand(script)
        if (success) {
            Log.i(TAG, "✓ ZRAM compression & virtual memory subsystem tuned for low-memory bypass")
        } else {
            Log.w(TAG, "⚠️ ZRAM optimization skipped or root not granted")
        }
        success
    }

    private fun executeSuperuserCommand(cmd: String): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Root execution error: ${e.message}")
            false
        }
    }
}
