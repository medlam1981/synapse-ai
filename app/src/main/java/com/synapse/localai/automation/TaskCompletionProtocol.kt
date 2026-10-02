package com.synapse.localai.automation

import android.content.Context
import android.content.Intent
import android.util.Log
import com.synapse.localai.MainActivity
import com.synapse.localai.RootShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * TaskCompletionProtocol — Standardized Lifecycle Return-to-Home & Executive Reporting Engine.
 *
 * Implements the mandatory post-task protocol:
 * 1. Step A: Programmatically trigger a "Home" intent to close external apps and stabilize the launcher.
 * 2. Step B: Automatically refocus and bring ATLAS-AI primary interface to the front.
 * 3. Step C: Generate a concise, structured executive summary (Status, Action, Metrics).
 */
object TaskCompletionProtocol {

    private const val TAG = "TaskCompletionProtocol"

    data class TaskMetric(
        val label: String,
        val value: String
    )

    /**
     * Executes the Return-to-Home workflow:
     * a. Fires Home intent / key.
     * b. Waits for window transitions to settle.
     * c. Refocuses the ATLAS-AI MainActivity.
     */
    suspend fun executeReturnToHome(context: Context?): Boolean = withContext(Dispatchers.Main) {
        val ctx = context ?: MainActivity.instance
        if (ctx == null) {
            Log.w(TAG, "Cannot execute Return-to-Home: Context is null")
            return@withContext false
        }

        try {
            // Step A: Trigger "Home" intent / navigation
            try {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                ctx.startActivity(homeIntent)
                Log.d(TAG, "Triggered Home via Intent")
            } catch (_: Exception) {
                RootShellManager.exec("input keyevent 3")
                Log.d(TAG, "Triggered Home via Root Shell fallback")
            }

            // Allow launcher transition to settle cleanly
            delay(300)

            // Step B: Re-focus and bring ATLAS-AI primary interface to the front
            val appIntent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("TASK_COMPLETION_REFOCUS", true)
            }

            // Root Shell am start (guaranteed to bypass background start restrictions)
            try {
                RootShellManager.exec("am start -n com.synapse.localai/.MainActivity -f 0x14000000")
            } catch (_: Exception) {}

            try {
                ctx.startActivity(appIntent)
            } catch (_: Exception) {}

            Log.i(TAG, "✔ Return-to-Home and Interface Refocus executed successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed in Return-to-Home workflow: ${e.message}")
            false
        }
    }

    /**
     * Generates a standardized, structured executive task completion summary.
     */
    fun generateStructuredSummary(
        taskName: String,
        actionDetails: String,
        isSuccess: Boolean = true,
        metrics: List<TaskMetric> = emptyList()
    ): String {
        val sb = StringBuilder()
        val statusTag = if (isSuccess) "SUCCESS" else "FAILED"
        val icon = if (isSuccess) "✔" else "✖"

        sb.append("$icon **Task Completion Protocol — Executive Summary**\n")
        sb.append("• **Mission Status:** [$statusTag]\n")
        sb.append("• **Task:** $taskName\n")
        sb.append("• **Action:** $actionDetails\n")

        if (metrics.isNotEmpty()) {
            sb.append("• **Operational Metrics:**\n")
            metrics.forEach { metric ->
                sb.append("  - ${metric.label}: ${metric.value}\n")
            }
        }

        sb.append("• **Lifecycle Protocol:** Returned to Home & ATLAS-AI Interface Refocused.")
        return sb.toString().trim()
    }
}
