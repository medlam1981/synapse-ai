package com.synapse.localai

import com.synapse.localai.AtlasLog as Log
import java.util.Locale

/**
 * Media Generation Intent.
 */
sealed class MediaIntent {
    /**
     * Informational or conversational queries intended for Text LLM dialogue.
     */
    data object TextDialogue : MediaIntent()

    /**
     * Imperative command to synthesize an image.
     */
    data class GenerateImage(
        val prompt: String,
        val rawInput: String,
        val isArabic: Boolean
    ) : MediaIntent()

    /**
     * Imperative command to synthesize a video or motion animation.
     */
    data class GenerateVideo(
        val prompt: String,
        val rawInput: String,
        val isArabic: Boolean
    ) : MediaIntent()
}

/**
 * Intelligent Media Intent Classifier & False-Positive Guard.
 *
 * Distinguishes imperative creation commands ("draw a cyberpunk city", "ارسم لي سيارة")
 * from informational queries ("what is cyberpunk art?", "كيف تعمل نماذج التوليد؟", "اشرح لي نظرية الرسم").
 */
object MediaIntentClassifier {

    private const val TAG = "MediaIntentClassifier"

    // Informational question phrases that MUST route strictly to Text LLM dialogue
    private val INFORMATIONAL_PREFIXES_EN = listOf(
        "what is", "what are", "how does", "how do", "how to", "why does", "why is", "why do",
        "who is", "who was", "who drew", "who painted", "who created", "who designed",
        "explain", "tell me about", "can you explain", "describe how", "describe what",
        "definition of", "history of", "theory of", "meaning of", "tell me how",
        "give me an explanation", "write an article about", "write an essay about"
    )

    private val INFORMATIONAL_PREFIXES_AR = listOf(
        "ما هو", "ما هي", "ماذا يعني", "ما معنى", "ما تعريف",
        "كيف يعمل", "كيف تعمل", "كيف يتم", "كيف تصنع", "كيف يمكن",
        "لماذا", "علل", "فَسّر",
        "من رسم", "من صنع", "من صمم", "من هو", "من هي",
        "اشرح لي", "اشرح", "وضح لي", "وضح", "حدثني عن", "تكلم عن", "عرف لي",
        "هل يمكنك شرح", "ما هي نظرية", "ما هو مفهوم"
    )

    // Code & Web design syntax terms to prevent accidental drawing intercepts
    private val CODE_INDICATORS = setOf(
        "html", "css", "javascript", "js", "python", "code", "kotlin", "java",
        "react", "flutter", "div", "script", "svg", "canvas", "drawrect", "drawline",
        "drawbitmap", "view", "function", "class", "tag", "element"
    )

    // Video generation command prefixes
    private val VIDEO_PREFIXES_EN = listOf(
        "/video", "/vid", "/animate", "/animation",
        "generate a video of", "generate video of", "generate a video", "generate video",
        "create a video of", "create video of", "create a video", "create video",
        "make a video of", "make video of", "make a video", "make video",
        "animate a", "animate an", "animate the", "animate ",
        "generate animation of", "generate an animation of", "create animation of", "create an animation of",
        "video of a", "video of an", "video of the", "video of ",
        "animation of a", "animation of an", "animation of the", "animation of "
    )

    private val VIDEO_PREFIXES_AR = listOf(
        "توليد مقطع فيديو ل", "توليد مقطع فيديو", "توليد فيديو ل", "توليد فيديو",
        "انشئ مقطع فيديو ل", "انشئ مقطع فيديو", "انشئ فيديو ل", "انشئ فيديو",
        "اصنع مقطع فيديو ل", "اصنع مقطع فيديو", "اصنع فيديو ل", "اصنع فيديو",
        "ولد فيديو ل", "ولد فيديو", "فيديو لـ", "فيديو ل ", "فيديو ",
        "انيميشن لـ", "انيميشن ل ", "انيميشن ", "حرك صورة", "اصنع رسوم متحركة ل", "اصنع رسوم متحركة"
    )

