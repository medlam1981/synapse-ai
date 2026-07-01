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
    fun optimalBatch(hw: HardwareProfile): Int = 512 // Boosted for maximum speed

    // ubatch is for generation speed.
    fun optimalUbatch(hw: HardwareProfile): Int = 128
    
    // Smart Threading: Use all available cores for maximum performance
    fun optimalThreads(hw: HardwareProfile): Int {
        return hw.cores
    }
}
