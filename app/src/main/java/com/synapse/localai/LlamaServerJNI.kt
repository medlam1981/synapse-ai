package com.synapse.localai

import android.util.Log

/** JNI bridge to libllama_server.so via the compiled synapse_bridge shared library. */
object LlamaServerJNI {

    private const val TAG = "LlamaServerJNI"
    private var loaded = false

    fun load(): Boolean {
        if (loaded) return true
        return try {
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
            stop()
        } catch (t: Throwable) {
            Log.w(TAG, "safeStop error: ${t.message}")
        }
    }
}
