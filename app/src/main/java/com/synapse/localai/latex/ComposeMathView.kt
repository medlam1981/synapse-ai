package com.synapse.localai.latex

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Visual Display Equation Box for Block Math ($$...$$).
 *
 * Enforces LTR layout direction, provides horizontal scrolling for wide formulas,
 * and maintains dark theme styling (#ECEFF4 crisp symbol color).
 */
@Composable
fun CliBlockMathFormula(
    formula: String,
    rawText: String = "",
    isDark: Boolean = true,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    val textColor = if (isDark) Color(0xFFECEFF4) else Color(0xFF1E293B)
    val cardBg = if (isDark) Color(0xFF0F172A) else Color(0xFFF8FAFC)
    val borderColor = if (isDark) Color(0xFF35353D) else Color(0xFFCBD5E1)

    // Render formula using MathRenderer with 44px text size for display math
    val rendered = remember(formula, isDark, density) {
        MathRenderer.renderFormula(
            formula = formula,
            textSizePx = 44f,
            colorArgb = textColor.toArgb(),
            density = density,
            isBlock = true
        )
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = cardBg,
            border = BorderStroke(0.75.dp, borderColor),
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (rendered != null) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            bitmap = rendered.bitmap,
                            contentDescription = formula,
                            modifier = Modifier
                                .width(rendered.intrinsicWidthDp.dp)
                                .height(rendered.intrinsicHeightDp.dp)
                        )
                    }
                } else {
                    // Fallback to monospace raw text if LaTeX syntax fails to parse
                    Text(
                        text = rawText.ifBlank { "$$ $formula $$" },
                        color = if (isDark) Color(0xFFA1A1AA) else Color(0xFF71717A),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Text renderer that seamlessly integrates inline LaTeX ($...$) and block LaTeX ($$...$$)
 * into Jetpack Compose with Markdown styling, complete BiDi RTL/LTR isolation,
 * and zero-jank streaming safety.
 */
@Composable
fun CliMathFormattedText(
    text: String,
    isDark: Boolean,
    isRtl: Boolean,
    color: Color,
    fontSize: TextUnit = 11.sp,
    lineHeight: TextUnit = if (isRtl) 18.sp else 15.sp,
    textAlign: TextAlign = TextAlign.Start,
    modifier: Modifier = Modifier,
    markdownStyler: (String, Boolean, Boolean) -> AnnotatedString
) {
    val density = LocalDensity.current
    val textColorArgb = if (isDark) Color(0xFFECEFF4).toArgb() else color.toArgb()
    val fontSizePx = with(density) { fontSize.toPx() }

    // Streaming-safe segmentation of math and plain text
    val segments = remember(text) { MathParser.parse(text) }

    // Check if there are any block math formulas in this text block
    val hasBlockMath = remember(segments) { segments.any { it is MathSegment.BlockMath } }

    if (hasBlockMath) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            segments.forEach { seg ->
                when (seg) {
                    is MathSegment.BlockMath -> {
                        CliBlockMathFormula(
                            formula = seg.formula,
                            rawText = seg.raw,
                            isDark = isDark
                        )
                    }
                    is MathSegment.PlainText -> {
                        if (seg.text.isNotBlank()) {
                            Text(
                                text = markdownStyler(seg.text, isDark, isRtl),
                                color = color,
                                fontSize = fontSize,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = lineHeight,
                                textAlign = textAlign,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    is MathSegment.InlineMath -> {
                        RenderInlineMathLine(
                            segments = listOf(seg),
                            isDark = isDark,
                            isRtl = isRtl,
                            color = color,
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            textAlign = textAlign,
                            density = density.density,
                            fontSizePx = fontSizePx,
                            textColorArgb = textColorArgb,
                            markdownStyler = markdownStyler
                        )
                    }
                }
            }
        }
    } else {
        // Pure inline flow (mixed text and inline $...$ formulas)
        RenderInlineMathLine(
            segments = segments,
            isDark = isDark,
            isRtl = isRtl,
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight,
            textAlign = textAlign,
            density = density.density,
            fontSizePx = fontSizePx,
            textColorArgb = textColorArgb,
            modifier = modifier,
            markdownStyler = markdownStyler
        )
    }
}

@Composable
private fun RenderInlineMathLine(
    segments: List<MathSegment>,
    isDark: Boolean,
    isRtl: Boolean,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    textAlign: TextAlign,
    density: Float,
    fontSizePx: Float,
    textColorArgb: Int,
    modifier: Modifier = Modifier,
    markdownStyler: (String, Boolean, Boolean) -> AnnotatedString
) {
    // If no math segments present, render standard styled markdown directly
    val hasInlineMath = segments.any { it is MathSegment.InlineMath }
    if (!hasInlineMath) {
        val fullText = segments.joinToString("") { (it as? MathSegment.PlainText)?.text ?: "" }
        Text(
            text = markdownStyler(fullText, isDark, isRtl),
            color = color,
            fontSize = fontSize,
            fontFamily = FontFamily.Monospace,
            lineHeight = lineHeight,
            textAlign = textAlign,
            modifier = modifier.fillMaxWidth()
        )
        return
    }

    // Build mixed inline text + math content
    val inlineContentMap = remember(segments, isDark, density) {
        val map = mutableMapOf<String, InlineTextContent>()
        segments.forEachIndexed { index, seg ->
            if (seg is MathSegment.InlineMath) {
                val mathId = "math_$index"
                // Size slightly scaled for clear subscript/superscript legibility
                val mathTextSize = fontSizePx * 1.25f
                val rendered = MathRenderer.renderFormula(
                    formula = seg.formula,
                    textSizePx = mathTextSize,
                    colorArgb = textColorArgb,
                    density = density,
                    isBlock = false
                )
                if (rendered != null) {
                    val widthSp = (rendered.intrinsicWidthDp * 1.05f).sp
                    val heightSp = (rendered.intrinsicHeightDp * 1.05f).sp
                    map[mathId] = InlineTextContent(
                        placeholder = Placeholder(
                            width = widthSp,
                            height = heightSp,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                        )
                    ) {
                        // Enforce LTR orientation for all math symbols even within Arabic sentences
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Image(
                                bitmap = rendered.bitmap,
                                contentDescription = seg.formula,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
        map
    }

    val annotatedString = remember(segments, isDark, isRtl) {
        val builder = AnnotatedString.Builder()
        segments.forEachIndexed { index, seg ->
            when (seg) {
                is MathSegment.PlainText -> {
                    // Plain text is passed to Markdown styler (isolates underscores from math)
                    builder.append(markdownStyler(seg.text, isDark, isRtl))
                }
                is MathSegment.InlineMath -> {
                    val mathId = "math_$index"
                    if (inlineContentMap.containsKey(mathId)) {
                        // Embed inline placeholder with alternate raw formula for copy/accessibility
                        builder.appendInlineContent(mathId, alternateText = seg.raw)
                    } else {
                        // Render fallback raw text if syntax invalid or incomplete
                        builder.append(seg.raw)
                    }
                }
                is MathSegment.BlockMath -> {
                    builder.append("\n" + seg.raw + "\n")
                }
            }
        }
        builder.toAnnotatedString()
    }

    Text(
        text = annotatedString,
        inlineContent = inlineContentMap,
        color = color,
        fontSize = fontSize,
        fontFamily = FontFamily.Monospace,
        lineHeight = lineHeight,
        textAlign = textAlign,
        modifier = modifier.fillMaxWidth()
    )
}
