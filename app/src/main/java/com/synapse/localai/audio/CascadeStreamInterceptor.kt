package com.synapse.localai.audio

import android.content.Context
import com.synapse.localai.AtlasLog as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cascade Streaming Subsystem for Real-Time Edge TTS.
 *
 * Implements:
 * 1. Sentence-boundary token buffering (. ? ! , \n) with low-latency clause chunking.
 * 2. Asynchronous background dispatch to ONNX TTS Engine on Dispatchers.IO.
 * 3. Concurrent AudioTrack MODE_STREAM playback masking LLM generation latency.
 * 4. Automatic filtering of <think> blocks, markdown code fences, and tool calls.
 * 5. Instant cancellation and buffer purge on user interruption.
 */
class CascadeStreamInterceptor(
    context: Context? = null,
    private val humanVoiceEngine: HumanNeuralVoiceEngine = HumanNeuralVoiceEngine(context)
) {

    companion object {
        private const val TAG = "CascadeStream"
        private const val MIN_CLAUSE_LENGTH = 32 // Characters before comma/clause triggers dispatch
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var workerJob: Job? = null
    private val sentenceChannel = Channel<String>(capacity = 32)

    private val tokenBuffer = StringBuilder()
    private val inCodeBlock = AtomicBoolean(false)
    private val inThinkingBlock = AtomicBoolean(false)
    private val isEnabled = AtomicBoolean(true)
    private val isRunning = AtomicBoolean(false)

    init {
        startWorker()
    }

    fun applyConfig(config: com.synapse.localai.AgentConfig) {
        setEnabled(config.ttsEnabled)
        humanVoiceEngine.setSpeechRate(config.ttsSpeechRate)
        humanVoiceEngine.setPitch(config.ttsPitch)
        humanVoiceEngine.setLanguageMode(config.ttsVoiceLanguage)
    }

    fun testVoice(sampleText: String? = null) {
        val text = sampleText ?: "مرحباً بك، أنا وكيلك الذكي بنظام Medlam AI. الصوت يعمل بنجاح وبدقة عالية."
        humanVoiceEngine.speak(text)
    }

    @Synchronized
    private fun startWorker() {
        if (isRunning.get() && workerJob?.isActive == true) return

        workerJob = scope.launch {
            isRunning.set(true)
            Log.i(TAG, "Cascade stream worker active on Dispatchers.IO (Google TTS)")

            try {
                for (sentence in sentenceChannel) {
                    if (!isActive) break
                    val cleanText = SpeechTextSanitizer.sanitize(sentence)
                    if (cleanText.isBlank()) continue

                    Log.d(TAG, "⚡ Synthesizing sentence with Google TTS: \"$cleanText\"")
                    if (isActive && isEnabled.get()) {
                        humanVoiceEngine.speak(cleanText)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Worker loop notice: ${e.message}")
            } finally {
                isRunning.set(false)
            }
        }
    }

    /**
     * Intercepts streaming tokens emitted by the LLM.
     * Buffers tokens and dispatches completed sentences instantly.
     */
    fun onTokenChunk(chunk: String) {
        if (!isEnabled.get() || chunk.isEmpty()) return

        synchronized(tokenBuffer) {
            tokenBuffer.append(chunk)
            val current = tokenBuffer.toString()

            // Detect state transitions (thinking tags, code blocks)
            if (current.contains("<think>") || current.contains("<reasoning>")) {
                inThinkingBlock.set(true)
            }
            if (current.contains("</think>") || current.contains("</reasoning>")) {
                inThinkingBlock.set(false)
                tokenBuffer.setLength(0)
                return
            }
            if (inThinkingBlock.get()) return

            // Detect code fences
            if (current.contains("```")) {
                val backtickCount = current.windowed(3).count { it == "```" }
                inCodeBlock.set(backtickCount % 2 != 0)
                if (inCodeBlock.get()) {
                    tokenBuffer.setLength(0)
                    return
                }
            }
            if (inCodeBlock.get()) return

            // Check for sentence/clause boundaries
            val boundaryIndex = findBoundaryIndex(current)
            if (boundaryIndex != -1) {
                val sentence = current.substring(0, boundaryIndex + 1).trim()
                val remaining = current.substring(boundaryIndex + 1)

                tokenBuffer.setLength(0)
                tokenBuffer.append(remaining)

                if (sentence.isNotBlank()) {
                    sentenceChannel.trySend(sentence)
                }
            }
        }
    }

    /**
     * Called when the LLM finishes streaming its entire response.
     * Flushes any remaining sentence fragment.
     */
    fun onStreamComplete() {
        synchronized(tokenBuffer) {
            if (tokenBuffer.isNotEmpty() && !inThinkingBlock.get() && !inCodeBlock.get()) {
                val remaining = tokenBuffer.toString().trim()
                tokenBuffer.setLength(0)
                if (remaining.isNotBlank()) {
                    sentenceChannel.trySend(remaining)
                }
            }
        }
    }

    /**
     * Finds the nearest sentence or early clause boundary in the text.
     */
    private fun findBoundaryIndex(text: String): Int {
        for (i in text.indices) {
            val c = text[i]

            // Major sentence boundaries
            if (c == '.' || c == '?' || c == '!' || c == '\n' || c == '؟' || c == '。' || c == '！' || c == '？') {
                // Ensure dot is not a decimal number (e.g. 3.14)
                if (c == '.' && i > 0 && i + 1 < text.length && text[i - 1].isDigit() && text[i + 1].isDigit()) {
                    continue
                }
                return i
            }

            // Clause boundaries for fast TTFA (< 250ms)
            if (i >= MIN_CLAUSE_LENGTH && (c == ',' || c == ';' || c == ':' || c == '؛')) {
                return i
            }
        }
        return -1
    }

    /**
     * Sanitizes markdown, symbols, and formatting for clean natural vocalization.
     */
    /**
     * Cancels active playback and flushes all audio buffers (e.g. user interruption).
     */
    fun stopAndFlush() {
        synchronized(tokenBuffer) {
            tokenBuffer.setLength(0)
        }
        // Drain pending channel items
        while (sentenceChannel.tryReceive().isSuccess) {}
        humanVoiceEngine.stop()
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
        if (!enabled) {
            stopAndFlush()
        }
    }

    fun isTtsEnabled(): Boolean = isEnabled.get()

    fun release() {
        stopAndFlush()
        workerJob?.cancel()
        humanVoiceEngine.release()
    }
}
