package com.synapse.localai.audio

import android.util.Log

/**
 * High-Performance Audio Normalizer Bridge.
 *
 * Implements C++/JNI Float32 to PCM_16BIT Normalization:
 * - Reads raw vocoder output waveforms (-1.0f to 1.0f).
 * - Hard clips samples to [-1.0f, 1.0f] to eliminate digital crackle and overflow.
 * - Scales to 16-bit signed PCM integer range ([-32767, 32767]).
 * - Produces Little-Endian byte array / short array ready for direct AudioTrack MODE_STREAM playback.
 * - Eliminates quantization mismatches and sonar/beeping artifacts entirely.
 */
object AudioNormalizerJNI {

    private const val TAG = "AudioNormalizerJNI"
    private var isNativeLoaded = false

    init {
        loadNative()
    }

    fun loadNative(): Boolean {
        if (isNativeLoaded) return true
        return try {
            System.loadLibrary("medlam_engine")
            isNativeLoaded = true
            Log.i(TAG, "libmedlam_engine.so (AudioNormalizer) loaded successfully")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Native normalizer not loaded: ${t.message}")
            false
        }
    }


    /**
     * C++ Float to PCM_16BIT Conversion JNI method.
     * Returns a Little-Endian byte array suitable for AudioTrack.write(ByteArray).
     */
    @JvmStatic
    external fun convertFloatToPcm16(onnxAudioOutput: FloatArray): ByteArray?

    /**
     * C++ Float to PCM_16BIT ShortArray JNI method.
     */
    @JvmStatic
    external fun convertFloatToPcmShorts(onnxAudioOutput: FloatArray): ShortArray?

    /**
     * Normalizes float32 audio waveform (-1.0 to 1.0) to 16-bit PCM Little-Endian ByteArray.
     * Uses native C++ JNI SIMD implementation with zero-overhead fallback.
     */
    fun normalizeToPcmBytes(floats: FloatArray): ByteArray {
        if (floats.isEmpty()) return ByteArray(0)

        if (isNativeLoaded) {
            try {
                val nativeBytes = convertFloatToPcm16(floats)
                if (nativeBytes != null && nativeBytes.isNotEmpty()) {
                    Log.d(TAG, "⚡ JNI convertFloatToPcm16 normalized ${floats.size} samples -> ${nativeBytes.size} bytes PCM16")
                    return nativeBytes
                }
            } catch (e: Throwable) {
                Log.w(TAG, "JNI normalize fallback: ${e.message}")
            }
        }
        Log.d(TAG, "⚡ Kotlin normalizeToPcmBytes converting ${floats.size} float samples")

        // Fast Kotlin Little-Endian conversion fallback
        val numSamples = floats.size
        val bytes = ByteArray(numSamples * 2)
        var byteIdx = 0
        for (i in 0 until numSamples) {
            var sample = floats[i]
            // 1. Hard clip to prevent overflow/crackle
            if (sample > 1.0f) sample = 1.0f
            if (sample < -1.0f) sample = -1.0f

            // 2. Scale to 16-bit PCM integer range
            val pcm = (sample * 32767.0f).toInt()
            bytes[byteIdx++] = (pcm and 0xFF).toByte()
            bytes[byteIdx++] = ((pcm ushr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * Normalizes float32 audio waveform to ShortArray.
     */
    fun normalizeToPcmShorts(floats: FloatArray): ShortArray {
        if (floats.isEmpty()) return ShortArray(0)

        if (isNativeLoaded) {
            try {
                val nativeShorts = convertFloatToPcmShorts(floats)
                if (nativeShorts != null && nativeShorts.isNotEmpty()) {
                    return nativeShorts
                }
            } catch (_: Throwable) {}
        }

        val numSamples = floats.size
        val shorts = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            var sample = floats[i]
            if (sample > 1.0f) sample = 1.0f
            if (sample < -1.0f) sample = -1.0f
            shorts[i] = (sample * 32767.0f).toInt().toShort()
        }
        return shorts
    }
}
