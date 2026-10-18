package com.synapse.localai.security

import com.synapse.localai.AtlasLog as Log
import java.util.Locale

/**
 * Intelligent Permission Guard, Command Sanitizer and Sandboxing Layer.
 *
 * Ensures system safety while maintaining full automation capabilities:
 * 1. Risk Tier Classification: Distinguishes safe read-only queries from sensitive modifications.
 * 2. Destructive Command Interception: Blocks bricking commands such as rm -rf / or dd to block devices.
 * 3. Execution Sanitization: Strips hazardous environment variable injections (LD_PRELOAD, LD_LIBRARY_PATH).
 * 4. Security decisions are returned to the caller without writing command audit logs.
 */
object CommandSecurityGuard {

    private const val TAG = "CommandSecurityGuard"

    enum class RiskTier {
        SAFE,
        AUDIT_LOG,
        RESTRICTED_REVERSIBLE,
        CRITICAL_BLOCKED
    }

    data class InspectionResult(
        val tier: RiskTier,
        val isAllowed: Boolean,
        val sanitizedCommand: String,
        val reason: String? = null
    )

    // Critical blocklist patterns
    private val DESTRUCTIVE_PATTERNS = listOf(
        Regex("""rm\s+(-[rRfF]{1,4})\s+(/|/\*|/system|/data|/boot|/vendor)\b""", RegexOption.IGNORE_CASE),
        Regex("""rm\s+-+[rRfF]+\s+--no-preserve-root""", RegexOption.IGNORE_CASE),
        Regex("""dd\s+.*of=/dev/block/""", RegexOption.IGNORE_CASE),
        Regex("""mkfs(\.[a-zA-Z0-9]+)?\s+/dev/block/""", RegexOption.IGNORE_CASE),
        Regex("""format\s+/dev/block/""", RegexOption.IGNORE_CASE),
        Regex("""flash_image\b""", RegexOption.IGNORE_CASE),
        Regex("""wipefs\b""", RegexOption.IGNORE_CASE)
    )

    // Safe read-only commands
    private val SAFE_COMMAND_PREFIXES = listOf(
        "cat", "ls", "grep", "egrep", "uptime", "free", "df", "ps", "top",
        "uname", "whoami", "id", "date", "echo", "which", "head", "tail",
        "wc", "find", "stat", "pwd", "netstat", "ss", "ifconfig", "ip", "env"
    )

    /**
     * Inspects and sanitizes a shell command before dispatching to root shell.
     */
    fun inspectAndFilter(rawCommand: String): InspectionResult {
        val trimmed = rawCommand.trim()
        if (trimmed.isEmpty()) {
            return InspectionResult(RiskTier.SAFE, true, "", null)
        }

        // 1. Check for Destructive commands
        for (pattern in DESTRUCTIVE_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                val reason = "Rejected destructive command: prohibited device-bricking operation."
                return InspectionResult(
                    tier = RiskTier.CRITICAL_BLOCKED,
                    isAllowed = false,
                    sanitizedCommand = trimmed,
                    reason = reason
                )
            }
        }

        // 2. Sanitize hazardous environment variable injections
        val sanitized = trimmed
            .replace(Regex("""LD_PRELOAD=[^\s;]+"""), "")
            .replace(Regex("""LD_LIBRARY_PATH=[^\s;]+"""), "")
            .trim()

        // 3. Classify Risk Tier
        val firstToken = sanitized.split(Regex("\\s+")).firstOrNull()?.lowercase(Locale.ROOT) ?: ""
        val tier = when {
            SAFE_COMMAND_PREFIXES.contains(firstToken) -> RiskTier.SAFE
            firstToken in listOf("kill", "pkill", "killall", "setprop", "iptables") -> RiskTier.RESTRICTED_REVERSIBLE
            else -> RiskTier.AUDIT_LOG
        }

        return InspectionResult(
            tier = tier,
            isAllowed = true,
            sanitizedCommand = sanitized,
            reason = null
        )
    }

}
