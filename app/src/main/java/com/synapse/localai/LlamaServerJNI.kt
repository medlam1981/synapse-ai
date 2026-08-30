package com.synapse.localai

import android.util.Log

/** JNI bridge to libllama_server.so via the compiled synapse_bridge shared library. */
object LlamaServerJNI {

    private const val TAG = "LlamaServerJNI"
    @Volatile private var loaded = false

    fun load(): Boolean {
        if (loaded) return true
        return synchronized(this) {
            if (loaded) return true
            try {
                // Load dependency chain bottom-up before libllama_server
                System.loadLibrary("ggml-base")
                System.loadLibrary("omp")
                System.loadLibrary("ggml-cpu")
                System.loadLibrary("ggml")
                System.loadLibrary("llama")
                System.loadLibrary("mtmd")
                System.loadLibrary("llama_server")
                System.loadLibrary("synapse_bridge")
                loaded = true
                Log.i(TAG, "Native libraries loaded successfully")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load native libraries: ${t.message}", t)
                false
            }
        }
    }

    fun isLoaded(): Boolean = loaded

    fun safeStop() {
        if (!load()) return
        try {
            stop()
        } catch (t: Throwable) {
            Log.e(TAG, "safeStop error: ${t.message}")
        }
    }

    fun safeGetAddress(): String? {
        if (!load()) return null
        return try {
            getAddress()
        } catch (t: Throwable) {
            null
        }
    }

    fun safeIsRunning(): Boolean {
        val addr = safeGetAddress()
        return !addr.isNullOrBlank()
    }

    fun safeStart(args: Array<String>): Int {
        if (!load()) return -1
        return try {
            start(args)
        } catch (t: Throwable) {
            Log.e(TAG, "safeStart error: ${t.message}")
            -1
        }
    }

    /** Starts llama-server with the given CLI arguments (excluding argv[0]).
     *  Blocks until the server exits or an error occurs. Call from a background thread. */
    @JvmStatic external fun start(args: Array<String>): Int

    /** Signals the running server to stop gracefully. */
    @JvmStatic external fun stop()

    /** Returns the server address (e.g. "http://127.0.0.1:11435") or null if not running. */
    @JvmStatic external fun getAddress(): String?
}
