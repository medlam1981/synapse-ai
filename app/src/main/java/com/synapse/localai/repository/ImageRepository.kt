package com.synapse.localai.repository

import android.content.Context
import com.synapse.localai.MediaIntentClassifier
import com.synapse.localai.MediaIntent
import java.util.Locale

/**
 * ImageRepository: Unified Repository layer managing Cloud & Local Image Generation pipelines.
 * 
 * Inspects system state (Cloud Mode vs Local GGUF Mode), routes synthesis requests to the
 * appropriate engine (GeminiImageGenerator vs LocalDiffusionGenerator), and handles prompt
 * intent classification and prompt extraction.
 */
class ImageRepository(
    private val geminiGenerator: GeminiImageGenerator,
    private val localGenerator: LocalDiffusionGenerator,
    private val isCloudModeProvider: () -> Boolean
) : ImageGenerator {

    val localDiffusionEngine: LocalDiffusionGenerator get() = localGenerator

    companion object {
        /**
         * Detects if an incoming command or user query is an image generation request.
         */
        fun isImageRequest(prompt: String): Boolean {
            return MediaIntentClassifier.classify(prompt) is MediaIntent.GenerateImage
        }

        /**
         * Extracts the clean visual description from the request by stripping command triggers.
         */
        fun extractPrompt(rawPrompt: String): String {
            return when (val intent = MediaIntentClassifier.classify(rawPrompt)) {
                is MediaIntent.GenerateImage -> intent.prompt
                is MediaIntent.GenerateVideo -> intent.prompt
                else -> rawPrompt.trim()
            }
        }
    }

    override fun isHardwareSupported(): Boolean {
        return if (isCloudModeProvider()) {
            true
        } else {
            localGenerator.isHardwareSupported()
        }
    }

    override suspend fun generateImage(prompt: String): ImageResult {
        return if (isCloudModeProvider()) {
            geminiGenerator.generateImage(prompt)
        } else {
            localGenerator.generateImage(prompt)
        }
    }
}
