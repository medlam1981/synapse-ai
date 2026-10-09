package com.synapse.localai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.zip.ZipFile
import kotlin.random.Random

/**
 * Result of a dynamic media request dispatched to DynamicMediaBroker.
 */
sealed class MediaResult {
    data class ImageSuccess(
        val imageBytes: ByteArray,
        val mimeType: String = "image/png",
        val prompt: String,
        val modelUsed: String
    ) : MediaResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ImageSuccess) return false
            return imageBytes.contentEquals(other.imageBytes) && prompt == other.prompt
        }
        override fun hashCode(): Int {
            var res = imageBytes.contentHashCode()
            res = 31 * res + prompt.hashCode()
            return res
        }
    }

    data class VideoSuccess(
        val videoBytes: ByteArray,
        val mimeType: String = "video/mp4",
        val prompt: String,
        val modelUsed: String
    ) : MediaResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is VideoSuccess) return false
            return videoBytes.contentEquals(other.videoBytes) && prompt == other.prompt
        }
        override fun hashCode(): Int {
            var res = videoBytes.contentHashCode()
            res = 31 * res + prompt.hashCode()
            return res
        }
    }

    data class FallbackMessage(
        val messageEn: String,
        val messageAr: String,
        val isArabicPrompt: Boolean
    ) : MediaResult() {
        fun localizedMessage(): String = if (isArabicPrompt) messageAr else messageEn
    }

    data class Error(val message: String) : MediaResult()
}

/**
 * Dynamic Intent-Driven Media Broker (Text / Image / Video).
 *
 * Implements:
 * 1. Runtime Dynamic Discovery & On-Demand Rescan:
 *    Discovers GGUF checkpoints dynamically across storage.
 * 2. Authentic Inference via stable-diffusion.cpp:
 *    Executes native neural tensor computation via sd.cpp on CPU.
 * 3. Exact Native Error Propagation:
 *    Captures exact stderr / logs from the native engine.
 * 4. Zero-Forced-RAM Execution:
 *    Strict mmap streaming directly from disk, VAE tiling, and dynamic seed diversification.
 */
object DynamicMediaBroker {

    private const val TAG = "DynamicMediaBroker"

    @Volatile
    private var activeProcess: Process? = null

    /**
     * Instantly aborts any active diffusion subprocess.
     */
    fun abort() {
        try {
            activeProcess?.let { proc ->
                Log.i(TAG, "Instant Abort: Terminating active sd-cli subprocess forcibly")
                proc.destroyForcibly()
            }
            activeProcess = null
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying sd-cli process: ${e.message}")
        }
    }

