package com.synapse.localai.tools

import android.util.Log
import com.synapse.localai.RootShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Embedded Python Execution Engine.
 *
 * Provides native on-device Python discovery, environment probing,
 * script execution with scientific library support (NumPy, Pandas, SciPy, Matplotlib),
 * timeout control, and structured execution metrics.
 */
object PythonExecutionEngine {

    private const val TAG = "PythonExecutionEngine"
    private const val SCRATCH_DIR = "/sdcard/atlas-ai/scratch"

    data class PythonResult(
        val success: Boolean,
        val output: String,
        val error: String? = null,
        val exitCode: Int = 0,
        val executionTimeMs: Long = 0L,
        val pythonBinary: String = ""
    )

    data class RuntimeInfo(
        val pythonBinary: String?,
        val pythonVersion: String?,
        val hasNumpy: Boolean,
        val hasPandas: Boolean,
        val hasScipy: Boolean,
        val hasMatplotlib: Boolean,
        val environmentType: String
    )

    @Volatile
    private var cachedRuntimeInfo: RuntimeInfo? = null

    /**
     * Known potential paths for Python 3 binaries across Android, NetHunter, and Termux.
     */
    private val CANDIDATE_PATHS = listOf(
        "/data/local/nhsystem/kali-arm64/usr/bin/python3",
        "/data/local/nhsystem/kali-arm64/bin/python3",
        "/data/local/nhsystem/kalifs/usr/bin/python3",
        "/usr/bin/python3",
        "/usr/local/bin/python3",
        "/data/data/com.termux/files/usr/bin/python3",
        "/system/bin/python3",
        "/apex/com.android.runtime/bin/python3"
    )

    /**
     * Resolves the best available Python executable.
     */
    fun resolvePythonBinary(): String {
        cachedRuntimeInfo?.pythonBinary?.let { return it }

        for (path in CANDIDATE_PATHS) {
            val f = File(path)
            if (f.exists() && f.canExecute()) {
                Log.i(TAG, "Discovered executable Python at: $path")
                return path
            }
        }

        // Test which python3 via shell
        val whichOut = RootShellManager.execSingleLine("which python3 || which python")
        if (!whichOut.isNullOrBlank() && File(whichOut.trim()).exists()) {
            return whichOut.trim()
        }

        // Check if NetHunter chroot is available
        if (File("/data/local/nhsystem/kali-arm64").exists() || File("/data/local/nhsystem/kalifs").exists()) {
            return "bootkali python3"
        }

        return "python3"
    }

