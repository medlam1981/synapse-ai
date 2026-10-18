package com.synapse.localai.tools

import com.synapse.localai.AtlasLog as Log
import com.synapse.localai.RootShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Embedded Python Execution Engine.
 *
 * Provides native on-device Python discovery, environment probing,
 * script execution with scientific library support (NumPy, Pandas, SciPy, Matplotlib)
 * and structured execution metrics.
 */
object PythonExecutionEngine {

    private const val TAG = "PythonExecutionEngine"
    private const val SCRATCH_DIR = "/sdcard/atlas-ai/scratch"
    private const val KALI_ROOT = "/data/local/nhsystem/kali-arm64"

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
        "/data/data/com.termux/files/usr/bin/python3",
        "/system/bin/python3"
    )

    /**
     * Resolves the best available Python executable.
     */
    fun resolvePythonBinary(): String {
        cachedRuntimeInfo?.pythonBinary?.let { return it }

        if (File(KALI_ROOT).isDirectory) {
            return "chroot $KALI_ROOT /usr/bin/python3"
        }

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

        val probeResult = executeRawScript(binary, probeScript)
        var ver: String? = null
        var hasNumpy = false
        var hasPandas = false
        var hasScipy = false
        var hasMatplotlib = false
        var envType = "Standard Host"

        val probeSucceeded = probeResult.success && probeResult.output.contains("___PY_PROBE___")
        if (probeSucceeded) {
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
            pythonBinary = binary.takeIf { probeSucceeded && ver != null },
            pythonVersion = ver ?: "Unavailable",
            hasNumpy = hasNumpy,
            hasPandas = hasPandas,
            hasScipy = hasScipy,
            hasMatplotlib = hasMatplotlib,
            environmentType = if (probeSucceeded) envType else "Unavailable"
        )
        if (probeSucceeded) cachedRuntimeInfo = info
        info
    }

    /**
     * Executes arbitrary Python code with structured output, time tracing, and data science prelude.
     *
     * @param script Python code to execute.
     * @param autoImportSci If true, injects helper imports (numpy, pandas, math, etc.) safely.
     */
    suspend fun execute(
        script: String,
        autoImportSci: Boolean = true
    ): PythonResult = withContext(Dispatchers.IO) {
        if (script.isBlank()) {
            return@withContext PythonResult(
                success = false,
                output = "",
                error = "Script cannot be empty.",
                exitCode = 1
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
        executeRawScript(binary, fullScript)
    }

    private suspend fun executeRawScript(
        binary: String,
        fullScript: String
    ): PythonResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var tempFile: File? = null
        try {
            val scratchDir = File(SCRATCH_DIR)
            if (!scratchDir.exists()) scratchDir.mkdirs()

            val scriptFile = File(scratchDir, "py_${System.currentTimeMillis()}_${(1000..9999).random()}.py")
            tempFile = scriptFile
            scriptFile.writeText(fullScript, StandardCharsets.UTF_8)

            val cmd = "$binary ${scriptFile.absolutePath} 2>&1"

            val (shellExitCode, lines) = RootShellManager.execWithExitCodeAsync(cmd)
            val duration = System.currentTimeMillis() - startTime
            val rawOutput = lines.joinToString("\n").trim()

            val succeeded = shellExitCode == 0

            PythonResult(
                success = succeeded,
                output = rawOutput.ifBlank { "[Python execution completed with no stdout]" },
                error = if (succeeded) null else "Python process exited with code $shellExitCode",
                exitCode = shellExitCode,
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
        } finally {
            tempFile?.delete()
        }
    }
}
