package com.synapse.localai

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object KeyUsageManager {
    private const val PREFS_NAME = "key_usage_prefs"
    private const val MAX_REQUESTS_PER_DAY = 1500

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getTodayDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    private fun getKeyHash(apiKey: String): String {
        return if (apiKey.length > 8) {
            apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length - 4)
        } else {
            apiKey
        }
    }

    fun incrementUsage(context: Context, apiKey: String) {
        val isExhausted = apiKey.endsWith("_EXHAUSTED")
        val realKey = if (isExhausted) apiKey.substringBefore("_EXHAUSTED") else apiKey
        
        val prefs = getPrefs(context)
        val today = getTodayDateString()
        val storedDate = prefs.getString("current_date", "")
        
        if (storedDate != today) {
            prefs.edit().clear().putString("current_date", today).apply()
        }

        val keyHash = getKeyHash(realKey)
        if (isExhausted) {
            prefs.edit().putInt("usage_$keyHash", MAX_REQUESTS_PER_DAY).apply()
        } else {
            val currentCount = prefs.getInt("usage_$keyHash", 0)
            prefs.edit().putInt("usage_$keyHash", currentCount + 1).apply()
        }
    }

    fun getTotalUsageStats(context: Context, apiKeysRaw: String): TotalKeyStat {
        val prefs = getPrefs(context)
        val today = getTodayDateString()
        val storedDate = prefs.getString("current_date", "")
        
        if (storedDate != today) {
            prefs.edit().clear().putString("current_date", today).apply()
        }

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
