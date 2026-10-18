package com.synapse.localai.ui

import com.synapse.localai.ui.theme.SuccessGreen
import com.synapse.localai.ui.theme.WarningAmber
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import java.util.regex.Pattern

/**
 * SyntaxHighlighter — IDE-grade regex tokenizer for code syntax highlighting in Compose.
 */
object SyntaxHighlighter {

    // Dark Theme IDE Colors (GitHub Dark / VS Code Dark style)
    private val DarkKeyword = Color(0xFFFF7B72)
    private val DarkString = WarningAmber
    private val DarkComment = Color(0xFF8B949E)
    private val DarkNumber = SuccessGreen
    private val DarkFunction = Color(0xFFD2A8FF)
    private val DarkType = Color(0xFFFFA657)
    private val DarkBase = Color(0xFFE6EDF3)

    // Light Theme IDE Colors (GitHub Light / VS Code Light style)
    private val LightKeyword = Color(0xFFCF222E)
    private val LightString = Color(0xFF065F46)
    private val LightComment = Color(0xFF6E7781)
    private val LightNumber = Color(0xFF047857)
    private val LightFunction = Color(0xFFB45309)
    private val LightType = Color(0xFF953800)
    private val LightBase = Color(0xFF1F2328)

    private val KEYWORDS = setOf(
        // Kotlin / Java
        "fun", "val", "var", "class", "interface", "object", "package", "import",
        "return", "if", "else", "when", "for", "while", "do", "try", "catch", "finally",
        "throw", "new", "override", "open", "sealed", "data", "enum", "suspend", "inline",
        "public", "private", "protected", "internal", "abstract", "final", "companion",
        "is", "as", "in", "by", "lazy", "lateinit", "this", "super", "null",
        // Python
        "def", "elif", "except", "lambda", "pass", "raise", "with", "yield",
        "async", "await", "from", "assert", "global", "nonlocal", "del",
        // JS / TS
        "function", "const", "let", "switch", "case", "break", "default", "continue",
        "export", "typeof", "instanceof", "void", "delete", "debugger", "true", "false",
        // Bash / Shell
        "then", "fi", "esac", "echo", "exit", "local",
        // Rust / C / C++
        "fn", "mut", "pub", "struct", "impl", "trait", "match", "loop",
        "int", "char", "float", "double", "bool", "auto", "static", "const"
    )

    private val KEYWORD_PATTERN = Pattern.compile("\\b(${KEYWORDS.joinToString("|")})\\b")
    private val STRING_PATTERN = Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`)")
    private val COMMENT_PATTERN = Pattern.compile("(//[^\n]*|#[^\n]*|/\\*[\\s\\S]*?\\*/)")
    private val NUMBER_PATTERN = Pattern.compile("\\b(\\d+(?:\\.\\d+)?(?:f|L|u)?|0x[0-9a-fA-F]+)\\b")
    private val FUNCTION_PATTERN = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)(?=\\s*\\()")
    private val TYPE_PATTERN = Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\b")

    /**
     * Highlights code snippet with full IDE colorization according to theme.
     */
    fun highlight(code: String, language: String, isDark: Boolean): AnnotatedString {
        val keywordColor = if (isDark) DarkKeyword else LightKeyword
        val stringColor = if (isDark) DarkString else LightString
        val commentColor = if (isDark) DarkComment else LightComment
        val numberColor = if (isDark) DarkNumber else LightNumber
        val functionColor = if (isDark) DarkFunction else LightFunction
        val typeColor = if (isDark) DarkType else LightType
        val baseColor = if (isDark) DarkBase else LightBase

        return buildAnnotatedString {
            append(code)
            addStyle(SpanStyle(color = baseColor), 0, code.length)

            // Numbers
            val numMatcher = NUMBER_PATTERN.matcher(code)
            while (numMatcher.find()) {
                addStyle(SpanStyle(color = numberColor), numMatcher.start(), numMatcher.end())
            }

            // Types / Classes
            val typeMatcher = TYPE_PATTERN.matcher(code)
            while (typeMatcher.find()) {
                val match = typeMatcher.group()
                if (match !in KEYWORDS) {
                    addStyle(SpanStyle(color = typeColor, fontWeight = FontWeight.SemiBold), typeMatcher.start(), typeMatcher.end())
                }
            }

            // Functions
            val funcMatcher = FUNCTION_PATTERN.matcher(code)
            while (funcMatcher.find()) {
                val match = funcMatcher.group()
                if (match !in KEYWORDS) {
                    addStyle(SpanStyle(color = functionColor), funcMatcher.start(), funcMatcher.end())
                }
            }

            // Keywords
            val kwMatcher = KEYWORD_PATTERN.matcher(code)
            while (kwMatcher.find()) {
                addStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold), kwMatcher.start(), kwMatcher.end())
            }

            // Strings (takes precedence over keywords/types inside strings)
            val strMatcher = STRING_PATTERN.matcher(code)
            while (strMatcher.find()) {
                addStyle(SpanStyle(color = stringColor), strMatcher.start(), strMatcher.end())
            }

            // Comments (takes top precedence)
            val commentMatcher = COMMENT_PATTERN.matcher(code)
            while (commentMatcher.find()) {
                addStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic), commentMatcher.start(), commentMatcher.end())
            }
        }
    }
}
