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
    const val TTS_DIR      = "/sdcard/Medlam-Ai/tts"
    const val VOICE_DIR    = "/sdcard/Medlam-Ai/voice"
    const val ROOT_DIR     = "/sdcard/Medlam-Ai"

    const val PREF_CACHED_BASE     = "cached_base_models_manifest"
    const val PREF_CACHED_MTP      = "cached_mtp_models_manifest"
    const val PREF_CACHED_ACOUSTIC = "cached_acoustic_models_manifest"
    const val PREF_CACHED_VOCODER  = "cached_vocoder_models_manifest"
    const val PREF_CACHED_SPEAKER  = "cached_speaker_models_manifest"

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

    private val TTS_DIRS = listOf(
        "/sdcard/Medlam-Ai/tts",
        "/sdcard/Medlam-Ai/Tts",
        "/sdcard/Medlam-Ai/TTS",
        "/storage/emulated/0/Medlam-Ai/tts",
        "/storage/emulated/0/Medlam-Ai/Tts"
    )

    private val VOICE_DIRS = listOf(
        "/sdcard/Medlam-Ai/voice",
        "/sdcard/Medlam-Ai/Voice",
        "/storage/emulated/0/Medlam-Ai/voice",
        "/storage/emulated/0/Medlam-Ai/Voice"
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

    @Volatile
    private var cachedAcousticModels: List<File>? = null

    @Volatile
    private var cachedVocoderModels: List<File>? = null

    @Volatile
    private var cachedSpeakerModels: List<File>? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences("synapse_config", Context.MODE_PRIVATE)
    }

    fun ensureDirectories() {
        try {
            File(ROOT_DIR).mkdirs()
            File(MODELS_DIR).mkdirs()
            File(MTP_DIR).mkdirs()
            File(TTS_DIR).mkdirs()
            File(VOICE_DIR).mkdirs()
        } catch (_: Exception) {}
    }

    fun isQuantizedModel(file: File): Boolean {
        val name = file.name.lowercase()
        return !name.contains("bf16") && !name.contains("fp16") && !name.contains(".f16") && !name.contains("-f16") && !name.contains(".f32") && !name.contains("-f32")
    }

    /**
     * Supports ALL quantization types and precisions for draft models:
     * q1, q2, q3, q4, q5, q6, q8, f16, bf16, fp16, f32, etc.
     */
    fun isIntegerQuantizedDraft(file: File): Boolean {
        if (file.name.startsWith(".")) return false
        return file.name.endsWith(".gguf", ignoreCase = true)
    }

    val BASE_MODEL_COMPARATOR: Comparator<File> = compareByDescending<File> { isQuantizedModel(it) }
        .thenByDescending { it.name.contains("q4_k_xl", ignoreCase = true) }
        .thenByDescending { it.name.contains("q4_k_m", ignoreCase = true) }
        .thenByDescending { it.name.contains("q4_0", ignoreCase = true) }
        .thenByDescending { it.name.contains("q4", ignoreCase = true) }
        .thenByDescending { it.name.contains("gemma", ignoreCase = true) }
        .thenByDescending { it.length() }

    /**
     * Retrieves base models from cache without triggering disk I/O.
     * Only scans if cache is completely uninitialized.
     */
    fun getBaseModels(context: Context? = null, forceRescan: Boolean = false): List<File> {
        if (!forceRescan) {
            cachedBaseModels?.let { return it.filter { f -> f.exists() && isBaseModelFile(f) }.distinctBy { f -> f.name.lowercase() }.sortedWith(BASE_MODEL_COMPARATOR) }

            if (context != null) {
                val prefs = getPrefs(context)
                val stored = prefs.getStringSet(PREF_CACHED_BASE, null)
                if (!stored.isNullOrEmpty()) {
                    val list = stored.map { File(it) }
                        .filter { it.exists() && isBaseModelFile(it) }
                        .distinctBy { it.name.lowercase() }
                        .sortedWith(BASE_MODEL_COMPARATOR)
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
            cachedMtpModels?.let { return it.filter { f -> f.exists() && isMtpModelFile(f) }.distinctBy { f -> f.name.lowercase() } }

            if (context != null) {
                val prefs = getPrefs(context)
                val stored = prefs.getStringSet(PREF_CACHED_MTP, null)
                if (!stored.isNullOrEmpty()) {
                    val list = stored.map { File(it) }
                        .filter { it.exists() && isMtpModelFile(it) }
                        .distinctBy { it.name.lowercase() }
                        .sortedBy { it.length() }
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
     * Retrieves Acoustic models (.onnx) from cache or disk without unnecessary I/O.
     */
    fun getAcousticModels(context: Context? = null, forceRescan: Boolean = false): List<File> {
        if (!forceRescan) {
            cachedAcousticModels?.let { return it.filter { f -> f.exists() }.distinctBy { f -> f.name.lowercase() } }

            if (context != null) {
                val prefs = getPrefs(context)
                val stored = prefs.getStringSet(PREF_CACHED_ACOUSTIC, null)
                if (!stored.isNullOrEmpty()) {
                    val list = stored.map { File(it) }
                        .filter { it.exists() }
                        .distinctBy { it.name.lowercase() }
                    if (list.isNotEmpty()) {
                        cachedAcousticModels = list
                        return list
                    }
                }
            }
        }
        val list = scanAcousticModelsFromDisk()
        cachedAcousticModels = list
        if (context != null) {
            getPrefs(context).edit().putStringSet(PREF_CACHED_ACOUSTIC, list.map { it.absolutePath }.toSet()).apply()
        }
        return list
    }

    /**
     * Retrieves Vocoder models (.onnx) from cache or disk without unnecessary I/O.
     */
    fun getVocoderModels(context: Context? = null, forceRescan: Boolean = false): List<File> {
        if (!forceRescan) {
            cachedVocoderModels?.let { return it.filter { f -> f.exists() }.distinctBy { f -> f.name.lowercase() } }

            if (context != null) {
                val prefs = getPrefs(context)
                val stored = prefs.getStringSet(PREF_CACHED_VOCODER, null)
                if (!stored.isNullOrEmpty()) {
                    val list = stored.map { File(it) }
                        .filter { it.exists() }
                        .distinctBy { it.name.lowercase() }
                    if (list.isNotEmpty()) {
                        cachedVocoderModels = list
                        return list
                    }
                }
            }
        }
        val list = scanVocoderModelsFromDisk()
        cachedVocoderModels = list
        if (context != null) {
            getPrefs(context).edit().putStringSet(PREF_CACHED_VOCODER, list.map { it.absolutePath }.toSet()).apply()
        }
        return list
    }

    /**
     * Retrieves Speaker Embedding profiles (.bin, .npy) from cache or disk.
     */
    fun getSpeakerEmbeddings(context: Context? = null, forceRescan: Boolean = false): List<File> {
        if (!forceRescan) {
            cachedSpeakerModels?.let { return it.filter { f -> f.exists() }.distinctBy { f -> f.name.lowercase() } }

            if (context != null) {
                val prefs = getPrefs(context)
                val stored = prefs.getStringSet(PREF_CACHED_SPEAKER, null)
                if (!stored.isNullOrEmpty()) {
                    val list = stored.map { File(it) }
                        .filter { it.exists() }
                        .distinctBy { it.name.lowercase() }
                    if (list.isNotEmpty()) {
                        cachedSpeakerModels = list
                        return list
                    }
                }
            }
        }
        val list = scanSpeakerModelsFromDisk()
        cachedSpeakerModels = list
        if (context != null) {
            getPrefs(context).edit().putStringSet(PREF_CACHED_SPEAKER, list.map { it.absolutePath }.toSet()).apply()
        }
        return list
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
        val acousticList = scanAcousticModelsFromDisk()
        val vocoderList = scanVocoderModelsFromDisk()
        val speakerList = scanSpeakerModelsFromDisk()

        cachedBaseModels = baseList
        cachedMtpModels = mtpList
        cachedAcousticModels = acousticList
        cachedVocoderModels = vocoderList
        cachedSpeakerModels = speakerList

        if (context != null) {
            val prefs = getPrefs(context)
            prefs.edit()
                .putStringSet(PREF_CACHED_BASE, baseList.map { it.absolutePath }.toSet())
                .putStringSet(PREF_CACHED_MTP, mtpList.map { it.absolutePath }.toSet())
                .putStringSet(PREF_CACHED_ACOUSTIC, acousticList.map { it.absolutePath }.toSet())
                .putStringSet(PREF_CACHED_VOCODER, vocoderList.map { it.absolutePath }.toSet())
                .putStringSet(PREF_CACHED_SPEAKER, speakerList.map { it.absolutePath }.toSet())
                .apply()
        }

        Log.i(TAG, "Explicit model rescan completed: ${baseList.size} base, ${mtpList.size} MTP, ${acousticList.size} acoustic, ${vocoderList.size} vocoder, ${speakerList.size} speaker")
        return Pair(baseList, mtpList)
    }

    private fun scanAcousticModelsFromDisk(): List<File> {
        val found = mutableListOf<File>()
        val candidateDirs = (TTS_DIRS + LEGACY_DIRS)
            .map { File(it) }
            .filter { it.exists() && it.isDirectory }
            .distinctBy { it.canonicalPath }

        for (dir in candidateDirs) {
            dir.listFiles { f ->
                if (!f.isFile || !f.name.endsWith(".onnx", ignoreCase = true) || f.name.startsWith(".")) return@listFiles false
                val name = f.name.lowercase()
                // Exclude vocoders, decoders, and speaker encoders
                if (name.contains("vocoder") || name.contains("decoder") || name.contains("mel2audio") || name.contains("hifigan") || name.contains("speaker") || name.contains("encoder")) {
                    return@listFiles false
                }
                // Match two-stage acoustic models or single-stage End-to-End Piper / VITS models
                name.contains("acoustic") || name.contains("prefill") || name.contains("talker") || name.contains("text2mel") ||
                name.contains("vits") || name.contains("piper") || name.contains("kareem") || name.contains("ar_jo") ||
                (f.parentFile?.name?.equals("tts", ignoreCase = true) == true)
            }?.let { found.addAll(it) }
        }
        return found.distinctBy { it.name.lowercase() }.sortedByDescending { it.length() }
    }

    private fun scanVocoderModelsFromDisk(): List<File> {
        val found = mutableListOf<File>()
        val candidateDirs = (TTS_DIRS + LEGACY_DIRS)
            .map { File(it) }
            .filter { it.exists() && it.isDirectory }
            .distinctBy { it.canonicalPath }

        for (dir in candidateDirs) {
            dir.listFiles { f ->
                if (!f.isFile || !f.name.endsWith(".onnx", ignoreCase = true) || f.name.startsWith(".")) return@listFiles false
                val name = f.name.lowercase()
                name.contains("vocoder") || name.contains("decoder") || name.contains("mel2audio") || name.contains("hifigan")
            }?.let { found.addAll(it) }
        }
        return found.distinctBy { it.name.lowercase() }.sortedByDescending { it.length() }
    }

    private fun scanSpeakerModelsFromDisk(): List<File> {
        val found = mutableListOf<File>()
        val candidateDirs = (VOICE_DIRS + TTS_DIRS + LEGACY_DIRS)
            .map { File(it) }
            .filter { it.exists() && it.isDirectory }
            .distinctBy { it.canonicalPath }

        for (dir in candidateDirs) {
            dir.listFiles { f ->
                if (!f.isFile || f.name.startsWith(".")) return@listFiles false
                val name = f.name.lowercase()
                val ext = f.extension.lowercase()
                // STRICT: Raw binary float32 (.bin) and NumPy (.npy) identity profiles only.
                // Prevent selecting neural network models (.onnx like speaker_encoder_q.onnx).
                if (ext == "onnx" || name.endsWith(".onnx")) return@listFiles false
                (ext == "bin" || ext == "npy") &&
                (name.contains("speaker") || name.contains("voice") || name.contains("embedding") || dir.name.equals("voice", ignoreCase = true))
            }?.let { found.addAll(it) }
        }
        return found.distinctBy { it.name.lowercase() }.sortedByDescending { it.length() }
    }

    /**
     * Checks if an ONNX model is a single-stage End-to-End model (e.g. Piper VITS)
     * which outputs direct audio waveform and bypasses the vocoder.
     */
    fun isPiperVitsModel(file: File?): Boolean {
        if (file == null) return false
        val name = file.name.lowercase()
        return name.contains("vits") || name.contains("piper") || name.contains("kareem") ||
               name.contains("ar_jo") || name.contains("medium") || name.contains("single_stage") ||
               name.contains("e2e")
    }

    // Compatibility wrappers for existing code
    fun scanBaseModels(): List<File> = getBaseModels(forceRescan = false)
    fun scanMtpModels(): List<File> = getMtpModels(forceRescan = false)

    /**
     * Identifies MTP draft companion models.
     * Supports ALL quantization types and precisions: q1, q2, q3, q4, q5, q6, q8, f16, bf16, fp16, f32, etc.
     */
    fun isMtpModelFile(file: File): Boolean {
        if (file.name.startsWith(".")) return false
        if (!file.name.endsWith(".gguf", ignoreCase = true)) return false
        val name = file.name.lowercase()

        // 1. Files located in dedicated MTP / draft directories
        val inMtpDir = file.parentFile?.name?.equals("mtp", ignoreCase = true) == true ||
                       file.absolutePath.contains("/mtp/", ignoreCase = true) ||
                       file.absolutePath.contains("/draft/", ignoreCase = true)
        if (inMtpDir) return true

        // 2. Explicit MTP / Draft naming conventions across all quantizations: q1..q8, f16, bf16, etc.
        val isMtpNamed = name.startsWith("mtp-") || name.startsWith("mtp_") ||
                         name.contains("-mtp") || name.contains("_mtp") || name.contains(".mtp") ||
                         name.contains("draft") || name.contains("assistant") || name.contains("speculative")
        if (isMtpNamed) return true

        return false
    }

    fun isBaseModelFile(file: File): Boolean {
        if (file.name.startsWith(".")) return false
        if (!file.name.endsWith(".gguf", ignoreCase = true)) return false
        val name = file.name.lowercase()
        val inMtpDir = file.parentFile?.name?.equals("mtp", ignoreCase = true) == true ||
                       file.absolutePath.contains("/mtp/", ignoreCase = true)
        if (inMtpDir) return false
        // Exclude dedicated draft/mtp head files from Base list
        if (name.startsWith("mtp-") || name.startsWith("mtp_") || name.contains("-mtp") || name.contains("draft") || name.contains("assistant")) {
            return false
        }
        return true
    }

    private fun scanBaseModelsFromDisk(): List<File> {
        val found = mutableListOf<File>()

        val candidateModelDirs = listOf(
            File(MODELS_DIR),
            File("/storage/emulated/0/Medlam-Ai/models"),
            File("/sdcard/Download"),
            File("/storage/emulated/0/Download"),
            File("/sdcard/Medlam-Ai"),
            File("/storage/emulated/0/Medlam-Ai")
        )
            .filter { it.exists() && it.isDirectory }
            .distinctBy { it.canonicalPath }

        for (dir in candidateModelDirs) {
            dir.listFiles { f ->
                f.isFile && f.extension.equals("gguf", ignoreCase = true) && isBaseModelFile(f)
            }?.let {
                found.addAll(it)
            }
        }

        return found.distinctBy { it.name.lowercase() }
            .sortedWith(BASE_MODEL_COMPARATOR)
    }

    private fun scanMtpModelsFromDisk(): List<File> {
        val found = mutableListOf<File>()

        // 1. Scan dedicated MTP directories (all .gguf files inside /mtp/)
        val candidateMtpDirs = listOf(
            File(MTP_DIR),
            File("/storage/emulated/0/Medlam-Ai/mtp"),
            File("/sdcard/mtp"),
            File("/storage/emulated/0/mtp")
        )
            .filter { it.exists() && it.isDirectory }
            .distinctBy { it.canonicalPath }

        for (dir in candidateMtpDirs) {
            dir.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) && !f.name.startsWith(".") }?.let {
                found.addAll(it)
            }
        }

        // 2. Scan model and download directories for files identified as MTP / companion drafts (all quants: q1..q8, f16, bf16...)
        val candidateModelDirs = listOf(
            File(MODELS_DIR),
            File("/storage/emulated/0/Medlam-Ai/models"),
            File("/sdcard/Download"),
            File("/storage/emulated/0/Download"),
            File("/sdcard/Medlam-Ai"),
            File("/storage/emulated/0/Medlam-Ai")
        )
            .filter { it.exists() && it.isDirectory }
            .distinctBy { it.canonicalPath }

        for (dir in candidateModelDirs) {
            dir.listFiles { f ->
                f.isFile &&
                f.extension.equals("gguf", ignoreCase = true) &&
                isMtpModelFile(f)
            }?.let { found.addAll(it) }
        }

        // Strictly deduplicate by lowercase file name to eliminate /sdcard vs /storage/emulated/0 duplication
        return found.distinctBy { it.name.lowercase() }
            .sortedBy { it.length() }
    }

    /**
     * Smart Auto-Pairing Logic:
     * When a Gemma base model is selected, find the best matching Gemma MTP draft model
     * from the CACHED manifest (zero disk sweep). Supports all quantizations: q1..q8, f16, bf16, etc.
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

        // Accept all discovered MTP draft candidates
        val candidateFiles = mtpFiles.filter { file ->
            file.exists() && (file.name.contains("gemma", ignoreCase = true) || isMtpModelFile(file))
        }

        if (candidateFiles.isEmpty()) return null

        data class Scored(val file: File, val score: Int)

        val scored = candidateFiles.map { mtpFile ->
            val mtpName = mtpFile.nameWithoutExtension.lowercase()
            val cleanMtp = mtpName
                .removePrefix("mtp-")
                .removePrefix("mtp_")
            val score = baseTokens.count { token -> cleanMtp.contains(token) }
            Scored(mtpFile, score)
        }.sortedWith(
            compareByDescending<Scored> { it.score }
                .thenByDescending { it.file.name.contains("q4_0", ignoreCase = true) }
                .thenByDescending { it.file.name.contains("q4_k_m", ignoreCase = true) }
                .thenByDescending { it.file.name.contains("q8_0", ignoreCase = true) }
                .thenByDescending { it.file.name.contains("f16", ignoreCase = true) }
                .thenByDescending { it.file.name.contains("bf16", ignoreCase = true) }
        )

        return scored.firstOrNull { it.score > 0 }?.file ?: candidateFiles.firstOrNull()
    }
}