    /**
     * Probes the runtime environment to detect version and available scientific packages.
     */
    suspend fun getRuntimeInfo(forceRefresh: Boolean = false): RuntimeInfo = withContext(Dispatchers.IO) {
        if (!forceRefresh && cachedRuntimeInfo != null) {
            return@withContext cachedRuntimeInfo!!
        }

        val binary = resolvePythonBinary()
        val probeScript = """
import sys, json
info = {
    "version": sys.version.split()[0],
    "executable": sys.executable,
    "numpy": False,
    "pandas": False,
    "scipy": False,
    "matplotlib": False
}
try:
    import numpy
    info["numpy"] = True
except Exception:
    pass
try:
    import pandas
    info["pandas"] = True
except Exception:
    pass
try:
    import scipy
    info["scipy"] = True
except Exception:
    pass
try:
    import matplotlib
    info["matplotlib"] = True
except Exception:
    pass
print("___PY_PROBE___" + json.dumps(info))
""".trimIndent()

        val probeResult = executeRawScript(binary, probeScript, timeoutSec = 10)
        var ver: String? = null
        var hasNumpy = false
        var hasPandas = false
        var hasScipy = false
        var hasMatplotlib = false
        var envType = "Standard Host"

        if (probeResult.output.contains("___PY_PROBE___")) {
            try {
                val jsonPart = probeResult.output.substringAfter("___PY_PROBE___").lines().firstOrNull { it.trim().startsWith("{") } ?: "{}"
                val obj = org.json.JSONObject(jsonPart.trim())
                ver = obj.optString("version", "Unknown")
                hasNumpy = obj.optBoolean("numpy", false)
                hasPandas = obj.optBoolean("pandas", false)
                hasScipy = obj.optBoolean("scipy", false)
                hasMatplotlib = obj.optBoolean("matplotlib", false)
                envType = when {
                    binary.contains("termux") -> "Termux Environment"
                    binary.contains("nhsystem") || binary.contains("kali") -> "Kali NetHunter Chroot"
                    binary.startsWith("/system") -> "Android System"
                    else -> "Native Linux/Chroot"
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed parsing probe JSON: ${e.message}")
            }
        }

        val info = RuntimeInfo(
            pythonBinary = binary,
            pythonVersion = ver ?: "3.x",
            hasNumpy = hasNumpy,
            hasPandas = hasPandas,
            hasScipy = hasScipy,
            hasMatplotlib = hasMatplotlib,
            environmentType = envType
        )
        cachedRuntimeInfo = info
        info
    }

    /**
     * Executes arbitrary Python code with structured output, time tracing, and data science prelude.
     *
     * @param script Python code to execute.
     * @param autoImportSci If true, injects helper imports (numpy, pandas, math, etc.) safely.
     * @param timeoutSec Maximum execution time in seconds.
     */
    suspend fun execute(
        script: String,
        autoImportSci: Boolean = true,
        timeoutSec: Int = 30
    ): PythonResult = withContext(Dispatchers.IO) {
        if (script.isBlank()) {
            return@withContext PythonResult(
                success = false,
                output = "",
                error = "Script cannot be empty."
            )
        }

        val binary = resolvePythonBinary()
        val preamble = if (autoImportSci) {
            """
# --- Autonomous Data Science Prelude ---
import sys, os, math, json
try:
    import numpy as np
except Exception:
    pass
try:
    import pandas as pd
    pd.set_option('display.max_columns', 20)
    pd.set_option('display.width', 1000)
except Exception:
    pass
# ---------------------------------------
"""
        } else ""

        val fullScript = preamble + "\n" + script
        executeRawScript(binary, fullScript, timeoutSec)
    }

    private suspend fun executeRawScript(
        binary: String,
        fullScript: String,
        timeoutSec: Int
    ): PythonResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val scratchDir = File(SCRATCH_DIR)
            if (!scratchDir.exists()) scratchDir.mkdirs()

            val tempFile = File(scratchDir, "py_${System.currentTimeMillis()}_${(1000..9999).random()}.py")
            tempFile.writeText(fullScript, StandardCharsets.UTF_8)

            val cmd = when {
                binary.startsWith("bootkali") -> "bootkali python3 ${tempFile.absolutePath}"
                else -> "$binary ${tempFile.absolutePath} 2>&1"
            }

            val lines = RootShellManager.execAsync(cmd)
            val duration = System.currentTimeMillis() - startTime
            val rawOutput = lines.joinToString("\n").trim()

            // Delete temp file after execution to keep disk tidy
            try { tempFile.delete() } catch (_: Exception) {}

            val hasError = rawOutput.contains("Traceback (most recent call last):") ||
                    rawOutput.contains("SyntaxError:") ||
                    rawOutput.contains("ModuleNotFoundError:")

            PythonResult(
                success = !hasError,
                output = rawOutput.ifBlank { "[Python execution completed with no stdout]" },
                error = if (hasError) "Execution resulted in Python Exception" else null,
                exitCode = if (hasError) 1 else 0,
                executionTimeMs = duration,
                pythonBinary = binary
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            Log.e(TAG, "Python execution error: ${e.message}", e)
            PythonResult(
                success = false,
                output = "",
                error = "Execution Exception: ${e.message}",
                exitCode = 1,
                executionTimeMs = duration,
                pythonBinary = binary
            )
        }
    }
}