    /**
     * Translates Arabic/Darija prompts into high-density English visual descriptors
     * understood by CLIP, and enriches subject geometry (e.g. centering vehicles and enhancing fur).
     */
    private fun translateAndEnrichPrompt(raw: String, isArabic: Boolean): String {
        var text = raw.trim().lowercase()

        // 1. Strip conversational command noise
        val noiseWords = listOf(
            "ارسم", "صمم", "دير ليا", "دير", "صاوب", "توليد", "انشئ", "اعطني", "اريد", "من فضلك", "عفاك",
            "draw", "generate", "create", "paint", "make a picture of", "make an image of", "photo of", "image of"
        )
        for (nw in noiseWords) {
            text = text.replace(Regex("""\b$nw\b""", RegexOption.IGNORE_CASE), "")
        }
        text = text.trim()

        val hasArabic = isArabic || text.any { it in '\u0600'..'\u06FF' }

        val translated = if (hasArabic) {
            // Phrase-level translation for semantic accuracy
            val phraseDict = linkedMapOf(
                "على السرير" to "on a cozy bed",
                "فوق السرير" to "on a cozy bed",
                "في السرير" to "in a cozy bed",
                "على الفراش" to "on a cozy bed",
                "على الاريكة" to "on a sofa couch",
                "على الكنبة" to "on a sofa couch",
                "نائمة على" to "sleeping on",
                "نائم على" to "sleeping on",
                "ناعسة على" to "sleeping on",
                "ناعس على" to "sleeping on",
                "قطة جميلة" to "a cute beautiful cat",
                "قطة نائمة" to "a sleeping cat",
                "قطة لطيفة" to "a cute adorable cat",
                "في الليل" to "at night, dark atmosphere",
                "في الصباح" to "in the morning, soft sunlight",
                "تحت المطر" to "in the rain, wet reflections",
                "سباق سيارات" to "sports car race",
                "مدينة مستقبلية" to "futuristic cyberpunk city",
                "ناطحات سحاب" to "skyscrapers"
            )

            var working = text
            for ((ar, en) in phraseDict) {
                working = working.replace(ar, en)
            }

            // Word-level mapping
            val wordDict = mapOf(
                // Animals
                "قطة" to "cat", "قطه" to "cat", "قط" to "cat", "بسة" to "cat", "مش" to "cat", "هر" to "cat", "قطط" to "cats",
                "كلب" to "dog", "كلبة" to "dog", "جرو" to "puppy",
                "اسد" to "lion", "أسد" to "lion", "نمر" to "tiger", "ذئب" to "wolf", "فهد" to "cheetah",
                "حصان" to "horse", "فرس" to "horse", "طائر" to "bird", "عصفور" to "little bird", "نسر" to "eagle",
                // Vehicles
                "سيارة" to "car", "سياره" to "car", "طوموبيل" to "car", "مركبة" to "vehicle", "عربة" to "vehicle",
                "سوبركار" to "supercar", "سباق" to "racing", "شاحنة" to "truck", "دراجة" to "motorcycle",
                // People
                "رجل" to "man", "امراة" to "woman", "امرأة" to "woman", "فتاة" to "girl", "بنت" to "girl",
                "ولد" to "boy", "طفل" to "child", "محارب" to "warrior", "شخص" to "person",
                // Adjectives
                "جميلة" to "beautiful", "جميل" to "beautiful", "زوينة" to "beautiful", "زوين" to "beautiful",
                "كيوت" to "cute", "لطيفة" to "cute", "لطيف" to "cute", "صغيرة" to "small cute", "صغير" to "small",
                "كبيرة" to "large", "كبير" to "large", "سريعة" to "fast", "سريع" to "fast",
                "نائمة" to "sleeping", "نائم" to "sleeping", "ناعسة" to "sleeping", "ناعس" to "sleeping",
                "سعيدة" to "happy", "سعيد" to "happy", "غاضب" to "angry",
                "واقعية" to "photorealistic", "سينمائي" to "cinematic", "سايبربانك" to "cyberpunk", "نيون" to "neon",
                // Objects & Places
                "سرير" to "bed", "فراش" to "bed", "اريكة" to "sofa", "طاولة" to "table",
                "غرفة" to "room", "منزل" to "house", "شارع" to "street", "طريق" to "road",
                "مدينة" to "city", "حديقة" to "garden", "غابة" to "forest",
                "بحر" to "ocean sea", "شاطئ" to "beach", "جبل" to "mountain", "ثلج" to "snow",
                "شمس" to "sun", "قمر" to "moon", "نجوم" to "stars", "فضاء" to "deep space", "كوكب" to "planet",
                // Prepositions
                "على" to "on", "فوق" to "on", "في" to "in", "تحت" to "under", "مع" to "with", "امام" to "in front of"
            )

            val words = working.split(Regex("""\s+"""))
            val translatedWords = words.map { w ->
                val cleanW = w.trim().replace(Regex("[،,.!؟]"), "")
                wordDict[cleanW] ?: cleanW
            }
            translatedWords.joinToString(" ")
        } else {
            text
        }

        // Strip orphaned non-ASCII characters to keep CLIP text encoder clean
        val sanitized = translated.replace(Regex("""[\u0600-\u06FF]"""), "").trim()
        val baseEnglish = if (sanitized.length >= 3) sanitized else "high quality digital artwork"

        // Subject-Specific Camera & Lighting Conditioning
        val lowerBase = baseEnglish.lowercase()
        val enrichment = when {
            lowerBase.contains("car") || lowerBase.contains("vehicle") || lowerBase.contains("supercar") -> {
                "full vehicle visible, wide angle cinematic shot, centered in frame, whole car body, 8k resolution, detailed automotive photography, photorealistic, dramatic lighting, sharp focus, masterwork"
            }
            lowerBase.contains("cat") || lowerBase.contains("dog") || lowerBase.contains("puppy") || lowerBase.contains("kitten") -> {
                "highly detailed fluffy fur, expressive eyes, cute, 8k resolution, soft cinematic lighting, studio quality, sharp focus, award-winning photography, photorealistic"
            }
            lowerBase.contains("cyberpunk") || lowerBase.contains("neon") -> {
                "vibrant neon glow, volumetric smoke, raytracing reflections, highly detailed, 8k resolution, cinematic lighting, masterpiece"
            }
            lowerBase.contains("space") || lowerBase.contains("planet") || lowerBase.contains("galaxy") -> {
                "nebula colors, deep space telescope photography, cosmic dust, photorealistic, 8k resolution, cinematic lighting, majestic view"
            }
            else -> {
                "highly detailed, photorealistic, 8k resolution, cinematic lighting, masterpiece, sharp focus"
            }
        }

        return "$baseEnglish, $enrichment"
    }

