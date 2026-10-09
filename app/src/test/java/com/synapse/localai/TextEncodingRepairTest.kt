package com.synapse.localai

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets

class TextEncodingRepairTest {

    @Test
    fun repairsUtf8ArabicMisreadAsLatin1() {
        val arabic = "مرحبا".toByteArray(StandardCharsets.UTF_8)
        val mojibake = String(arabic, StandardCharsets.ISO_8859_1)

        assertEquals("مرحبا", TextEncodingRepair.repairIfLikely(mojibake))
    }

    @Test
    fun leavesNormallyEncodedTextUnchanged() {
        val text = "مرحبا ATLAS-AI"
        assertEquals(text, TextEncodingRepair.repairIfLikely(text))
    }
}
