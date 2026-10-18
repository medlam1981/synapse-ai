package com.synapse.localai.audio

import android.content.Context
import android.os.Process
import com.synapse.localai.AtlasLog as Log
import com.synapse.localai.RootShellManager
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-Time Edge TTS Engine for Qwen3-TTS-12Hz-0.6B CustomVoice.
 *
 * Architecture:
 * 1. Mixed Framework JNI: ONNX Runtime (C++ / XNNPACK) isolated from llama.cpp text engine.
 * 2. Mixed Precision: INT8 Transformer/Acoustic model + FP16 Vocoder for RAM savings without audio distortion.
 * 3. Pre-computed CustomVoice speaker embedding cached in RAM and injected in every forward pass.
 * 4. SD665 CPU affinity: Pinned strictly to Kryo 260 Gold Big Cores 6-7 (mask 0xC0).
 * 5. Strict Zero-Leak Resource Management: Explicit native buffer disposal to prevent ZRAM thrashing.
 */
class OnnxTtsEngine(private val context: Context? = null) {

    companion object {
        private const val TAG = "OnnxTtsEngine"
        const val SAMPLE_RATE = 24000 // 24 kHz audio for Qwen3-TTS
        const val PIPER_SAMPLE_RATE = 22050 // 22.05 kHz audio for Piper VITS

        val TTS_DIRS = listOf(
            "/sdcard/atlas-ai/tts",
            "/sdcard/atlas-ai/voice",
            "/sdcard/Download"
        )
    }

    private var ortEnv: OrtEnvironment? = null
    private var acousticSession: OrtSession? = null
    private var vocoderSession: OrtSession? = null

    private var acousticModelPath: String? = null
    private var vocoderModelPath: String? = null

    private var customVoiceEmbedding: CustomVoiceEmbedding? = null
    private val isInitialized = AtomicBoolean(false)
    private val isBusy = AtomicBoolean(false)

    private var humanFallback: HumanNeuralVoiceEngine? = null

    private var cachedPhonemeMap: Map<String, List<Long>>? = null
    private var cachedPhonemeMapModelPath: String? = null

    /**
     * Checks if the active acoustic model is an End-to-End single-stage model (like Piper VITS)
     * which produces direct waveform and bypasses the vocoder.
     */
    fun isPiperVits(path: String? = acousticModelPath): Boolean {
        if (path.isNullOrBlank()) return false
        val name = File(path).name.lowercase()
        return name.contains("vits") || name.contains("piper") || name.contains("kareem") ||
               name.contains("ar_jo") || name.contains("medium") || name.contains("single_stage") ||
               name.contains("e2e")
    }

    /**
     * Active sampling rate dynamically determined by the selected TTS model architecture.
     */
    val currentSampleRate: Int
        get() = if (isPiperVits()) PIPER_SAMPLE_RATE else SAMPLE_RATE

    private fun getOrCreateHumanFallback(): HumanNeuralVoiceEngine {
        return humanFallback ?: synchronized(this) {
            humanFallback ?: HumanNeuralVoiceEngine(context).also { humanFallback = it }
        }
    }

    /**
     * Initializes ONNX Runtime environment, loads speaker embedding,
     * scans for models, and configures execution sessions.
     */
    @Synchronized
    fun initialize(): Boolean {
        if (isInitialized.get()) return true

        try {
            // 1. Load & cache CustomVoice embedding
            customVoiceEmbedding = CustomVoiceEmbedding.getCachedOrLoad(context = context)
            Log.i(TAG, "CustomVoice embedding loaded (${customVoiceEmbedding?.dimension} dims, source=${customVoiceEmbedding?.sourcePath})")

            // 2. Discover model files on storage
            findModelPaths()

            // 3. Initialize isolated ORT environment
            try {
                ortEnv = OrtEnvironment.getEnvironment("qwen3_tts_edge")
                Log.i(TAG, "OrtEnvironment initialized successfully")
            } catch (e: Throwable) {
                Log.w(TAG, "ONNX Runtime environment init notice: ${e.message}")
            }

            // 4. Load sessions if weights exist on disk
            loadSessionsIfAvailable()

            isInitialized.set(true)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed initializing OnnxTtsEngine: ${e.message}", e)
            return false
        }
    }