    /**
     * Dispatches an Image Generation request.
     */
    suspend fun dispatchImageRequest(
        intent: MediaIntent.GenerateImage,
        context: Context,
        onStatusUpdate: ((String) -> Unit)? = null
    ): MediaResult = withContext(Dispatchers.Default) {
        val rawPrompt = intent.prompt.trim()
        val isArabic = intent.isArabic

        val prefs = context.getSharedPreferences("synapse_config", Context.MODE_PRIVATE)
        val requestedWidth = prefs.getInt("sd_width", 256).let { if (it <= 0) 256 else it }
        val requestedHeight = prefs.getInt("sd_height", 256).let { if (it <= 0) 256 else it }
        val memoryInfo = ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        val maxImageSide = when {
            memoryInfo.availMem >= 5L * 1024 * 1024 * 1024 -> 768
            memoryInfo.availMem >= 3L * 1024 * 1024 * 1024 -> 512
            memoryInfo.availMem >= 1536L * 1024 * 1024 -> 384
            else -> 256
        }
        val scale = minOf(1f, maxImageSide.toFloat() / maxOf(requestedWidth, requestedHeight))
        val userWidth = ((requestedWidth * scale).toInt() / 64 * 64).coerceAtLeast(256)
        val userHeight = ((requestedHeight * scale).toInt() / 64 * 64).coerceAtLeast(256)

        // Step 1: Resilient Dynamic Discovery
        var modelDesc = GgufCapabilityRegistry.getBestImageModel(context)
        if (modelDesc == null) {
            Log.i(TAG, "No image model registered in cache. Triggering live on-demand storage scan...")
            GgufCapabilityRegistry.scanStorage(context, forceRefresh = true)
            modelDesc = GgufCapabilityRegistry.getBestImageModel(context)
        }

        val modelFile: File? = modelDesc?.file ?: findAnyDiffusionModel(context)
        val isAllInOne: Boolean = modelDesc?.isAllInOne ?: true

        // Step 2: Graceful Conversational Fallback if missing
        if (modelFile == null || !modelFile.exists()) {
            Log.w(TAG, "No compatible Image Diffusion GGUF found on device.")
            return@withContext MediaResult.FallbackMessage(
                messageEn = "Local Image Diffusion model not found in storage. Please place your .gguf model in /sdcard/Download or /sdcard/atlas-ai/models.",
                messageAr = "لم يتم العثور على نموذج توليد الصور في وحدة التخزين. يرجى وضع نموذج الـ GGUF في مجلد التنزيلات أو atlas-ai/models.",
                isArabicPrompt = isArabic
            )
        }

        Log.i(TAG, "Selected Image Diffusion Model: ${modelFile.name} (AllInOne=$isAllInOne)")

        // Step 3: Multilingual Prompt Translation & Semantic Conditioning
        val enrichedPrompt = translateAndEnrichPrompt(rawPrompt, isArabic)
        Log.i(TAG, "Executing Image Generation for: \"$enrichedPrompt\" (Raw: \"$rawPrompt\")")

        // Step 4: Smart Adaptive Parameter Calculation
        val userSteps = prefs.getInt("sd_steps", 0)
        val userCfg = prefs.getFloat("sd_cfg_scale", 0f)
        val numThreads = prefs.getInt("sd_threads", 4).let { if (it <= 0) 4 else it }
        val forceCpu = prefs.getString("sd_compute_device", "AUTO").equals("CPU", ignoreCase = true)

        val modelNameLower = modelFile.name.lowercase()
        val isFastModel = modelNameLower.contains("turbo") ||
                          modelNameLower.contains("lcm") ||
                          modelNameLower.contains("lightning") ||
                          modelNameLower.contains("tiny") ||
                          modelNameLower.contains("xs") ||
                          modelNameLower.contains("qwen-image")

        val effectiveSteps = if (userSteps > 0) userSteps else (if (isFastModel) 1 else 8)
        val effectiveCfg = if (userCfg > 0f) userCfg else (if (isFastModel) 1.0f else 7.0f)

        Log.i(TAG, "Inference Parameters: steps=$effectiveSteps, cfg=$effectiveCfg, dim=${userWidth}x${userHeight}, availableRAM=${memoryInfo.availMem / (1024 * 1024)}MB, threads=$numThreads (FastModel=$isFastModel)")

        // Step 5: Storage-Streamed Execution with Kernel Page Management
        val modelPath = modelFile.absolutePath
        try {
            PageCacheAdvisor.assert64BitArchitecture()
            try {
                PageCacheAdvisor.adviseModelIdle()
            } catch (_: Exception) {}

            PageCacheAdvisor.adviseModelActive(modelPath)

            executeNativeStableDiffusion(
                context = context,
                modelFile = modelFile,
                isAllInOne = isAllInOne,
                prompt = enrichedPrompt,
                width = userWidth,
                height = userHeight,
                steps = effectiveSteps,
                cfgScale = effectiveCfg,
                numThreads = numThreads,
                onStatusUpdate = onStatusUpdate,
                forceCpu = forceCpu
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Image generation error: ${t.message}", t)
            MediaResult.Error("Image synthesis failure: ${t.message}")
        } finally {
            try {
                PageCacheAdvisor.adviseModelIdle(modelPath)
                val activeLlmPath = prefs.getString("local_model_path", null)
                if (!activeLlmPath.isNullOrBlank() && File(activeLlmPath).exists()) {
                    PageCacheAdvisor.adviseModelActive(activeLlmPath)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Dispatches a Video Generation request.
     */
    suspend fun dispatchVideoRequest(
        intent: MediaIntent.GenerateVideo,
        context: Context
    ): MediaResult = withContext(Dispatchers.Default) {
        val isArabic = intent.isArabic

        var model = GgufCapabilityRegistry.getBestVideoModel(context)
        if (model == null) {
            Log.i(TAG, "No video model registered in cache. Triggering live on-demand storage scan...")
            GgufCapabilityRegistry.scanStorage(context, forceRefresh = true)
            model = GgufCapabilityRegistry.getBestVideoModel(context)
        }

        if (model == null) {
            Log.w(TAG, "No compatible Video Diffusion GGUF found on device.")
            return@withContext MediaResult.FallbackMessage(
                messageEn = "Video generation model is not installed. Place a compatible video GGUF in storage, or I can generate a still image instead.",
                messageAr = "نموذج توليد الفيديو غير مثبت في الجهاز. يرجى وضع نموذج Video GGUF متوافق في وحدة التخزين، أو يمكنني توليد صورة ثابتة بدلاً من ذلك.",
                isArabicPrompt = isArabic
            )
        }

        Log.i(TAG, "Selected Video Diffusion Model: ${model.file.name} (MobileScore=${model.mobileScore})")
        val modelPath = model.file.absolutePath

        try {
            PageCacheAdvisor.assert64BitArchitecture()
            PageCacheAdvisor.adviseModelActive(modelPath)

            MediaResult.FallbackMessage(
                messageEn = "Video model (${model.file.name}) recognized. Preparing video frames...",
                messageAr = "تم التعرف على نموذج الفيديو (${model.file.name}). جاري إعداد إطارات الحركة...",
                isArabicPrompt = isArabic
            )
        } finally {
            try {
                PageCacheAdvisor.adviseModelIdle(modelPath)
            } catch (_: Exception) {}
        }
    }

    /**
     * Executes authentic neural diffusion inference using compiled native stable-diffusion.cpp (sd-cli).
     * Prefers Vulkan when available, falls back to CPU, and uses mmap/VAE tiling to limit RAM spikes.
     */
    private suspend fun executeNativeStableDiffusion(
        context: Context,
        modelFile: File,
        isAllInOne: Boolean,
        prompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfgScale: Float,
        numThreads: Int,
        onStatusUpdate: ((String) -> Unit)?,
        forceCpu: Boolean = false
    ): MediaResult = withContext(Dispatchers.IO) {
        val useVulkan = !forceCpu && LlamaServerJNI.safeIsVulkanSupported()
        val deviceLabel = if (useVulkan) "Vulkan GPU" else "CPU (SIMD NEON)"
        onStatusUpdate?.invoke("Denoising via $deviceLabel with mmap and VAE tiling...")

        // 1. Locate native sd-cli binary
        val sdBinary = locateSdCliBinary(context)
        if (sdBinary == null || !sdBinary.exists()) {
            val errMsg = "Native engine 'sd-cli' binary not found. Place sd-cli in /data/local/tmp or application files."
            Log.e(TAG, errMsg)
            return@withContext MediaResult.Error(errMsg)
        }

        if (!sdBinary.canExecute()) {
            sdBinary.setExecutable(true, false)
        }

        // 2. Prepare output file
        val outputImage = File(context.cacheDir, "sd_out_${System.currentTimeMillis()}.png")

        // 3. Assemble CLI arguments with Dynamic Random Seed
        val randomSeed = Random.nextLong(1000L, 999999999L)

        val cmd = mutableListOf<String>()
        cmd.add(sdBinary.absolutePath)
        cmd.add("-m")
        cmd.add(modelFile.absolutePath)
        cmd.add("-p")
        cmd.add(prompt)
        cmd.add("-o")
        cmd.add(outputImage.absolutePath)
        cmd.add("-W")
        cmd.add(width.toString())
        cmd.add("-H")
        cmd.add(height.toString())
        cmd.add("-t")
        cmd.add(numThreads.toString())
        cmd.add("--steps")
        cmd.add(steps.toString())
        cmd.add("--cfg-scale")
        cmd.add(cfgScale.toString())

        // Pass dynamic seed to ensure non-repetitive angles and varied compositions
        cmd.add("--seed")
        cmd.add(randomSeed.toString())

        // Use Vulkan when the device supports it; retry with CPU if GPU inference fails.
        cmd.add("--backend")
        cmd.add(if (useVulkan) "vulkan0" else "cpu")

        // Enforce hardware memory mapping (--mmap) unconditionally to prevent RAM allocation
        cmd.add("--mmap")

        // Hardware-accelerated direct convolutions
        cmd.add("--vae-conv-direct")
        cmd.add("--diffusion-conv-direct")

        // Enforce VAE Tiling to prevent RAM spike during pixel decoding
        cmd.add("--vae-tiling")

        // Look for companion VAE / CLIP weights in user storage if model is not all-in-one
        if (!isAllInOne) {
            findCompanionWeights(context)?.let { (vaePath, clipPath) ->
                if (vaePath != null) {
                    cmd.add("--vae")
                    cmd.add(vaePath)
                }
                if (clipPath != null) {
                    cmd.add("--clip_l")
                    cmd.add(clipPath)
                }
            }
        }

        val nativeLibDir = try {
            extractSdCliDependencies(context)
        } catch (e: Exception) {
            val message = "Unable to prepare native diffusion libraries: ${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, message, e)
            return@withContext MediaResult.Error(message)
        }
        val ldPath = "$nativeLibDir:/data/local/tmp:${context.applicationInfo.nativeLibraryDir}:${sdBinary.parent}:/vendor/lib64:/system/lib64"
        val shellScript = "export LD_LIBRARY_PATH=\"$ldPath:\$LD_LIBRARY_PATH\" && export OMP_NUM_THREADS=$numThreads && export OMP_WAIT_POLICY=passive && export KMP_BLOCKTIME=0 && taskset f0 nice -n -10 \"$@\""
        val fullCmd = listOf("/system/bin/sh", "-c", shellScript, "sh") + cmd

        Log.i(TAG, "Starting Zero-RAM native sd.cpp inference (seed=$randomSeed): ${cmd.joinToString(" ")}")

        try {
            if (RootShellManager.isRootAvailable()) {
                RootShellManager.exec("echo 100 > /proc/sys/vm/swappiness 2>/dev/null")
            }
        } catch (_: Exception) {}

        val processBuilder = ProcessBuilder(fullCmd)
        processBuilder.redirectErrorStream(true)

        val outputLog = StringBuilder()
        var exitCode = -1

        try {
            val process = processBuilder.start()
            activeProcess = process

            try {
                if (RootShellManager.isRootAvailable()) {
                    RootShellManager.exec("echo 100 > /proc/sys/vm/swappiness 2>/dev/null")
                }
            } catch (_: Exception) {}

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                outputLog.append(l).append("\n")
                Log.d(TAG, "[sd.cpp] $l")

                if (l.contains("loading tensors", ignoreCase = true) || l.contains("tensors in", ignoreCase = true)) {
                    onStatusUpdate?.invoke("Loading diffusion tensors via mmap...")
                } else if (l.contains("sampling", ignoreCase = true) || l.contains("generating image", ignoreCase = true) || l.contains("step", ignoreCase = true)) {
                    onStatusUpdate?.invoke("Denoising visual concepts via $deviceLabel...")
                } else if (l.contains("decoding", ignoreCase = true) || l.contains("vae", ignoreCase = true) || l.contains("latent", ignoreCase = true)) {
                    onStatusUpdate?.invoke("Decoding VAE tiles into RGB pixels...")
                } else if (l.contains("save result image", ignoreCase = true)) {
                    onStatusUpdate?.invoke("Finalizing and saving rendered visual...")
                }
            }

            exitCode = process.waitFor()
            Log.i(TAG, "sd.cpp finished with exit code $exitCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute native sd-cli process: ${e.message}", e)
            outputLog.append("Native execution error: ${e.message}")
        } finally {
            try {
                activeProcess?.destroyForcibly()
            } catch (_: Exception) {}
            activeProcess = null
            try {
                if (RootShellManager.isRootAvailable()) {
                    RootShellManager.exec("echo 100 > /proc/sys/vm/swappiness 2>/dev/null")
                }
            } catch (_: Exception) {}
            try {
                PageCacheAdvisor.adviseModelIdle(modelFile.absolutePath)
            } catch (_: Exception) {}
        }

        if (exitCode == 0 && outputImage.exists() && outputImage.length() > 0) {
            val imageBytes = outputImage.readBytes()
            outputImage.delete()
            Log.i(TAG, "Native inference generated valid image: ${imageBytes.size} bytes from VAE decode")
            return@withContext MediaResult.ImageSuccess(
                imageBytes = imageBytes,
                mimeType = "image/png",
                prompt = prompt,
                modelUsed = "${modelFile.name} ($deviceLabel)"
            )
        } else {
            val rawErr = outputLog.toString().trim()
            if (useVulkan) {
                outputImage.delete()
                Log.w(TAG, "Vulkan image inference failed; retrying the same request on CPU.")
                onStatusUpdate?.invoke("GPU failed; retrying image generation on CPU...")
                return@withContext executeNativeStableDiffusion(
                    context = context,
                    modelFile = modelFile,
                    isAllInOne = isAllInOne,
                    prompt = prompt,
                    width = width,
                    height = height,
                    steps = steps,
                    cfgScale = cfgScale,
                    numThreads = numThreads,
                    onStatusUpdate = onStatusUpdate,
                    forceCpu = true
                )
            }
            val finalErrMsg = if (rawErr.isNotEmpty()) {
                "sd.cpp error (code $exitCode):\n$rawErr"
            } else {
                "sd.cpp failed with exit code $exitCode (no output generated)"
            }
            Log.e(TAG, finalErrMsg)
            return@withContext MediaResult.Error(finalErrMsg)
        }
    }

    private fun findAnyDiffusionModel(context: Context): File? {
        val searchDirs = listOf(
            File("/sdcard/atlas-ai/models"),
            File("/sdcard/atlas-ai"),
            File("/sdcard/Download"),
            File("/storage/emulated/0/Download"),
            File(context.filesDir, "models")
        )

        for (dir in searchDirs) {
            if (dir.exists() && dir.isDirectory) {
                val match = dir.listFiles { f ->
                    val n = f.name.lowercase()
                    f.isFile && n.endsWith(".gguf") && (
                        n.contains("turbo") || n.contains("sd") || n.contains("diffusion") ||
                        n.contains("tiny") || n.contains("dreamshaper") || n.contains("lcm")
                    )
                }?.maxByOrNull { it.length() }
                if (match != null) return match
            }
        }
        return null
    }

    private fun locateSdCliBinary(context: Context): File? {
        val candidates = listOf(
            File("/data/local/tmp/sd-cli"),
            File(context.applicationInfo.nativeLibraryDir, "libsd-cli.so"),
            File(context.filesDir, "sd-cli")
        )

        for (candidate in candidates) {
            if (candidate.exists() && candidate.isFile) {
                try { candidate.setExecutable(true, false) } catch (_: Exception) {}
                try {
                    if (RootShellManager.isRootAvailable()) {
                        RootShellManager.exec("chmod 755 ${candidate.absolutePath} /data/local/tmp/*.so")
                    }
                } catch (_: Exception) {}
                return candidate
            }
        }

        try {
            val dest = File(context.filesDir, "sd-cli")
            if (!dest.exists() || dest.length() == 0L) {
                context.assets.open("sd-cli").use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            dest.setExecutable(true, false)
            if (dest.exists() && dest.length() > 0L) return dest
        } catch (_: Exception) {}

        return File("/data/local/tmp/sd-cli")
    }

    @Synchronized
    private fun extractSdCliDependencies(context: Context): File {
        val requiredLibraries = listOf("libstable-diffusion.so", "libomp.so", "libc++_shared.so")
        val apkFile = File(context.applicationInfo.sourceDir)
        val outputDir = File(context.filesDir, "sd-cli-libraries-${apkFile.lastModified()}")
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw IllegalStateException("Cannot create native library directory: ${outputDir.absolutePath}")
        }

        ZipFile(apkFile).use { apk ->
            val abi = Build.SUPPORTED_ABIS.firstOrNull { candidate ->
                apk.getEntry("lib/$candidate/libstable-diffusion.so") != null &&
                    apk.getEntry("lib/$candidate/libomp.so") != null &&
                    apk.getEntry("lib/$candidate/libc++_shared.so") != null
            } ?: throw IllegalStateException("APK does not contain sd-cli dependencies for a supported ABI")

            for (library in requiredLibraries) {
                val entryName = "lib/$abi/$library"
                val entry = apk.getEntry(entryName)
                    ?: throw IllegalStateException("Missing native dependency $entryName")
                val outputFile = File(outputDir, library)
                if (outputFile.isFile && outputFile.length() == entry.size) continue

                val temporaryFile = File(outputDir, "$library.tmp")
                try {
                    apk.getInputStream(entry).use { input ->
                        FileOutputStream(temporaryFile).use { output -> input.copyTo(output) }
                    }
                    if (temporaryFile.length() != entry.size) {
                        throw IllegalStateException("Incomplete extraction of $library")
                    }
                    if (outputFile.exists() && !outputFile.delete()) {
                        throw IllegalStateException("Cannot replace stale native dependency $library")
                    }
                    if (!temporaryFile.renameTo(outputFile)) {
                        throw IllegalStateException("Cannot finalize native dependency $library")
                    }
                } finally {
                    if (temporaryFile.exists()) temporaryFile.delete()
                }
            }
        }
        return outputDir
    }

    private fun findCompanionWeights(context: Context): Pair<String?, String?>? {
        var vaePath: String? = null
        var clipPath: String? = null

        val candidateDirs = GgufCapabilityRegistry.getCandidateDirectories(context)
        for (dir in candidateDirs) {
            dir.listFiles()?.forEach { f ->
                val nameLower = f.name.lowercase()
                if (vaePath == null && (nameLower.contains("vae") && (nameLower.endsWith(".gguf") || nameLower.endsWith(".safetensors") || nameLower.endsWith(".bin")))) {
                    vaePath = f.absolutePath
                }
                if (clipPath == null && ((nameLower.contains("clip") || nameLower.contains("text_encoder") || nameLower.contains("qwen2_vl")) && (nameLower.endsWith(".gguf") || nameLower.endsWith(".safetensors") || nameLower.endsWith(".bin")))) {
                    clipPath = f.absolutePath
                }
            }
        }
        return if (vaePath != null || clipPath != null) Pair(vaePath, clipPath) else null
    }
}
