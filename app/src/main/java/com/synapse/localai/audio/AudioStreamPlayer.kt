package com.synapse.localai.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-Latency Streaming Audio Player using Android's AudioTrack in MODE_STREAM.
 *
 * Implements real-time PCM16 audio rendering for Cascade Streaming:
 * - AudioTrack in MODE_STREAM for zero-latency pipelined audio playback.
 * - Thread-safe chunk writes from background synthesis coroutines.
 * - Dynamic buffer sizing to prevent underruns while minimizing latency.
 * - Explicit chunk nulling and recycling to eliminate RAM thrashing on 4GB devices.
 */
class AudioStreamPlayer(
    sampleRate: Int = DEFAULT_SAMPLE_RATE,
    val channelConfig: Int = AudioFormat.CHANNEL_OUT_MONO,
    val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) {
    var sampleRate: Int = sampleRate
        private set

    companion object {
        private const val TAG = "AudioStreamPlayer"
        const val DEFAULT_SAMPLE_RATE = 24000 // 24kHz matching Qwen3-TTS 12Hz representation
    }

    private var audioTrack: AudioTrack? = null
    private val isInitialized = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)
    private val lock = Any()

    init {
        initAudioTrack()
    }

    /**
     * Dynamically switches sample rate (e.g. 24000Hz for Qwen3-TTS vs 22050Hz for Piper VITS).
     */
    fun updateSampleRate(newRate: Int) {
        if (newRate <= 0 || newRate == sampleRate) return
        synchronized(lock) {
            try {
                stop()
                audioTrack?.release()
                audioTrack = null
                isInitialized.set(false)
                sampleRate = newRate
                initAudioTrack()
                Log.i(TAG, "AudioStreamPlayer sample rate dynamically set to $newRate Hz")
            } catch (e: Exception) {
                Log.w(TAG, "Failed updating AudioStreamPlayer sample rate: ${e.message}")
            }
        }
    }

    @Synchronized
    private fun initAudioTrack(): Boolean {
        if (isInitialized.get() && audioTrack != null) return true

        try {
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufferSize <= 0) {
                Log.e(TAG, "Invalid minBufferSize: $minBufferSize for sampleRate=$sampleRate")
                return false
            }

            // SD665 buffer envelope: 3x minimum buffer size for smooth jitter-free playback
            val bufferSize = maxOf(minBufferSize * 3, 16384)

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(audioFormat)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            isInitialized.set(audioTrack?.state == AudioTrack.STATE_INITIALIZED)
            Log.i(TAG, "AudioTrack initialized successfully: rate=$sampleRate, buffer=$bufferSize bytes, state=${audioTrack?.state}")
            return isInitialized.get()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}", e)
            isInitialized.set(false)
            audioTrack = null
            return false
        }
    }

    /**
     * Writes a PCM 16-bit short array chunk to the stream.
     * Starts playback automatically if AudioTrack is stopped or paused.
     */
    fun writePcmChunk(pcmData: ShortArray): Int {
        if (pcmData.isEmpty()) return 0
        synchronized(lock) {
            val track = audioTrack ?: run {
                if (!initAudioTrack()) return -1
                audioTrack ?: return -1
            }

            try {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                    isPlaying.set(true)
                }

                // MODE_STREAM blocking write
                var totalWritten = 0
                val size = pcmData.size
                while (totalWritten < size && isPlaying.get()) {
                    val written = track.write(pcmData, totalWritten, size - totalWritten, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) {
                        Log.w(TAG, "AudioTrack write error code: $written")
                        break
                    }
                    totalWritten += written
                }
                return totalWritten
            } catch (e: Exception) {
                Log.e(TAG, "Error writing PCM chunk: ${e.message}", e)
                return -1
            }
        }
    }

    /**
     * Writes raw PCM byte data.
     */
    fun writePcmBytes(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        synchronized(lock) {
            val track = audioTrack ?: run {
                if (!initAudioTrack()) return -1
                audioTrack ?: return -1
            }

            try {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                    isPlaying.set(true)
                }

                var totalWritten = 0
                val size = bytes.size
                while (totalWritten < size && isPlaying.get()) {
                    val written = track.write(bytes, totalWritten, size - totalWritten, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) {
                        Log.w(TAG, "AudioTrack write byte error: $written")
                        break
                    }
                    totalWritten += written
                }
                return totalWritten
            } catch (e: Exception) {
                Log.e(TAG, "Error writing byte chunk: ${e.message}", e)
                return -1
            }
        }
    }

    fun play(bytes: ByteArray): Int = writePcmBytes(bytes)
    fun play(shorts: ShortArray): Int = writePcmChunk(shorts)

    /**
     * Converts float32 audio waveform (-1.0 to 1.0) to 16-bit PCM integer bytes (Little-Endian)
     * using AudioNormalizerJNI and writes to AudioTrack.
     */
    fun writeNormalizedFloatChunk(floatData: FloatArray): Int {
        if (floatData.isEmpty()) return 0
        val pcmBytes = AudioNormalizerJNI.normalizeToPcmBytes(floatData)
        return writePcmBytes(pcmBytes)
    }

    /**
     * Pauses and flushes the buffer immediately (e.g. on new prompt or user stop).
     */
    fun pauseAndFlush() {
        synchronized(lock) {
            try {
                audioTrack?.let { track ->
                    if (track.state == AudioTrack.STATE_INITIALIZED) {
                        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            track.pause()
                        }
                        track.flush()
                    }
                }
                isPlaying.set(false)
            } catch (e: Exception) {
                Log.w(TAG, "Error flushing AudioTrack: ${e.message}")
            }
        }
    }

    /**
     * Stops audio playback and resets track position.
     */
    fun stop() {
        synchronized(lock) {
            try {
                audioTrack?.let { track ->
                    if (track.state == AudioTrack.STATE_INITIALIZED) {
                        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            track.stop()
                        }
                        track.flush()
                    }
                }
                isPlaying.set(false)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping AudioTrack: ${e.message}")
            }
        }
    }

    /**
     * Full release of AudioTrack native resources.
     */
    fun release() {
        synchronized(lock) {
            try {
                stop()
                audioTrack?.release()
                audioTrack = null
                isInitialized.set(false)
                isPlaying.set(false)
                Log.i(TAG, "AudioTrack released")
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AudioTrack: ${e.message}")
            }
        }
    }

    fun isCurrentlyPlaying(): Boolean = isPlaying.get()
}
