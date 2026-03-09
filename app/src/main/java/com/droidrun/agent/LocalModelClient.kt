package com.droidrun.agent

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class LocalModelClient private constructor() {

    companion object {
        private const val TAG = "LocalModel"
        private const val DEFAULT_PATH = "/sdcard/Download/gemma3-1b.litertlm"

        @Volatile
        private var _instance: LocalModelClient? = null
        val shared: LocalModelClient
            get() = _instance ?: synchronized(this) {
                _instance ?: LocalModelClient().also { _instance = it }
            }
    }

    private var engine: Engine? = null
    val isLoaded: Boolean get() = engine != null

    suspend fun loadModel(context: Context, path: String = DEFAULT_PATH) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Loading model from: $path")
            val config = EngineConfig(
                modelPath = path,
                backend = Backend.CPU,
                cacheDir = context.cacheDir.path
            )
            val eng = Engine(config)
            eng.initialize()
            engine = eng
            Log.d(TAG, "Model loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${e.message}", e)
            engine = null
            throw e
        }
    }

    suspend fun generateResponse(
        prompt: String,
        systemInstruction: String = ""
    ): String = withContext(Dispatchers.IO) {
        val eng = engine ?: throw IllegalStateException("Model not loaded")

        try {
            Log.d(TAG, "Generating response (${prompt.length} chars)")
            val convConfig = if (systemInstruction.isNotBlank()) {
                ConversationConfig(
                    systemMessage = Message.of(systemInstruction),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)
                )
            } else {
                ConversationConfig(
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)
                )
            }
            eng.createConversation(convConfig).use { conversation ->
                val response = conversation.sendMessage(Message.of(prompt))
                Log.d(TAG, "Response: ${response.toString().length} chars")
                response.toString().trim()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Generation failed: ${e.message}", e)
            throw e
        }
    }

    fun generateResponseStream(
        prompt: String,
        systemInstruction: String = ""
    ): Flow<String> = flow {
        val eng = engine ?: throw IllegalStateException("Model not loaded")

        Log.d(TAG, "Streaming response (${prompt.length} chars)")
        val convConfig = if (systemInstruction.isNotBlank()) {
            ConversationConfig(
                systemMessage = Message.of(systemInstruction),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)
            )
        } else {
            ConversationConfig(
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)
            )
        }
        eng.createConversation(convConfig).use { conversation ->
            conversation.sendMessageAsync(Message.of(prompt))
                .collect { chunk ->
                    val text = chunk.toString()
                    if (text.isNotEmpty()) {
                        emit(text)
                    }
                }
        }
        Log.d(TAG, "Streaming complete")
    }.flowOn(Dispatchers.IO)

    fun close() {
        try {
            engine?.close()
        } catch (_: Exception) {}
        engine = null
        Log.d(TAG, "Model closed")
    }
}
