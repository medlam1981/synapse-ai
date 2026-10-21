package com.synapse.localai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BidiTextFormatterTest {
    @Test
    fun detectsDominantDirectionInMixedText() {
        assertTrue(isRtlText("الإصدار Qwen 3.8 يعمل"))
        assertFalse(isRtlText("Qwen 3.8 runs locally ويعمل"))
        assertFalse(isRtlText("x86_64"))
    }

    @Test
    fun isolatesIncompleteOpeningParenthesesDuringStreaming() {
        val input = "افتح النموذج (Qwen 3.8"
        val isolated = applyBidiIsolation(input)

        assertTrue(isolated.contains("\u2066(Qwen 3.8\u2069"))
        assertEquals(input, isolated.filterNot { it == '\u2066' || it == '\u2069' })
        assertEquals(isolated.count { it == '\u2066' }, isolated.count { it == '\u2069' })
    }

    @Test
    fun isolatesMatchedBracketsAndDoesNotNestExistingIsolates() {
        val input = "المسار [src/Main.kt]"
        val isolated = applyBidiIsolation(input)

        assertTrue(isolated.contains("\u2066[src/Main.kt]\u2069"))
        assertEquals(isolated, applyBidiIsolation(isolated))
    }
}