    // Image generation command prefixes
    private val IMAGE_PREFIXES_EN = listOf(
        "/image", "/img", "/draw", "/paint", "/art",
        "draw a picture of", "draw an image of", "draw a photo of",
        "draw me a", "draw me an", "draw a", "draw an", "draw ",
        "paint a picture of", "paint an image of", "paint a photo of",
        "paint me a", "paint me an", "paint a", "paint an", "paint ",
        "generate an image of", "generate image of", "generate a photo of", "generate photo of",
        "generate a picture of", "generate picture of", "generate an image", "generate image",
        "create an image of", "create image of", "create a picture of", "create picture of",
        "create a photo of", "create photo of", "create an image", "create image",
        "make an image of", "make image of", "make a picture of", "make picture of",
        "make a photo of", "make photo of",
        "picture of a", "picture of an", "picture of the", "picture of ",
        "photo of a", "photo of an", "photo of the", "photo of ",
        "artwork of a", "artwork of an", "artwork of ",
        "illustration of a", "illustration of an", "illustration of "
    )

    private val IMAGE_PREFIXES_AR = listOf(
        "ارسم لي صورة لـ", "ارسم لي صورة ل", "ارسم لي صورة", "ارسم لي صوره لـ", "ارسم لي صوره", "ارسم لي",
        "ارسم صورة لـ", "ارسم صورة ل", "ارسم صورة", "ارسم صوره لـ", "ارسم صوره ل", "ارسم صوره", "ارسم ",
        "توليد صورة لـ", "توليد صورة ل", "توليد صورة", "توليد صوره لـ", "توليد صوره ل", "توليد صوره",
        "ولد صورة لـ", "ولد صورة ل", "ولد صورة", "ولد صوره لـ", "ولد صوره ل", "ولد صوره",
        "انشئ صورة لـ", "انشئ صورة ل", "انشئ صورة", "انشئ صوره لـ", "انشئ صوره ل", "انشئ صوره",
        "اصنع صورة لـ", "اصنع صورة ل", "اصنع صورة", "اصنع صوره لـ", "اصنع صوره ل", "اصنع صوره",
        "صورة لـ", "صورة ل ", "صوره لـ", "صوره ل ", "رسمة لـ", "رسمة ل ", "رسمة ", "صورة "
    )

    /**
     * Determines whether the raw user input is an imperative media generation request
     * or an informational question.
     */
    fun classify(rawInput: String): MediaIntent {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return MediaIntent.TextDialogue

        val lower = trimmed.lowercase(Locale.ROOT)
        val isArabic = isArabicText(trimmed)

        // 1. Check for Explicit Informational Inquiries (False-Positive Guards)
        if (isInformationalQuery(lower, isArabic)) {
            Log.d(TAG, "Input classified as Informational Query (TextDialogue): \"$trimmed\"")
            return MediaIntent.TextDialogue
        }

        // 2. Check for Code / Web development queries
        if (isCodeQuery(lower)) {
            Log.d(TAG, "Input contains code syntax (TextDialogue): \"$trimmed\"")
            return MediaIntent.TextDialogue
        }

        // 3. Check for Imperative Video Generation Intent
        val videoPrefix = findMatchingPrefix(lower, VIDEO_PREFIXES_EN + VIDEO_PREFIXES_AR)
        if (videoPrefix != null) {
            val cleanPrompt = extractCleanPrompt(trimmed, videoPrefix)
            if (cleanPrompt.isNotBlank()) {
                Log.i(TAG, "Classified as GenerateVideo: prompt=\"$cleanPrompt\"")
                return MediaIntent.GenerateVideo(
                    prompt = cleanPrompt,
                    rawInput = trimmed,
                    isArabic = isArabic
                )
            }
        }

        // 4. Check for Imperative Image Generation Intent
        val imagePrefix = findMatchingPrefix(lower, IMAGE_PREFIXES_EN + IMAGE_PREFIXES_AR)
        if (imagePrefix != null) {
            val cleanPrompt = extractCleanPrompt(trimmed, imagePrefix)
            if (cleanPrompt.isNotBlank()) {
                Log.i(TAG, "Classified as GenerateImage: prompt=\"$cleanPrompt\"")
                return MediaIntent.GenerateImage(
                    prompt = cleanPrompt,
                    rawInput = trimmed,
                    isArabic = isArabic
                )
            }
        }

        // 5. Default: Standard Text Dialogue
        return MediaIntent.TextDialogue
    }

