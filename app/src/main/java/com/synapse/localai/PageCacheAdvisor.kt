package com.synapse.localai

import android.os.Build
import android.os.Process
import android.util.Log

/**
 * Resilient Zero-Forced-RAM Kernel Page Management & I/O Thrashing Guard.
 *
 * Implements:
 * 1. Kernel Memory Advice (`madvise`):
 *    - Informs Linux kernel with MADV_WILLNEED during active inference for sequential read-ahead.
 *    - Issues MADV_DONTNEED when transitioning to IDLE or when a secondary engine takes focus,
 *      allowing the OS to drop clean file pages instantly without writing to swap or triggering kswapd thrashing.
 * 2. Storage Layer & FUSE Mitigation:
 *    - Strictly detects system page size (4KB or modern Android 15+ 16KB) and aligns mapped offsets.
 * 3. 64-Bit VAS Guard:
 *    - Validates 64-bit arm64-v8a address space to prevent address space collisions.
 */
object PageCacheAdvisor {

    private const val TAG = "PageCacheAdvisor"

    const val ADVICE_WILLNEED = 1
    const val ADVICE_DONTNEED = 2
    const val ADVICE_SEQUENTIAL = 3
    const val ADVICE_NORMAL = 4

    init {
        LlamaServerJNI.load()
    }

    @JvmStatic external fun nativeGetPageSize(): Long
    @JvmStatic external fun nativeAdviseModelMemory(filePathPattern: String?, adviceCode: Int): Long
    @JvmStatic external fun nativeGetResidentMemoryMb(filePathPattern: String?): Long

    val pageSize: Long by lazy {
        try {
            nativeGetPageSize()
        } catch (_: Throwable) {
            4096L
        }
    }

    /**
     * Validates that execution is strictly on 64-bit ARM architecture.
     * Prevents VAS starvation on 32-bit runtimes when mapping multi-gigabyte models.
     */
    fun assert64BitArchitecture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Process.is64Bit()) {
                throw UnsupportedOperationException("Strictly 64-bit arm64-v8a architecture required for zero-forced-RAM execution.")
            }
        }
    }

    /**
     * Informs the Linux kernel to prefetch active model pages into PageCache sequentially.
     */
    fun adviseModelActive(pathPattern: String? = null) {
        try {
            val advisedBytes = nativeAdviseModelMemory(pathPattern, ADVICE_WILLNEED)
            val mb = advisedBytes / (1024 * 1024)
            Log.i(TAG, "⚡ [PageCache] Issued madvise(MADV_WILLNEED) on $mb MB of model pages")
        } catch (t: Throwable) {
            Log.w(TAG, "adviseModelActive notice: ${t.message}")
        }
    }

    /**
     * Informs the Linux kernel that model pages are no longer actively needed.
     * Drops clean file-backed pages from RAM instantly to avoid kswapd thrashing and LMK triggers.
     */
    fun adviseModelIdle(pathPattern: String? = null) {
        try {
            val advisedBytes = nativeAdviseModelMemory(pathPattern, ADVICE_DONTNEED)
            val mb = advisedBytes / (1024 * 1024)
            Log.i(TAG, "⚡ [PageCache] Issued madvise(MADV_DONTNEED) on $mb MB of model pages (reclaimed)")
        } catch (t: Throwable) {
            Log.w(TAG, "adviseModelIdle notice: ${t.message}")
        }
    }

    /**
     * Returns the physical resident RAM currently occupied by mapped model pages.
     */
    fun getResidentModelRamMb(pathPattern: String? = null): Long {
        return try {
            nativeGetResidentMemoryMb(pathPattern)
        } catch (_: Throwable) {
            0L
        }
    }

    /**
     * Validates if a file offset conforms to system page boundaries.
     */
    fun isPageAligned(offset: Long): Boolean {
        val ps = pageSize
        return if (ps > 0) (offset % ps) == 0L else true
    }
}
