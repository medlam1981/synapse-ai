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
    /**
     * Called for each generated token piece.
     * @return true to continue generation, false to cancel.
     */
    fun onToken(piece: String): Boolean

    /**
     * Called when generation finishes.
     */
    fun onComplete(tokensGenerated: Int, tokensPerSecond: Double)

    /**
     * Called if a native error occurs during generation.
     */
    fun onError(message: String)
}

/**
 * High-Performance Native LLM Inference Engine Bridge using llama.cpp.
 *
 * Implements:
 * 1. Zero-copy memory-mapped GGUF loading (mmap).
 * 2. Speculative Decoding Pipeline (Dual-Model: Target 7B/13B + Draft ~0.5B for 3x-4x speedup).
 * 3. Performance core affinity binding (ARM big.LITTLE) and thermal throttling protection.
 * 4. Ultra-quantization support (Q2_K, IQ2_XXS, Q3_K_M).
 * 5. Exception-safe JNI streaming interface.
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
            System.loadLibrary("libc++")
            System.loadLibrary("ggml-base")
            System.loadLibrary("omp")
            System.loadLibrary("ggml-cpu")
            System.loadLibrary("ggml")
            System.loadLibrary("llama")
            System.loadLibrary("medlam_engine")
            isLoaded = true
            Log.i(TAG, "Native MedlamEngine libraries loaded successfully")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native libraries: ${e.message}", e)
            false
        }
    }

    // =========================================================================
    // Core Specification JNI Functions
    // =========================================================================

    /**
     * Initializes the target model and optional draft model with zero-copy mmap.
     *
     * @param model_path Absolute path to the Target GGUF model (e.g. 7B/13B Q2_K/Q3_K/Q4_K).
     * @param draft_model_path Optional path to the Draft GGUF model (~0.5B parameters) for speculative decoding. Pass null/empty to disable.
     * @param thread_count Number of compute threads (defaults to physical performance core count).
     * @return true if initialized successfully, false otherwise.
     */
    @JvmStatic
    external fun init_engine(
        model_path: String,
        draft_model_path: String?,
        thread_count: Int
    ): Boolean

    /**
     * Executes streaming inference using the speculative decoding pipeline.
     *
     * @param prompt The tokenized prompt text.
     * @param callback_handler Callback receiver for streaming tokens and completion metrics.
     * @return Total tokens generated, or -1 on error.
     */
    @JvmStatic
    external fun generate_stream(
        prompt: String,
        callback_handler: NativeInferenceCallback
    ): Int

    /**
     * Signals the active native generation loop to immediately stop.
     */
    @JvmStatic
    external fun stop_generation()

    /**
     * Releases all native memory, models, contexts, and samplers.
     */
    @JvmStatic
    external fun free_engine()

    // =========================================================================
    // Extended CamelCase Aliases & Diagnostics
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

    @JvmStatic
    external fun generateStream(
        prompt: String,
        callback_handler: NativeInferenceCallback
    ): Int

    @JvmStatic
    external fun stopGeneration()

    @JvmStatic
    external fun freeEngine()

    @JvmStatic
    external fun getModelInfo(): String

    @JvmStatic
    external fun isInitialized(): Boolean

    // =========================================================================
    // Reactive Kotlin Flow API for Jetpack Compose Integration
    // =========================================================================

    /**
     * High-level reactive Flow that emits generated token pieces in real time,
     * handling background thread dispatching, cancellation, and metrics logging.
     */
    fun stream(
        prompt: String,
        onMetrics: ((tokens: Int, tokensPerSec: Double) -> Unit)? = null
    ): Flow<String> = callbackFlow {
        val callback = object : NativeInferenceCallback {
            override fun onToken(piece: String): Boolean {
                val sent = trySend(piece).isSuccess
                return sent
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

        // Execute on Dispatchers.IO background thread
        withContext(Dispatchers.IO) {
            generate_stream(prompt, callback)
        }

        awaitClose {
            stop_generation()
        }
    }.flowOn(Dispatchers.IO)
}
