package com.synapse.localai

import com.synapse.localai.AtlasLog as Log

/** JNI bridge to libllama_server.so via the compiled synapse_bridge shared library. */
object LlamaServerJNI {

    private const val TAG = "LlamaServerJNI"
    private var loaded = false

    init {
        load()
    }

    fun load(): Boolean {
        if (loaded) return true
        return try {
            // 1. libc++_shared.so
            try { System.loadLibrary("c++_shared") } catch (_: Throwable) {}
            // 2. libomp.so
            System.loadLibrary("omp")
            // 3. libggml-base.so -> libggml.so -> libggml-vulkan.so -> libggml-cpu.so
            System.loadLibrary("ggml-base")
            System.loadLibrary("ggml")
            try { System.loadLibrary("ggml-vulkan") } catch (t: Throwable) { Log.w(TAG, "libggml-vulkan not loaded: ${t.message}") }
            System.loadLibrary("ggml-cpu")
            // 4. libllama.so / libstable-diffusion.so
            System.loadLibrary("llama")
            try { System.loadLibrary("stable-diffusion") } catch (_: Throwable) {}
            System.loadLibrary("mtmd")
            System.loadLibrary("llama_server")
            System.loadLibrary("synapse_bridge")
            try { System.loadLibrary("medlam_engine") } catch (_: Throwable) {}

            loaded = true
            Log.i(TAG, "Native libraries loaded successfully")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native libraries: ${e.message}")
            false
        }
    }

    /** Starts llama-server with the given CLI arguments (excluding argv[0]).
     *  Blocks until the server exits or an error occurs. Call from a background thread. */
    @JvmStatic external fun start(args: Array<String>): Int

    /** Signals the running server to stop gracefully. */
    @JvmStatic external fun stop()

    /** Returns the host:port the server is bound to, or null if not running. */
    @JvmStatic external fun getAddress(): String?

    /** Binds all active server & compute threads to performance cores (Cores 4-7). */
    @JvmStatic external fun bindInferenceThreadsToPerformanceCores(): Boolean

    /** Registers the Vulkan backend into GGML backend registry. */
    @JvmStatic external fun registerVulkanBackend(): Boolean

    /** Checks if the Vulkan backend library is loaded and accessible. */
    @JvmStatic external fun isVulkanSupported(): Boolean

    @JvmStatic
    fun safeRegisterVulkan(): Boolean {
        return try {
            load()
            registerVulkanBackend()
        } catch (t: Throwable) {
            Log.w(TAG, "safeRegisterVulkan error: ${t.message}")
            false
        }
    }

    @JvmStatic
    fun safeIsVulkanSupported(): Boolean {
        return try {
            load()
            isVulkanSupported()
        } catch (t: Throwable) {
            false
        }
    }

    @JvmStatic
    fun safeBindPerformanceCores(): Boolean {
        return try {
            load()
            bindInferenceThreadsToPerformanceCores()
        } catch (t: Throwable) {
            Log.w(TAG, "safeBindPerformanceCores notice: ${t.message}")
            false
        }
    }

    @JvmStatic
    fun safeStart(args: Array<String>): Int {
        return try {
            start(args)
        } catch (t: Throwable) {
            Log.e(TAG, "safeStart error: ${t.message}")
            -1
        }
    }

    @JvmStatic
    fun safeStop() {
        try {
            load()
            stop()
        } catch (t: Throwable) {
            Log.w(TAG, "safeStop error: ${t.message}")
        }
    }
}
