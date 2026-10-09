package com.synapse.localai

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Model Capability Classification.
 */
enum class ModelCapability {
    TEXT_LLM,
    IMAGE_DIFFUSION,
    VIDEO_DIFFUSION,
    AUDIO_TTS,
    UNKNOWN
}

/**
 * Comprehensive Metadata Descriptor for discovered GGUF models.
 */
data class GgufModelDescriptor(
    val file: File,
    val capability: ModelCapability,
    val architecture: String,
    val modelName: String,
    val isAllInOne: Boolean, // Bundled UNet/DiT, CLIP, and VAE
    val hasUnet: Boolean,
    val hasTextEncoder: Boolean,
    val hasVae: Boolean,
    val hasMotionModules: Boolean,
    val contextLength: Int,
    val mobileScore: Int,
    val tensorCount: Long,
    val quantType: String
)

/**
 * Stream-Safe GGUF Binary Header Parser.
 *
 * Fully dynamic: Does NOT enforce a hardcoded 4KB/8KB buffer read.
 * Sequentially extracts metadata key-values and tensor names directly
 * from the binary file stream.
 */
object GgufHeaderParser {

    private const val TAG = "GgufHeaderParser"
    private const val GGUF_MAGIC = 0x46554747 // "GGUF" in little-endian

    fun parse(file: File): GgufModelDescriptor? {
        if (!file.exists() || !file.isFile || file.length() < 24) return null
        if (!file.name.endsWith(".gguf", ignoreCase = true) || file.name.startsWith(".")) return null

        try {
            RandomAccessFile(file, "r").use { raf ->
                // 1. Verify Magic Number (0x46554747)
                val b0 = raf.read()
                val b1 = raf.read()
                val b2 = raf.read()
                val b3 = raf.read()
                if (b0 != 0x47 || b1 != 0x47 || b2 != 0x55 || b3 != 0x46) {
                    return null
                }

                // 2. Read Version (uint32)
                val version = readLittleEndianInt(raf)
                if (version < 1 || version > 10) return null

                // 3. Read Tensor Count and Metadata KV Count (uint64)
                val tensorCount = readLittleEndianLong(raf)
                val kvCount = readLittleEndianLong(raf)

                var rawArch: String? = null
                var modelName: String? = null
                var contextLength = 4096
                var quantType = "UNKNOWN"

                // 4. Sequential Stream KV Parsing (Dynamic, no fixed buffer limit)
                var kvIndex = 0L
                val maxKvToScan = minOf(kvCount, 500L) // Scan up to 500 metadata entries safely

                while (kvIndex < maxKvToScan && raf.filePointer < raf.length() - 8) {
                    kvIndex++
                    val keyLen = readLittleEndianLong(raf)
                    if (keyLen <= 0 || keyLen > 4096) break // Safety guard against corrupted headers

                    val keyBytes = ByteArray(keyLen.toInt())
                    raf.readFully(keyBytes)
                    val key = String(keyBytes, Charsets.UTF_8)

                    val valType = readLittleEndianInt(raf)
                    when (valType) {
                        8 -> { // STRING
                            val strLen = readLittleEndianLong(raf)
                            if (strLen in 0..65536) {
                                val strBytes = ByteArray(strLen.toInt())
                                raf.readFully(strBytes)
                                val strVal = String(strBytes, Charsets.UTF_8)
                                if (key == "general.architecture") {
                                    rawArch = strVal.lowercase(Locale.ROOT)
                                } else if (key == "general.name") {
                                    modelName = strVal
                                } else if (key == "general.file_type") {
                                    quantType = strVal
                                }
                            } else {
                                skipStringOrArray(raf, strLen)
                            }
                        }
                        4 -> { // UINT32
                            val u32 = readLittleEndianInt(raf)
                            if (key.endsWith(".context_length")) {
                                contextLength = u32
                            }
                        }
                        10 -> { // UINT64
                            val u64 = readLittleEndianLong(raf)
                            if (key.endsWith(".context_length")) {
                                contextLength = u64.toInt()
                            }
                        }
                        0, 1, 7 -> raf.skipBytes(1) // UINT8, INT8, BOOL
                        2, 3 -> raf.skipBytes(2)    // UINT16, INT16
                        5, 6 -> raf.skipBytes(4)    // INT32, FLOAT32
                        11, 12 -> raf.skipBytes(8)  // INT64, FLOAT64
                        9 -> { // ARRAY
                            val itemType = readLittleEndianInt(raf)
                            val arrayLen = readLittleEndianLong(raf)
                            if (!skipArray(raf, itemType, arrayLen)) break
                        }
                        else -> break
                    }
                }

                // 5. Sequential Tensor Header Scanning (Integrity & Capability Check)
                var hasUnet = false
                var hasTextEncoder = false
                var hasVae = false
                var hasMotionModules = false

                // Scan tensor headers sequentially up to 1000 tensors to evaluate architecture coverage
                val tensorsToScan = minOf(tensorCount, 1200L)
                var tensorIdx = 0L

                while (tensorIdx < tensorsToScan && raf.filePointer < raf.length() - 16) {
                    tensorIdx++
                    val nameLen = readLittleEndianLong(raf)
                    if (nameLen <= 0 || nameLen > 2048) break

                    val nameBytes = ByteArray(nameLen.toInt())
                    raf.readFully(nameBytes)
                    val tensorName = String(nameBytes, Charsets.UTF_8).lowercase(Locale.ROOT)

                    // Check for UNet / DiT tensors
                    if (tensorName.contains("diffusion_model") ||
                        tensorName.contains("double_blocks") ||
                        tensorName.contains("single_blocks") ||
                        tensorName.contains("input_blocks") ||
                        tensorName.contains("middle_block") ||
                        tensorName.contains("output_blocks") ||
                        tensorName.contains("transformer_blocks") ||
                        tensorName.contains("unet")
                    ) {
                        hasUnet = true
                    }

                    // Check for CLIP / Text Encoder tensors
                    if (tensorName.contains("cond_stage_model") ||
                        tensorName.contains("text_model") ||
                        tensorName.contains("te1") ||
                        tensorName.contains("te2") ||
                        tensorName.contains("clip_l") ||
                        tensorName.contains("clip_g") ||
                        tensorName.contains("t5xxl") ||
                        tensorName.contains("conditioner")
                    ) {
                        hasTextEncoder = true
                    }

                    // Check for VAE tensors
                    if (tensorName.contains("first_stage_model") ||
                        tensorName.contains("vae.") ||
                        tensorName.startsWith("vae") ||
                        tensorName.contains("decoder.conv_in") ||
                        tensorName.contains("decoder.conv_out")
                    ) {
                        hasVae = true
                    }

                    // Check for Motion Module tensors (Video Diffusion)
                    if (tensorName.contains("motion_modules") ||
                        tensorName.contains("temporal") ||
                        tensorName.contains("motion_model") ||
                        tensorName.contains("time_embed") ||
                        tensorName.contains("frame_rate")
                    ) {
                        hasMotionModules = true
                    }

                    // Read n_dimensions (uint32)
                    val nDims = readLittleEndianInt(raf)
                    if (nDims < 0 || nDims > 16) break

                    // Skip dimensions (8 bytes per dimension)
                    raf.skipBytes(nDims * 8)
                    // Skip type (4 bytes)
                    raf.skipBytes(4)
                    // Skip offset (8 bytes)
                    raf.skipBytes(8)
                }

                // 6. Capability Classification
                val arch = (rawArch ?: detectArchFromFileName(file.name)).lowercase(Locale.ROOT)
                val fileNameLower = file.name.lowercase(Locale.ROOT)

                val capability: ModelCapability = when {
                    // Video Diffusion
                    hasMotionModules ||
                    arch.contains("animatediff") ||
                    arch.contains("ltx") ||
                    arch.contains("cogvideo") ||
                    arch.contains("wan") ||
                    arch.contains("svd") ||
                    fileNameLower.contains("animatediff") ||
                    fileNameLower.contains("ltx-video") ||
                    fileNameLower.contains("cogvideo") -> ModelCapability.VIDEO_DIFFUSION

                    // Image Diffusion
                    hasUnet ||
                    arch.contains("sd") ||
                    arch.contains("sdxl") ||
                    arch.contains("flux") ||
                    arch.contains("stable-diffusion") ||
                    fileNameLower.contains("turbo") ||
                    fileNameLower.contains("sd_turbo") ||
                    fileNameLower.contains("sd-turbo") ||
                    fileNameLower.contains("sdxl") ||
                    fileNameLower.contains("flux") ||
                    fileNameLower.contains("diffusion") ||
                    fileNameLower.contains("stable-diffusion") -> ModelCapability.IMAGE_DIFFUSION

                    // Text LLM
                    arch.contains("gemma") ||
                    arch.contains("llama") ||
                    arch.contains("qwen") ||
                    arch.contains("mistral") ||
                    arch.contains("phi") ||
                    arch.contains("deepseek") ||
                    arch.contains("falcon") ||
                    arch.contains("starcoder") -> ModelCapability.TEXT_LLM

                    else -> {
                        if (fileNameLower.contains("diffusion") || fileNameLower.contains("image")) {
                            ModelCapability.IMAGE_DIFFUSION
                        } else {
                            ModelCapability.TEXT_LLM
                        }
                    }
                }

                val isAllInOne = (hasUnet && hasTextEncoder && hasVae) ||
                                 fileNameLower.contains("turbo") ||
                                 fileNameLower.contains("all-in-one") ||
                                 fileNameLower.contains("sd15")

                // 7. Mobile Priority Score
                val mobileScore = calculateMobileScore(file, fileNameLower, capability, isAllInOne)

                return GgufModelDescriptor(
                    file = file,
                    capability = capability,
                    architecture = arch,
                    modelName = modelName ?: file.nameWithoutExtension,
                    isAllInOne = isAllInOne,
                    hasUnet = hasUnet,
                    hasTextEncoder = hasTextEncoder,
                    hasVae = hasVae,
                    hasMotionModules = hasMotionModules,
                    contextLength = contextLength,
                    mobileScore = mobileScore,
                    tensorCount = tensorCount,
                    quantType = quantType
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Header parse notice for ${file.name}: ${e.message}")
            return fallbackFromFileName(file)
        }
    }

    private fun detectArchFromFileName(name: String): String {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.contains("gemma") -> "gemma"
            lower.contains("llama") -> "llama"
            lower.contains("qwen") -> "qwen2"
            lower.contains("mistral") -> "mistral"
            lower.contains("phi") -> "phi"
            lower.contains("flux") -> "flux"
            lower.contains("sdxl") -> "sdxl"
            lower.contains("turbo") || lower.contains("sd") -> "sd"
            lower.contains("animatediff") -> "animatediff"
            lower.contains("ltx") -> "ltx-video"
            lower.contains("wan") -> "wan"
            else -> "unknown"
        }
    }

    private fun calculateMobileScore(file: File, nameLower: String, cap: ModelCapability, isAllInOne: Boolean): Int {
        var score = 100

        // Mobile-optimized variants prioritization
        if (nameLower.contains("turbo")) score += 80
        if (nameLower.contains("lcm")) score += 70
        if (nameLower.contains("lightning")) score += 60
        if (nameLower.contains("xs") || nameLower.contains("fast") || nameLower.contains("tiny") || nameLower.contains("small")) score += 50
        if (nameLower.contains("q4_0") || nameLower.contains("q4_k")) score += 40
        if (nameLower.contains("q5_0") || nameLower.contains("q5_k")) score += 30
        if (nameLower.contains("q8_0")) score += 20

        // Favor all-in-one checkpoints to avoid missing component crashes
        if (isAllInOne) score += 35

        // File size consideration (prefer smaller sizes for mobile thermal budget)
        val sizeMb = file.length() / (1024 * 1024)
        if (sizeMb in 500..2500) score += 30
        else if (sizeMb > 6000) score -= 40

        return score
    }

    private fun fallbackFromFileName(file: File): GgufModelDescriptor {
        val nameLower = file.name.lowercase(Locale.ROOT)
        val cap = when {
            nameLower.contains("animatediff") || nameLower.contains("video") || nameLower.contains("ltx") -> ModelCapability.VIDEO_DIFFUSION
            nameLower.contains("sd") || nameLower.contains("diffusion") || nameLower.contains("flux") -> ModelCapability.IMAGE_DIFFUSION
            else -> ModelCapability.TEXT_LLM
        }
        val isAllInOne = nameLower.contains("turbo") || nameLower.contains("all-in-one") || nameLower.contains("sd15")
        return GgufModelDescriptor(
            file = file,
            capability = cap,
            architecture = detectArchFromFileName(file.name),
            modelName = file.nameWithoutExtension,
            isAllInOne = isAllInOne,
            hasUnet = true,
            hasTextEncoder = true,
            hasVae = true,
            hasMotionModules = cap == ModelCapability.VIDEO_DIFFUSION,
            contextLength = 4096,
            mobileScore = calculateMobileScore(file, nameLower, cap, isAllInOne),
            tensorCount = 0L,
            quantType = "UNKNOWN"
        )
    }

    private fun readLittleEndianInt(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        if ((b0 or b1 or b2 or b3) < 0) return 0
        return (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or ((b2 and 0xFF) shl 16) or ((b3 and 0xFF) shl 24)
    }

    private fun readLittleEndianLong(raf: RandomAccessFile): Long {
        var res = 0L
        for (i in 0 until 8) {
            val b = raf.read()
            if (b < 0) return res
            res = res or ((b.toLong() and 0xFFL) shl (i * 8))
        }
        return res
    }

    private fun skipStringOrArray(raf: RandomAccessFile, len: Long) {
        if (len <= 0) return
        var remaining = len
        while (remaining > 0) {
            val skipped = raf.skipBytes(minOf(remaining, 65536L).toInt())
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    private fun skipArray(raf: RandomAccessFile, itemType: Int, arrayLen: Long): Boolean {
        if (arrayLen <= 0) return true
        val itemSize: Long = when (itemType) {
            0, 1, 7 -> 1L // UINT8, INT8, BOOL
            2, 3 -> 2L    // UINT16, INT16
            4, 5, 6 -> 4L // UINT32, INT32, FLOAT32
            10, 11, 12 -> 8L // UINT64, INT64, FLOAT64
            8 -> { // ARRAY OF STRINGS
                for (i in 0 until arrayLen) {
                    val sLen = readLittleEndianLong(raf)
                    if (sLen < 0 || sLen > 1048576) return false
                    skipStringOrArray(raf, sLen)
                }
                return true
            }
            else -> return false
        }

        val totalSkip = itemSize * arrayLen
        skipStringOrArray(raf, totalSkip)
        return true
    }
}

/**
 * Resilient GGUF Capability Registry.
 *
 * Scans user storage dynamically across all standard Android paths without
 * hardcoded path locks, registers model capabilities, and applies intelligent
 * mobile prioritization heuristics.
 */
object GgufCapabilityRegistry {

    private const val TAG = "GgufCapabilityRegistry"

    @Volatile
    private var cachedModels: List<GgufModelDescriptor> = emptyList()

    @Volatile
    private var lastScanTime: Long = 0L

    /**
     * Standard Candidate Directories (Dynamic Discovery across device storage).
     */
    fun getCandidateDirectories(context: Context? = null): List<File> {
        val dirs = mutableListOf<File>()

        // Common Android User Storage roots
        val rootPaths = listOf(
            "/sdcard/Download",
            "/storage/emulated/0/Download",
            "/sdcard/atlas-ai",
            "/sdcard/atlas-ai/models",
            "/storage/emulated/0/atlas-ai",
            "/storage/emulated/0/atlas-ai/models",
            "/sdcard/Medlam-Ai",
            "/sdcard/Medlam-Ai/models",
            "/storage/emulated/0/Medlam-Ai",
            "/storage/emulated/0/Medlam-Ai/models",
            "/data/media/0/Download",
            "/data/media/0/atlas-ai",
            "/data/media/0/atlas-ai/models",
            "/data/media/0/Medlam-Ai",
            "/data/media/0/Medlam-Ai/models",
            "/data/media/0",
            "/sdcard/Documents",
            "/storage/emulated/0/Documents",
            "/sdcard/Models",
            "/storage/emulated/0/Models",
            "/sdcard/AI",
            "/storage/emulated/0/AI",
            "/sdcard",
            "/storage/emulated/0"
        )

        for (path in rootPaths) {
            val f = File(path)
            if (f.exists() && f.isDirectory) {
                dirs.add(f)
            }
        }

        val storageRoot = File("/storage")
        if (storageRoot.exists() && storageRoot.isDirectory) {
            storageRoot.listFiles()?.forEach { vol ->
                if (vol.isDirectory && !vol.name.startsWith(".") && vol.name != "emulated" && vol.name != "self") {
                    dirs.add(vol)
                    val dl = File(vol, "Download")
                    if (dl.exists() && dl.isDirectory) dirs.add(dl)
                    val docs = File(vol, "Documents")
                    if (docs.exists() && docs.isDirectory) dirs.add(docs)
                    val models = File(vol, "Models")
                    if (models.exists() && models.isDirectory) dirs.add(models)
                }
            }
        }

        // Context-specific directories
        if (context != null) {
            try {
                context.getExternalFilesDir(null)?.let { dirs.add(it) }
                context.externalCacheDir?.let { dirs.add(it) }
                context.filesDir?.let { dirs.add(it) }
            } catch (_: Exception) {}
        }

        return dirs.distinctBy { it.canonicalPath }
    }

    /**
     * Performs a live scan of storage directories to register available models.
     */
    @Synchronized
    fun scanStorage(context: Context? = null, forceRefresh: Boolean = false): List<GgufModelDescriptor> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedModels.isNotEmpty() && (now - lastScanTime < 60_000L)) {
            return cachedModels
        }

        Log.i(TAG, "Scanning device storage dynamically for GGUF capability models...")
        val candidateDirs = getCandidateDirectories(context)
        val discoveredFiles = mutableListOf<File>()

        for (dir in candidateDirs) {
            scanDirectoryRecursively(dir, depth = 0, maxDepth = 2, result = discoveredFiles)
        }

        val uniqueFiles = discoveredFiles.distinctBy { it.canonicalPath }
        val descriptors = mutableListOf<GgufModelDescriptor>()

        for (file in uniqueFiles) {
            try {
                val desc = GgufHeaderParser.parse(file)
                if (desc != null) {
                    descriptors.add(desc)
                    Log.i(TAG, "Registered GGUF: ${file.name} | Cap=${desc.capability} | Arch=${desc.architecture} | AllInOne=${desc.isAllInOne} | Score=${desc.mobileScore}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error registering ${file.name}: ${t.message}")
            }
        }

        cachedModels = descriptors
        lastScanTime = now
        Log.i(TAG, "GGUF Capability Registry scan complete. Found ${descriptors.size} models (${descriptors.count { it.capability == ModelCapability.IMAGE_DIFFUSION }} image, ${descriptors.count { it.capability == ModelCapability.VIDEO_DIFFUSION }} video, ${descriptors.count { it.capability == ModelCapability.TEXT_LLM }} text).")
        return descriptors
    }

    private fun scanDirectoryRecursively(dir: File, depth: Int, maxDepth: Int, result: MutableList<File>) {
        if (!dir.exists() || !dir.isDirectory || depth > maxDepth) return
        val list = dir.listFiles() ?: return

        for (item in list) {
            if (item.name.startsWith(".")) continue
            if (item.isFile && item.extension.equals("gguf", ignoreCase = true)) {
                result.add(item)
            } else if (item.isDirectory && depth < maxDepth) {
                // Avoid scanning large unrelated system folders
                val nameLower = item.name.lowercase(Locale.ROOT)
                if (nameLower != "android" && nameLower != "dcim" && nameLower != "pictures" && nameLower != "music") {
                    scanDirectoryRecursively(item, depth + 1, maxDepth, result)
                }
            }
        }
    }

    /**
     * Returns all registered models matching a given capability.
     */
    fun getModelsByCapability(cap: ModelCapability, context: Context? = null): List<GgufModelDescriptor> {
        if (cachedModels.isEmpty()) {
            scanStorage(context, forceRefresh = false)
        }
        val matching = cachedModels.filter { it.capability == cap }
        if (matching.isEmpty()) {
            // On-demand live rescan if not found in current manifest
            return scanStorage(context, forceRefresh = true).filter { it.capability == cap }
        }
        return matching
    }

    /**
     * Resolves the best available Image Diffusion model using mobile prioritization heuristics.
     */
    fun getBestImageModel(context: Context? = null): GgufModelDescriptor? {
        val candidates = getModelsByCapability(ModelCapability.IMAGE_DIFFUSION, context)
        return candidates.maxByOrNull { it.mobileScore }
    }

    /**
     * Resolves the best available Video Diffusion model.
     */
    fun getBestVideoModel(context: Context? = null): GgufModelDescriptor? {
        val candidates = getModelsByCapability(ModelCapability.VIDEO_DIFFUSION, context)
        return candidates.maxByOrNull { it.mobileScore }
    }

    /**
     * Resolves the best available Text LLM model.
     */
    fun getBestTextModel(context: Context? = null): GgufModelDescriptor? {
        val candidates = getModelsByCapability(ModelCapability.TEXT_LLM, context)
        return candidates.maxByOrNull { it.mobileScore }
    }
}
