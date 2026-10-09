package com.synapse.localai.orchestration

import android.util.Log
import com.synapse.localai.HtmlSanitizer
import com.synapse.localai.PersonaManager
import com.synapse.localai.FileChangeJournal
import com.synapse.localai.ToolExecutor
import com.synapse.localai.tools.ContentReaderTool
import com.synapse.localai.tools.PythonExecutionEngine
import com.synapse.localai.tools.WebSearchClient as WebSearchTool
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

// Data class to encapsulate parsed tool invocation requests from the AI
data class ToolInvocation(val name: String, val params: Map<String, String>)

/**
 * Multi-Worker MCP Orchestration Pipeline & Autonomous Agent Protocol
 */
class MultiWorkerOrchestrator(
    private val sessionId: String,
    private val isCloudMode: Boolean,
    private val modelInvoker: suspend (systemPrompt: String, userPrompt: String, onChunk: ((String) -> Unit)?) -> String,
    private val onTelemetryUpdate: (activeTaskCount: Int) -> Unit,
    private val onTerminalLogUpdate: (cliText: String) -> Unit,
    private val onStatusUpdate: ((status: String) -> Unit)? = null,
    private val shellExecutor: suspend (command: String) -> String = { "" },
    private val fileChangeJournal: FileChangeJournal? = null
) {

    companion object {
        private const val TAG = "MultiWorkerOrch"
        private const val DEFAULT_PRODUCTION_DIR = "/sdcard/atlas-ai/webapp"
        private val ggufExecutionMutex = Mutex()

        /**
         * Determines if a user prompt is an orchestration candidate.
         * Disabled by default to prevent prompt pollution.
         */
        fun isOrchestrationCandidate(command: String): Boolean {
            return false 
        }
    }

    // =========================================================================
    // 1. AUTONOMOUS UI AGENT PATH (ReAct Loop)
    // =========================================================================
    
    suspend fun executeAutonomousAgent(userPrompt: String): String = withContext(Dispatchers.IO) {
        val terminalBuffer = StringBuilder()
        var lastTerminalUpdateTime = 0L

        fun appendCli(line: String) {
            terminalBuffer.append(line).append("\n")
            lastTerminalUpdateTime = System.currentTimeMillis()
            onTerminalLogUpdate(terminalBuffer.toString().trimEnd())
        }

        appendCli("> Initiating Autonomous Protocol: $userPrompt")
        onStatusUpdate?.invoke("Agent Active: Initializing...")

        // Dynamic fetch of unified system prompt from PersonaManager
        val systemPrompt = PersonaManager.getActivePersonaContent()

        val maxSteps = 25
        var step = 0
        var isCompleted = false
        var consecutiveNoToolCount = 0
        var currentContext = "User Request: $userPrompt\n"
        var finalResult = "Task completed."

        onTelemetryUpdate(1)

        while (step < maxSteps && !isCompleted) {
            step++
            appendCli("  ├─ [Step $step/$maxSteps] Thinking...")

            try {
                val llmResponse = runLlmPass(systemPrompt = systemPrompt, userPrompt = currentContext)
                val toolInvocation = extractToolInvocation(llmResponse)

                if (toolInvocation != null) {
                    consecutiveNoToolCount = 0
                    val toolName = toolInvocation.name.trim().lowercase(Locale.ROOT)
                    appendCli("  ├─ Invoking: ${toolInvocation.name}")
                    onStatusUpdate?.invoke("Executing ${toolInvocation.name}...")

                    val toolResult: String = try {
                        when (toolName) {
                            "run_shell", "execute_shell", "bash" -> {
                                val cmd = toolInvocation.params["command"] ?: toolInvocation.params.values.firstOrNull() ?: ""
                                if (cmd.isNotBlank()) {
                                    // Intercept Python execution inside shell calls and route to embedded engine
                                    if (cmd.contains(".py") || cmd.contains("python")) {
                                        val pyFileMatch = Regex("""/sdcard/[^\s]+\.py|/[^\s]+\.py|[^\s]+\.py""").find(cmd)
                                        if (pyFileMatch != null) {
                                            val rawPath = pyFileMatch.value.trimEnd('"', '\'', ';', '&')
                                            val filePath = ToolExecutor.resolvePath(rawPath)
                                            val scriptFile = java.io.File(filePath)
                                            
                                            if (scriptFile.exists()) {
                                                val scriptContent = scriptFile.readText()
                                                val result = PythonExecutionEngine.execute(scriptContent)
                                                "[Intercepted & Executed via Python Engine]\n${result.output}"
                                            } else {
                                                val result = PythonExecutionEngine.execute(cmd)
                                                "[Intercepted & Executed via Python Engine]\n${result.output}"
                                            }
                                        } else {
                                            val result = PythonExecutionEngine.execute(cmd)
                                            "[Intercepted & Executed via Python Engine]\n${result.output}"
                                        }
                                    } else {
                                        shellExecutor(cmd)
                                    }
                                } else "[Error: empty command]"
                            }
                            "run_python" -> {
                                val script = toolInvocation.params["script"] ?: toolInvocation.params.values.firstOrNull() ?: ""
                                if (script.isNotBlank()) PythonExecutionEngine.execute(script).output
                                else "[Error: empty script]"
                            }
                            "web_search" -> {
                                val q = toolInvocation.params["query"] ?: toolInvocation.params.values.firstOrNull() ?: ""
                                if (q.isNotBlank()) WebSearchTool.search(q) else "[Error: empty query]"
                            }
                            "write_file" -> {
                                val path = toolInvocation.params["path"] ?: ""
                                val content = toolInvocation.params["content"] ?: ""
                                if (path.isNotBlank()) {
                                    val journal = fileChangeJournal
                                        ?: throw IllegalStateException("Reversible file storage is unavailable.")
                                    val result = journal.write(sessionId, java.io.File(ToolExecutor.resolvePath(path)), content)
                                    "Wrote ${content.length} bytes to ${result.file.absolutePath}; rollback transaction: ${result.transactionId}"
                                } else "[Error: empty path]"
                            }
                            "rollback_file_changes", "undo_file_changes" -> {
                                val transactionId = toolInvocation.params["transaction_id"]
                                    ?: toolInvocation.params["task_id"]
                                    ?: sessionId
                                fileChangeJournal?.rollback(transactionId)?.message
                                    ?: "[Error: reversible file storage is unavailable]"
                            }
                            "read_file_range" -> {
                                val path = toolInvocation.params["path"] ?: ""
                                val start = toolInvocation.params["start_line"]?.toIntOrNull() ?: 1
                                val count = toolInvocation.params["line_count"]?.toIntOrNull() ?: 60
                                if (path.isNotBlank()) ContentReaderTool.readFileRange(path, start, count)
                                else "[Error: empty path]"
                            }
                            "complete", "task_completion_protocol" -> {
                                val summary = toolInvocation.params["summary"] ?: toolInvocation.params.values.firstOrNull() ?: "Task finished."
                                isCompleted = true
                                finalResult = summary
                                appendCli("  └─ TASK COMPLETE: $summary")
                                break
                            }
                            else -> "[Unknown tool: $toolName]"
                        }
                    } catch (e: Exception) {
                        "Tool Error: ${e.message}"
                    }

                    appendCli("  ├─ Result: ${toolResult.take(120)}")
                    currentContext += "\nStep $step Action: ${toolInvocation.name}\nResult:$toolResult\nContinue the task."

                } else {
                    consecutiveNoToolCount++
                    val proseSnippet = llmResponse.take(200)
                    currentContext += "\nStep $step Note:$proseSnippet\nYou MUST now call a tool or call complete() if the task is done. DO NOT stop."
                    appendCli("  ├─ [No tool call, retry push #$consecutiveNoToolCount]:${proseSnippet.take(60)}")

                    if (consecutiveNoToolCount >= 3) {
                        finalResult = llmResponse.trim().ifBlank { "Task processed." }
                        isCompleted = true
                        appendCli("  └─ Forced completion after $consecutiveNoToolCount prose turns.")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendCli("  ├─ Exception on step $step:${e.message} — retrying...")
                currentContext += "\nStep $step Error:${e.message}\nAnalyze and retry."
            }
        }

        if (!isCompleted) {
            appendCli("  └─ Max steps ($maxSteps) reached — returning best result.")
            finalResult = "Task execution reached max steps ($maxSteps). Last context:\n${currentContext.takeLast(300)}"
        }

        onTelemetryUpdate(0)
        return@withContext finalResult
    }

    private fun extractToolInvocation(response: String): ToolInvocation? {
        val invokeRegex = Regex("""<invoke\s+name="([^"]+)">([\s\S]*?)</invoke>""")
        val match = invokeRegex.find(response) ?: return null
        
        val name = match.groupValues[1]
        val paramsBlock = match.groupValues[2]
        
        val paramRegex = Regex("""<parameter\s+name="([^"]+)">([\s\S]*?)</parameter>""")
        val params = mutableMapOf<String, String>()
        
        paramRegex.findAll(paramsBlock).forEach { paramMatch ->
            params[paramMatch.groupValues[1]] = paramMatch.groupValues[2].trim()
        }
        
        return ToolInvocation(name, params)
    }

    // =========================================================================
    // 2. CODE SYNTHESIS PATH (Original Architecture)
    // =========================================================================

    fun decomposeIntent(userPrompt: String, destinationDir: String): List<WorkerSubTask> {
        val tasks = mutableListOf<WorkerSubTask>()

        val lower = userPrompt.lowercase()
        val isPythonProject = lower.contains("python") || lower.contains("flask") || lower.contains("fastapi")

        if (isPythonProject) {
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
CRITICAL RULES:
1. Clean Document Tree Requirement: Every HTML output MUST start at Byte 0 with <!DOCTYPE html>. Zero leading brackets, fences, or worker tags.
2. Zero-ASCII Navigation Rule: Strictly forbid simulating UI buttons, arrows, chevrons, or indicators with raw ASCII or keyboard characters.
3. Ensure all HTML tags are closed, all CSS braces are balanced. Output ONLY the file blocks. Zero XML tags.
""".trimIndent()
                )
            )
        } else {
            tasks.add(
                WorkerSubTask(
                    workerId = "CoreLogic",
                    role = WorkerRole.CORE_LOGIC,
                    taskDescription = "Synthesizing app.js & business logic...",
                    targetFileNames = listOf("app.js"),
                    prompt = """
Synthesize complete, production-ready JavaScript application logic and state handling for:
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

            tasks.add(
                WorkerSubTask(
                    workerId = "LayoutPresentation",
                    role = WorkerRole.LAYOUT_PRESENTATION,
                    taskDescription = "Scaffolding index.html & styles.css...",
                    targetFileNames = listOf("index.html", "styles.css"),
                    prompt = """
Synthesize modern semantic HTML and responsive dark-mode styling for:
"$userPrompt"

Target deliverables:
- index.html (links to styles.css and app.js)
- styles.css (modern glassmorphic cyberpunk / high-contrast palette)

Format your output EXACTLY as:
===FILE: index.html===
<!DOCTYPE html>
<html lang="en">...
===FILE: styles.css===
/* Clean modern responsive styles */
...
CRITICAL RULES:
1. Clean Document Tree Requirement: Every HTML output MUST start at Byte 0 with <!DOCTYPE html>. Zero leading brackets, fences, or worker tags.
2. Zero-ASCII Navigation Rule: Strictly forbid simulating UI buttons with raw ASCII.
3. Verify all tags are closed and all CSS braces are balanced. Zero markdown fences outside the blocks, zero XML tags.
""".trimIndent()
                )
            )
        }
        return tasks
    }

    suspend fun execute(
        userPrompt: String,
        destinationDir: String = DEFAULT_PRODUCTION_DIR
    ): String = withContext(Dispatchers.IO) {
        val terminalBuffer = StringBuilder()
        var lastTerminalUpdateTime = 0L

        fun updateTerminal(throttleMs: Long = 50L) {
            val now = System.currentTimeMillis()
            if (now - lastTerminalUpdateTime >= throttleMs) {
                lastTerminalUpdateTime = now
                onTerminalLogUpdate(terminalBuffer.toString().trimEnd())
            }
        }

        fun appendCli(line: String) {
            terminalBuffer.append(line).append("\n")
            lastTerminalUpdateTime = System.currentTimeMillis()
            onTerminalLogUpdate(terminalBuffer.toString().trimEnd())
        }

        val stagingManager = StagingManager(
            sessionId = sessionId,
            userIntent = userPrompt,
            destinationDir = destinationDir
        )

        val runFolderRelative = ".staging/${stagingManager.runDir.name}"
        val subTasks = decomposeIntent(userPrompt, destinationDir)
        stagingManager.initializeManifest(subTasks)

        val initialTaskCount = subTasks.size
        val activeTaskCounter = AtomicInteger(initialTaskCount)
        onTelemetryUpdate(initialTaskCount)

        appendCli("> $userPrompt\n")
        appendCli("Orchestrator: Decomposed intent into $initialTaskCount parallel tasks [TSK $initialTaskCount]\n")

        val workerResults = mutableMapOf<String, WorkerExecutionResult>()

        for ((workerIndex, task) in subTasks.withIndex()) {
            val workerNum = workerIndex + 1
            val totalWorkers = subTasks.size
            val defaultTarget = task.targetFileNames.firstOrNull() ?: "output.txt"
            val fullTarget = "$destinationDir/$defaultTarget"

            onStatusUpdate?.invoke("Synthesizing $fullTarget [Worker $workerNum/$totalWorkers]...")
            appendCli("  ├─ [${task.workerId}] ${task.taskDescription} (Running...)")

            val streamStartOffset = terminalBuffer.length
            val liveWorkerBuffer = StringBuilder()

            val workerResult = executeWorker(
                task = task,
                onChunk = { chunk ->
                    liveWorkerBuffer.append(chunk)
                    val formatted = formatWorkerOutputForDisplay(
                        rawText = liveWorkerBuffer.toString(),
                        destinationDir = destinationDir,
                        task = task,
                        workerNum = workerNum,
                        totalWorkers = totalWorkers,
                        isFinal = false
                    )
                    terminalBuffer.setLength(streamStartOffset)
                    terminalBuffer.append(formatted)
                    updateTerminal(50L)
                }
            )

            terminalBuffer.setLength(streamStartOffset)
            val finalOutput = if (liveWorkerBuffer.isNotBlank()) liveWorkerBuffer.toString() else (workerResult.executionLog)
            val finalFormatted = formatWorkerOutputForDisplay(
                rawText = finalOutput,
                destinationDir = destinationDir,
                task = task,
                workerNum = workerNum,
                totalWorkers = totalWorkers,
                isFinal = true
            )
            terminalBuffer.append(finalFormatted)
            terminalBuffer.append("\n")

            val statusWord = if (workerResult.success) "Complete" else "Failed"
            appendCli("  ├─ [${task.workerId}] ${task.taskDescription} ($statusWord)")

            val remaining = activeTaskCounter.decrementAndGet()
            onTelemetryUpdate(remaining.coerceAtLeast(0))

            workerResults[task.workerId] = workerResult
            if (workerResult.success && workerResult.generatedFiles.isNotEmpty()) {
                stagingManager.stageWorkerOutputs(workerResult.workerId, workerResult.generatedFiles)
            } else {
                stagingManager.purgeWorkerStaging(workerResult.workerId)
            }
        }

        appendCli("Verifier: Auditing staging artifacts in $runFolderRelative/")
        stagingManager.updateVerifierStatus("AUDITING")

        var stagedFiles = stagingManager.getAllStagedFiles()
        var auditResult = VerifierGatekeeper.auditStagedFiles(stagedFiles)

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

        val syntaxStatus = if (auditResult.missingClosures == 0 && auditResult.unclosedTags == 0) "PASSED" else "WARNING"
        appendCli("  ├─ Syntax validation: $syntaxStatus (${auditResult.missingClosures} missing closures, ${auditResult.unclosedTags} unclosed tags)")

        val leakStatus = if (auditResult.toolCallLeaksFound == 0) "CLEAN" else "FLAGGED"
        appendCli("  ├─ Tool-call leak check: $leakStatus (No raw XML/JSON detected)")

        val gatekeeperStatus = if (auditResult.isVerified || auditResult.toolCallLeaksFound == 0) "VERIFIED" else "FAILED"
        appendCli("  ├─ Gatekeeper status: $gatekeeperStatus")

        if (gatekeeperStatus == "VERIFIED") {
            stagingManager.updateVerifierStatus("VERIFIED")
            appendCli("Production Sync: Atomically promoted files to $destinationDir/")

            val promotedFiles = stagingManager.promoteToProduction()
            for (file in promotedFiles) {
                val sizeFormatted = ToolExecutor.formatFileSize(file.absolutePath)
                appendCli("  ├─ ${file.absolutePath} ($sizeFormatted)")
            }

            stagingManager.cleanupStaging()
            appendCli("Status: Ready / Operational")
        } else {
            stagingManager.updateVerifierStatus("FAILED", auditResult.errors.map { "${it.fileName}: ${it.description}" })
            stagingManager.cleanupStaging()
            appendCli("Production Sync: Aborted — Verifier gated promotion to protect production storage.")
            appendCli("Status: Remediation Exhausted")
        }

        onTelemetryUpdate(0)
        terminalBuffer.toString().trimEnd()
    }

    private suspend fun executeWorker(
        task: WorkerSubTask,
        onChunk: ((String) -> Unit)? = null
    ): WorkerExecutionResult {
        if (task.isShellTask && task.shellCommand != null) {
            return try {
                val result = shellExecutor(task.shellCommand)
                onChunk?.invoke(result)
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
                systemPrompt = "You are ATLAS-AI, The Elite Autonomous Engineer. Output ONLY pure file code without XML tool tags.",
                userPrompt = task.prompt,
                onChunk = onChunk
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

    private suspend fun runLlmPass(
        systemPrompt: String,
        userPrompt: String,
        onChunk: ((String) -> Unit)? = null
    ): String {
        return if (isCloudMode) {
            modelInvoker(systemPrompt, userPrompt, onChunk)
        } else {
            ggufExecutionMutex.withLock {
                modelInvoker(systemPrompt, userPrompt, onChunk)
            }
        }
    }

    private fun formatWorkerOutputForDisplay(
        rawText: String,
        destinationDir: String,
        task: WorkerSubTask,
        workerNum: Int,
        totalWorkers: Int,
        isFinal: Boolean = false
    ): String {
        if (rawText.isBlank()) return ""

        if (rawText.contains("===FILE:")) {
            val sb = StringBuilder()
            val matches = Regex("""===FILE:\s*([^\n=]+)===""").findAll(rawText).toList()
            val parts = rawText.split(Regex("""===FILE:\s*([^\n=]+)==="""))

            if (matches.isNotEmpty()) {
                for (i in matches.indices) {
                    val rawName = matches[i].groupValues[1].trim()
                    val fileName = File(rawName).name
                    val fullPath = "$destinationDir/$fileName"
                    val codeContent = if (i + 1 < parts.size) parts[i + 1].trimStart('\r', '\n') else ""
                    val lang = when {
                        fileName.endsWith(".html", ignoreCase = true) || fileName.endsWith(".htm", ignoreCase = true) -> "html"
                        fileName.endsWith(".css", ignoreCase = true) -> "css"
                        fileName.endsWith(".js", ignoreCase = true) || fileName.endsWith(".jsx", ignoreCase = true) -> "javascript"
                        fileName.endsWith(".ts", ignoreCase = true) || fileName.endsWith(".tsx", ignoreCase = true) -> "typescript"
                        fileName.endsWith(".py", ignoreCase = true) -> "python"
                        fileName.endsWith(".sh", ignoreCase = true) || fileName.endsWith(".bash", ignoreCase = true) -> "bash"
                        fileName.endsWith(".json", ignoreCase = true) -> "json"
                        else -> "plaintext"
                    }

                    if (i > 0) sb.append("\n\n")
                    sb.append("┌─ [${task.workerId}/$totalWorkers: Synthesizing $fullPath] ─────────┐\n")
                    sb.append("```$lang filepath=\"$fullPath\"\n")
                    sb.append(codeContent)
                    val isLast = (i == matches.size - 1)
                    if (isFinal || !isLast) {
                        if (!codeContent.endsWith("\n")) sb.append("\n")
                        sb.append("```\n")
                        sb.append("└────────────────────────────────────────────────────────────┘")
                    }
                }
                return sb.toString()
            }
        }

        if (rawText.contains("```")) {
            val defaultFile = task.targetFileNames.firstOrNull() ?: "output.txt"
            val fullPath = "$destinationDir/$defaultFile"
            val sb = StringBuilder()
            sb.append("┌─ [${task.workerId}/$totalWorkers: Synthesizing$fullPath] ─────────┐\n")
            sb.append(rawText.trim())
            if (isFinal) {
                if (!rawText.trimEnd().endsWith("```")) sb.append("\n```")
                sb.append("\n└────────────────────────────────────────────────────────────┘")
            }
            return sb.toString()
        }

        val defaultFile = task.targetFileNames.firstOrNull() ?: "output.txt"
        val fullPath = "$destinationDir/$defaultFile"
        val lang = when {
            defaultFile.endsWith(".html", ignoreCase = true) || defaultFile.endsWith(".htm", ignoreCase = true) -> "html"
            defaultFile.endsWith(".css", ignoreCase = true) -> "css"
            defaultFile.endsWith(".js", ignoreCase = true) || defaultFile.endsWith(".jsx", ignoreCase = true) -> "javascript"
            defaultFile.endsWith(".ts", ignoreCase = true) || defaultFile.endsWith(".tsx", ignoreCase = true) -> "typescript"
            defaultFile.endsWith(".py", ignoreCase = true) -> "python"
            defaultFile.endsWith(".sh", ignoreCase = true) || defaultFile.endsWith(".bash", ignoreCase = true) -> "bash"
            defaultFile.endsWith(".json", ignoreCase = true) -> "json"
            else -> "plaintext"
        }

        val sb = StringBuilder()
        sb.append("┌─ [${task.workerId}/$totalWorkers: Synthesizing$fullPath] ─────────┐\n")
        sb.append("```$lang filepath=\"$fullPath\"\n")
        sb.append(rawText)
        if (isFinal) {
            if (!rawText.endsWith("\n")) sb.append("\n")
            sb.append("```\n")
            sb.append("└────────────────────────────────────────────────────────────┘")
        }
        return sb.toString()
    }

    private fun parseWorkerFiles(rawText: String, expectedFiles: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()

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

        if (result.isEmpty() && expectedFiles.size == 1) {
            val targetName = expectedFiles.first()
            val clean = sanitizeGeneratedCode(rawText, targetName)
            if (clean.isNotBlank()) {
                result[targetName] = clean
            }
        }

        return result
    }

    private fun sanitizeGeneratedCode(raw: String, fileName: String): String {
        var clean = raw.trim()

        if (clean.startsWith("```")) {
            val newline = clean.indexOf('\n')
            if (newline != -1) {
                clean = clean.substring(newline + 1)
            }
        }
        if (clean.endsWith("```")) {
            clean = clean.removeSuffix("```").trimEnd()
        }

        clean = clean
            .replace("<function_calls>", "")
            .replace("</function_calls>", "")
            .replace(Regex("""<invoke\b[^>]*>"""), "")
            .replace("</invoke>", "")
            .replace(Regex("""<parameter\b[^>]*>"""), "")
            .replace("</parameter>", "")
            .trim()

        if (fileName.endsWith(".html", ignoreCase = true) || fileName.equals("index.html", ignoreCase = true)) {
            clean = HtmlSanitizer.sanitizeHtmlStream(clean)
        }

        return clean
    }
}
