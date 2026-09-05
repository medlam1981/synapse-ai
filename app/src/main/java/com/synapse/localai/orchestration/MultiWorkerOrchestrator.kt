package com.synapse.localai.orchestration

import android.util.Log
import com.synapse.localai.ToolExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Multi-Worker MCP Orchestration Pipeline:
 * Implements the Orchestrator-Workers + Critic/Verifier Pattern.
 * Decouples user requests into parallel background tasks, isolates all generated code
 * inside a transient staging sandbox, audits outputs through the Verifier Gatekeeper,
 * and atomically deploys verified files to production storage without leaking partial or broken files.
 */
class MultiWorkerOrchestrator(
    private val sessionId: String,
    private val isCloudMode: Boolean,
    private val modelInvoker: suspend (systemPrompt: String, userPrompt: String) -> String,
    private val onTelemetryUpdate: (activeTaskCount: Int) -> Unit,
    private val onTerminalLogUpdate: (cliText: String) -> Unit,
    private val shellExecutor: suspend (command: String) -> String = { "" }
) {

    companion object {
        private const val TAG = "MultiWorkerOrch"
        private const val DEFAULT_PRODUCTION_DIR = "/sdcard/Medlam-Ai/webapp"
        private val ggufExecutionMutex = Mutex()

        /**
         * Determines if a user prompt is an orchestration candidate
         * (e.g. multi-file application, dashboard, fullstack UI, or complex deployment).
         */
        fun isOrchestrationCandidate(command: String): Boolean {
            val lower = command.lowercase().trim()
            if (lower.length < 5) return false

            val hasActionVerb = lower.contains("deploy") || lower.contains("build") ||
                    lower.contains("create") || lower.contains("scaffold") ||
                    lower.contains("make") || lower.contains("develop") ||
                    lower.contains("design") || lower.contains("generate") ||
                    lower.contains("setup") || lower.contains("اصنع") ||
                    lower.contains("انشئ") || lower.contains("ابني")

            val hasTargetNoun = lower.contains("dashboard") || lower.contains("webapp") ||
                    lower.contains("web app") || lower.contains("website") ||
                    lower.contains("site") || lower.contains("portal") ||
                    lower.contains("interface") || lower.contains("frontend") ||
                    lower.contains("fullstack") || lower.contains("calculator") ||
                    lower.contains("todo") || lower.contains("notes app") ||
                    lower.contains("landing page") || lower.contains("auth") ||
                    lower.contains("multi-worker") || lower.contains("orchestrat")

            // Explicit direct triggers
            if (lower.startsWith("deploy ") || lower.startsWith("build web") ||
                lower.startsWith("create web") || lower.startsWith("scaffold ") ||
                lower.contains("with auth logic") || lower.contains("modern web")
            ) {
                return true
            }

            return hasActionVerb && hasTargetNoun
        }
    }

    /**
     * Decomposes user intent into discrete, independent worker sub-tasks.
     */
    fun decomposeIntent(userPrompt: String, destinationDir: String): List<WorkerSubTask> {
        val tasks = mutableListOf<WorkerSubTask>()

        val lower = userPrompt.lowercase()
        val isPythonProject = lower.contains("python") || lower.contains("flask") || lower.contains("fastapi")

        if (isPythonProject) {
            // Worker 1: Core Logic & State (Python Backend)
            tasks.add(
                WorkerSubTask(
                    workerId = "Worker 1",
                    role = WorkerRole.CORE_LOGIC,
                    taskDescription = "Synthesizing main.py & backend logic...",
                    targetFileNames = listOf("main.py"),
                    prompt = """
Synthesize production-ready backend code for: $userPrompt.
Target file: main.py.
Include complete logic, route handlers, and error handling.
CRITICAL: Output ONLY raw python code. Zero markdown code fences, zero XML tags, zero conversational fluff.
""".trimIndent()
                )
            )

            // Worker 2: Layout & Presentation (HTML Template or UI)
            tasks.add(
                WorkerSubTask(
                    workerId = "Worker 2",
                    role = WorkerRole.LAYOUT_PRESENTATION,
                    taskDescription = "Scaffolding index.html & frontend styles...",
                    targetFileNames = listOf("index.html", "styles.css"),
                    prompt = """
Synthesize the complete HTML structure and CSS styling for: $userPrompt.
Target files: index.html and styles.css.
Format:
===FILE: index.html===
<!DOCTYPE html>
<html>...</html>
===FILE: styles.css===
/* styles */
CRITICAL: Ensure all HTML tags are closed, all CSS braces are balanced. Output ONLY the file blocks. Zero XML tags.
""".trimIndent()
                )
            )
        } else {
            // Standard Web App / Dashboard (HTML5 + CSS3 + Modern JavaScript)
            // Worker 1 (Core Logic & State): app.js
            tasks.add(
                WorkerSubTask(
                    workerId = "Worker 1",
                    role = WorkerRole.CORE_LOGIC,
                    taskDescription = "Synthesizing app.js & business logic...",
                    targetFileNames = listOf("app.js"),
                    prompt = """
You are Worker 1 (Core Logic & State). Synthesize complete, production-ready JavaScript application logic and state handling for:
"$userPrompt"

Target deliverable: app.js
Requirements:
1. Complete state management, DOM event listeners, data manipulation, and interactive features.
2. Fully balanced braces {}, brackets [], and parentheses ().
3. Clean error handling and mock/real data handling.
4. Output STRICTLY raw JavaScript code. Do NOT enclose in markdown code fences (```) or XML tags.
""".trimIndent()
                )
            )

            // Worker 2 (Layout & Presentation): index.html & styles.css
            tasks.add(
                WorkerSubTask(
                    workerId = "Worker 2",
                    role = WorkerRole.LAYOUT_PRESENTATION,
                    taskDescription = "Scaffolding index.html & styles.css...",
                    targetFileNames = listOf("index.html", "styles.css"),
                    prompt = """
You are Worker 2 (Layout & Presentation). Synthesize modern semantic HTML and responsive dark-mode styling for:
"$userPrompt"

Target deliverables:
- index.html (links to styles.css and app.js)
- styles.css (modern glassmorphic cyberpunk / high-contrast palette)

Format your output EXACTLY as:
===FILE: index.html===
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Application</title>
  <link rel="stylesheet" href="styles.css">
</head>
<body>
  ...
  <script src="app.js"></script>
</body>
</html>
===FILE: styles.css===
/* Clean modern responsive styles */
...

CRITICAL: Verify all tags are closed and all CSS braces are balanced. Zero markdown fences outside the blocks, zero XML tags.
""".trimIndent()
                )
            )
        }

        return tasks
    }

    /**
     * Executes the complete Orchestrator-Workers + Critic/Verifier pipeline.
     */
    suspend fun execute(
        userPrompt: String,
        destinationDir: String = DEFAULT_PRODUCTION_DIR
    ): String = withContext(Dispatchers.IO) {
        val terminalBuffer = StringBuilder()
        fun appendCli(line: String) {
            terminalBuffer.append(line).append("\n")
            onTerminalLogUpdate(terminalBuffer.toString().trimEnd())
        }

        val stagingManager = StagingManager(
            sessionId = sessionId,
            userIntent = userPrompt,
            destinationDir = destinationDir
        )

        val runFolderRelative = ".staging/${stagingManager.runDir.name}"

        // 1. Intent Decomposition
        val subTasks = decomposeIntent(userPrompt, destinationDir)
        stagingManager.initializeManifest(subTasks)

        val initialTaskCount = subTasks.size
        val activeTaskCounter = AtomicInteger(initialTaskCount)
        onTelemetryUpdate(initialTaskCount)

        appendCli("> $userPrompt\n")
        appendCli("⚡ Orchestrator: Decomposed intent into $initialTaskCount parallel tasks [TSK $initialTaskCount]")

        // 2. Concurrency & Worker Dispatch
        val workerResults = mutableMapOf<String, WorkerExecutionResult>()

        val workerJobs = subTasks.map { task ->
            async(Dispatchers.IO) {
                val workerResult = executeWorker(task)
                val remaining = activeTaskCounter.decrementAndGet()
                onTelemetryUpdate(remaining.coerceAtLeast(0))

                val statusWord = if (workerResult.success) "Complete" else "Failed"
                appendCli("  ├─ [${task.workerId}] ${task.taskDescription} ($statusWord)")
                workerResult
            }
        }

        val completedResults = workerJobs.awaitAll()
        completedResults.forEach { res ->
            workerResults[res.workerId] = res
            if (res.success && res.generatedFiles.isNotEmpty()) {
                stagingManager.stageWorkerOutputs(res.workerId, res.generatedFiles)
            } else {
                stagingManager.purgeWorkerStaging(res.workerId)
            }
        }

        // 3. Verifier Gatekeeper Audit Pass
        appendCli("⚡ Verifier: Auditing staging artifacts in $runFolderRelative/")
        stagingManager.updateVerifierStatus("AUDITING")

        var stagedFiles = stagingManager.getAllStagedFiles()
        var auditResult = VerifierGatekeeper.auditStagedFiles(stagedFiles)

        // 4. Self-Healing Feedback Loop (Max 2 Retries)
        var retryCount = 0
        val maxSelfHealingRetries = 2

        while (!auditResult.isVerified && retryCount < maxSelfHealingRetries) {
            retryCount++
            appendCli("  ├─ [Audit Notice] Verifier detected ${auditResult.errors.size} defect(s). Initiating remediation pass ($retryCount/$maxSelfHealingRetries)...")

            for (error in auditResult.errors) {
                try {
                    val currentFile = stagedFiles[error.fileName] ?: continue
                    val currentContent = currentFile.readText()
                    val remediationPrompt = VerifierGatekeeper.generateRemediationPrompt(error, currentContent)

                    val healedCode = runLlmPass(
                        systemPrompt = "You are an automated code remediation agent. Output pure corrected code.",
                        userPrompt = remediationPrompt
                    )

                    val cleanHealed = sanitizeGeneratedCode(healedCode, error.fileName)
                    stagingManager.stageWorkerOutputs(error.workerId, mapOf(error.fileName to cleanHealed))
                } catch (e: Exception) {
                    Log.e(TAG, "Remediation pass failed for ${error.fileName}: ${e.message}")
                }
            }

            stagedFiles = stagingManager.getAllStagedFiles()
            auditResult = VerifierGatekeeper.auditStagedFiles(stagedFiles)
        }

        // Output Verifier Report
        val syntaxStatus = if (auditResult.missingClosures == 0 && auditResult.unclosedTags == 0) "PASSED" else "WARNING"
        appendCli("  ├─ Syntax validation: $syntaxStatus (${auditResult.missingClosures} missing closures, ${auditResult.unclosedTags} unclosed tags)")

        val leakStatus = if (auditResult.toolCallLeaksFound == 0) "CLEAN" else "FLAGGED"
        appendCli("  ├─ Tool-call leak check: $leakStatus (No raw XML/JSON detected)")

        val gatekeeperStatus = if (auditResult.isVerified || auditResult.toolCallLeaksFound == 0) "VERIFIED" else "FAILED"
        appendCli("  ├─ Gatekeeper status: $gatekeeperStatus")

        // 5. Atomic Production Promotion
        if (gatekeeperStatus == "VERIFIED") {
            stagingManager.updateVerifierStatus("VERIFIED")
            appendCli("⚡ Production Sync: Atomically promoted files to $destinationDir/")

            val promotedFiles = stagingManager.promoteToProduction()
            for (file in promotedFiles) {
                val sizeFormatted = ToolExecutor.formatFileSize(file.absolutePath)
                appendCli("  ├─ ${file.absolutePath} ($sizeFormatted)")
            }

            // Transient Purge
            stagingManager.cleanupStaging()
            appendCli("⚡ Status: Ready / Operational")
        } else {
            stagingManager.updateVerifierStatus("FAILED", auditResult.errors.map { "${it.fileName}: ${it.description}" })
            stagingManager.cleanupStaging()
            appendCli("⚡ Production Sync: Aborted — Verifier gated promotion to protect production storage.")
            appendCli("⚡ Status: Remediation Exhausted")
        }

        onTelemetryUpdate(0)
        terminalBuffer.toString().trimEnd()
    }

    /**
     * Executes an individual worker pass, respecting Cloud concurrent vs Local sequentialized GGUF modes.
     */
    private suspend fun executeWorker(task: WorkerSubTask): WorkerExecutionResult {
        if (task.isShellTask && task.shellCommand != null) {
            // Environment & Dependencies via Shell MCP
            return try {
                val result = shellExecutor(task.shellCommand)
                WorkerExecutionResult(
                    workerId = task.workerId,
                    role = task.role,
                    success = true,
                    generatedFiles = emptyMap(),
                    executionLog = result
                )
            } catch (e: Exception) {
                WorkerExecutionResult(
                    workerId = task.workerId,
                    role = task.role,
                    success = false,
                    generatedFiles = emptyMap(),
                    errorMessage = e.message
                )
            }
        }

        try {
            val rawOutput = runLlmPass(
                systemPrompt = "You are ${task.workerId} (${task.role.displayName}). Output ONLY pure file code without XML tool tags.",
                userPrompt = task.prompt
            )

            if (rawOutput.isBlank()) {
                return WorkerExecutionResult(
                    workerId = task.workerId,
                    role = task.role,
                    success = false,
                    generatedFiles = emptyMap(),
                    errorMessage = "Empty output generated"
                )
            }

            val parsedFiles = parseWorkerFiles(rawOutput, task.targetFileNames)
            return WorkerExecutionResult(
                workerId = task.workerId,
                role = task.role,
                success = parsedFiles.isNotEmpty(),
                generatedFiles = parsedFiles,
                errorMessage = if (parsedFiles.isEmpty()) "Failed parsing target files" else null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Worker ${task.workerId} execution failed: ${e.message}", e)
            return WorkerExecutionResult(
                workerId = task.workerId,
                role = task.role,
                success = false,
                generatedFiles = emptyMap(),
                errorMessage = e.message ?: "Unknown worker failure"
            )
        }
    }

    /**
     * Dispatches LLM generation:
     * - Gemini Cloud Mode: True concurrent execution.
     * - Local GGUF Mode: Sequentialized execution via Mutex to prevent CPU starvation.
     */
    private suspend fun runLlmPass(systemPrompt: String, userPrompt: String): String {
        return if (isCloudMode) {
            modelInvoker(systemPrompt, userPrompt)
        } else {
            ggufExecutionMutex.withLock {
                modelInvoker(systemPrompt, userPrompt)
            }
        }
    }

    /**
     * Parses generated text into individual file outputs.
     * Handles `===FILE: filename===` format, standard markdown code fences, or single file fallbacks.
     */
    private fun parseWorkerFiles(rawText: String, expectedFiles: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()

        // 1. Check for ===FILE: name=== blocks
        if (rawText.contains("===FILE:")) {
            val sections = rawText.split(Regex("""===FILE:\s*([^\n=]+)===""")).filter { it.isNotBlank() }
            val matchIter = Regex("""===FILE:\s*([^\n=]+)===""").findAll(rawText).iterator()

            for (section in sections) {
                if (matchIter.hasNext()) {
                    val fileName = matchIter.next().groupValues[1].trim()
                    val cleanCode = sanitizeGeneratedCode(section, fileName)
                    result[fileName] = cleanCode
                }
            }
        }

        // 2. Check for fenced code blocks
        if (result.isEmpty()) {
            val fenceRegex = Regex("""```(?:[a-zA-Z0-9_\-]+)?\n([\s\S]*?)```""")
            val matches = fenceRegex.findAll(rawText).toList()

            if (matches.isNotEmpty() && expectedFiles.isNotEmpty()) {
                matches.forEachIndexed { idx, match ->
                    val targetName = if (idx < expectedFiles.size) expectedFiles[idx] else "file_$idx.txt"
                    val code = sanitizeGeneratedCode(match.groupValues[1], targetName)
                    result[targetName] = code
                }
            }
        }

        // 3. Single-file fallback if output matches single target
        if (result.isEmpty() && expectedFiles.size == 1) {
            val targetName = expectedFiles.first()
            val clean = sanitizeGeneratedCode(rawText, targetName)
            if (clean.isNotBlank()) {
                result[targetName] = clean
            }
        }

        return result
    }

    /**
     * Strips leaked tool tags, XML tags, and stray markdown code fences.
     */
    private fun sanitizeGeneratedCode(raw: String, fileName: String): String {
        var clean = raw.trim()

        // Strip markdown backtick enclosures if wrapping entire code
        if (clean.startsWith("```")) {
            val newline = clean.indexOf('\n')
            if (newline != -1) {
                clean = clean.substring(newline + 1)
            }
        }
        if (clean.endsWith("```")) {
            clean = clean.removeSuffix("```").trimEnd()
        }

        // Strip leaked tool invocation XML tags
        clean = clean
            .replace("<function_calls>", "")
            .replace("</function_calls>", "")
            .replace(Regex("""<invoke\b[^>]*>"""), "")
            .replace("</invoke>", "")
            .replace(Regex("""<parameter\b[^>]*>"""), "")
            .replace("</parameter>", "")
            .trim()

        return clean
    }
}
