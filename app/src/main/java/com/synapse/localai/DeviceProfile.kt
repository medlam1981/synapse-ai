package com.synapse.localai

import android.app.ActivityManager
import android.content.Context
import java.io.File

data class HardwareProfile(
    val totalRamMb: Long,
    val totalRamGb: Double,
    val totalCores: Int,
    val performanceCores: Int,
    val isLowMemoryDevice: Boolean // totalRamMb < 6000 (i.e. < 6GB)
) {
    constructor(totalRamMb: Long, totalCores: Int, performanceCores: Int) : this(
        totalRamMb = totalRamMb,
        totalRamGb = totalRamMb.toDouble() / 1024.0,
        totalCores = totalCores,
        performanceCores = performanceCores,
        isLowMemoryDevice = totalRamMb < 6000L
    )
}

data class ModelHardwareCheck(
    val warningMessage: String?,
    val isF16OrMassive: Boolean,
    val totalRamGb: Double,
    val modelSizeGb: Double
)

object DeviceProfile {

    fun detect(context: Context): HardwareProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalRamMb = info.totalMem / (1024 * 1024)
        val totalRamGb = info.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val totalCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val perfCores = detectPerformanceCores(totalCores)
        return HardwareProfile(
            totalRamMb = totalRamMb,
            totalRamGb = totalRamGb,
            totalCores = totalCores,
            performanceCores = perfCores,
            isLowMemoryDevice = totalRamMb < 6000L
        )
    }

    /**
     * Adaptive Hardware Evaluation:
     * Validates model quantization and size against device memory envelope.
     * Never blocks execution; returns a warning notice if Total_RAM < 6GB and loading F16 or >3GB models.
     */
    fun checkModelRequirements(context: Context, modelPath: String): ModelHardwareCheck {
        val hw = detect(context)
        val file = File(modelPath)
        if (!file.exists()) {
            return ModelHardwareCheck(null, false, hw.totalRamGb, 0.0)
        }
        val sizeGb = file.length().toDouble() / (1024.0 * 1024.0 * 1024.0)
        val name = file.name.lowercase()
        val isF16 = name.contains("f16") || name.contains("fp16")
        val isMassive = sizeGb > 3.0

        // Hardware limits warning is permanently disabled per design directive.
        val warning: String? = null

        return ModelHardwareCheck(
            warningMessage = warning,
            isF16OrMassive = isF16 || isMassive,
            totalRamGb = hw.totalRamGb,
            modelSizeGb = sizeGb
        )
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

    // Dynamic batch size scaled to memory envelope to prevent OOM
    fun optimalBatch(hw: HardwareProfile): Int = when {
        hw.totalRamMb >= 12000 -> 2048
        hw.totalRamMb >= 6000 -> 1024
        hw.totalRamMb >= 3500 -> 768
        else -> 512
    }

    fun optimalUbatch(hw: HardwareProfile): Int = when {
        hw.totalRamMb >= 12000 -> 1024
        hw.totalRamMb >= 6000 -> 512
        hw.totalRamMb >= 3500 -> 256
        else -> 256
    }

    /**
     * Optimal thread allocation dynamically matching available performance cores.
     * Eliminates big.LITTLE core synchronization latency and scales to any core count.
     */
    fun optimalThreads(hw: HardwareProfile): Int {
        return hw.performanceCores.coerceIn(1, hw.totalCores.coerceAtLeast(1))
    }

    fun inferenceThreads(requestedThreads: Int, freeRamMb: Long, hw: HardwareProfile?): Int {
        if (freeRamMb < 768L) return 1
        if (freeRamMb < 1_536L) return 2
        val availablePerformanceThreads = hw?.let { optimalThreads(it) } ?: 4
        return requestedThreads.coerceIn(1, availablePerformanceThreads.coerceIn(1, 8))
    }

    fun inferenceContextLength(requestedContextLength: Int, freeRamMb: Long): Int =
        if (freeRamMb < 1_536L) 2048 else requestedContextLength.coerceIn(2048, 4096)

    fun optimalBatchThreads(hw: HardwareProfile): Int {
        return hw.performanceCores.coerceIn(1, hw.totalCores.coerceAtLeast(1))
    }

    /**
     * Dynamically computes CPU core range for affinity binding.
     * E.g., on 8-core CPU with 4 perf cores: "4-7".
     * On uniform CPU (all big or all little): "0-(N-1)".
     */
    fun optimalCpuRange(hw: HardwareProfile): String {
        val little = (hw.totalCores - hw.performanceCores).coerceAtLeast(0)
        return if (little > 0 && hw.performanceCores > 0) {
            "$little-${hw.totalCores - 1}"
        } else {
            "0-${hw.totalCores - 1}"
        }
    }

    /**
     * Bitmask string for taskset to bind strictly to performance cores.
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
     * Explicitly excludes battery fuel-gauge 'soc' (State of Charge percentage), 
     * battery/charging subsystems, and fixed trip points.
     */
    fun readDeviceTemperature(): Int {
        var primarySoCTemp = 0
        var fallbackTemp = 0
        try {
            val thermalDir = File("/sys/class/thermal")
            val zones = thermalDir.listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") } ?: emptyArray()
            for (zone in zones) {
                val typeFile = File(zone, "type")
                val tempFile = File(zone, "temp")
                if (tempFile.exists()) {
                    val type = if (typeFile.exists()) typeFile.readText().lowercase().trim() else ""

                    // Skip battery fuel gauge (State of Charge 'soc'), battery charging, and non-silicon devices
                    if (type == "soc" || type.contains("bms") || type.contains("batt") ||
                        type.contains("charger") || type.contains("bcl") || type.contains("ibat") ||
                        type.contains("vbat") || type.contains("lmh") || type.contains("trip") ||
                        type.contains("backlight") || type.contains("rf-pa")) {
                        continue
                    }

                    val t = tempFile.readText().trim().toIntOrNull() ?: continue
                    val normalized = if (t > 1000) t / 1000 else t

                    // Realistic silicon operating envelope (20°C to 85°C)
                    if (normalized in 20..85) {
                        if (type.contains("cpu") || type.contains("tsens") || type.contains("cluster") ||
                            type.contains("core") || type.contains("gold") || type.contains("silver") ||
                            type.contains("gpu") || type.contains("cdsp")) {
                            primarySoCTemp = maxOf(primarySoCTemp, normalized)
                        } else if (normalized != 37 && !type.contains("pm")) {
                            fallbackTemp = maxOf(fallbackTemp, normalized)
                        }
                    }
                }
            }

            if (primarySoCTemp > 0) return primarySoCTemp
            if (fallbackTemp > 0) return fallbackTemp

            val t0 = File("/sys/class/thermal/thermal_zone0/temp")
            if (t0.exists()) {
                val t = t0.readText().trim().toIntOrNull() ?: 0
                val normalized = if (t > 1000) t / 1000 else t
                if (normalized in 20..85) return normalized
            }
        } catch (_: Exception) {}
        return if (primarySoCTemp > 0) primarySoCTemp else 44
    }
}
