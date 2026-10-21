package com.synapse.localai

private const val LTR_ISOLATE = '\u2066'
private const val POP_DIRECTIONAL_ISOLATE = '\u2069'

private val ltrRunRegex = Regex(
    """[\(\[\{]*[A-Za-z0-9][A-Za-z0-9_./:@+\-]*(?:[ \t]+[A-Za-z0-9][A-Za-z0-9_./:@+\-]*)*[\)\]\}]*[,;.!?]?"""
)

fun isRtlText(text: String): Boolean {
    var rtlRuns = 0
    var ltrRuns = 0
    var currentRun: Boolean? = null
    var firstStrongDirection: Boolean? = null

    text.forEach { char ->
        val direction = when (Character.getDirectionality(char)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> false
            else -> null
        }

        if (direction != null) {
            if (firstStrongDirection == null) firstStrongDirection = direction
            if (currentRun != direction) {
                if (direction) rtlRuns++ else ltrRuns++
                currentRun = direction
            }
        } else if (char.isWhitespace()) {
            currentRun = null
        }
    }

    return when {
        rtlRuns > ltrRuns -> true
        ltrRuns > rtlRuns -> false
        else -> firstStrongDirection ?: false
    }
}

fun isArabic(text: String): Boolean = isRtlText(text)

/**
 * Isolates Latin/digit runs so their punctuation remains LTR inside RTL prose.
 * An incomplete opening bracket is preserved inside the isolate while streaming;
 * it is never synthetically closed or removed from the model output.
 */
fun applyBidiIsolation(text: String): String {
    if (text.isBlank()) return text

    return ltrRunRegex.replace(text) { match ->
        val alreadyIsolated = text.getOrNull(match.range.first - 1) == LTR_ISOLATE &&
            text.getOrNull(match.range.last + 1) == POP_DIRECTIONAL_ISOLATE
        if (alreadyIsolated) match.value else "$LTR_ISOLATE${match.value}$POP_DIRECTIONAL_ISOLATE"
    }
}
