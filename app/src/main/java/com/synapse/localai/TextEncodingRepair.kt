package com.synapse.localai

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal object TextEncodingRepair {
    private val mojibakeMarkers = setOf('Ã', 'Â', 'Ð', 'Ñ', 'Ø', 'Ù', 'â', 'ð', '�')

    fun repairIfLikely(text: String): String {
        val markerCount = text.count { it in mojibakeMarkers }
        if (markerCount < 2 || markerCount * 200 < text.length) return text
        if (text.any { it.code > 0xFF && it != '�' }) return text

        val bytes = ByteArray(text.length) { index -> text[index].code.toByte() }
        val decoded = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull() ?: return text

        val decodedMarkers = decoded.count { it in mojibakeMarkers }
        return if (decodedMarkers < markerCount && decoded.any { it in '\u0600'..'\u06FF' }) decoded else text
    }
}