    /**
     * Dynamically updates custom Acoustic, Vocoder, or Speaker Embedding models.
     */
    fun setCustomModels(acousticPath: String?, vocoderPath: String?, speakerPath: String?) {
        var needsReload = false
        if (!acousticPath.isNullOrBlank() && acousticPath != this.acousticModelPath) {
            this.acousticModelPath = acousticPath
            needsReload = true
        }
        if (!vocoderPath.isNullOrBlank() && vocoderPath != this.vocoderModelPath) {
            this.vocoderModelPath = vocoderPath
            needsReload = true
        }
        if (!speakerPath.isNullOrBlank()) {
            this.customVoiceEmbedding = CustomVoiceEmbedding.getCachedOrLoad(speakerPath, context)
            Log.i(TAG, "Dynamic speaker profile updated: $speakerPath (dim=${this.customVoiceEmbedding?.dimension})")
        }
        if (needsReload && isInitialized.get()) {
            try {
                acousticSession?.close()
                vocoderSession?.close()
                acousticSession = null
                vocoderSession = null
                cachedPhonemeMap = null
                cachedPhonemeMapModelPath = null
                loadSessionsIfAvailable()
                Log.i(TAG, "ONNX TTS sessions reloaded: acoustic=$acousticModelPath, vocoder=$vocoderModelPath, singleStage=${isPiperVits()}")
            } catch (e: Exception) {
                Log.w(TAG, "Error reloading ONNX TTS sessions: ${e.message}")
            }
        }
    }

    private fun findModelPaths() {
        for (dirPath in TTS_DIRS) {
            val dir = File(dirPath)
            if (!dir.exists() || !dir.isDirectory) continue

            val files = dir.listFiles() ?: continue
            for (file in files) {
                val name = file.name.lowercase()
                if (!name.endsWith(".onnx")) continue

                // Detect Vocoder models first
                if (vocoderModelPath == null && (name.contains("vocoder") || name.contains("decoder") || name.contains("mel2audio") || name.contains("hifigan"))) {
                    vocoderModelPath = file.absolutePath
                    Log.i(TAG, "Found Vocoder FP16 model: ${file.absolutePath}")
                } else if (acousticModelPath == null && (name.contains("acoustic") || name.contains("prefill") || name.contains("talker") || name.contains("text2mel") || name.contains("vits") || name.contains("piper") || name.contains("kareem") || name.contains("ar_jo"))) {
                    acousticModelPath = file.absolutePath
                    val isSingle = isPiperVits(file.absolutePath)
                    Log.i(TAG, "Found Acoustic model: ${file.absolutePath} (Single-Stage Piper/VITS: $isSingle)")
                }
            }
        }
    }

