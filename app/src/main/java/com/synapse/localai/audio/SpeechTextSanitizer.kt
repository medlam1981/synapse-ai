package com.synapse.localai.audio

object SpeechTextSanitizer {
    private val fencedCode = Regex("(?s)```.*?(?:```|$)")
    private val inlineCode = Regex("(?s)(`+).*?\\1")
    private val htmlComments = Regex("(?s)<!--.*?-->")
    private val nonSpeechBlocks = Regex(
        "(?is)<(think|reasoning|analysis|system|developer|tool|invoke)\\b[^>]*>.*?</\\1\\s*>"
    )
    private val tags = Regex("<[^>]*>")
    private val markdownLinks = Regex("\\[([^]]+)]\\([^)]*\\)")
    private val urls = Regex("(?i)\\b(?:https?://|www\\.)\\S+")
    private val paths = Regex(
        """(?<![\p{L}\p{N}])(?:/[\w.@+-]+)+(?:\.[A-Za-z0-9]{1,8})?|(?:[\w.-]+/){1,}[\w.-]+"""
    )
    private val fileNames = Regex(
        """(?i)\b[\w.-]+\.(?:kt|java|kts|cpp|h|hpp|c|py|js|ts|xml|json|yaml|yml|gradle|sh|apk|so|onnx|gguf|txt)\b"""
    )
    private val commandLine = Regex(
        """(?i)^\s*(?:[$#>]\s*|(?:adb|am|pm|cmd|cd|ls|cat|grep|find|sudo|python\d*|git|gradle|gradlew|cmake|ninja|chmod|chown|mkdir|rm|mv|cp|curl|wget|pip\d*|npm|yarn|export|set|sh|bash)\b).*"""
    )
    private val markdownPrefix = Regex("""^\s{0,3}(?:#{1,6}\s+|[-*+]\s+|>\s*)""")
    private val syntax = Regex("""[<>{}\[\]|\\^=_*~`#$]""")
    private val whitespace = Regex("""\s+""")

    fun sanitize(raw: String): String {
        if (raw.isBlank()) return ""

        val withoutNonSpeech = raw
            .replace(htmlComments, " ")
            .replace(nonSpeechBlocks, " ")
            .replace(fencedCode, " ")
            .replace(inlineCode, " ")
            .replace(tags, " ")
            .replace(markdownLinks) { it.groupValues[1] }
            .replace(urls, " ")

        return withoutNonSpeech
            .lineSequence()
            .filterNot { commandLine.matches(it) }
            .map { line ->
                line.replace(markdownPrefix, "")
                    .replace(paths, " ")
                    .replace(fileNames, " ")
                    .replace(syntax, " ")
            }
            .joinToString(" ")
            .replace(Regex("&(?:amp|lt|gt|quot|apos);"), " ")
            .replace(whitespace, " ")
            .trim()
    }
}
