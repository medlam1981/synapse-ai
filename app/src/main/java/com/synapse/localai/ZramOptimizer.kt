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
            "chmod 444 /proc/$pid/oom_score_adj 2>/dev/null",
            "echo -17 > /proc/$pid/oom_adj 2>/dev/null",
            "for p in \$(pgrep -f com.synapse.localai); do echo -1000 > /proc/\$p/oom_score_adj 2>/dev/null; chmod 444 /proc/\$p/oom_score_adj 2>/dev/null; done",
            "for p in \$(pgrep llama-server); do echo -1000 > /proc/\$p/oom_score_adj 2>/dev/null; chmod 444 /proc/\$p/oom_score_adj 2>/dev/null; done"
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
            append("am kill-all 2>/dev/null; ")
            append("sync; echo 3 > /proc/sys/vm/drop_caches 2>/dev/null; ")
            append("echo 1 > /proc/sys/vm/compact_memory 2>/dev/null; ")
            append("for p in /proc/[0-9]*; do adj=\$(cat \$p/oom_score_adj 2>/dev/null); if [ \"\$adj\" -ge 900 ] 2>/dev/null; then kill -9 \$(basename \$p) 2>/dev/null; fi; done; ")
            append("echo 100 > /proc/sys/vm/swappiness 2>/dev/null; ")
            append("echo 100 > /proc/sys/vm/watermark_scale_factor 2>/dev/null; ")
            append("echo 32768 > /proc/sys/vm/extra_free_kbytes 2>/dev/null; ")
            append("echo 50 > /proc/sys/vm/vfs_cache_pressure 2>/dev/null; ")
            append("echo 1 > /proc/sys/vm/overcommit_memory 2>/dev/null; ")
            append("if [ -f /sys/block/zram0/disksize ]; then ")
            append("  if ! grep -q zram /proc/swaps; then ")
            append("    echo 4294967296 > /sys/block/zram0/disksize 2>/dev/null; ")
            append("    mkswap /dev/block/zram0 2>/dev/null; ")
            append("    swapon -p 32767 /dev/block/zram0 2>/dev/null; ")
            append("  fi; ")
            append("fi; ")
            append("if [ -f /sys/block/mmcblk0/queue/read_ahead_kb ]; then echo 4096 > /sys/block/mmcblk0/queue/read_ahead_kb 2>/dev/null; elif [ -f /sys/block/sda/queue/read_ahead_kb ]; then echo 4096 > /sys/block/sda/queue/read_ahead_kb 2>/dev/null; fi; ")
            append("echo -1000 > /proc/$pid/oom_score_adj; ")
            append("echo -17 > /proc/$pid/oom_adj 2>/dev/null")
        }.toString()

        ensureNoMedia()
        val success = executeSuperuserCommand(script)
        if (success) {
            Log.i(TAG, "✓ ZRAM compression & virtual memory subsystem tuned for low-memory bypass")
        } else {
            Log.w(TAG, "⚠️ ZRAM optimization skipped or root not granted")
        }
        success
    }

    /**
     * Kernel Read-Ahead Optimization (Root Execution):
     * Accelerates mmap page faults for the massive Base Model by expanding
     * the block device read-ahead buffer to 4096 KB (eMMC mmcblk0 with UFS sda fallback).
     */
    suspend fun optimizeKernelReadAhead(): Boolean = withContext(Dispatchers.IO) {
        val script = "if [ -f /sys/block/mmcblk0/queue/read_ahead_kb ]; then " +
                "echo 4096 > /sys/block/mmcblk0/queue/read_ahead_kb 2>/dev/null; " +
                "elif [ -f /sys/block/sda/queue/read_ahead_kb ]; then " +
                "echo 4096 > /sys/block/sda/queue/read_ahead_kb 2>/dev/null; " +
                "fi"
        val success = executeSuperuserCommand(script)
        if (success) {
            Log.i(TAG, "✓ Kernel read_ahead_kb set to 4096 KB successfully")
        } else {
            // Direct command execution fallback
            executeSuperuserCommand("su -c \"echo 4096 > /sys/block/mmcblk0/queue/read_ahead_kb\" 2>/dev/null || su -c \"echo 4096 > /sys/block/sda/queue/read_ahead_kb\" 2>/dev/null")
        }
        success
    }

    /**
     * Pins all current application threads and child llama-server worker threads
     * directly to the big / high-performance CPU cores (e.g. Kryo Gold Cores 4-7, mask 0xF0).
     * Unrestricts Android cpuset cgroups and sets scaling governor to performance.
     */
    suspend fun pinToPerformanceCores(mask: String = "f0"): Boolean = withContext(Dispatchers.IO) {
        val pid = Process.myPid()
        val script = """
            # 1. Unrestrict cpuset cgroups so all 8 cores are accessible
            echo 0-7 > /dev/cpuset/foreground/cpus 2>/dev/null
            echo 0-7 > /dev/cpuset/top-app/cpus 2>/dev/null
            echo $pid > /dev/cpuset/top-app/cgroup.procs 2>/dev/null

            # 2. Maximize CPU frequency & performance governor for big cores 4-7
            for c in 4 5 6 7; do
                echo performance > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                cat /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
            done

            # 3. Pin all compute and worker threads strictly to Big Cores 4-7 (mask 0xF0)
            for t in /proc/$pid/task/*; do
                tid=${'$'}(basename ${'$'}t)
                comm=${'$'}(cat ${'$'}t/comm 2>/dev/null)
                case "${'$'}comm" in
                    *llama*|*server*|*compute*|*ggml*|*omp*|*Dispatch*|*synapse*)
                        taskset -p $mask ${'$'}tid 2>/dev/null
                        renice -n -10 -p ${'$'}tid 2>/dev/null
                        ;;
                esac
            done
            for p in ${'$'}(pgrep -f llama 2>/dev/null); do
                taskset -a -p $mask ${'$'}p 2>/dev/null
                renice -n -10 -p ${'$'}p 2>/dev/null
            done
        """.trimIndent()
        val ok = executeSuperuserCommand(script)
        if (ok) {
            Log.i(TAG, "✓ Inference threads pinned to Big Cores 4-7 (mask $mask) with elevated priority")
        }
        ok
    }

    /**
     * Prevents Android MediaProvider from scanning model storage,
     * which causes extreme background CPU usage (150%+) and I/O disk starvation.
     */
    fun ensureNoMedia() {
        try {
            val dirs = listOf(
                File("/sdcard/Download"),
                File("/sdcard/Medlam-Ai"),
                File("/sdcard/Medlam-Ai/models"),
                File("/sdcard/Medlam-Ai/Models"),
                File("/sdcard/Medlam-Ai/mtp"),
                File("/sdcard/Medlam-Ai/Mtp"),
                File("/sdcard/Medlam-Ai/slots")
            )
            for (d in dirs) {
                if (!d.exists()) d.mkdirs()
                val nomedia = File(d, ".nomedia")
                if (!nomedia.exists()) {
                    nomedia.createNewFile()
                }
            }
            try {
                RootShellManager.exec("chmod 755 /data/media /data/media/0 /data/media/0/Download 2>/dev/null; chmod 666 /data/media/0/Download/* 2>/dev/null")
            } catch (_: Throwable) {}
        } catch (_: Exception) {}
    }

    /**
     * Terminates cached background Android tasks, drops Linux kernel pagecache, dentries,
     * and inodes (drop_caches=3), defragments memory, and freezes idle CPU tasks.
     */
    suspend fun closeCachedCpuProcesses(): Boolean = withContext(Dispatchers.IO) {
        val script = """
            am kill-all 2>/dev/null
            sync
            echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
            echo 1 > /proc/sys/vm/compact_memory 2>/dev/null
            for p in /proc/[0-9]*; do
                if [ -f ${'$'}p/oom_score_adj ]; then
                    adj=${'$'}(cat ${'$'}p/oom_score_adj 2>/dev/null)
                    if [ "${'$'}adj" -ge 900 ] 2>/dev/null; then
                        pid=${'$'}(basename ${'$'}p)
                        kill -9 ${'$'}pid 2>/dev/null
                    fi
                fi
            done
            sync
            echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
        """.trimIndent()
        val ok = executeSuperuserCommand(script)
        if (ok) {
            Log.i(TAG, "✓ Cached CPU processes terminated and memory caches flushed")
        }
        ok
    }

    private fun executeSuperuserCommand(cmd: String): Boolean {
        return try {
            RootShellManager.exec(cmd)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Root execution error: ${e.message}")
            false
        }
    }
}