    private fun isInformationalQuery(lower: String, isArabic: Boolean): Boolean {
        val informationalList = if (isArabic) INFORMATIONAL_PREFIXES_AR else INFORMATIONAL_PREFIXES_EN
        // Check if query starts with or contains prominent inquiry frames
        return informationalList.any { prefix ->
            lower.startsWith(prefix) || lower.startsWith("$prefix ") || lower.contains(" $prefix ")
        }
    }

    private fun isCodeQuery(lower: String): Boolean {
        val words = lower.split(Regex("[^a-z0-9_#+-]+")).toSet()
        return words.any { it in CODE_INDICATORS }
    }

    private fun findMatchingPrefix(lower: String, prefixes: List<String>): String? {
        return prefixes.firstOrNull { lower.startsWith(it) }
    }

    private fun extractCleanPrompt(rawInput: String, prefix: String): String {
        var clean = rawInput.trim()
        val lower = clean.lowercase(Locale.ROOT)
        if (lower.startsWith(prefix)) {
            clean = clean.substring(prefix.length).trim()
        }

        // Strip quotes or trailing filler words
        clean = clean.removeSurrounding("\"", "\"")
            .removeSurrounding("'", "'")
            .removeSurrounding("«", "»")
            .trim()

        // Strip polite fillers in English and Arabic
        val fillers = listOf(
            "please", "kindly", "for me", "من فضلك", "لو سمحت", "بالله عليك", "أرجوك", "ارجوك"
        )
        for (filler in fillers) {
            if (clean.endsWith(filler, ignoreCase = true)) {
                clean = clean.removeSuffix(filler).trim()
            }
            if (clean.startsWith(filler, ignoreCase = true)) {
                clean = clean.removePrefix(filler).trim()
            }
        }

        return clean.trim()
    }

    /**
     * Checks if the text contains Arabic script characters.
     */
    fun isArabicText(text: String): Boolean {
        return text.any { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' }
    }

    /**
     * Multilingual Translation & Prompt Enrichment.
     *
     * Converts non-English (Arabic) prompts into descriptive, English visual concepts
     * optimized for diffusion text-encoders (e.g. CLIP / T5).
     */
    fun enrichAndTranslatePrompt(prompt: String, isArabic: Boolean): String {
        val clean = prompt.trim()
        if (!isArabic) {
            // Already English: enrich with essential photography & quality anchors
            return if (!clean.contains("lighting", ignoreCase = true) && !clean.contains("detailed", ignoreCase = true)) {
                "$clean, highly detailed, sharp focus, cinematic lighting, 8k resolution, masterpiece"
            } else {
                clean
            }
        }

        // Fast-path visual concept translation dictionary for common Arabic drawing concepts
        var translated = clean
        val dictionary = mapOf(
            "سيارة رياضية" to "sports car",
            "سيارة" to "car",
            "مدينة مستقبلية" to "futuristic cyberpunk city with neon lights",
            "مدينة" to "city skyline",
            "منظر طبيعي" to "majestic landscape, natural scenery",
            "بحر" to "serene ocean with crashing waves",
            "شاطئ" to "tropical beach with sunset",
            "حديقة" to "lush flowering garden",
            "فضاء" to "deep cosmic space, nebula and stars",
            "روبوت" to "futuristic android robot",
            "قطة" to "cute cat, soft fur",
            "كلب" to "playful dog",
            "شجرة" to "ancient majestic tree",
            "جبل" to "snow-capped mountain range",
            "غروب الشمس" to "golden hour sunset",
            "شروق الشمس" to "golden sunrise",
            "قصر" to "majestic royal palace",
            "قلعة" to "ancient medieval castle",
            "صحراء" to "golden sand dunes desert",
            "لوحة فنية" to "oil painting masterpiece",
            "شخصية انمي" to "anime character illustration",
            "صقر" to "majestic flying falcon",
            "حصان" to "noble galloping horse"
        )

        for ((ar, en) in dictionary) {
            if (translated.contains(ar)) {
                translated = translated.replace(ar, en)
            }
        }

        // If Arabic letters still remain, provide a clean artistic fallback frame
        return if (isArabicText(translated)) {
            "$clean, artistic visual depiction, cinematic lighting, ultra-detailed, 8k resolution, highly aesthetic masterpiece"
        } else {
            "$translated, intricate details, photorealistic, cinematic lighting, 8k resolution, masterpiece"
        }
    }
}
