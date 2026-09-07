package com.synapse.localai

import android.app.ActivityManager
import android.content.Context
import java.io.File

data class HardwareProfile(
    val totalRamMb: Long,
    val totalCores: Int,
    val performanceCores: Int
)

object DeviceProfile {

    fun detect(context: Context): HardwareProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val perfCores = detectPerformanceCores(totalCores)
        return HardwareProfile(info.totalMem / (1024 * 1024), totalCores, perfCores)
    }

    /**
     * Inspects CPU maximum frequencies to identify high-performance ("big") cores
     * vs efficiency ("little") cores across big.LITTLE / DynamIQ architectures.
     */
    private fun detectPerformanceCores(totalCores: Int): Int {
        try {
            val freqs = mutableListOf<Long>()
            for (i in 0 until totalCores) {
                val freqFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                val altFreqFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_max_freq")
                val freq = when {
                    freqFile.exists() -> freqFile.readText().trim().toLongOrNull()
                    altFreqFile.exists() -> altFreqFile.readText().trim().toLongOrNull()
                    else -> null
                }
                if (freq != null && freq > 0) {
                    freqs.add(freq)
                }
            }

            if (freqs.size == totalCores && freqs.distinct().size > 1) {
                val maxFreq = freqs.maxOrNull() ?: 0L
                // Count cores within 15% of max frequency as performance cores
                val threshold = (maxFreq * 0.85).toLong()
                val perfCount = freqs.count { it >= threshold }
                if (perfCount in 1 until totalCores) {
                    return perfCount
                }
            }
        } catch (_: Exception) {}

        // Fallback heuristic: 8-core SoCs usually have 4 performance cores
        return if (totalCores >= 8) 4 else totalCores.coerceIn(2, 4)
    }

    // Optimal batch size scaled to memory envelope to prevent OOM
    fun optimalBatch(hw: HardwareProfile): Int = when {
        hw.totalRamMb >= 6000 -> 512
        hw.totalRamMb >= 3500 -> 256
        else -> 128
    }

    fun optimalUbatch(hw: HardwareProfile): Int = when {
        hw.totalRamMb >= 6000 -> 256
        else -> 128
    }

    /**
     * Optimal thread allocation prioritizing high-performance cores
     * over efficiency clusters to maximize token generation speed without thrashing.
     */
    fun optimalThreads(hw: HardwareProfile): Int {
        return hw.performanceCores.coerceIn(2, 4)
    }

    fun optimalBatchThreads(hw: HardwareProfile): Int {
        // Thermal guardrail: cap burst prefill threads to at most 4 to prevent package thermal spikes
        return optimalThreads(hw).coerceAtMost(4)
    }

    /**
     * Bitmask string for taskset to pin threads strictly to physical Performance/Big cores.
     * e.g., on an 8-core CPU with 4 performance cores (cores 4..7), mask is "f0".
     */
    fun performanceAffinityMask(hw: HardwareProfile): String {
        val total = hw.totalCores.coerceIn(1, 16)
        val perf = hw.performanceCores.coerceIn(1, total)
        val little = (total - perf).coerceAtLeast(0)
        val mask = ((1 shl perf) - 1) shl little
        return mask.toString(16)
    }

    /**
     * Reads the real-time package / CPU temperature in °C from system thermal zones.
     */
    fun readDeviceTemperature(): Int {
        var maxTemp = 0
        try {
            val thermalDir = File("/sys/class/thermal")
            val zones = thermalDir.listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") } ?: emptyArray()
            for (zone in zones) {
                val typeFile = File(zone, "type")
                val tempFile = File(zone, "temp")
                if (tempFile.exists()) {
                    val type = if (typeFile.exists()) typeFile.readText().lowercase() else ""
                    val t = tempFile.readText().trim().toIntOrNull() ?: 0
                    val normalized = if (t > 1000) t / 1000 else t
                    if (normalized in 20..115) {
                        if (type.contains("cpu") || type.contains("tsens") || type.contains("cluster")) {
                            maxTemp = normalized
                            break
                        }
                        if (normalized > maxTemp && normalized != 37) {
                            maxTemp = normalized
                        }
                    }
                }
            }
            if (maxTemp == 0 || maxTemp == 37) {
                val t0 = File("/sys/class/thermal/thermal_zone0/temp")
                if (t0.exists()) {
                    val t = t0.readText().trim().toIntOrNull() ?: 0
                    val normalized = if (t > 1000) t / 1000 else t
                    if (normalized in 20..115) maxTemp = normalized
                }
            }
        } catch (_: Exception) {}
        return maxTemp
    }
}
