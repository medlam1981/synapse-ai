package com.synapse.localai.repository

/**
 * ImageState: Reactive state stream for AI visual synthesis operations.
 */
sealed class ImageState {
    object Idle : ImageState()
    data class Generating(val prompt: String) : ImageState()
    data class Success(val imageBytes: ByteArray, val prompt: String = "") : ImageState() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Success
            return imageBytes.contentEquals(other.imageBytes) && prompt == other.prompt
        }
        override fun hashCode(): Int = 31 * imageBytes.contentHashCode() + prompt.hashCode()
    }
    data class Error(val message: String) : ImageState()
}

/**
 * ImageResult: Domain result wrapper for native in-memory image generation.
 * Contains pure byte array data, completely free of external URLs or remote CDNs.
 */
sealed class ImageResult {
    data class Success(
        val imageBytes: ByteArray,
        val mimeType: String = "image/jpeg",
        val prompt: String = ""
    ) : ImageResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Success
            return imageBytes.contentEquals(other.imageBytes) && mimeType == other.mimeType && prompt == other.prompt
        }
        override fun hashCode(): Int = 31 * imageBytes.contentHashCode() + mimeType.hashCode()
    }
    data class Error(val message: String) : ImageResult()
}

/**
 * Abstract Unified Image Generator Interface.
 * Serves as the single contract for Cloud (Google Imagen) and Local (Diffusion/MediaPipe) engines.
 */
interface ImageGenerator {
    suspend fun generateImage(prompt: String): ImageResult
    fun isHardwareSupported(): Boolean = true
}
