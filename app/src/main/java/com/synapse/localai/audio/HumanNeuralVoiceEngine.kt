package com.synapse.localai.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-Fidelity Human Neural Voice Engine.
 *
 * Replaces robotic algorithmic synthesizer with natural, intelligible human voice:
 * 1. Leverages Android / Google Neural Speech Engine (com.google.android.tts).
 * 2. Real-time language switching: Natural human Arabic (العربية الفصحى) and English (US).
 * 3. High-quality neural voice selection (WaveNet / neural timbre).
 * 4. Pipelined QUEUE_ADD playback for seamless Cascade Streaming.
 * 5. Instant cancellation on user interruption.
 */
class HumanNeuralVoiceEngine(private val context: Context?) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "HumanNeuralVoice"
        private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
    }

    private var tts: TextToSpeech? = null
    private val isReady = AtomicBoolean(false)
    private val pendingQueue = ConcurrentLinkedQueue<String>()
    private var utteranceCounter = 0L

    init {
        initializeTts()
    }

    private fun initializeTts() {
        val ctx = context?.applicationContext ?: return
        try {
            // First try Google's Neural TTS engine
            tts = TextToSpeech(ctx, this, GOOGLE_TTS_PACKAGE)
            Log.i(TAG, "Initializing TextToSpeech with Google TTS engine")
        } catch (e: Exception) {
            Log.w(TAG, "Google TTS engine init failed, falling back to default: ${e.message}")
            try {
                tts = TextToSpeech(ctx, this)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to initialize default TextToSpeech: ${e2.message}", e2)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            isReady.set(true)
            Log.i(TAG, "TextToSpeech initialized successfully (Default engine: ${engine.defaultEngine})")

            // Configure natural human conversational settings
            configureHumanTimbre(engine)

            // Setup utterance listener for logging and state tracking
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "▶ Speaking: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "✓ Completed utterance: $utteranceId")
                }

                override fun onError(utteranceId: String?) {
                    Log.w(TAG, "⚠ Utterance error: $utteranceId")
                }
            })

            // Flush any sentences queued during initialization
            drainPendingQueue()
        } else {
            Log.e(TAG, "TextToSpeech initialization failed with status: $status")
            isReady.set(false)
        }
    }

    @Volatile
    private var currentSpeechRate: Float = 1.0f

    @Volatile
    private var currentPitch: Float = 0.88f // Deep, resonant masculine timbre (Atlas-AI persona)

    @Volatile
    private var currentLanguageMode: String = "AUTO" // "AUTO", "ARABIC", "ENGLISH", etc.

    fun setSpeechRate(rate: Float) {
        currentSpeechRate = rate.coerceIn(0.5f, 2.0f)
        try {
            tts?.setSpeechRate(currentSpeechRate)
        } catch (_: Exception) {}
    }

    fun setPitch(pitch: Float) {
        currentPitch = pitch.coerceIn(0.5f, 2.0f)
        try {
            tts?.setPitch(currentPitch)
        } catch (_: Exception) {}
    }

    fun setLanguageMode(mode: String) {
        currentLanguageMode = mode
    }

    /**
     * Configures pitch, rate, and selects the most natural human neural voice.
     */
    private fun configureHumanTimbre(engine: TextToSpeech) {
        try {
            // Natural human speech rate (1.0 = conversational)
            engine.setSpeechRate(currentSpeechRate)
            // Masculine resonance pitch
            engine.setPitch(currentPitch)

            // Auto-select highest quality male neural voice
            selectBestVoice(engine, Locale.getDefault())
        } catch (e: Exception) {
            Log.w(TAG, "Error configuring human timbre: ${e.message}")
        }
    }

    private fun isMaleVoice(voice: Voice): Boolean {
        val name = voice.name.lowercase()
        val features = voice.features?.map { it.lowercase() } ?: emptyList()

        if (features.any { it.contains("gender=male") || it == "male" }) return true
        if (features.any { it.contains("gender=female") || it == "female" }) return false

        if (name.contains("male") || name.contains("man") || name.contains("boy") || name.contains("guy") || name.contains("masculine")) return true
        if (name.contains("female") || name.contains("woman") || name.contains("girl") || name.contains("feminine")) return false

        // Google TTS standard male voice suffix codes
        val code = name.substringAfterLast("-x-").substringBefore("-")
        val knownMaleCodes = setOf("ard", "arc", "are", "iol", "iom", "iob", "iog", "tpd", "tpc", "bfb", "bfc", "gwa", "vda", "vdb", "deg", "deh", "eee", "eed", "rud", "rue", "trc", "trd")
        if (knownMaleCodes.any { code.contains(it) }) return true

        return false
    }

    private fun isFemaleVoice(voice: Voice): Boolean {
        val name = voice.name.lowercase()
        val features = voice.features?.map { it.lowercase() } ?: emptyList()
        if (features.any { it.contains("gender=female") || it == "female" }) return true
        if (name.contains("female") || name.contains("woman") || name.contains("girl") || name.contains("feminine")) return true

        val code = name.substringAfterLast("-x-").substringBefore("-")
        val knownFemaleCodes = setOf("ara", "arb", "sfg", "iok", "iof", "bfa", "vfc", "dfa", "eea")
        return knownFemaleCodes.any { code.contains(it) }
    }

    /**
     * Finds and applies the highest-fidelity male neural voice for the given locale.
     */
    private fun selectBestVoice(engine: TextToSpeech, locale: Locale) {
        try {
            val availableVoices = engine.voices ?: return
            val matchingVoices = availableVoices.filter { 
                it.locale.language.equals(locale.language, ignoreCase = true) 
            }

            if (matchingVoices.isNotEmpty()) {
                // Priority: Masculine neural voice, high quality, low latency
                val bestVoice = matchingVoices.maxByOrNull { voice ->
                    var score = 0
                    val male = isMaleVoice(voice)
                    val female = isFemaleVoice(voice)
                    if (male) score += 300
                    else if (female) score -= 300

                    if (voice.quality >= Voice.QUALITY_VERY_HIGH) score += 50
                    else if (voice.quality >= Voice.QUALITY_HIGH) score += 30
                    if (voice.latency <= Voice.LATENCY_NORMAL) score += 20
                    if (!voice.isNetworkConnectionRequired) score += 20
                    val name = voice.name.lowercase()
                    if (name.contains("neural") || name.contains("natural") || name.contains("wavenet")) score += 30
                    score
                }

                if (bestVoice != null) {
                    engine.voice = bestVoice
                    engine.setPitch(currentPitch)
                    Log.i(TAG, "Selected male neural voice: ${bestVoice.name} (locale=${bestVoice.locale}, pitch=$currentPitch)")
                    return
                }
            }
            engine.language = locale
            engine.setPitch(currentPitch)
        } catch (e: Exception) {
            Log.w(TAG, "Voice selection notice: ${e.message}")
            try {
                engine.language = locale
                engine.setPitch(currentPitch)
            } catch (_: Exception) {}
        }
    }

    /**
     * Intelligent Multilingual Locale Detector:
     * Supports Arabic, English, French, German, Spanish, Russian, Chinese, Japanese, Korean, Italian, Turkish, etc.
     */
    private fun detectTextLocale(text: String): Locale {
        if (currentLanguageMode.uppercase() == "ARABIC") return Locale("ar")
        if (currentLanguageMode.uppercase() == "ENGLISH") return Locale.US

        // 1. Arabic & Persian / Urdu script
        if (text.any { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\u08A0'..'\u08FF' }) {
            return Locale("ar")
        }
        // 2. Cyrillic (Russian, Ukrainian)
        if (text.any { it in '\u0400'..'\u04FF' }) {
            return Locale("ru")
        }
        // 3. Chinese (CJK Unified)
        if (text.any { it in '\u4E00'..'\u9FFF' }) {
            return Locale.SIMPLIFIED_CHINESE
        }
        // 4. Japanese (Hiragana & Katakana)
        if (text.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }) {
            return Locale.JAPANESE
        }
        // 5. Korean (Hangul)
        if (text.any { it in '\uAC00'..'\uD7AF' }) {
            return Locale.KOREAN
        }
        // 6. Latin-based European languages:
        val lower = " " + text.lowercase().replace("[^a-zA-Záéíóúñäöüßàèùç]".toRegex(), " ") + " "
        if (lower.contains(" c'est ") || lower.contains(" qu'est ") || lower.contains(" avec ") || lower.contains(" pour ") || lower.contains(" dans ")) {
            return Locale.FRENCH
        }
        if (lower.contains("ä") || lower.contains("ö") || lower.contains("ü") || lower.contains("ß") || lower.contains(" nicht ") || lower.contains(" und ")) {
            return Locale.GERMAN
        }
        if (lower.contains("ñ") || lower.contains("¿") || lower.contains("¡") || lower.contains(" para ") || lower.contains(" como ")) {
            return Locale("es")
        }
        if (lower.contains(" anche ") || lower.contains(" grazie ") || lower.contains(" questo ")) {
            return Locale.ITALIAN
        }
        if (lower.contains("ğ") || lower.contains("ş") || lower.contains("ı") || lower.contains(" evet ") || lower.contains(" hayır ")) {
            return Locale("tr")
        }

        return Locale.US
    }

    /**
     * Speaks a sentence using natural human neural speech synthesis.
     * Queues sentences seamlessly for cascade streaming.
     */
    fun speak(sentence: String) {
        val text = sentence.trim()
        if (text.isBlank()) return

        if (!isReady.get() || tts == null) {
            pendingQueue.offer(text)
            return
        }

        val engine = tts ?: return

        try {
            // Apply current speech rate and pitch
            try {
                engine.setSpeechRate(currentSpeechRate)
                engine.setPitch(currentPitch)
            } catch (_: Exception) {}

            // Detect or apply target language across all languages
            val targetLocale = detectTextLocale(text)

            // Adjust voice/language if needed
            if (engine.voice?.locale?.language != targetLocale.language) {
                selectBestVoice(engine, targetLocale)
            }

            val utteranceId = "synapse_tts_${++utteranceCounter}"
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }

            val result = engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
            if (result == TextToSpeech.SUCCESS) {
                Log.d(TAG, "⚡ Queued human voice sentence: \"$text\" (locale=${targetLocale.language})")
            } else {
                Log.w(TAG, "TextToSpeech.speak returned error code: $result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error speaking sentence: ${e.message}", e)
        }
    }

    /**
     * Immediately stops active speech and clears queued sentences.
     */
    fun stop() {
        pendingQueue.clear()
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TTS: ${e.message}")
        }
    }

    private fun drainPendingQueue() {
        while (pendingQueue.isNotEmpty()) {
            val sentence = pendingQueue.poll() ?: break
            speak(sentence)
        }
    }

    fun isSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }

    fun release() {
        stop()
        try {
            tts?.shutdown()
            tts = null
            isReady.set(false)
            Log.i(TAG, "HumanNeuralVoiceEngine released")
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down TTS: ${e.message}")
        }
    }
}
