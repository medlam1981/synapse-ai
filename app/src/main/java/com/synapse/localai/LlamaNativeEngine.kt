package com.synapse.localai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Interface matching the C++ native JNI callback contract.
 */
interface NativeInferenceCallback {
    fun onToken(piece: String): Boolean
    fun onComplete(tokensGenerated: Int, tokensPerSecond: Double)
    fun onError(message: String)
}

/**
 * High-Performance Native LLM Inference Engine Bridge using llama.cpp & Vulkan.
 */
object LlamaNativeEngine {

    private const val TAG = "LlamaNativeEngine"
    private var isLoaded = false

    /**
     * Loads required native shared libraries in topological dependency order.
     */
    @Synchronized
    fun load(): Boolean {
        if (isLoaded) return true
        return try {
            // 1. Core C++ runtime
            try { System.loadLibrary("c++_shared") } catch (e: Throwable) {
                Log.w(TAG, "c++_shared load skipped: ${e.message}")
            }
            
            // 2. OpenMP (if present)
            try { System.loadLibrary("omp") } catch (e: Throwable) {
                Log.w(TAG, "omp load skipped: ${e.message}")
            }

            // 3. GGML Core Architecture
            System.loadLibrary("ggml-base")
            System.loadLibrary("ggml")

            // 4. Hardware Accelerators: Vulkan (GPU) then CPU fallback
            try { 
                System.loadLibrary("ggml-vulkan") 
                Log.i(TAG, "Vulkan acceleration backend loaded successfully")
            } catch (e: Throwable) {
                Log.w(TAG, "Vulkan backend not available, falling back to CPU: ${e.message}")
            }
            System.loadLibrary("ggml-cpu")

            // 5. High-Level Engine & Multi-Modal Tensors
            try { System.loadLibrary("mtmd") } catch (_: Throwable) {}
            System.loadLibrary("llama")
            try { System.loadLibrary("stable-diffusion") } catch (_: Throwable) {}
            try { System.loadLibrary("sd-cli") } catch (_: Throwable) {}

            // 6. JNI Bridge (FIXED: loads synapse_bridge instead of medlam_engine)
            System.loadLibrary("synapse_bridge")

            isLoaded = true
            Log.i(TAG, "All Synapse Native Libraries loaded successfully!")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native libraries: ${e.message}", e)
            false
        }
    }

    // =========================================================================
    // Core Specification JNI Functions
    // =========================================================================

    @JvmStatic
    external fun initEngine(
        model_path: String,
        draft_model_path: String?,
        thread_count: Int,
        gpu_layers: Int = 0,
        context_size: Int = 4096,
        use_mmap: Boolean = true
    ): Boolean

    // Legacy overload mapping to unified initEngine
    @JvmStatic
    fun init_engine(
        model_path: String,
        draft_model_path: String?,
        thread_count: Int
    ): Boolean {
        // Automatically default to Vulkan GPU layers if hardware is present
        return initEngine(
            model_path = model_path,
            draft_model_path = draft_model_path,
            thread_count = thread_count,
            gpu_layers = 33, // Offload layers to Vulkan GPU
            context_size = 4096,
            use_mmap = true
        )
    }

    @JvmStatic
    external fun generate_stream(
        prompt: String,
        callback_handler: NativeInferenceCallback
    ): Int

    @JvmStatic
    external fun stop_generation()

    @JvmStatic
    external fun abort()

    @JvmStatic
    external fun isAborted(): Boolean

    @JvmStatic
    external fun resetAbort()

    @JvmStatic
    external fun free_engine()

    @JvmStatic
    external fun pinToBigCores(): Boolean

    @JvmStatic
    external fun bindToPerformanceCores(): Boolean

    @JvmStatic
    external fun bindAllThreadsToPerformanceCores(): Boolean

    @JvmStatic
    external fun clearKvCache()

    @JvmStatic
    external fun getModelInfo(): String

    @JvmStatic
    external fun isInitialized(): Boolean

    @JvmStatic
    @Synchronized
    fun unloadModel() {
        Log.i(TAG, "Unloading native LLM engine to flush RAM/VRAM...")
        try {
            abort()
            stop_generation()
            free_engine()
        } catch (t: Throwable) {
            Log.e(TAG, "Exception in free_engine: ${t.message}", t)
        }
        try {
            GGUFModelClient.stopGlobalServer()
        } catch (t: Throwable) {
            Log.e(TAG, "Exception in stopGlobalServer: ${t.message}", t)
        }
        try {
            PageCacheAdvisor.adviseModelIdle()
        } catch (t: Throwable) {
            Log.e(TAG, "Exception in adviseModelIdle: ${t.message}", t)
        }
        System.gc()
        System.runFinalization()
        Log.i(TAG, "Native LLM completely flushed from memory.")
    }

    // =========================================================================
    // Reactive Kotlin Flow with Hardware Pinning & Robust Token Stripping
    // =========================================================================

    private val STOP_SEQUENCES = setOf(
        "<end_of_turn>",
        "<start_of_turn>",
        "<eos>",
        "<|end_of_turn|>",
        "<|im_end|>",
        "end_of_turn"
    )

    fun stream(
        prompt: String,
        onMetrics: ((tokens: Int, tokensPerSec: Double) -> Unit)? = null
    ): Flow<String> = callbackFlow {
        // 1. Force hardware binding strictly to Big Cores (Cores 4-7) before generation starts
        try {
            pinToBigCores()
            bindToPerformanceCores()
        } catch (e: Throwable) {
            Log.w(TAG, "CPU Core binding warning: ${e.message}")
        }

        resetAbort()

        val callback = object : NativeInferenceCallback {
            override fun onToken(piece: String): Boolean {
                // Check for stop tokens anywhere in the piece
                for (stop in STOP_SEQUENCES) {
                    if (piece.contains(stop)) {
                        // Strip out the stop token to keep output clean
                        val cleanPiece = piece.replace(stop, "").replace("<", "").replace(">", "").replace("/", "")
                        if (cleanPiece.isNotEmpty()) {
                            trySend(cleanPiece)
                        }
                        // Instantly abort native compute loop
                        abort()
                        stop_generation()
                        return false
                    }
                }
                return trySend(piece).isSuccess
            }

            override fun onComplete(tokensGenerated: Int, tokensPerSecond: Double) {
                Log.i(TAG, "Inference complete: $tokensGenerated tokens (%.2f tok/s)".format(tokensPerSecond))
                onMetrics?.invoke(tokensGenerated, tokensPerSecond)
                close()
            }

            override fun onError(message: String) {
                Log.e(TAG, "Native inference error: $message")
                close(RuntimeException(message))
            }
        }

        withContext(Dispatchers.IO) {
            generate_stream(prompt, callback)
        }

        awaitClose {
            abort()
            stop_generation()
        }
    }.flowOn(Dispatchers.IO)
}

val llamaNativeEngine = LlamaNativeEngine
