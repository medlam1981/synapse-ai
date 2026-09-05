package com.synapse.localai

import android.os.Environment
import android.os.StatFs
import android.util.Log

/**
 * Hardware Storage Sentinel (Disk Exhaustion Guardrail)
 * Inspects device external storage available space in real time.
 * Prevents device freezing or OOM by halting recursive continuation
 * loops when available storage drops below the critical safety threshold (500 MB).
 */
object StorageSentinel {
    private const val TAG = "StorageSentinel"
    
    // Safety floor: 500 MB
    const val CRITICAL_THRESHOLD_BYTES: Long = 500L * 1024L * 1024L

    fun getAvailableBytes(path: String = Environment.getExternalStorageDirectory().path): Long {
        return try {
            val stat = StatFs(path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            Log.e(TAG, "Storage inspection failed: ${e.message}")
            Long.MAX_VALUE
        }
    }

    fun getAvailableMb(path: String = Environment.getExternalStorageDirectory().path): Long {
        return getAvailableBytes(path) / (1024L * 1024L)
    }

    fun isStorageCritical(thresholdBytes: Long = CRITICAL_THRESHOLD_BYTES): Boolean {
        val available = getAvailableBytes()
        val critical = available < thresholdBytes
        if (critical) {
            Log.w(TAG, "Storage critical! Available: ${available / (1024 * 1024)}MB < Threshold: ${thresholdBytes / (1024 * 1024)}MB")
        }
        return critical
    }
}
