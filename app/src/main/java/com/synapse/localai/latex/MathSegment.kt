package com.synapse.localai.latex

/**
 * Represents segments of text and LaTeX math formulas.
 */
sealed class MathSegment {
    /** Raw text with no math delimiters (standard markdown / plain text) */
    data class PlainText(val text: String) : MathSegment()

    /** Inline math delimited by $...$ */
    data class InlineMath(val raw: String, val formula: String) : MathSegment()

    /** Display/block math delimited by $$...$$ */
    data class BlockMath(val raw: String, val formula: String) : MathSegment()
}

/**
 * Streaming-Safe LaTeX Math Parser with guards for:
 * 1. Incomplete Delimiters: unclosed math during active token streaming remains PlainText.
 * 2. Markdown Conflict Isolation: math equations extracted before Markdown parsing to prevent _ italics.
 * 3. Currency & Escaping: \$ and currency values like $50 or $10.99 are strictly treated as PlainText.
 */
object MathParser {

    /**
     * Splits [text] into [MathSegment] blocks.
     */
    fun parse(text: String): List<MathSegment> {
        if (text.isEmpty()) return emptyList()

        val segments = mutableListOf<MathSegment>()
        var cursor = 0
        val len = text.length

        while (cursor < len) {
            val dollarIdx = text.indexOf('$', cursor)
            if (dollarIdx == -1) {
                // No more dollar signs, rest is plain text
                segments.add(MathSegment.PlainText(text.substring(cursor)))
                break
            }

            // Check if dollar is escaped: e.g. \$
            if (isEscaped(text, dollarIdx)) {
                // Skip the backslash or keep going
                val nextDollar = dollarIdx + 1
                // Add up to dollarIdx + 1 as text
                segments.add(MathSegment.PlainText(text.substring(cursor, nextDollar)))
                cursor = nextDollar
                continue
            }

            // Check if this is a Block Math delimiter ($$)
            val isDoubleDollar = (dollarIdx + 1 < len && text[dollarIdx + 1] == '$')

            if (isDoubleDollar) {
                // Block Math: search for closing $$
                val contentStart = dollarIdx + 2
                val closingIdx = findClosingDoubleDollar(text, contentStart)

                if (closingIdx == -1) {
                    // Streaming Incomplete Delimiter Guard:
                    // Opening $$ without closing $$ in current stream buffer.
                    // Keep everything from cursor to end as PlainText to avoid crashing renderer.
                    segments.add(MathSegment.PlainText(text.substring(cursor)))
                    break
                } else {
                    // Closed block math found!
                    if (dollarIdx > cursor) {
                        segments.add(MathSegment.PlainText(text.substring(cursor, dollarIdx)))
                    }
                    val raw = text.substring(dollarIdx, closingIdx + 2)
                    val formula = text.substring(contentStart, closingIdx).trim()
                    segments.add(MathSegment.BlockMath(raw = raw, formula = formula))
                    cursor = closingIdx + 2
                }
            } else {
                // Single dollar: check currency guard first ($ followed immediately by digit)
                val isCurrency = isCurrencyValue(text, dollarIdx)
                if (isCurrency) {
                    // It's a currency like $50 or $10.99, not math
                    val nextPos = dollarIdx + 1
                    segments.add(MathSegment.PlainText(text.substring(cursor, nextPos)))
                    cursor = nextPos
                    continue
                }

                // Check invalid opening: "$ " (dollar followed by space is not standard inline math)
                if (dollarIdx + 1 < len && text[dollarIdx + 1].isWhitespace()) {
                    val nextPos = dollarIdx + 1
                    segments.add(MathSegment.PlainText(text.substring(cursor, nextPos)))
                    cursor = nextPos
                    continue
                }

                // Inline Math: search for closing $
                val contentStart = dollarIdx + 1
                val closingIdx = findClosingSingleDollar(text, contentStart)

                if (closingIdx == -1) {
                    // Streaming Incomplete Delimiter Guard:
                    // Opening $ without closing $ in current line/stream buffer.
                    // Treat as PlainText so tokens stream smoothly without parser crashes.
                    segments.add(MathSegment.PlainText(text.substring(cursor)))
                    break
                } else {
                    // Closed inline math found!
                    if (dollarIdx > cursor) {
                        segments.add(MathSegment.PlainText(text.substring(cursor, dollarIdx)))
                    }
                    val raw = text.substring(dollarIdx, closingIdx + 1)
                    val formula = text.substring(contentStart, closingIdx).trim()
                    segments.add(MathSegment.InlineMath(raw = raw, formula = formula))
                    cursor = closingIdx + 1
                }
            }
        }

        // Merge contiguous PlainText segments for rendering efficiency
        return mergePlainTextSegments(segments)
    }

    private fun isEscaped(text: String, index: Int): Boolean {
        var backslashCount = 0
        var i = index - 1
        while (i >= 0 && text[i] == '\\') {
            backslashCount++
            i--
        }
        return backslashCount % 2 == 1
    }

    private fun isCurrencyValue(text: String, dollarIndex: Int): Boolean {
        if (dollarIndex + 1 >= text.length) return false
        val nextChar = text[dollarIndex + 1]
        // $50, $10.99, etc.
        if (nextChar.isDigit()) return true
        // $ 50
        if (nextChar.isWhitespace() && dollarIndex + 2 < text.length && text[dollarIndex + 2].isDigit()) {
            return true
        }
        return false
    }

    private fun findClosingDoubleDollar(text: String, startIdx: Int): Int {
        var i = startIdx
        val len = text.length
        while (i < len - 1) {
            if (text[i] == '$' && text[i + 1] == '$' && !isEscaped(text, i)) {
                return i
            }
            i++
        }
        return -1
    }

    private fun findClosingSingleDollar(text: String, startIdx: Int): Int {
        var i = startIdx
        val len = text.length
        while (i < len) {
            val c = text[i]
            // Inline math does not span across multiple newlines
            if (c == '\n') {
                return -1
            }
            if (c == '$' && !isEscaped(text, i)) {
                // Must not be preceded by space (e.g. "$x $" is invalid)
                if (i > startIdx && text[i - 1].isWhitespace()) {
                    return -1
                }
                // Must not be part of $$
                if (i + 1 < len && text[i + 1] == '$') {
                    return -1
                }
                return i
            }
            i++
        }
        return -1
    }

    private fun mergePlainTextSegments(segments: List<MathSegment>): List<MathSegment> {
        if (segments.size <= 1) return segments
        val merged = mutableListOf<MathSegment>()
        val textBuf = StringBuilder()

        for (seg in segments) {
            when (seg) {
                is MathSegment.PlainText -> {
                    textBuf.append(seg.text)
                }
                is MathSegment.InlineMath, is MathSegment.BlockMath -> {
                    if (textBuf.isNotEmpty()) {
                        merged.add(MathSegment.PlainText(textBuf.toString()))
                        textBuf.clear()
                    }
                    merged.add(seg)
                }
            }
        }
        if (textBuf.isNotEmpty()) {
            merged.add(MathSegment.PlainText(textBuf.toString()))
        }
        return merged
    }
}
