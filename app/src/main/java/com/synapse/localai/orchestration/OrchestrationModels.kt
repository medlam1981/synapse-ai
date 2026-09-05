package com.synapse.localai.orchestration

/**
 * Roles for dedicated workers in the Orchestrator-Workers pipeline.
 */
enum class WorkerRole(val displayName: String) {
    CORE_LOGIC("Core Logic & State"),
    LAYOUT_PRESENTATION("Layout & Presentation"),
    ENVIRONMENT_DEPS("Environment & Dependencies")
}

/**
 * Definition of an individual worker sub-task decomposed by the Orchestrator.
 */
data class WorkerSubTask(
    val workerId: String,                // e.g. "Worker 1", "Worker 2", "Worker 3"
    val role: WorkerRole,
    val taskDescription: String,         // e.g. "Synthesizing app.js & business logic..."
    val targetFileNames: List<String>,   // e.g. ["app.js"] or ["index.html", "styles.css"]
    val prompt: String,                  // Instructions for the worker LLM
    val isShellTask: Boolean = false,    // If true, Worker 3 executes shell/MCP commands directly
    val shellCommand: String? = null     // Shell command if applicable
)

/**
 * Result produced by an individual worker before staging.
 */
data class WorkerExecutionResult(
    val workerId: String,
    val role: WorkerRole,
    val success: Boolean,
    val generatedFiles: Map<String, String>, // relative path -> file content (in-memory buffer)
    val errorMessage: String? = null,
    val executionLog: String = ""
)

/**
 * Manifest stored in `.staging/run_<session_id>_<timestamp>/task_manifest.json`.
 */
data class StagingManifest(
    val runId: String,
    val sessionId: String,
    val timestamp: Long,
    val userIntent: String,
    val destinationDir: String,
    val workers: List<WorkerManifestEntry>,
    var verifierStatus: String = "PENDING", // PENDING, AUDITING, VERIFIED, FAILED
    var auditErrors: List<String> = emptyList(),
    var promotedFiles: List<String> = emptyList()
)

data class WorkerManifestEntry(
    val workerId: String,
    val role: String,
    val targetFiles: List<String>,
    var status: String = "PENDING", // PENDING, RUNNING, COMPLETED, FAILED
    var error: String? = null
)

/**
 * Audit result from the Verifier Gatekeeper.
 */
data class VerificationResult(
    val isVerified: Boolean,
    val missingClosures: Int = 0,
    val unclosedTags: Int = 0,
    val toolCallLeaksFound: Int = 0,
    val assetErrors: List<String> = emptyList(),
    val dryRunPassed: Boolean = true,
    val errors: List<VerificationError> = emptyList()
)

data class VerificationError(
    val workerId: String,
    val fileName: String,
    val errorType: String,
    val description: String,
    val suggestedFix: String
)
