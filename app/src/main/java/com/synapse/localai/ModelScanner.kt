package com.synapse.localai

import java.io.File

object ModelScanner {
    const val MODELS_DIR = "/sdcard/Medlam-Ai/models"
    const val MTP_DIR = "/sdcard/Medlam-Ai/mtp"
    const val PERSONA_DIR = "/sdcard/Medlam-Ai/persona"
    const val ROOT_DIR = "/sdcard/Medlam-Ai"

    private val LEGACY_DIRS = listOf(
        "/sdcard/Medlam-Ai/Models",
        "/sdcard/Medlam-Ai",
        "/sdcard/Models",
        "/sdcard/Download",
        "/storage/emulated/0/Medlam-Ai/models",
        "/storage/emulated/0/Medlam-Ai/mtp",
        "/storage/emulated/0/Download"
    )

    fun ensureDirectories() {
        try {
            File(ROOT_DIR).mkdirs()
            File(MODELS_DIR).mkdirs()
            File(MTP_DIR).mkdirs()
            File(PERSONA_DIR).mkdirs()
            PersonaManager.ensurePersonaInitialized()
        } catch (_: Exception) {}
    }

    fun scanBaseModels(): List<File> {
        ensureDirectories()
        val found = mutableListOf<File>()
        val primaryDir = File(MODELS_DIR)
        if (primaryDir.exists() && primaryDir.isDirectory) {
            primaryDir.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) }?.let {
                found.addAll(it)
            }
        }
        // Fallback to legacy directories if primary is empty
        if (found.isEmpty()) {
            LEGACY_DIRS.forEach { path ->
                val dir = File(path)
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) }?.let {
                        found.addAll(it)
                    }
                }
            }
        }
        return found.distinctBy { it.name }.sortedByDescending { it.length() }
    }

    fun scanMtpModels(): List<File> {
        ensureDirectories()
        val primaryDir = File(MTP_DIR)
        if (primaryDir.exists() && primaryDir.isDirectory) {
            val list = primaryDir.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) }?.toList()
            if (!list.isNullOrEmpty()) return list
        }
        // Also check if any mtp files exist in /models or legacy dirs
        val fallback = mutableListOf<File>()
        val modelsDir = File(MODELS_DIR)
        if (modelsDir.exists() && modelsDir.isDirectory) {
            modelsDir.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) && f.name.contains("mtp", ignoreCase = true) }?.let {
                fallback.addAll(it)
            }
        }
        return fallback.distinctBy { it.name }
    }

    /**
     * Smart Auto-Pairing Logic:
     * When a base model is selected, search /sdcard/Medlam-Ai/mtp/ for candidate draft models.
     */
    fun findMatchingMtpModel(baseModelPath: String): File? {
        if (baseModelPath.isBlank()) return null
        val mtpFiles = scanMtpModels()
        if (mtpFiles.isEmpty()) return null

        val baseName = File(baseModelPath).nameWithoutExtension.lowercase()
        val baseTokens = baseName.split("-", "_", ".").filter { it.length > 2 }

        // Filter out auxiliary assistant layer files that lack standalone context (require full draft models)
        val candidateFiles = mtpFiles.filter { file ->
            val name = file.name.lowercase()
            !name.contains("assistant") && 
            !name.contains("bf16") && 
            !name.contains("mtp-gemma-4")
        }
        if (candidateFiles.isEmpty()) return null

        // Priority 1: Match tokens from base model name
        val match = candidateFiles.firstOrNull { mtpFile ->
            val mtpName = mtpFile.nameWithoutExtension.lowercase()
            val cleanMtp = mtpName.removePrefix("mtp-").removePrefix("mtp_")
            baseTokens.any { token -> cleanMtp.contains(token) }
        }

        return match ?: candidateFiles.firstOrNull()
    }
}
