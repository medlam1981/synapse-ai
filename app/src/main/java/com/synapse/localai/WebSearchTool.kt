package com.synapse.localai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object WebSearchTool {

    suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return@withContext "Empty search query."

        try {
            // Fast-path for live weather inquiries
            val lower = trimmedQuery.lowercase()
            if (lower.contains("طقس") || lower.contains("weather") || lower.contains("météo") || lower.contains("meteo")) {
                val weather = fetchWeather(trimmedQuery)
                if (weather.isNotBlank()) return@withContext weather
            }

            // Headless web search via DuckDuckGo HTML
            val encoded = URLEncoder.encode(trimmedQuery, "UTF-8")
            val targetUrl = "https://html.duckduckgo.com/html/?q=$encoded"
            val conn = URL(targetUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            val html = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val snippetRegex = Regex("""<a class="result__snippet[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            val results = snippetRegex.findAll(html).take(4).map { match ->
                match.groupValues[1]
                    .replace(Regex("<.*?>"), "")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&amp;", "&")
                    .replace("&nbsp;", " ")
                    .trim()
            }.filter { it.isNotBlank() }.toList()

            if (results.isNotEmpty()) {
                results.joinToString("\n• ", prefix = "Verified Web Results for \"$trimmedQuery\":\n• ")
            } else {
                "No direct web results found for: $trimmedQuery"
            }
        } catch (e: Exception) {
            "Internet retrieval failed: ${e.message}"
        }
    }

    private fun fetchWeather(query: String): String {
        return try {
            val clean = query
                .replace("حالة", "")
                .replace("الطقس", "")
                .replace("طقس", "")
                .replace("في", "")
                .replace("بـ", "")
                .replace("اليوم", "")
                .replace("today", "", ignoreCase = true)
                .replace("weather", "", ignoreCase = true)
                .replace("in", "", ignoreCase = true)
                .replace("بليونش", "Belyounech")
                .replace("طنجة", "Tangier")
                .replace("تطوان", "Tetouan")
                .replace("الرباط", "Rabat")
                .replace("الدار البيضاء", "Casablanca")
                .replace("مراكش", "Marrakech")
                .replace("المغرب", "Morocco")
                .trim()

            val targetLocation = if (clean.isNotBlank()) clean else "Belyounech"
            val encodedLoc = URLEncoder.encode(targetLocation, "UTF-8")
            val conn = URL("https://wttr.in/$encodedLoc?format=%l:+%c+%t,+Wind:+%w,+Humidity:+%h&m").openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "curl/7.88.1")
            conn.connectTimeout = 6000
            conn.readTimeout = 6000

            val text = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            conn.disconnect()

            if (text.isNotBlank() && !text.contains("Unknown location")) {
                "Live Weather Observation: $text"
            } else ""
        } catch (_: Exception) {
            ""
        }
    }
}