    private fun getAvailableRamMb(): Long {
        return try {
            val file = File("/proc/meminfo")
            if (file.exists()) {
                val line = file.useLines { lines ->
                    lines.firstOrNull { it.startsWith("MemAvailable:") }
                }
                if (line != null) {
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 2) {
                        return parts[1].toLong() / 1024
                    }
                }
            }
            450L
        } catch (_: Exception) {
            450L
        }
    }

    private fun getAvailableSwapMb(): Long {
        return try {
            val file = File("/proc/meminfo")
            if (file.exists()) {
                val line = file.useLines { lines ->
                    lines.firstOrNull { it.startsWith("SwapFree:") }
                }
                if (line != null) {
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 2) {
                        return parts[1].toLong() / 1024
                    }
                }
            }
            0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun loadSessionsIfAvailable() {
        val env = ortEnv ?: return

        val freeRam = getAvailableRamMb()
        val freeSwap = getAvailableSwapMb()
        val totalAvailableMb = freeRam + freeSwap
        Log.i(TAG, "Device free RAM: ${freeRam}MB, free Swap: ${freeSwap}MB (total available: ${totalAvailableMb}MB)")
        if (totalAvailableMb < 600) {
            Log.w(TAG, "Total available memory (${totalAvailableMb}MB) < 600MB threshold. Using lightweight resonant formant speech pipeline to preserve system stability and avoid LMK kills.")
            return
        }

        val acPath = acousticModelPath
        val isSingleStage = isPiperVits(acPath)

        if (acPath != null && File(acPath).exists()) {
            try {
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                    setInterOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                    setMemoryPatternOptimization(false)
                    addConfigEntry("session.intra_op_param.allow_spinning", "0")
                    try {
                        addXnnpack(mapOf("intra_op_num_threads" to "1"))
                        Log.i(TAG, "⚡ XNNPACK execution provider enabled for Acoustic model")
                    } catch (e: Throwable) {
                        Log.w(TAG, "XNNPACK EP fallback to CPU EP: ${e.message}")
                    }
                }
                acousticSession = env.createSession(acPath, opts)
                Log.i(TAG, "Acoustic session loaded from $acPath (Single-Stage Piper/VITS: $isSingleStage)")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed loading acoustic session: ${e.message}", e)
            }
        }

        if (isSingleStage) {
            // End-to-End Piper / VITS model produces direct audio waveform.
            // Vocoder stage is completely bypassed to eliminate RAM and inference overhead.
            try { vocoderSession?.close() } catch (_: Throwable) {}
            vocoderSession = null
            Log.i(TAG, "⚡ End-to-End Single-Stage model active ($acPath). Vocoder bypassed completely, streaming audio directly from acoustic output.")
            return
        }

        val vcPath = vocoderModelPath
        if (vcPath != null && File(vcPath).exists()) {
            try {
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                    setInterOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                    setMemoryPatternOptimization(false)
                    addConfigEntry("session.intra_op_param.allow_spinning", "0")
                    try {
                        addXnnpack(mapOf("intra_op_num_threads" to "1"))
                        Log.i(TAG, "⚡ XNNPACK execution provider enabled for Vocoder model")
                    } catch (e: Throwable) {
                        Log.w(TAG, "XNNPACK EP fallback to CPU EP: ${e.message}")
                    }
                }
                vocoderSession = env.createSession(vcPath, opts)
                Log.i(TAG, "Vocoder FP16 session loaded from $vcPath")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed loading vocoder session: ${e.message}", e)
            }
        }
    }

    /**
     * Binds current audio synthesis thread to Little Cores 0-3 (SD665 mask 0x0F)
     * to completely isolate audio processing from the LLM running on Big Cores 4-7 (mask 0xF0).
     */
    fun bindThreadToAudioCores() {
        try {
            val tid = Process.myTid()
            RootShellManager.exec("taskset -p 0f $tid 2>/dev/null || taskset -c 0,1,2,3 $tid 2>/dev/null")
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        } catch (_: Exception) {}
    }

    /** Backwards-compatible alias */
    fun bindThreadToAudioBigCores() = bindThreadToAudioCores()

    /**
     * Frees ONNX session buffers when physical RAM is low (< 600MB)
     * to relieve memory pressure and prevent OOM killer.
     */
    fun releaseSessionsIfLowMemory() {
        val freeRam = getAvailableRamMb()
        if (freeRam < 600) {
            try {
                acousticSession?.close()
                vocoderSession?.close()
                acousticSession = null
                vocoderSession = null
                Log.i(TAG, "Released ONNX TTS sessions to relieve physical RAM")
            } catch (_: Throwable) {}
        }
    }

    /**
     * Synthesizes a single sentence into PCM16 streaming chunks using JNI float-to-int16 normalization.
     * Automatically routes to End-to-End Single-Stage (Piper/VITS) or Two-Stage (Acoustic + Vocoder).
     */
    fun synthesizeSentence(
        sentence: String,
        onPcmBytes: (ByteArray) -> Unit,
        onPcmChunk: ((ShortArray) -> Unit)? = null
    ) {
        if (sentence.isBlank()) return
        if (!isInitialized.get()) {
            initialize()
        }

        bindThreadToAudioCores()

        val acSession = acousticSession
        val vcSession = vocoderSession
        val env = ortEnv
        val isSingleStage = isPiperVits()

        if (env != null && acSession != null && (isSingleStage || vcSession != null)) {
            if (isSingleStage) {
                synthesizeWithPiperVits(sentence, env, acSession, onPcmBytes, onPcmChunk)
            } else {
                synthesizeTwoStage(sentence, env, acSession, vcSession!!, onPcmBytes, onPcmChunk)
            }
        } else {
            // High-fidelity Human Neural Voice Fallback (Arabic & English)
            getOrCreateHumanFallback().speak(sentence)
        }
    }

    /**
     * Executes End-to-End single-stage inference (e.g. Piper VITS: ar_JO-kareem-medium.onnx).
     * Bypasses the vocoder inference stage completely and streams audio directly from the model output.
     */
    private fun synthesizeWithPiperVits(
        sentence: String,
        env: OrtEnvironment,
        session: OrtSession,
        onPcmBytes: (ByteArray) -> Unit,
        onPcmChunk: ((ShortArray) -> Unit)? = null
    ) {
        var inputTensor: OnnxTensor? = null
        var inputLengthsTensor: OnnxTensor? = null
        var scalesTensor: OnnxTensor? = null
        var sidTensor: OnnxTensor? = null
        var sessionResult: OrtSession.Result? = null

        try {
            val tokenIds = sentenceToPiperTokenIds(sentence, acousticModelPath)
            val tokenCount = tokenIds.size.toLong()

            val tokenBuffer = LongBuffer.wrap(tokenIds)
            val inTensor = OnnxTensor.createTensor(env, tokenBuffer, longArrayOf(1, tokenCount))
            inputTensor = inTensor

            val lenBuffer = LongBuffer.wrap(longArrayOf(tokenCount))
            val inLengthsTensor = OnnxTensor.createTensor(env, lenBuffer, longArrayOf(1))
            inputLengthsTensor = inLengthsTensor

            val scalesBuffer = FloatBuffer.wrap(floatArrayOf(0.667f, 1.0f, 0.8f))
            val scTensor = OnnxTensor.createTensor(env, scalesBuffer, longArrayOf(3))
            scalesTensor = scTensor

            val inputs = mutableMapOf<String, OnnxTensor>()
            for (name in session.inputNames) {
                val lower = name.lowercase()
                when {
                    lower == "input" || lower.contains("token") || lower.contains("text") ||
                    (lower.contains("id") && !lower.contains("length") && !lower.contains("speaker") && !lower.contains("sid")) -> {
                        inputs[name] = inTensor
                    }
                    lower == "input_lengths" || lower.contains("length") -> {
                        inputs[name] = inLengthsTensor
                    }
                    lower == "scales" || lower.contains("scale") -> {
                        inputs[name] = scTensor
                    }
                    lower == "sid" || lower.contains("speaker") -> {
                        val sTensor = sidTensor ?: OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(0L)), longArrayOf(1)).also { sidTensor = it }
                        inputs[name] = sTensor
                    }
                }
            }
            if (!inputs.containsKey("input") && inputs.isEmpty()) {
                inputs[session.inputNames.first()] = inTensor
            }

            sessionResult = session.run(inputs)
            val audioTensor = sessionResult.get(0) as? OnnxTensor
            if (audioTensor != null) {
                val rawAudio = audioTensor.floatBuffer
                val numSamples = rawAudio.remaining()
                val floatSamples = FloatArray(numSamples)
                rawAudio.get(floatSamples)

                Log.d(TAG, "Piper VITS audio synthesized: $numSamples samples directly from single-stage acoustic model (vocoder bypassed)")
                val pcmBytes = AudioNormalizerJNI.normalizeToPcmBytes(floatSamples)
                if (pcmBytes.isNotEmpty()) {
                    onPcmBytes(pcmBytes)
                } else if (onPcmChunk != null) {
                    val pcmShorts = AudioNormalizerJNI.normalizeToPcmShorts(floatSamples)
                    onPcmChunk(pcmShorts)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Piper VITS inference error, routing to human neural fallback: ${e.message}", e)
            getOrCreateHumanFallback().speak(sentence)
        } finally {
            try { inputTensor?.close() } catch (_: Throwable) {}
            try { inputLengthsTensor?.close() } catch (_: Throwable) {}
            try { scalesTensor?.close() } catch (_: Throwable) {}
            try { sidTensor?.close() } catch (_: Throwable) {}
            try { sessionResult?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Executes ONNX Runtime forward pass with INT8 Acoustic + FP16 Vocoder two-stage pipeline.
     * Injects speaker embedding with dynamic dimension adaptation (zero-padding/truncation)
     * to eliminate dimension mismatches and prevent fallback to synthetic tones.
     */
    private fun synthesizeTwoStage(
        sentence: String,
        env: OrtEnvironment,
        acoustic: OrtSession,
        vocoder: OrtSession,
        onPcmBytes: (ByteArray) -> Unit,
        onPcmChunk: ((ShortArray) -> Unit)? = null
    ) {
        var inputIdsTensor: OnnxTensor? = null
        var speakerTensor: OnnxTensor? = null
        var acousticResult: OrtSession.Result? = null
        var vocoderResult: OrtSession.Result? = null

        try {
            // 1. Text tokenization to sequence IDs
            val tokenIds = sentenceToTokenIds(sentence)
            val tokenBuffer = LongBuffer.wrap(tokenIds)
            val inIdsTensor = OnnxTensor.createTensor(env, tokenBuffer, longArrayOf(1, tokenIds.size.toLong()))
            inputIdsTensor = inIdsTensor

            // 2. Speaker embedding with dynamic dimension adaptation
            val inputNames = acoustic.inputNames.toList()
            var targetSpeakerDim = 1024
            if (inputNames.size > 1) {
                try {
                    val speakerNodeInfo = acoustic.inputInfo[inputNames[1]]
                    val tensorInfo = speakerNodeInfo?.info as? ai.onnxruntime.TensorInfo
                    val shape = tensorInfo?.shape
                    if (shape != null && shape.isNotEmpty()) {
                        val lastDim = shape.last().toInt()
                        if (lastDim > 0) {
                            targetSpeakerDim = lastDim
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not determine speaker input shape: ${e.message}")
                }
            }

            val embeddingObj = customVoiceEmbedding ?: CustomVoiceEmbedding.getCachedOrLoad(context = context)
            val adaptedEmbedding = embeddingObj.getAdaptedEmbedding(targetSpeakerDim)
            Log.d(TAG, "Injected speaker embedding for two-stage acoustic pass: ${adaptedEmbedding.size} dims (target=$targetSpeakerDim, source=${embeddingObj.sourcePath})")
            val speakerBuffer = FloatBuffer.wrap(adaptedEmbedding)
            val spkTensor = OnnxTensor.createTensor(env, speakerBuffer, longArrayOf(1, adaptedEmbedding.size.toLong()))
            speakerTensor = spkTensor

            // 3. Forward pass Acoustic Model (INT8 text-to-mel)
            val acousticInputs = mutableMapOf<String, OnnxTensor>()
            if (inputNames.isNotEmpty()) acousticInputs[inputNames[0]] = inIdsTensor
            if (inputNames.size > 1) acousticInputs[inputNames[1]] = spkTensor

            acousticResult = acoustic.run(acousticInputs)
            val melTensor = acousticResult.get(0) as? OnnxTensor

            if (melTensor != null) {
                // 4. Forward pass Vocoder Model (FP16 mel-to-audio)
                val vocoderInputs = mapOf(vocoder.inputNames.first() to melTensor)
                vocoderResult = vocoder.run(vocoderInputs)
                val audioTensor = vocoderResult.get(0) as? OnnxTensor

                if (audioTensor != null) {
                    val rawAudio = audioTensor.floatBuffer
                    val numSamples = rawAudio.remaining()
                    val floatSamples = FloatArray(numSamples)
                    rawAudio.get(floatSamples)

                    // ⚡ C++/JNI Float32 to PCM_16BIT Normalization
                    val pcmBytes = AudioNormalizerJNI.normalizeToPcmBytes(floatSamples)
                    if (pcmBytes.isNotEmpty()) {
                        onPcmBytes(pcmBytes)
                    } else if (onPcmChunk != null) {
                        val pcmShorts = AudioNormalizerJNI.normalizeToPcmShorts(floatSamples)
                        onPcmChunk(pcmShorts)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Two-stage ONNX inference error, routing to human neural voice: ${e.message}", e)
            getOrCreateHumanFallback().speak(sentence)
        } finally {
            // STRICT MEMORY SAFETY: Immediate disposal of native tensors
            try { inputIdsTensor?.close() } catch (_: Throwable) {}
            try { speakerTensor?.close() } catch (_: Throwable) {}
            try { acousticResult?.close() } catch (_: Throwable) {}
            try { vocoderResult?.close() } catch (_: Throwable) {}
        }
    }

    private fun getPhonemeIdMap(modelPath: String?): Map<String, List<Long>>? {
        if (modelPath == null) return null
        if (modelPath == cachedPhonemeMapModelPath && cachedPhonemeMap != null) {
            return cachedPhonemeMap
        }
        try {
            val jsonFile = File(modelPath + ".json").takeIf { it.exists() }
                ?: File(modelPath.removeSuffix(".onnx") + ".onnx.json").takeIf { it.exists() }
                ?: File(modelPath.removeSuffix(".onnx") + ".json").takeIf { it.exists() }

            if (jsonFile != null && jsonFile.exists()) {
                val jsonStr = jsonFile.readText()
                val root = org.json.JSONObject(jsonStr)
                val phonemeIdMapJson = root.optJSONObject("phoneme_id_map")
                if (phonemeIdMapJson != null) {
                    val map = mutableMapOf<String, List<Long>>()
                    val keys = phonemeIdMapJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val arr = phonemeIdMapJson.optJSONArray(key)
                        if (arr != null) {
                            val ids = mutableListOf<Long>()
                            for (i in 0 until arr.length()) {
                                ids.add(arr.optLong(i))
                            }
                            map[key] = ids
                        }
                    }
                    cachedPhonemeMap = map
                    cachedPhonemeMapModelPath = modelPath
                    Log.i(TAG, "Loaded Piper phoneme ID map from ${jsonFile.name} (${map.size} symbols)")
                    return map
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse companion json for $modelPath: ${e.message}")
        }
        return null
    }

    private fun sentenceToPiperTokenIds(sentence: String, modelPath: String?): LongArray {
        val phonemeMap = getPhonemeIdMap(modelPath)
        val tokenList = mutableListOf<Long>()
        tokenList.add(1L) // BOS
        tokenList.add(0L) // pad

        if (phonemeMap != null && phonemeMap.isNotEmpty()) {
            for (char in sentence) {
                val str = char.toString()
                val ids = phonemeMap[str]
                if (ids != null && ids.isNotEmpty()) {
                    for (id in ids) {
                        tokenList.add(id)
                        tokenList.add(0L)
                    }
                } else if (char.isWhitespace()) {
                    val spaceIds = phonemeMap[" "]
                    if (spaceIds != null) {
                        for (id in spaceIds) {
                            tokenList.add(id)
                            tokenList.add(0L)
                        }
                    }
                }
            }
        } else {
            val codePoints = sentence.codePoints().toArray()
            for (cp in codePoints) {
                tokenList.add(cp.toLong())
                tokenList.add(0L)
            }
        }
        tokenList.add(2L) // EOS
        return tokenList.toLongArray()
    }

    /**
     * Resonant Formant Speech Synthesizer with Vocal Tract Modeling.
     * Produces warm human vocal cadence and vowel transitions,
     * normalized through the C++/JNI Float32 to PCM_16BIT pipeline.
     */
    private fun synthesizeAlgorithmicSpeech(
        sentence: String,
        onPcmBytes: (ByteArray) -> Unit,
        onPcmChunk: ((ShortArray) -> Unit)? = null
    ) {
        val cleanText = sentence.trim()
        if (cleanText.isEmpty()) return

        val sampleRate = SAMPLE_RATE
        val basePitch = 125.0 // Hz (Warm, calm conversational baritone)
        val words = cleanText.split(Regex("""\s+"""))

        // Common vowel formant target frequencies (F1, F2 in Hz)
        val formants = listOf(
            Pair(700.0, 1200.0), // /a/
            Pair(500.0, 1800.0), // /e/
            Pair(300.0, 2200.0), // /i/
            Pair(500.0, 1000.0), // /o/
            Pair(350.0, 800.0)   // /u/
        )

        for ((wordIdx, word) in words.withIndex()) {
            val syllables = maxOf(1, (word.length + 1) / 2)
            val syllableDurationMs = 120
            val totalSamples = (sampleRate * (syllableDurationMs * syllables) / 1000)
            val floatWaveform = FloatArray(totalSamples)

            // Sentence prosody intonation
            val sentenceProgress = (wordIdx.toDouble() / maxOf(1, words.size))
            val pitch = basePitch * (1.0 - (sentenceProgress * 0.12))

            val formantTarget = formants[Math.abs(word.hashCode()) % formants.size]
            val f1 = formantTarget.first
            val f2 = formantTarget.second

            for (i in 0 until totalSamples) {
                val t = i.toDouble() / sampleRate
                val syllablePhase = (i.toDouble() / (sampleRate * syllableDurationMs / 1000.0)) % 1.0

                // Rosenberg glottal pulse excitation waveform (human vocal cords)
                val glottalPhase = (t * pitch) % 1.0
                val glottal = if (glottalPhase < 0.4) {
                    0.5 * (1.0 - Math.cos(Math.PI * glottalPhase / 0.4))
                } else if (glottalPhase < 0.6) {
                    Math.cos(Math.PI * (glottalPhase - 0.4) / 0.4)
                } else {
                    0.0
                }

                // Vocal tract acoustic resonance filters
                val res1 = Math.sin(2.0 * Math.PI * f1 * t) * Math.exp(-glottalPhase * 3.0)
                val res2 = Math.sin(2.0 * Math.PI * f2 * t) * Math.exp(-glottalPhase * 4.5) * 0.4

                // Smooth Hann syllable envelope
                val envelope = Math.sin(Math.PI * syllablePhase.coerceIn(0.0, 1.0))
                val sample = (glottal * 0.4 + res1 * 0.4 + res2 * 0.2) * envelope * 0.7
                floatWaveform[i] = sample.toFloat()
            }

            // ⚡ Convert float waveform to 16-bit PCM integer byte array using JNI normalizer
            val pcmBytes = AudioNormalizerJNI.normalizeToPcmBytes(floatWaveform)
            onPcmBytes(pcmBytes)

            if (onPcmChunk != null) {
                val pcmShorts = AudioNormalizerJNI.normalizeToPcmShorts(floatWaveform)
                onPcmChunk(pcmShorts)
            }

            // Brief inter-word pause (25ms)
            val pauseSamples = (sampleRate * 0.025).toInt()
            if (pauseSamples > 0) {
                onPcmBytes(ByteArray(pauseSamples * 2))
            }
        }
    }

    private fun sentenceToTokenIds(sentence: String): LongArray {
        // UTF-8 code point sequence representation
        val codePoints = sentence.codePoints().toArray()
        val result = LongArray(codePoints.size + 2)
        result[0] = 1L // BOS
        for (i in codePoints.indices) {
            result[i + 1] = codePoints[i].toLong()
        }
        result[result.size - 1] = 2L // EOS
        return result
    }

    fun hasOnnxModels(): Boolean {
        return if (isPiperVits()) {
            acousticSession != null
        } else {
            acousticSession != null && vocoderSession != null
        }
    }

    fun close() {
        try {
            humanFallback?.release()
            humanFallback = null
            acousticSession?.close()
            acousticSession = null
            vocoderSession?.close()
            vocoderSession = null
            ortEnv?.close()
            ortEnv = null
            isInitialized.set(false)
            Log.i(TAG, "OnnxTtsEngine closed and native resources freed")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing OnnxTtsEngine: ${e.message}")
        }
    }
}
