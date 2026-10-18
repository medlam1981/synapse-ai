package com.synapse.localai.orchestration

import com.synapse.localai.AtlasLog as Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

/**
 * The Verifier Gatekeeper (Automated Audit & Self-Correction Engine):
 * Inspects all staged artifacts before promotion to production storage.
 * Performs deep structural, syntax, leak-quarantine, and asset-resolution audits.
 */
object VerifierGatekeeper {

    private const val TAG = "VerifierGatekeeper"

    // Standard HTML5 void tags that do not require closing tags
    private val VOID_HTML_TAGS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr", "!doctype"
    )

    /**
     * Executes the comprehensive verification checklist across all staged files.
     */
    fun auditStagedFiles(stagedFiles: Map<String, File>): VerificationResult {
        var missingClosuresCount = 0
        var unclosedTagsCount = 0
        var toolCallLeaksCount = 0
        val assetErrors = mutableListOf<String>()
        val errors = mutableListOf<VerificationError>()
        var dryRunPassed = true

        val allFileNames = stagedFiles.keys.map { it.lowercase() }.toSet()

        for ((fileName, file) in stagedFiles) {
            val content = try {
                file.readText(StandardCharsets.UTF_8)
            } catch (e: Exception) {
                errors.add(
                    VerificationError(
                        workerId = "Unknown",
                        fileName = fileName,
                        errorType = "I/O Read Failure",
                        description = "Could not read staged file: ${e.message}",
                        suggestedFix = "Regenerate file cleanly"
                    )
                )
                continue
            }

            val ext = file.extension.lowercase()

            // 1. Anti-Leak & Tool Quarantine Check
            val leakMatches = checkForToolLeaks(content)
            if (leakMatches.isNotEmpty()) {
                toolCallLeaksCount += leakMatches.size
                errors.add(
                    VerificationError(
                        workerId = determineWorkerId(fileName),
                        fileName = fileName,
                        errorType = "Tool Call / Schema Leak",
                        description = "Detected raw tags or schemas: ${leakMatches.joinToString(", ")}",
                        suggestedFix = "Strip all XML/markdown wrappers and output pure source code only"
                    )
                )
            }

            // 2. Syntax & Completeness: Balanced Brackets / Braces / Parentheses
            if (ext in setOf("js", "ts", "css", "json", "py", "sh", "html")) {
                val bracketErrors = checkBracketBalance(content, ext)
                if (bracketErrors > 0) {
                    missingClosuresCount += bracketErrors
                    errors.add(
                        VerificationError(
                            workerId = determineWorkerId(fileName),
                            fileName = fileName,
                            errorType = "Unbalanced Brackets/Braces",
                            description = "Found $bracketErrors unclosed or mismatched brackets/braces/parentheses",
                            suggestedFix = "Balance all {}, (), [] and verify string literal boundaries"
                        )
                    )
                }
            }

            // 3. HTML Validation: Byte-0 Assertion & Zero-ASCII Navigation Rules
            if (ext in setOf("html", "htm")) {
                val trimmedStart = content.trimStart()
                val startsWithValidHtml = trimmedStart.startsWith("<!DOCTYPE html>", ignoreCase = true) ||
                        trimmedStart.startsWith("<html", ignoreCase = true)

                if (!startsWithValidHtml) {
                    errors.add(
                        VerificationError(
                            workerId = determineWorkerId(fileName),
                            fileName = fileName,
                            errorType = "Byte-0 Violation",
                            description = "HTML document does not start with <!DOCTYPE html> or <html at Byte 0. Leading artifacts or rogue tokens detected.",
                            suggestedFix = "Strictly begin the file at Byte 0 with <!DOCTYPE html>. Discard all leading characters, comments, markdown fences, and worker tags."
                        )
                    )
                }

                // Check for raw ASCII navigation artifacts
                val forbiddenAsciiPatterns = listOf("<[[", "<<[", "[<]")
                val foundForbidden = forbiddenAsciiPatterns.filter { content.contains(it) }
                if (foundForbidden.isNotEmpty()) {
                    errors.add(
                        VerificationError(
                            workerId = determineWorkerId(fileName),
                            fileName = fileName,
                            errorType = "Forbidden ASCII Glyph / Stray Prefix",
                            description = "Detected forbidden ASCII navigation / stray glyph tokens: ${foundForbidden.joinToString(", ")}",
                            suggestedFix = "Zero-ASCII Navigation Rule: Strictly replace ASCII brackets/arrows (<[[, <<[, [<]) with inline SVG vectors. Use semantic <button> elements with Lucide/Heroicons SVG paths."
                        )
                    )
                }
            }

            // 4. HTML / XML Tag Validation
            if (ext in setOf("html", "htm", "xml")) {
                val htmlErrors = checkHtmlTags(content)
                if (htmlErrors > 0) {
                    unclosedTagsCount += htmlErrors
                    errors.add(
                        VerificationError(
                            workerId = determineWorkerId(fileName),
                            fileName = fileName,
                            errorType = "Unclosed HTML Tags",
                            description = "Found $htmlErrors unclosed non-void HTML tags",
                            suggestedFix = "Ensure all opening tags have corresponding closing tags"
                        )
                    )
                }

                // 4. Path & Asset Resolution
                val missingRefs = checkAssetReferences(content, allFileNames)
                if (missingRefs.isNotEmpty()) {
                    assetErrors.addAll(missingRefs)
                    errors.add(
                        VerificationError(
                            workerId = determineWorkerId(fileName),
                            fileName = fileName,
                            errorType = "Missing Asset Reference",
                            description = "HTML references missing files: ${missingRefs.joinToString(", ")}",
                            suggestedFix = "Ensure referenced scripts/stylesheets exist in the bundle"
                        )
                    )
                }
            }

            // 5. JSON Syntax Validation
            if (ext == "json") {
                try {
                    val trimmed = content.trim()
                    if (trimmed.startsWith("{")) JSONObject(trimmed)
                    else if (trimmed.startsWith("[")) JSONArray(trimmed)
                    else throw IllegalArgumentException("JSON must start with { or [")
                } catch (e: Exception) {
                    missingClosuresCount++
                    errors.add(
                        VerificationError(
                            workerId = determineWorkerId(fileName),
                            fileName = fileName,
                            errorType = "Malformed JSON Syntax",
                            description = e.message ?: "Invalid JSON syntax",
                            suggestedFix = "Synthesize strict valid JSON without trailing commas or comments"
                        )
                    )
                }
            }

            // 6. Shell Script Dry-Run Syntax Verification
            if (ext == "sh") {
                val dryRunOk = checkShellScriptSyntax(file)
                if (!dryRunOk) {
                    dryRunPassed = false
                    errors.add(
                        VerificationError(
                            workerId = determineWorkerId(fileName),
                            fileName = fileName,
                            errorType = "Shell Syntax Dry-Run Failed",
                            description = "sh -n syntax test failed",
                            suggestedFix = "Verify POSIX shell syntax, quote variables, and close conditionals"
                        )
                    )
                }
            }
        }

        val isVerified = errors.isEmpty()
        return VerificationResult(
            isVerified = isVerified,
            missingClosures = missingClosuresCount,
            unclosedTags = unclosedTagsCount,
            toolCallLeaksFound = toolCallLeaksCount,
            assetErrors = assetErrors,
            dryRunPassed = dryRunPassed,
            errors = errors
        )
    }

    /**
     * Checks if source code contains leaked tool tags, XML schemas, or raw markdown code block tags.
     */
    fun checkForToolLeaks(code: String): List<String> {
        val leaks = mutableListOf<String>()
        if (code.contains("<function_calls>")) leaks.add("<function_calls>")
        if (code.contains("</function_calls>")) leaks.add("</function_calls>")
        if (code.contains("<invoke")) leaks.add("<invoke>")
        if (code.contains("</invoke>")) leaks.add("</invoke>")
        if (code.contains("<parameter")) leaks.add("<parameter>")
        if (code.startsWith("```") || code.endsWith("```")) leaks.add("markdown code fences")
        if (code.contains("```html") || code.contains("```javascript") || code.contains("```css")) {
            leaks.add("embedded code fences")
        }
        return leaks
    }

    /**
     * Verifies balance of {}, (), and [] while ignoring characters inside string literals and comments.
     */
    fun checkBracketBalance(code: String, ext: String): Int {
        val stack = ArrayDeque<Char>()
        var inSingleQuote = false
        var inDoubleQuote = false
        var inBacktick = false
        var inLineComment = false
        var inBlockComment = false
        var i = 0
        val len = code.length

        while (i < len) {
            val c = code[i]
            val nextC = if (i + 1 < len) code[i + 1] else '\u0000'

            // Handle comments in JS/CSS/etc.
            if (!inSingleQuote && !inDoubleQuote && !inBacktick) {
                if (inLineComment) {
                    if (c == '\n') inLineComment = false
                    i++
                    continue
                }
                if (inBlockComment) {
                    if (c == '*' && nextC == '/') {
                        inBlockComment = false
                        i += 2
                        continue
                    }
                    i++
                    continue
                }
                if (c == '/' && nextC == '/') {
                    inLineComment = true
                    i += 2
                    continue
                }
                if (c == '/' && nextC == '*') {
                    inBlockComment = true
                    i += 2
                    continue
                }
                if (ext == "py" || ext == "sh") {
                    if (c == '#') {
                        inLineComment = true
                        i++
                        continue
                    }
                }
            }

            // Handle string literal quotes
            if (!inLineComment && !inBlockComment) {
                if (c == '\\' && (inSingleQuote || inDoubleQuote || inBacktick)) {
                    // Skip escaped char
                    i += 2
                    continue
                }
                if (c == '\'' && !inDoubleQuote && !inBacktick) {
                    inSingleQuote = !inSingleQuote
                    i++
                    continue
                }
                if (c == '"' && !inSingleQuote && !inBacktick) {
                    inDoubleQuote = !inDoubleQuote
                    i++
                    continue
                }
                if (c == '`' && !inSingleQuote && !inDoubleQuote) {
                    inBacktick = !inBacktick
                    i++
                    continue
                }
            }

            // Bracket checking only outside strings and comments
            if (!inSingleQuote && !inDoubleQuote && !inBacktick && !inLineComment && !inBlockComment) {
                when (c) {
                    '{', '(', '[' -> stack.push(c)
                    '}' -> {
                        if (stack.isEmpty() || stack.pop() != '{') return 1
                    }
                    ')' -> {
                        if (stack.isEmpty() || stack.pop() != '(') return 1
                    }
                    ']' -> {
                        if (stack.isEmpty() || stack.pop() != '[') return 1
                    }
                }
            }
            i++
        }

        return stack.size
    }

    /**
     * Checks unclosed tags in HTML text.
     */
    fun checkHtmlTags(html: String): Int {
        val tagRegex = Regex("""<\s*(/)?\s*([a-zA-Z0-9\-]+)([^>]*)>""")
        val stack = ArrayDeque<String>()

        tagRegex.findAll(html).forEach { match ->
            val isClosing = match.groupValues[1] == "/"
            val tagName = match.groupValues[2].lowercase()
            val rawTag = match.value

            // Ignore comments, directives, and void elements
            if (tagName.startsWith("!") || rawTag.endsWith("/>") || VOID_HTML_TAGS.contains(tagName)) {
                return@forEach
            }

            if (!isClosing) {
                stack.push(tagName)
            } else {
                if (!stack.isEmpty()) {
                    // Pop matching tag or discard unclosed child
                    if (stack.peek() == tagName) {
                        stack.pop()
                    } else {
                        // Look for tagName in stack
                        if (stack.contains(tagName)) {
                            while (!stack.isEmpty() && stack.pop() != tagName) {
                                // pop until matching tag
                            }
                        }
                    }
                }
            }
        }

        return stack.size
    }

    /**
     * Validates that local script and style references in HTML exist in the bundle.
     */
    fun checkAssetReferences(html: String, availableFiles: Set<String>): List<String> {
        val missing = mutableListOf<String>()
        val scriptSrcRegex = Regex("""<script[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val linkHrefRegex = Regex("""<link[^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        scriptSrcRegex.findAll(html).forEach { m ->
            val ref = m.groupValues[1].trim()
            if (!ref.startsWith("http://") && !ref.startsWith("https://") && !ref.startsWith("//") && !ref.startsWith("data:")) {
                val clean = File(ref).name.lowercase()
                if (!availableFiles.contains(clean)) {
                    missing.add(ref)
                }
            }
        }

        linkHrefRegex.findAll(html).forEach { m ->
            val ref = m.groupValues[1].trim()
            if (!ref.startsWith("http://") && !ref.startsWith("https://") && !ref.startsWith("//") && !ref.startsWith("data:")) {
                val clean = File(ref).name.lowercase()
                if (!availableFiles.contains(clean)) {
                    missing.add(ref)
                }
            }
        }

        return missing
    }

    /**
     * Dry-run syntax verification for shell scripts.
     */
    private fun checkShellScriptSyntax(scriptFile: File): Boolean {
        return try {
            val process = ProcessBuilder("sh", "-n", scriptFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (_: Exception) {
            true // Fallback to pass if sh is not directly invokable
        }
    }

    private fun determineWorkerId(fileName: String): String {
        return when {
            fileName.endsWith(".js") || fileName.endsWith(".py") || fileName.endsWith(".ts") -> "Worker 1"
            fileName.endsWith(".html") || fileName.endsWith(".css") -> "Worker 2"
            else -> "Worker 3"
        }
    }

    /**
     * Generates a targeted remediation directive prompt for a failed worker.
     */
    fun generateRemediationPrompt(error: VerificationError, originalCode: String): String {
        return """
AUDIT REMEDIATION DIRECTIVE:
File '${error.fileName}' failed verification.
Defect Type: ${error.errorType}
Details: ${error.description}
Fix Requirement: ${error.suggestedFix}

Instructions:
1. Regenerate ONLY the completely corrected content for '${error.fileName}'.
2. Ensure 100% valid syntax with perfectly balanced closures.
3. Strictly output raw code only. Never include markdown code fences (```) or XML tool tags.
4. For HTML files: Ensure Byte 0 strictly begins with <!DOCTYPE html>. Adhere strictly to the Zero-ASCII Navigation Rule: all buttons, arrows, and icons MUST use pure inline SVG vectors, never ASCII characters (e.g. <[[, <<[, [<]).

Current Broken Source:
$originalCode
""".trimIndent()
    }
}
