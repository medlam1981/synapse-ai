package com.synapse.localai.tools

import com.synapse.localai.RootShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object PythonTool {
    private const val SCRATCH_DIR = "/sdcard/Medlam-Ai/scratch"

    suspend fun execute(script: String): String = withContext(Dispatchers.IO) {
        if (script.isBlank()) return@withContext "[Error: Empty Python script]"
        try {
            val dir = File(SCRATCH_DIR)
            if (!dir.exists()) dir.mkdirs()
            val scriptFile = File(dir, "temp_exec.py")
            scriptFile.writeText(script)

            val pythonCmd = when {
                File("/data/data/com.termux/files/usr/bin/python3").exists() -> 
                    "/data/data/com.termux/files/usr/bin/python3 ${scriptFile.absolutePath}"
                File("/system/bin/python3").exists() -> 
                    "python3 ${scriptFile.absolutePath}"
                else -> "python3 ${scriptFile.absolutePath} 2>&1 || python ${scriptFile.absolutePath}"
            }

            val resultLines = RootShellManager.execAsync(pythonCmd)
            val output = resultLines.joinToString("\n").trim()
            if (output.isBlank()) "[Python execution finished with no output]" else output
        } catch (e: Exception) {
            "Python Execution Error: ${e.message}"
        }
    }
}
