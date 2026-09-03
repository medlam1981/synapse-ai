package com.synapse.localai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File

/**
 * Zero-Overhead Asset Discovery & Model Cache
 * 
 * 1. Deprecation of Automatic Background Scanning:
 *    Auto-polling and directory observers are eliminated.
 *    Model discovery occurs strictly on explicit user request (Re-Scan button).
 * 
 * 2. Persistent Cached Manifest:
 *    Discovered models are cached in-memory and persisted in SharedPreferences.
 *    App operations read exclusively from the cached registry.
 */
object ModelScanner {
    private const val TAG = "ModelScanner"

    // Primary dirs (canonical)
    const val MODELS_DIR   = "/sdcard/Medlam-Ai/models"
    const val MTP_DIR      = "/sdcard/Medlam-Ai/mtp"
    const val PERSONAS_DIR = "/sdcard/Medlam-Ai/personas"
    const val ROOT_DIR     = "/sdcard/Medlam-Ai"

    const val PREF_CACHED_BASE = "cached_base_models_manifest"
    const val PREF_CACHED_MTP  = "cached_mtp_models_manifest"

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

    // In-memory manifests for zero RAM and storage overhead
    @Volatile
    private var cachedBaseModels: List<File>? = null

    @Volatile
    private var cachedMtpModels: List<File>? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences("synapse_config", Context.MODE_PRIVATE)
    }

    fun ensureDirectories() {
        try {
            File(ROOT_DIR).mkdirs()
            File(MODELS_DIR).mkdirs()
            File(MTP_DIR).mkdirs()
            File(PERSONAS_DIR).mkdirs()
            PersonaManager.ensureDefaultPersonas()
        } catch (_: Exception) {}
    }

    /**
     * Retrieves base models from cache without triggering disk I/O.
     * Only scans if cache is completely uninitialized.
     */
    fun getBaseModels(context: Context? = null, forceRescan: Boolean = false): List<File> {
        if (!forceRescan) {
            cachedBaseModels?.let { return it }

            if (context != null) {
                val prefs = getPrefs(context)
                val stored = prefs.getStringSet(PREF_CACHED_BASE, null)
                if (!stored.isNullOrEmpty()) {
                    val list = stored.map { File(it) }.filter { it.exists() }
                    if (list.isNotEmpty()) {
                        cachedBaseModels = list
                        return list
                    }
                }
            }
        }

        return rescanModels(context).first
    }

    /**
     * Retrieves MTP draft models from cache without triggering disk I/O.
     */
    fun getMtpModels(context: Context? = null, forceRescan: Boolean = false): List<File> {
        if (!forceRescan) {
            cachedMtpModels?.let { return it }

            if (context != null) {
                val prefs = getPrefs(context)
                val stored = prefs.getStringSet(PREF_CACHED_MTP, null)
                if (!stored.isNullOrEmpty()) {
                    val list = stored.map { File(it) }.filter { it.exists() }
                    if (list.isNotEmpty()) {
                        cachedMtpModels = list
                        return list
                    }
                }
            }
        }

        return rescanModels(context).second
    }

    /**
     * Explicit On-Demand Re-Scanning:
     * Scans storage strictly when manually triggered by the user in Settings.
     * Updates in-memory cache and persists the manifests to SharedPreferences.
     */
    fun rescanModels(context: Context? = null): Pair<List<File>, List<File>> {
        ensureDirectories()
        val baseList = scanBaseModelsFromDisk()
        val mtpList = scanMtpModelsFromDisk()

        cachedBaseModels = baseList
        cachedMtpModels = mtpList

        if (context != null) {
            val prefs = getPrefs(context)
            prefs.edit()
                .putStringSet(PREF_CACHED_BASE, baseList.map { it.absolutePath }.toSet())
                .putStringSet(PREF_CACHED_MTP, mtpList.map { it.absolutePath }.toSet())
                .apply()
        }

        Log.i(TAG, "Explicit model rescan completed: ${baseList.size} base, ${mtpList.size} MTP")
        return Pair(baseList, mtpList)
    }

    // Compatibility wrappers for existing code
    fun scanBaseModels(): List<File> = getBaseModels(forceRescan = false)
    fun scanMtpModels(): List<File> = getMtpModels(forceRescan = false)

    private fun scanBaseModelsFromDisk(): List<File> {
        val found = mutableListOf<File>()

        for (path in MODELS_DIRS) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) }?.let {
                    found.addAll(it)
                }
            }
        }

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

        return found.distinctBy { it.name }
            .sortedWith(
                compareByDescending<File> { it.name.contains("gemma", ignoreCase = true) }
                    .thenByDescending { it.length() }
            )
    }

    private fun scanMtpModelsFromDisk(): List<File> {
        val found = mutableListOf<File>()

        for (path in MTP_DIRS) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) }?.let {
                    found.addAll(it)
                }
            }
        }

        if (found.isNotEmpty()) return found.distinctBy { it.name }

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
     * When a base model is selected, find the best matching MTP draft model
     * from the CACHED manifest (zero disk sweep).
     */
    fun findMatchingMtpModel(baseModelPath: String, context: Context? = null): File? {
        if (baseModelPath.isBlank()) return null
        val mtpFiles = getMtpModels(context, forceRescan = false)
        if (mtpFiles.isEmpty()) return null

        val baseFile = File(baseModelPath)
        val baseName = baseFile.nameWithoutExtension.lowercase()

        val baseTokens = baseName
            .split("-", "_", ".")
            .filter { it.length > 1 }
            .map { it.lowercase() }

        val baseFamily = when {
            baseName.contains("gemma") -> "gemma"
            baseName.contains("qwen") -> "qwen"
            baseName.contains("llama") -> "llama"
            baseName.contains("phi") -> "phi"
            baseName.contains("deepseek") -> "deepseek"
            else -> ""
        }

        val candidateFiles = mtpFiles.filter { file ->
            val name = file.name.lowercase()
            val isAssistantBlock = name.contains("assistant")
            val isBf16OnlyAuxiliary = name.contains("bf16") && name.contains("mtp-gemma-4") && !name.contains("e2b")
            val matchesFamily = if (baseFamily.isNotEmpty()) name.contains(baseFamily) else true
            !isAssistantBlock && !isBf16OnlyAuxiliary && matchesFamily
        }

        if (candidateFiles.isEmpty()) return null

        data class Scored(val file: File, val score: Int)

        val scored = candidateFiles.map { mtpFile ->
            val mtpName = mtpFile.nameWithoutExtension.lowercase()
            val cleanMtp = mtpName
                .removePrefix("mtp-")
                .removePrefix("mtp_")
            val score = baseTokens.count { token -> token != "bf16" && cleanMtp.contains(token) }
            Scored(mtpFile, score)
        }.sortedWith(
            compareByDescending<Scored> { it.score }
                .thenByDescending { it.file.name.contains("bf16", ignoreCase = true) }
        )

        return scored.firstOrNull { it.score > 0 }?.file
    }
}
