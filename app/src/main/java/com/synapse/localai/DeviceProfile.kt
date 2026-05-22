package com.synapse.localai

import android.app.ActivityManager
import android.content.Context

data class HardwareProfile(val totalRamMb: Long, val cores: Int)

object DeviceProfile {
    fun detect(context: Context): HardwareProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val cores = Runtime.getRuntime().availableProcessors()
        return HardwareProfile(info.totalMem / (1024 * 1024), cores)
    }

    // Professor's Turbo Settings: 
    // Higher batch size for prefilling makes initial understanding faster.
    fun optimalBatch(hw: HardwareProfile): Int = when {
        hw.totalRamMb >= 8192 -> 512
        hw.totalRamMb >= 4000 -> 256
        else                  -> 128 // Still conservative for 3.6GB during generation
    }

    // ubatch is for generation speed.
    fun optimalUbatch(hw: HardwareProfile): Int = when {
        hw.totalRamMb >= 8192 -> 128
        else                  -> 64
    }
    
    // Smart Threading: Leave 1 core for system/UI to prevent lag
    fun optimalThreads(hw: HardwareProfile): Int {
        return if (hw.cores > 2) hw.cores - 1 else hw.cores
    }
}
