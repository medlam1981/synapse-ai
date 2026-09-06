package com.synapse.localai

/**
 * Streaming File Sink & HTML Sanitizer:
 * Enforces Start-of-File Strict Anchor and eliminates stray prefix glyphs (<[[, <<, markdown fences).
 */
object HtmlSanitizer {

    /**
     * Start-of-File Strict Anchor:
     * Strips all leading characters until the exact case-insensitive sequence <!DOCTYPE html> or <html is encountered.
     * Completely discards any leading ASCII artifacts, markdown fences (```html), worker tags, or whitespace.
     */
    fun sanitizeHtmlStream(content: String): String {
        val doctypeIndex = content.indexOf("<!DOCTYPE html>", ignoreCase = true)
        val htmlIndex = content.indexOf("<html", ignoreCase = true)

        val startIndex = when {
            doctypeIndex != -1 -> doctypeIndex
            htmlIndex != -1 -> htmlIndex
            else -> 0
        }

        var result = content.substring(startIndex).trim()
        if (result.endsWith("```")) {
            result = result.removeSuffix("```").trimEnd()
        }
        return result
    }
}
