package com.synapse.localai.latex

import org.junit.Assert.*
import org.junit.Test

class MathParserTest {

    private val d = '$'

    @Test
    fun testClosedInlineMath() {
        val input = "Consider the congruence ${d}N \\equiv 5 \\pmod{7}${d} where ${d}x_1 \\times k_i = 1${d}."
        val segments = MathParser.parse(input)

        assertEquals(5, segments.size)
        assertTrue(segments[0] is MathSegment.PlainText)
        assertEquals("Consider the congruence ", (segments[0] as MathSegment.PlainText).text)

        assertTrue(segments[1] is MathSegment.InlineMath)
        val math1 = segments[1] as MathSegment.InlineMath
        assertEquals("${d}N \\equiv 5 \\pmod{7}${d}", math1.raw)
        assertEquals("N \\equiv 5 \\pmod{7}", math1.formula)

        assertTrue(segments[2] is MathSegment.PlainText)
        assertEquals(" where ", (segments[2] as MathSegment.PlainText).text)

        assertTrue(segments[3] is MathSegment.InlineMath)
        val math2 = segments[3] as MathSegment.InlineMath
        assertEquals("${d}x_1 \\times k_i = 1${d}", math2.raw)
        assertEquals("x_1 \\times k_i = 1", math2.formula)

        assertTrue(segments[4] is MathSegment.PlainText)
        assertEquals(".", (segments[4] as MathSegment.PlainText).text)
    }

    @Test
    fun testIncompleteDelimiterStreamingGuard() {
        // While streaming, unclosed math delimiter must NOT be parsed as math to prevent parser crashes
        val streamingBuffer = "We observe that ${d}N \\equiv "
        val segments = MathParser.parse(streamingBuffer)

        assertEquals(1, segments.size)
        assertTrue(segments[0] is MathSegment.PlainText)
        assertEquals("We observe that ${d}N \\equiv ", (segments[0] as MathSegment.PlainText).text)
    }

    @Test
    fun testBlockMath() {
        val input = "Formula:\n${d}${d}x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}${d}${d}\nwhich gives the roots."
        val segments = MathParser.parse(input)

        assertEquals(3, segments.size)
        assertTrue(segments[0] is MathSegment.PlainText)
        assertEquals("Formula:\n", (segments[0] as MathSegment.PlainText).text)

        assertTrue(segments[1] is MathSegment.BlockMath)
        val block = segments[1] as MathSegment.BlockMath
        assertEquals("${d}${d}x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}${d}${d}", block.raw)
        assertEquals("x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}", block.formula)

        assertTrue(segments[2] is MathSegment.PlainText)
        assertEquals("\nwhich gives the roots.", (segments[2] as MathSegment.PlainText).text)
    }

    @Test
    fun testIncompleteBlockMathStreamingGuard() {
        val streamingBuffer = "Formula:\n${d}${d}x = \\frac{-b"
        val segments = MathParser.parse(streamingBuffer)

        assertEquals(1, segments.size)
        assertTrue(segments[0] is MathSegment.PlainText)
        assertEquals("Formula:\n${d}${d}x = \\frac{-b", (segments[0] as MathSegment.PlainText).text)
    }

    @Test
    fun testCurrencyExclusion() {
        val input = "The price was ${d}50 and the tip was ${d}10.99 for dinner."
        val segments = MathParser.parse(input)

        assertEquals(1, segments.size)
        assertTrue(segments[0] is MathSegment.PlainText)
        assertEquals(input, (segments[0] as MathSegment.PlainText).text)
    }

    @Test
    fun testEscapedDollar() {
        val input = "Escaped \\${d}not_math\\${d} here."
        val segments = MathParser.parse(input)

        assertEquals(1, segments.size)
        assertTrue(segments[0] is MathSegment.PlainText)
    }

    @Test
    fun testArabicMixedWithMath() {
        val input = "المعادلة التربيعية هي ${d}ax^2 + bx + c = 0${d} والحل هو ${d}x = \\frac{-b}{2a}${d} دائماً."
        val segments = MathParser.parse(input)

        assertEquals(5, segments.size)
        assertTrue(segments[0] is MathSegment.PlainText)
        assertTrue(segments[1] is MathSegment.InlineMath)
        assertEquals("ax^2 + bx + c = 0", (segments[1] as MathSegment.InlineMath).formula)
        assertTrue(segments[2] is MathSegment.PlainText)
        assertTrue(segments[3] is MathSegment.InlineMath)
        assertEquals("x = \\frac{-b}{2a}", (segments[3] as MathSegment.InlineMath).formula)
        assertTrue(segments[4] is MathSegment.PlainText)
    }
}
