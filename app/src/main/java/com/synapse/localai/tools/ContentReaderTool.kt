package com.synapse.localai.tools

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ContentReaderTool {

    fun fetchUrl(urlString: String, maxChars: Int = 1800): String {
        return try {
            val url = URL(urlString.trim())
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            }

            if (conn.responseCode !in 200..299) {
                return "[Failed to fetch page: HTTP ${conn.responseCode}]"
            }

            val html = conn.inputStream.bufferedReader().use { it.readText() }
            
            val cleanText = html
                .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("&[a-zA-Z]+;"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (cleanText.isBlank()) "[Page content is empty]" else cleanText.take(maxChars)
        } catch (e: Exception) {
            "[Error fetching URL: ${e.message}]"
        }
    }

    fun readFileRange(path: String, startLine: Int = 1, lineCount: Int = 50): String {
        val file = File(path.trim())
        if (!file.exists()) return "[File does not exist: $path]"
        if (file.isDirectory) return "[Target is a directory, not a file]"

        return try {
            val lines = mutableListOf<String>()
            var currentLine = 1
            val from = startLine.coerceAtLeast(1)
            val to = from + lineCount.coerceIn(1, 200) - 1

            file.useLines { sequence ->
                for (line in sequence) {
                    if (currentLine in from..to) {
                        lines.add("$currentLine | $line")
                    }
                    if (currentLine > to) break
                    currentLine++
                }
            }

            if (lines.isEmpty()) "[No lines found in specified range]" else lines.joinToString("\n")
        } catch (e: Exception) {
            "[Error reading file range: ${e.message}]"
        }
    }
}
