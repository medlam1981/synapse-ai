package com.synapse.localai

import android.content.Context
import android.os.Process
import com.synapse.localai.AtlasLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ZramOptimizer {
    private const val TAG = "ZramOptimizer"

    /**
     * Enables available ZRAM swap and applies conservative storage/memory tuning.
     */
    suspend fun optimizeMemoryAndZram(context: Context): Boolean = withContext(Dispatchers.IO) {
        val script = StringBuilder().apply {
            // Preserve application state and model pages; do not kill apps or drop page cache.
            append("echo 1 > /proc/sys/vm/compact_memory 2>/dev/null; ")
            append("echo 100 > /proc/sys/vm/swappiness 2>/dev/null; ")
            append("echo 50 > /proc/sys/vm/vfs_cache_pressure 2>/dev/null; ")
            append("if ! grep -qE '^[^[:space:]]*zram[0-9]*[[:space:]]' /proc/swaps 2>/dev/null; then ")
            append("  zram_dev=/dev/block/zram0; [ -b \"\$zram_dev\" ] || zram_dev=/dev/zram0; ")
            append("  if [ -f /sys/block/zram0/disksize ] && [ -b \"\$zram_dev\" ]; then ")
            append("    zram_bytes=\$(cat /sys/block/zram0/disksize 2>/dev/null); ")
            append("    case \"\$zram_bytes\" in ''|*[!0-9]*) zram_bytes=0;; esac; ")
            append("    if [ \"\$zram_bytes\" = 0 ]; then ")
            append("      mem_kb=\$(awk '/MemTotal:/ {print \$2}' /proc/meminfo); ")
            append("      case \"\$mem_kb\" in ''|*[!0-9]*) mem_kb=0;; esac; ")
            append("      if [ \"\$mem_kb\" -gt 0 ]; then ")
            append("        zram_bytes=\$((mem_kb * 512)); ")
            append("        [ \"\$zram_bytes\" -le 4294967296 ] || zram_bytes=4294967296; ")
            append("        echo \"\$zram_bytes\" > /sys/block/zram0/disksize 2>/dev/null; ")
            append("      fi; ")
            append("    fi; ")
            append("    if [ \"\$zram_bytes\" -gt 0 ]; then mkswap \"\$zram_dev\" >/dev/null 2>&1 && swapon -p 100 \"\$zram_dev\" 2>/dev/null; fi; ")
            append("  fi; ")
            append("fi; ")
            append("if grep -qE '^[^[:space:]]*zram[0-9]*[[:space:]]' /proc/swaps 2>/dev/null; then echo ZRAM_SWAP_ENABLED; else echo ZRAM_SWAP_UNAVAILABLE; fi; ")
            append("if [ -f /sys/block/mmcblk0/queue/read_ahead_kb ]; then echo 1024 > /sys/block/mmcblk0/queue/read_ahead_kb 2>/dev/null; elif [ -f /sys/block/sda/queue/read_ahead_kb ]; then echo 1024 > /sys/block/sda/queue/read_ahead_kb 2>/dev/null; fi")
        }.toString()

        ensureNoMedia()
        val success = try {
            RootShellManager.exec(script).any { it.trim() == "ZRAM_SWAP_ENABLED" }
        } catch (e: Exception) {
            Log.e(TAG, "ZRAM configuration failed: ${e.message}")
            false
        }
        if (success) {
            Log.i(TAG, "✓ Swap preference, ZRAM availability, and model-storage read-ahead configured")
        } else {
            Log.w(TAG, "⚠️ ZRAM could not be confirmed active; root, device support, or swap configuration may be unavailable")
        }
        success
    }

    /**
     * Kernel read-ahead tuning for sequential model mmap workloads.
     */
    suspend fun optimizeKernelReadAhead(): Boolean = withContext(Dispatchers.IO) {
        val script = "if [ -f /sys/block/mmcblk0/queue/read_ahead_kb ]; then " +
                "echo 1024 > /sys/block/mmcblk0/queue/read_ahead_kb 2>/dev/null; " +
                "elif [ -f /sys/block/sda/queue/read_ahead_kb ]; then " +
                "echo 1024 > /sys/block/sda/queue/read_ahead_kb 2>/dev/null; " +
                "fi"
        val success = executeSuperuserCommand(script)
        if (success) {
            Log.i(TAG, "✓ Kernel read_ahead_kb set to 1024 KB successfully")
        } else {
            // Direct command execution fallback
            executeSuperuserCommand("su -c \"echo 1024 > /sys/block/mmcblk0/queue/read_ahead_kb\" 2>/dev/null || su -c \"echo 1024 > /sys/block/sda/queue/read_ahead_kb\" 2>/dev/null")
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
            # 1. Unrestrict all cpuset cgroups so all 8 cores are accessible
            for c in /dev/cpuset /dev/cpuset/*; do
                [ -f "${'$'}c/cpus" ] && echo 0-7 > "${'$'}c/cpus" 2>/dev/null
            done
            echo $pid > /dev/cpuset/top-app/cgroup.procs 2>/dev/null
            echo $pid > /dev/cpuset/top-app/tasks 2>/dev/null
            echo $pid > /dev/stune/top-app/tasks 2>/dev/null

            # 2. Dynamic Performance EAS scaling for big cores 4-7 (schedutil prevents thermal runaway at 85°C)
            for c in 4 5 6 7; do
                echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                cat /sys/devices/system/cpu/cpu${'$'}c/cpufreq/cpuinfo_min_freq > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                cat /sys/devices/system/cpu/cpu${'$'}c/cpufreq/cpuinfo_max_freq > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
            done

            # 3. Pin compute and worker threads strictly to Big Cores 4-7 (mask 0xF0)
            for t in /proc/$pid/task/*; do
                tid=${'$'}(basename ${'$'}t)
                comm=${'$'}(cat ${'$'}t/comm 2>/dev/null)
                case "${'$'}comm" in
                    *llama*|*server*|*compute*|*ggml*|*omp*|*Dispatch*|*synapse*)
                        echo ${'$'}tid > /dev/cpuset/top-app/tasks 2>/dev/null
                        echo ${'$'}tid > /dev/stune/top-app/tasks 2>/dev/null
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
            Log.i(TAG, "✓ Inference threads pinned to Big Cores 4-7 (mask $mask) with elevated priority (schedutil EAS)")
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
                File("/sdcard/atlas-ai"),
                File("/sdcard/atlas-ai/models"),
                File("/sdcard/atlas-ai/Models"),
                File("/sdcard/atlas-ai/mtp"),
                File("/sdcard/atlas-ai/Mtp"),
                File("/sdcard/atlas-ai/slots")
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

    suspend fun compactMemoryForInference(): Boolean = withContext(Dispatchers.IO) {
        executeSuperuserCommand("echo 1 > /proc/sys/vm/compact_memory 2>/dev/null")
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
