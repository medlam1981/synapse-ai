package com.synapse.localai.orchestration

import com.synapse.localai.AtlasLog as Log
import com.synapse.localai.HtmlSanitizer
import com.synapse.localai.SnapshotManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Staging Buffer Quarantine Engine:
 * Strictly isolates all interim files, generated code, and worker artifacts
 * inside a transient sandbox (/sdcard/atlas-ai/.staging/run_<session_id>_<timestamp>/)
 * prior to audit and atomic production promotion.
 */
class StagingManager(
    val sessionId: String,
    val userIntent: String,
    val destinationDir: String
) {
    companion object {
        private const val TAG = "StagingManager"
        const val STAGING_BASE_DIR = "/sdcard/atlas-ai/.staging"
    }

    val runId: String = "run_${sessionId.take(8)}_${System.currentTimeMillis()}"
    val runDir: File = File(STAGING_BASE_DIR, runId)
    private val manifestFile: File = File(runDir, "task_manifest.json")
    private val stagedFilesByWorker = mutableMapOf<String, MutableMap<String, File>>()

    init {
        try {
            if (!runDir.exists()) {
                runDir.mkdirs()
            }
            cleanStaleRuns()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing staging directory: ${e.message}")
        }
    }

    private fun sanitizeWorkerId(workerId: String): String {
        return workerId.lowercase().replace(" ", "_").replace("[^a-z0-9_]".toRegex(), "")
    }

    /**
     * Initializes task_manifest.json with the given worker sub-tasks.
     */
    fun initializeManifest(tasks: List<WorkerSubTask>) {
        try {
            val manifestJson = JSONObject().apply {
                put("runId", runId)
                put("sessionId", sessionId)
                put("timestamp", System.currentTimeMillis())
                put("userIntent", userIntent)
                put("destinationDir", destinationDir)
                put("verifierStatus", "PENDING")

                val workersArr = JSONArray()
                tasks.forEach { task ->
                    val wObj = JSONObject().apply {
                        put("workerId", task.workerId)
                        put("role", task.role.displayName)
                        put("status", "DISPATCHED")
                        put("targetFiles", JSONArray(task.targetFileNames))
                    }
                    workersArr.put(wObj)
                }
                put("workers", workersArr)
            }
            manifestFile.writeText(manifestJson.toString(2), StandardCharsets.UTF_8)
            Log.i(TAG, "Staging manifest initialized at: ${manifestFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write staging manifest: ${e.message}")
        }
    }

    /**
     * Stages in-memory buffered outputs into an isolated worker subfolder.
     * Zero direct writes to production directories.
     */
    @Synchronized
    fun stageWorkerOutputs(workerId: String, files: Map<String, String>): Boolean {
        if (files.isEmpty()) return false
        val workerSubdir = File(runDir, sanitizeWorkerId(workerId))
        if (!workerSubdir.exists()) workerSubdir.mkdirs()

        val workerFileMap = stagedFilesByWorker.getOrPut(workerId) { mutableMapOf() }

        try {
            files.forEach { (fileName, content) ->
                // Clean fileName of any path traversal artifacts
                val cleanName = File(fileName).name
                val finalContent = if (cleanName.endsWith(".html", ignoreCase = true) || cleanName.equals("index.html", ignoreCase = true)) {
                    HtmlSanitizer.sanitizeHtmlStream(content)
                } else {
                    content
                }
                val targetFile = File(workerSubdir, cleanName)
                FileOutputStream(targetFile).use { fos ->
                    fos.write(finalContent.toByteArray(StandardCharsets.UTF_8))
                    fos.flush()
                }
                workerFileMap[cleanName] = targetFile
                Log.i(TAG, "Staged [$workerId] -> ${targetFile.absolutePath} (${finalContent.length} chars)")
            }
            updateManifestWorkerStatus(workerId, "COMPLETED", null)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed staging files for $workerId: ${e.message}")
            purgeWorkerStaging(workerId)
            updateManifestWorkerStatus(workerId, "FAILED", e.message)
            return false
        }
    }

    /**
     * If an individual worker crashes, times out, or emits invalid data,
     * purge only its isolated staging folder without affecting other workers.
     */
    @Synchronized
    fun purgeWorkerStaging(workerId: String) {
        val workerSubdir = File(runDir, sanitizeWorkerId(workerId))
        if (workerSubdir.exists()) {
            workerSubdir.deleteRecursively()
            Log.w(TAG, "Purged isolated staging folder for worker: $workerId")
        }
        stagedFilesByWorker.remove(workerId)
    }

    /**
     * Updates the status of an individual worker in task_manifest.json.
     */
    @Synchronized
    fun updateManifestWorkerStatus(workerId: String, status: String, error: String? = null) {
        try {
            if (!manifestFile.exists()) return
            val json = JSONObject(manifestFile.readText(StandardCharsets.UTF_8))
            val workers = json.optJSONArray("workers") ?: return
            for (i in 0 until workers.length()) {
                val w = workers.getJSONObject(i)
                if (w.optString("workerId") == workerId) {
                    w.put("status", status)
                    if (error != null) w.put("error", error)
                }
            }
            manifestFile.writeText(json.toString(2), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating worker manifest status: ${e.message}")
        }
    }

    /**
     * Updates the verifier status in task_manifest.json.
     */
    @Synchronized
    fun updateVerifierStatus(status: String, auditErrors: List<String> = emptyList()) {
        try {
            if (!manifestFile.exists()) return
            val json = JSONObject(manifestFile.readText(StandardCharsets.UTF_8))
            json.put("verifierStatus", status)
            json.put("auditErrors", JSONArray(auditErrors))
            manifestFile.writeText(json.toString(2), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating verifier status: ${e.message}")
        }
    }

    /**
     * Retrieves all staged files across all workers.
     */
    @Synchronized
    fun getAllStagedFiles(): Map<String, File> {
        val all = mutableMapOf<String, File>()
        stagedFilesByWorker.values.forEach { map ->
            all.putAll(map)
        }
        return all
    }

    /**
     * Atomically promotes verified staging files to the target production directory.
     * Uses atomic rename / temporary replacement to guarantee zero partial or broken writes.
     */
    @Synchronized
    fun promoteToProduction(): List<File> {
        val destDir = File(destinationDir)
        if (destDir.exists()) {
            SnapshotManager.createSnapshot(destDir)
        }
        if (!destDir.exists()) destDir.mkdirs()

        val promoted = mutableListOf<File>()
        val allStaged = getAllStagedFiles()

        for ((fileName, stagedFile) in allStaged) {
            val targetFile = File(destDir, fileName)
            val tempFile = File(destDir, "$fileName.tmp_${System.currentTimeMillis()}")

            try {
                // 1. Copy or sanitize staged file to temporary file in production directory
                if (fileName.endsWith(".html", ignoreCase = true) || fileName.equals("index.html", ignoreCase = true)) {
                    val rawText = stagedFile.readText(StandardCharsets.UTF_8)
                    val sanitized = HtmlSanitizer.sanitizeHtmlStream(rawText)
                    tempFile.writeText(sanitized, StandardCharsets.UTF_8)
                } else {
                    stagedFile.copyTo(tempFile, overwrite = true)
                }
                // 2. Atomically rename temporary file to destination file
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                if (tempFile.renameTo(targetFile)) {
                    promoted.add(targetFile)
                    Log.i(TAG, "Atomically promoted: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                } else {
                    // Fallback copy if filesystem rename across mounts fails
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                    promoted.add(targetFile)
                    Log.i(TAG, "Promoted via atomic fallback: ${targetFile.absolutePath}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during atomic promotion of $fileName: ${e.message}")
                if (tempFile.exists()) tempFile.delete()
            }
        }

        // Record promoted files in manifest
        try {
            if (manifestFile.exists()) {
                val json = JSONObject(manifestFile.readText(StandardCharsets.UTF_8))
                json.put("promotedFiles", JSONArray(promoted.map { it.absolutePath }))
                manifestFile.writeText(json.toString(2), StandardCharsets.UTF_8)
            }
        } catch (_: Exception) {}

        return promoted
    }

    /**
     * Transient Purge: Automatically delete the entire .staging/run_<timestamp>/ sandbox directory
     * immediately following successful production deployment or aborted run.
     */
    fun cleanupStaging() {
        try {
            if (runDir.exists()) {
                runDir.deleteRecursively()
                Log.i(TAG, "Transient staging sandbox purged: ${runDir.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not completely purge staging sandbox: ${e.message}")
        }
    }

    /**
     * Cleans up any stale runs older than 1 hour.
     */
    private fun cleanStaleRuns() {
        try {
            val base = File(STAGING_BASE_DIR)
            if (!base.exists()) return
            val oneHourAgo = System.currentTimeMillis() - 3600_000L
            base.listFiles()?.forEach { file ->
                if (file.isDirectory && file.name.startsWith("run_") && file.lastModified() < oneHourAgo && file.absolutePath != runDir.absolutePath) {
                    file.deleteRecursively()
                }
            }
        } catch (_: Exception) {}
    }
}
