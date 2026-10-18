package com.synapse.localai

import android.content.Context
import android.content.SharedPreferences
import com.synapse.localai.AtlasLog as Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object KeyUsageManager {
    private const val PREFS_NAME = "key_usage_prefs"
    private const val MAX_REQUESTS_PER_DAY = 1500

    // Temporary in-memory cooldown tracking for 503 / 429 RPM errors
    private val keyCooldowns = mutableMapOf<String, Long>()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getTodayDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    private fun getKeyHash(apiKey: String): String {
        val cleanKey = apiKey.substringBefore("_EXHAUSTED")
        return if (cleanKey.length > 8) {
            cleanKey.substring(0, 4) + "..." + cleanKey.substring(cleanKey.length - 4)
        } else {
            cleanKey
        }
    }

    private fun checkAndResetNewDay(context: Context) {
        val prefs = getPrefs(context)
        val today = getTodayDateString()
        val storedDate = prefs.getString("current_date", "")

        if (storedDate != today) {
            prefs.edit().clear().putString("current_date", today).apply()
            synchronized(keyCooldowns) {
                keyCooldowns.clear()
            }
            Log.i("KeyUsageManager", "New date detected ($today). Resetting key usage and cooldowns.")
        }
    }

    @Synchronized
    fun incrementUsage(context: Context, apiKey: String, modelName: String, tokensUsed: Long = 1L) {
        checkAndResetNewDay(context)

        val isExhausted = apiKey.endsWith("_EXHAUSTED")
        val realKey = if (isExhausted) apiKey.substringBefore("_EXHAUSTED") else apiKey
        val prefs = getPrefs(context)
        val keyHash = getKeyHash(realKey)

        val editor = prefs.edit()
        if (isExhausted) {
            editor.putInt("usage_$keyHash", MAX_REQUESTS_PER_DAY)
        } else {
            val currentCount = prefs.getInt("usage_$keyHash", 0)
            editor.putInt("usage_$keyHash", currentCount + 1)
            val tokenUsageKey = "token_usage_${keyHash}_$modelName"
            val tokenUsage = prefs.getLong(tokenUsageKey, 0L)
            editor.putLong(tokenUsageKey, tokenUsage + tokensUsed.coerceAtLeast(1L))
        }
        editor.apply()
    }

    @Synchronized
    fun getTokenUsage(context: Context, apiKey: String, modelName: String): Long {
        checkAndResetNewDay(context)
        return getPrefs(context).getLong("token_usage_${getKeyHash(apiKey)}_$modelName", 0L)
    }

    /**
     * Put a key into a temporary cooldown (e.g., 30s) due to 503 or 429 server pressure
     */
    fun markCooldown(apiKey: String, durationMs: Long = 30000L) {
        val keyHash = getKeyHash(apiKey)
        val unblockTime = System.currentTimeMillis() + durationMs
        synchronized(keyCooldowns) {
            keyCooldowns[keyHash] = unblockTime
        }
        Log.w("KeyUsageManager", "Key [$keyHash] cooling down for ${durationMs / 1000}s due to server load.")
    }

    /**
     * Checks if a key is eligible for execution
     */
    fun isKeyAvailable(context: Context, apiKey: String): Boolean {
        checkAndResetNewDay(context)

        val keyHash = getKeyHash(apiKey)
        val prefs = getPrefs(context)

        // Check 1: Daily quota limit
        val currentUsage = prefs.getInt("usage_$keyHash", 0)
        if (currentUsage >= MAX_REQUESTS_PER_DAY) {
            return false
        }

        // Check 2: Temporary server overload cooldown
        val now = System.currentTimeMillis()
        synchronized(keyCooldowns) {
            val cooldownUntil = keyCooldowns[keyHash] ?: 0L
            if (now < cooldownUntil) {
                return false
            }
        }

        return true
    }

    /**
     * Dynamically picks the best available key from any list size (28, 50, etc.)
     * Selection is based on load balancing (picks key with lowest daily usage).
     */
    fun getNextAvailableKey(context: Context, apiKeys: List<String>): String? {
        checkAndResetNewDay(context)

        if (apiKeys.isEmpty()) return null

        val availableKeys = apiKeys.filter { isKeyAvailable(context, it) }
        if (availableKeys.isEmpty()) {
            return null // All keys exhausted or cooling down -> trigger local fallback
        }

        val prefs = getPrefs(context)
        return availableKeys.minByOrNull { key ->
            prefs.getInt("usage_${getKeyHash(key)}", 0)
        }
    }

    fun getTotalUsageStats(context: Context, apiKeysRaw: String): TotalKeyStat {
        checkAndResetNewDay(context)

        val prefs = getPrefs(context)
        val keys = apiKeysRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        var totalConsumed = 0
        var totalCapacity = keys.size * MAX_REQUESTS_PER_DAY

        for (key in keys) {
            val keyHash = getKeyHash(key)
            totalConsumed += prefs.getInt("usage_$keyHash", 0)
        }

        if (totalCapacity == 0) totalCapacity = 1 // Prevent division by zero

        val totalRemaining = (totalCapacity - totalConsumed).coerceAtLeast(0)
        val percentRemaining = (totalRemaining * 100) / totalCapacity

        return TotalKeyStat(totalConsumed, totalRemaining, percentRemaining, totalCapacity)
    }
}

data class TotalKeyStat(
    val consumed: Int,
    val remaining: Int,
    val percentRemaining: Int,
    val capacity: Int
)
