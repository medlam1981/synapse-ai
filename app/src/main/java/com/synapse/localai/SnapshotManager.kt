package com.synapse.localai

import android.util.Log
import java.io.File
import java.util.UUID

/**
 * Local Checkpoints & Instant Rollback Engine
 *
 * 1. Pre-Promotion Snapshot Buffer:
 *    Before promoting verified files from .staging/ to /sdcard/Medlam-Ai/webapp/:
 *    - Archive current production files into local snapshot directory:
 *      /sdcard/Medlam-Ai/.snapshots/snap_<timestamp>_<short_hash>/
 *    - Maintain a ring buffer of strictly the last 5 snapshots; automatically prune older folders to prevent storage bloat.
 *
 * 2. Rollback Execution (/rollback):
 *    - Atomically replace current production files with the contents of latest snapshot via copy/replace.
 *    - Print terminal feedback: Rollback Complete: Restored state from snapshot <name>.
 */
object SnapshotManager {
    private const val TAG = "SnapshotManager"
    const val SNAPSHOTS_BASE_DIR = "/sdcard/Medlam-Ai/.snapshots"
    const val MAX_SNAPSHOTS = 5

    /**
     * Creates an archival checkpoint of the production directory before promotion.
     * Enforces a strict 5-slot ring buffer.
     */
    @Synchronized
    fun createSnapshot(productionDir: File): File? {
        try {
            if (!productionDir.exists() || !productionDir.isDirectory) return null
            val existingFiles = productionDir.listFiles() ?: return null
            if (existingFiles.isEmpty()) return null

            val baseDir = File(SNAPSHOTS_BASE_DIR)
            if (!baseDir.exists()) baseDir.mkdirs()

            val timestamp = System.currentTimeMillis()
            val shortHash = UUID.randomUUID().toString().take(6)
            val snapshotDir = File(baseDir, "snap_${timestamp}_$shortHash")
            if (!snapshotDir.exists()) snapshotDir.mkdirs()

            productionDir.copyRecursively(snapshotDir, overwrite = true)
            Log.i(TAG, "Snapshot created successfully: ${snapshotDir.absolutePath}")

            pruneRingBuffer(baseDir)
            return snapshotDir
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create snapshot: ${e.message}", e)
            return null
        }
    }

    /**
     * Prunes snapshots so only strictly the most recent MAX_SNAPSHOTS remain.
     */
    private fun pruneRingBuffer(baseDir: File) {
        try {
            val snapshots = baseDir.listFiles { f -> f.isDirectory && f.name.startsWith("snap_") } ?: return
            if (snapshots.size > MAX_SNAPSHOTS) {
                // Sort by folder name (snap_<timestamp>_...) ascending; oldest folders first
                val sorted = snapshots.sortedWith(compareBy { it.name })
                val excess = sorted.take(snapshots.size - MAX_SNAPSHOTS)
                excess.forEach { oldSnap ->
                    oldSnap.deleteRecursively()
                    Log.i(TAG, "Pruned old snapshot: ${oldSnap.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prune snapshots: ${e.message}")
        }
    }

    /**
     * Retrieves the latest snapshot directory based on timestamp in folder name.
     */
    @Synchronized
    fun getLatestSnapshot(): File? {
        val baseDir = File(SNAPSHOTS_BASE_DIR)
        if (!baseDir.exists()) return null
        val snapshots = baseDir.listFiles { f -> f.isDirectory && f.name.startsWith("snap_") } ?: return null
        if (snapshots.isEmpty()) return null
        return snapshots.maxByOrNull { it.name }
    }

    /**
     * Restores production files from the latest snapshot.
     */
    @Synchronized
    fun rollbackLatest(targetDir: File = File("/sdcard/Medlam-Ai/webapp")): Pair<Boolean, String> {
        try {
            val latest = getLatestSnapshot()
                ?: return Pair(false, "Rollback Aborted: No snapshot checkpoints found in $SNAPSHOTS_BASE_DIR")

            if (!targetDir.exists()) {
                targetDir.mkdirs()
            } else {
                // Purge existing target files to prevent orphaned artifacts
                targetDir.listFiles()?.forEach { file ->
                    file.deleteRecursively()
                }
            }

            // Restore files from snapshot
            latest.copyRecursively(targetDir, overwrite = true)
            val feedback = "Rollback Complete: Restored state from snapshot ${latest.name}"
            Log.i(TAG, feedback)
            return Pair(true, feedback)
        } catch (e: Exception) {
            val err = "Rollback Failed: ${e.message}"
            Log.e(TAG, err, e)
            return Pair(false, err)
        }
    }
}
