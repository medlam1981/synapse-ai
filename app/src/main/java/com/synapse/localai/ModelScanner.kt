package com.synapse.localai

import java.io.File

object ModelScanner {
    // Primary dirs (lowercase canonical)
    const val MODELS_DIR  = "/sdcard/Medlam-Ai/models"
    const val MTP_DIR     = "/sdcard/Medlam-Ai/mtp"
    const val PERSONA_DIR = "/sdcard/Medlam-Ai/persona"
    const val ROOT_DIR    = "/sdcard/Medlam-Ai"

    // All case variants to search (case-insensitive FS support)
    private val MODELS_DIRS = listOf(
        "/sdcard/Medlam-Ai/models",
        "/sdcard/Medlam-Ai/Models",
        "/storage/emulated/0/Medlam-Ai/models",
        "/storage/emulated/0/Medlam-Ai/Models"
    )

    private val MTP_DIRS = listOf(
        "/sdcard/Medlam-Ai/mtp",
        "/sdcard/Medlam-Ai/Mtp",
        "/sdcard/Medlam-Ai/MTP",
        "/storage/emulated/0/Medlam-Ai/mtp",
        "/storage/emulated/0/Medlam-Ai/Mtp"
    )

    private val LEGACY_DIRS = listOf(
        "/sdcard/Medlam-Ai",
        "/sdcard/Models",
        "/sdcard/Download",
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

        // Search all case variants of models dir
        for (path in MODELS_DIRS) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) }?.let {
                    found.addAll(it)
                }
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
        val found = mutableListOf<File>()

        // Search all case variants of mtp dir
        for (path in MTP_DIRS) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) }?.let {
                    found.addAll(it)
                }
            }
        }

        if (found.isNotEmpty()) return found.distinctBy { it.name }

        // Also check if any mtp files exist in /models dirs
        val fallback = mutableListOf<File>()
        for (path in MODELS_DIRS) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles { f ->
                    f.isFile &&
                    f.extension.equals("gguf", ignoreCase = true) &&
                    f.name.contains("mtp", ignoreCase = true)
                }?.let { fallback.addAll(it) }
            }
        }
        return fallback.distinctBy { it.name }
    }

    /**
     * Smart Auto-Pairing Logic:
     * When a base model is selected, find the best matching MTP draft model.
     *
     * Matching strategy (priority order):
     *  1. Same model family tokens (e.g. "e2b", "12b", "gemma", "4") in MTP filename
     *  2. Highest token overlap score
     *  3. First available MTP candidate if no specific match
     *
     * Exclusions: standalone auxiliary assistant/bf16 blocks that lack full draft context.
     * NOTE: For the E2B model, mtp-gemma-4-E2B files ARE valid draft models and must NOT be excluded.
     */
    fun findMatchingMtpModel(baseModelPath: String): File? {
        if (baseModelPath.isBlank()) return null
        val mtpFiles = scanMtpModels()
        if (mtpFiles.isEmpty()) return null

        val baseFile = File(baseModelPath)
        val baseName = baseFile.nameWithoutExtension.lowercase()

        // Extract meaningful tokens from base model name
        val baseTokens = baseName
            .split("-", "_", ".")
            .filter { it.length > 1 }
            .map { it.lowercase() }

        // Determine if a file is a disqualified auxiliary block:
        // Only exclude "mtp-gemma-4" paired with "bf16" AND "assistant" together,
        // or files that explicitly say "assistant" — NOT generic E2B mtp files.
        val candidateFiles = mtpFiles.filter { file ->
            val name = file.name.lowercase()
            val isAssistantBlock = name.contains("assistant")
            val isBf16OnlyAuxiliary = name.contains("bf16") && name.contains("mtp-gemma-4") && !name.contains("e2b")
            !isAssistantBlock && !isBf16OnlyAuxiliary
        }

        if (candidateFiles.isEmpty()) return null

        // Score each candidate by token overlap with base model
        data class Scored(val file: File, val score: Int)

        val scored = candidateFiles.map { mtpFile ->
            val mtpName = mtpFile.nameWithoutExtension.lowercase()
            val cleanMtp = mtpName
                .removePrefix("mtp-")
                .removePrefix("mtp_")
            val score = baseTokens.count { token -> cleanMtp.contains(token) }
            Scored(mtpFile, score)
        }.sortedByDescending { it.score }

        // Return best match if it shares at least 1 token, otherwise first candidate
        return scored.firstOrNull { it.score > 0 }?.file ?: candidateFiles.firstOrNull()
    }
}
