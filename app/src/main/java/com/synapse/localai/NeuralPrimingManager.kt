package com.synapse.localai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Background Neural Priming & Persistent Persona KV Cache Engine
 *
 * 1. Boot-Time / Background Runtime Priming:
 *    Pre-loads the configured GGUF model into memory and warms up the inference context
 *    ahead of user interaction.
 * 2. Persistent KV Cache / Prompt Slot State:
 *    Evaluates and caches the key-value states of the active modular persona (.md)
 *    and core identity.
 * 3. Zero Cold-Start Reasoning Delay:
 *    Eliminates prefill latency, cutting TTFT to near-zero.
 */
object NeuralPrimingManager {
    private const val TAG = "NeuralPriming"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var isPriming = false

    fun primeNeuralCore(context: Context, forceReload: Boolean = false) {
        if (isPriming) return
        isPriming = true

        scope.launch {
            try {
                val appContext = context.applicationContext
                val prefs = appContext.getSharedPreferences("agent_config", Context.MODE_PRIVATE)
                val modelTypeStr = prefs.getString("preferred_model_type", ModelType.GEMINI.name)
                val modelType = try { ModelType.valueOf(modelTypeStr!!) } catch (_: Exception) { ModelType.GEMINI }

                var localPath = prefs.getString("local_model_path", "") ?: ""
                if (localPath.isBlank()) {
                    val cachedModels = ModelScanner.getBaseModels(appContext, forceRescan = false)
                    if (cachedModels.isNotEmpty()) {
                        localPath = cachedModels.first().absolutePath
                    }
                }

                if (localPath.isBlank() || !File(localPath).exists()) {
                    Log.d(TAG, "No local GGUF model available to prime.")
                    isPriming = false
                    return@launch
                }

                // If user prefers Gemini and has keys, don't hog RAM unless GGUF is requested
                val geminiKeys = prefs.getString("gemini_api_keys", "") ?: ""
                if (modelType == ModelType.GEMINI && geminiKeys.isNotBlank() && !forceReload) {
                    Log.d(TAG, "Gemini Cloud active with valid keys; skipping background GGUF priming.")
                    isPriming = false
                    return@launch
                }

                Log.d(TAG, "⚡ Background Neural Priming initiated for model: $localPath")

                val client = GGUFModelClient().apply {
                    serverHost = prefs.getString("server_host", "0.0.0.0") ?: "0.0.0.0"
                    serverPort = prefs.getInt("server_port", 8088)
                    contextLength = prefs.getInt("context_length", 8192)
                    threadCount = prefs.getInt("thread_count", 4)
                    maxTokens = -1
                    temperature = prefs.getFloat("temperature", 0.7f)
                    draftModelPath = prefs.getString("draft_model_path", null)?.ifBlank { null }
                    gpuLayers = prefs.getInt("gpu_layers", 0)
                    useMmap = prefs.getBoolean("use_mmap", true)
                }

                client.loadModel(
                    context = appContext,
                    path = localPath,
                    onStatus = { Log.d(TAG, "Status: $it") },
                    onProgress = { progress, text -> Log.d(TAG, "Progress $progress: $text") },
                    onLoaded = {
                        if (!client.isLoaded) {
                            isPriming = false
                            return@loadModel
                        }
                        scope.launch {
                            try {
                                // Compile active persona system prompt prefix
                                val activePersonas = PersonaManager.getActivePersonasContent(appContext)
                                val baseIdentity = "You are MEDLAM, a powerful autonomous local AI agent operating on Android."
                                val primingPrefix = "$baseIdentity\n\n$activePersonas"
                                
                                val warmed = client.warmupPersonaKVCache(primingPrefix)
                                Log.d(TAG, "✅ Neural Core Primed & Persona KV Cache Active (success: $warmed)")
                            } catch (e: Exception) {
                                Log.e(TAG, "KV cache priming error: ${e.message}")
                            } finally {
                                isPriming = false
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Neural Priming failed: ${e.message}")
                isPriming = false
            }
        }
    }
}
