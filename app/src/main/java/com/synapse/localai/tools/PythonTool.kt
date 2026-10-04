package com.synapse.localai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-Level Python Tool Interface for the Autonomous Agent.
 *
 * Delegates execution directly to [PythonExecutionEngine] to support
 * scientific computing (NumPy, Pandas, SciPy) and data analysis.
 */
object PythonTool {

    suspend fun execute(script: String): String = withContext(Dispatchers.IO) {
        val result = PythonExecutionEngine.execute(script, autoImportSci = true)
        val metaHeader = if (result.executionTimeMs > 0) {
            "⏱ Executed in ${result.executionTimeMs}ms via ${result.pythonBinary.substringAfterLast('/')}\n"
        } else ""

        if (result.success) {
            "$metaHeader${result.output}"
        } else {
            "✖ Python Execution Error:\n${result.output}"
        }
    }

    suspend fun getStatus(): String = withContext(Dispatchers.IO) {
        val info = PythonExecutionEngine.getRuntimeInfo()
        buildString {
            append("Python Runtime: ${info.pythonBinary ?: "Not found"} (${info.pythonVersion})\n")
            append("Environment: ${info.environmentType}\n")
            append("Core Libraries: ")
            val libs = mutableListOf<String>()
            if (info.hasNumpy) libs.add("NumPy")
            if (info.hasPandas) libs.add("Pandas")
            if (info.hasScipy) libs.add("SciPy")
            if (info.hasMatplotlib) libs.add("Matplotlib")
            if (libs.isEmpty()) append("None detected") else append(libs.joinToString(", "))
        }
    }
}
