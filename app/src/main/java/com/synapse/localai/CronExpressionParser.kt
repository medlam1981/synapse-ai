package com.synapse.localai

import java.util.Calendar
import java.util.Locale

/**
 * Lightweight Cron Expression Parser & Next Run Calculator.
 *
 * Supports:
 * - 5-field standard cron: "minute hour day-of-month month day-of-week"
 *   e.g. "0 * * * *" (every hour), "* / 15 * * * *" (every 15 min), "0 2 * * *" (2 AM daily)
 * - Shorthand aliases: "@hourly", "@daily", "@midnight"
 * - Interval syntax: "@every 10m", "@every 2h"
 */
object CronExpressionParser {

    /**
     * Computes the next epoch timestamp (ms) matching [cronExpr] strictly after [fromTimeMs].
     * Returns 0L if expression is invalid or cannot be matched.
     */
    fun getNextExecutionTime(cronExpr: String, fromTimeMs: Long = System.currentTimeMillis()): Long {
        val trimmed = cronExpr.trim().lowercase(Locale.ROOT)

        // Handle interval syntax: "@every 15m", "@every 2h", "@every 30s"
        if (trimmed.startsWith("@every ")) {
            val intervalStr = trimmed.removePrefix("@every ").trim()
            val millis = parseIntervalToMillis(intervalStr)
            return if (millis > 0) fromTimeMs + millis else 0L
        }

        // Standard aliases
        val normalized = when (trimmed) {
            "@hourly" -> "0 * * * *"
            "@daily", "@midnight" -> "0 0 * * *"
            "@weekly" -> "0 0 * * 0"
            else -> trimmed
        }

        val parts = normalized.split(Regex("\\s+"))
        if (parts.size != 5) return 0L

        val minutePart = parts[0]
        val hourPart = parts[1]
        val domPart = parts[2]
        val monthPart = parts[3]
        val dowPart = parts[4]

        val cal = Calendar.getInstance()
        cal.timeInMillis = fromTimeMs
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.MINUTE, 1) // strictly after fromTime
        cal.set(Calendar.SECOND, 0)

        // Iterate up to 366 days (527040 minutes) max to prevent infinite loops
        var steps = 0
        val maxSteps = 60 * 24 * 366

        while (steps < maxSteps) {
            val m = cal.get(Calendar.MINUTE)
            val h = cal.get(Calendar.HOUR_OF_DAY)
            val dom = cal.get(Calendar.DAY_OF_MONTH)
            val month = cal.get(Calendar.MONTH) + 1 // 1-12
            val dow = cal.get(Calendar.DAY_OF_WEEK) - 1 // 0=Sunday, 1=Monday, ...

            if (!matchesField(monthPart, month, 1, 12)) {
                cal.add(Calendar.MONTH, 1)
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                steps += 60
                continue
            }

            if (!matchesField(domPart, dom, 1, 31) || !matchesField(dowPart, dow, 0, 6)) {
                cal.add(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                steps += 60
                continue
            }

            if (!matchesField(hourPart, h, 0, 23)) {
                cal.add(Calendar.HOUR_OF_DAY, 1)
                cal.set(Calendar.MINUTE, 0)
                steps += 60
                continue
            }

            if (!matchesField(minutePart, m, 0, 59)) {
                cal.add(Calendar.MINUTE, 1)
                steps++
                continue
            }

            // All 5 fields matched
            return cal.timeInMillis
        }

        return 0L
    }

    private fun parseIntervalToMillis(interval: String): Long {
        val num = interval.filter { it.isDigit() }.toLongOrNull() ?: return 0L
        return when {
            interval.endsWith("s") -> num * 1_000L
            interval.endsWith("m") -> num * 60_000L
            interval.endsWith("h") -> num * 3_600_000L
            interval.endsWith("d") -> num * 86_400_000L
            else -> num * 60_000L // default minutes
        }
    }

    private fun matchesField(field: String, value: Int, min: Int, max: Int): Boolean {
        if (field == "*") return true

        // Step: */5 or 1-30/5
        if (field.contains("/")) {
            val subParts = field.split("/")
            if (subParts.size == 2) {
                val step = subParts[1].toIntOrNull() ?: return false
                val rangePart = subParts[0]
                val range = if (rangePart == "*") min..max else {
                    val r = rangePart.split("-")
                    val start = r[0].toIntOrNull() ?: min
                    val end = if (r.size > 1) r[1].toIntOrNull() ?: max else max
                    start..end
                }
                if (value !in range) return false
                return (value - range.first) % step == 0
            }
        }

        // List: 1,2,5
        if (field.contains(",")) {
            val list = field.split(",").mapNotNull { it.toIntOrNull() }
            return value in list
        }

        // Range: 1-5
        if (field.contains("-")) {
            val r = field.split("-")
            val start = r[0].toIntOrNull() ?: return false
            val end = r.getOrNull(1)?.toIntOrNull() ?: return false
            return value in start..end
        }

        // Exact number
        val exact = field.toIntOrNull()
        return exact == value
    }
}
