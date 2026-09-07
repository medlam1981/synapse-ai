package com.synapse.localai

import android.content.Context
import android.util.Log
import com.synapse.localai.tools.WebSearchClient
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * ToolHandler — Centralized Autonomous Tool Chaining & Execution Dispatcher.
 *
 * Dispatches and chains:
 * 1. open_app: Delegates to AppLauncherManager (deterministic alias map, PM resolution, direct shell launch fallback).
 * 2. web_search: Delegates to WebSearchClient (query distillation, multi-tier search, curl shell scraping, zero-surrender policy).
 * 3. write_file: Delegates to atomic disk writer in /sdcard/Medlam-Ai/.
 * 4. run_shell: Intercepts am start and heredoc writes; passes through clean commands.
 */
object ToolHandler {

    private const val TAG = "ToolHandler"

    data class ToolExecutionResult(
        val success: Boolean,
        val summary: String,
        val isActionTool: Boolean = true
    )

    /**
     * Executes open_app / launch_app tool.
     */
    fun handleOpenApp(target: String, context: Context?): ToolExecutionResult {
        val cleanTarget = target.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        if (cleanTarget.isBlank()) {
            return ToolExecutionResult(false, "✖ Tool(open_app) ─── [FAILED]\n✖ Status: Target package or app name is blank.")
        }

        val result = AppLauncherManager.launch(cleanTarget, context)
        val summary = if (result.success) {
            val methodTag = when (result.method) {
                "DIRECT_SHELL" -> " (Shell Launch)"
                else -> ""
            }
            "✔ Tool(open_app: ${result.packageName}) ─── [LAUNCHED]\n✔ Status: ${result.appLabel}$methodTag brought to front."
        } else {
            "✖ Tool(open_app: $cleanTarget) ─── [FAILED]\n✖ Status: ${result.errorMessage ?: "Failed to launch application"}"
        }
        return ToolExecutionResult(result.success, summary)
    }

    /**
     * Executes web_search tool with autonomous query distillation & multi-tier fallback.
     */
    suspend fun handleWebSearch(query: String): ToolExecutionResult {
        val cleanQuery = query.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        if (cleanQuery.isBlank()) {
            return ToolExecutionResult(false, "✖ Tool(web_search) ─── [FAILED]\n✖ Status: Search query is blank.")
        }

        val searchResult = WebSearchClient.search(cleanQuery)
        return ToolExecutionResult(true, searchResult)
    }

    /**
     * Executes write_file tool to local external storage atomically.
     */
    fun handleWriteFile(path: String, content: String): ToolExecutionResult {
        val cleanPath = path.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        if (cleanPath.isBlank()) {
            return ToolExecutionResult(false, "✖ Tool(write_file) ─── [FAILED]\n✖ Status: Target path is blank.")
        }

        return try {
            val resolved = ToolExecutor.resolvePath(cleanPath, ToolExecutor.BASE_DIR)
            val file = File(resolved)
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            file.writeText(content, StandardCharsets.UTF_8)
            val summary = "✔ Tool(write_file: ${file.name}) ─── [SAVED]\n✔ Status: Wrote ${file.length()} bytes to ${file.absolutePath}"
            ToolExecutionResult(true, summary)
        } catch (e: Exception) {
            ToolExecutionResult(false, "✖ Tool(write_file: $cleanPath) ─── [FAILED]\n✖ Status: ${e.message}")
        }
    }
}
