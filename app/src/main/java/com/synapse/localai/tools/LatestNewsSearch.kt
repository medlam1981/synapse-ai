package com.synapse.localai.tools

import com.synapse.localai.AtlasLog as Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun isLatestNewsRequest(query: String): Boolean = Regex(
    """(?:latest|breaking|top|today'?s|recent)\s+(?:world\s+)?(?:news|headlines)|\bnews\s+(?:today|now|latest)\b|(?:اخر|آخر|أحدث)\s+(?:ال)?(?:اخبار|أخبار|مستجدات|عناوين)|(?:مستجدات|عناوين)\s+(?:اليوم|الاخبار|الأخبار)|(?:أخبار|اخبار)\s+(?:اليوم|العالم|عاجلة)|(?:actualit[eé]s|derni[eè]res?\s+(?:nouvelles|actualit[eé]s)|titres du jour)|(?:ultimas noticias|últimas noticias|noticias (?:de hoy|recientes))""",
    RegexOption.IGNORE_CASE
).containsMatchIn(query)

internal object LatestNewsSearch {
    private const val TAG = "LatestNewsSearch"
    private const val MAX_AGE_MS = 72L * 60 * 60 * 1000
    private const val MAX_RESULTS = 8
    private const val MAX_RESULTS_PER_FEED = 8
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36"

    private data class NewsLocale(val language: String, val country: String, val feedLocale: String)
    private data class Article(
        val title: String,
        val source: String,
        val publishedAt: Long,
        val link: String,
        val summary: String
    )

    private val preferredSources = listOf(
        "reuters", "associated press", "ap news", "bbc", "the guardian", "financial times",
        "new york times", "washington post", "al jazeera", "france 24", "dw", "deutsche welle",
        "npr", "afp", "le monde", "el pais", "the economist", "sky news"
    )

    suspend fun search(rawQuery: String): String = withContext(Dispatchers.IO) {
        val locale = when {
            rawQuery.any { it in '\u0600'..'\u06FF' } -> NewsLocale("ar", "MA", "ar-MA")
            rawQuery.contains(Regex("""\b(actualit[eé]s|nouvelles|titres)\b""", RegexOption.IGNORE_CASE)) ->
                NewsLocale("fr", "FR", "fr-FR")
            rawQuery.contains(Regex("""\b(noticias|últimas|ultimas)\b""", RegexOption.IGNORE_CASE)) ->
                NewsLocale("es", "ES", "es-ES")
            else -> NewsLocale("en", "US", "en-US")
        }
        val topic = extractTopic(rawQuery)
        val queries = if (topic.isBlank()) {
            listOf(
                "world news when:1d",
                "international politics diplomacy when:1d",
                "global economy when:1d",
                "world technology science when:1d",
                "climate health when:1d",
                "world news site:reuters.com when:1d",
                "world news site:apnews.com when:1d",
                "world news site:bbc.com when:1d",
                "world news site:aljazeera.com when:1d"
            )
        } else {
            listOf(
                "$topic when:1d",
                "$topic site:reuters.com when:3d",
                "$topic site:apnews.com when:3d",
                "$topic site:bbc.com when:3d",
                "$topic site:aljazeera.com when:3d",
                "$topic site:theguardian.com when:3d",
                "$topic site:france24.com when:3d"
            )
        }
        val now = System.currentTimeMillis()
        val candidates = fetchFeeds(queries, locale)
            .filter { it.publishedAt in (now - MAX_AGE_MS)..now }
            .distinctBy { it.link.ifBlank { it.title.lowercase(Locale.ROOT) } }
            .sortedWith(
                compareByDescending<Article> { sourceRank(it.source) }
                    .thenByDescending { it.publishedAt }
            )
        val sourceCounts = mutableMapOf<String, Int>()
        val diverseArticles = candidates.filter { article ->
            val source = article.source.lowercase(Locale.ROOT).trim()
            val currentCount = sourceCounts[source] ?: 0
            if (currentCount >= 2) false else {
                sourceCounts[source] = currentCount + 1
                true
            }
        }
        val selectedArticles = (diverseArticles + candidates.filterNot { it in diverseArticles })
            .distinctBy { it.link.ifBlank { it.title.lowercase(Locale.ROOT) } }
            .take(MAX_RESULTS)
        val articles = enrichArticles(selectedArticles)
        if (articles.isEmpty()) {
            return@withContext "No verifiable news articles published in the last 72 hours were retrieved. Do not present older headlines as today's news; retry with a specific topic or region."
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        buildString {
            appendLine("LIVE NEWS RESEARCH — retrieved at ${dateFormat.format(Date(now))}.")
            appendLine("Freshness filter: reports published in the last 72 hours. Retrieved and reviewed ${articles.size} distinct reports.")
            appendLine("The following is research context for your synthesis, not text to reproduce verbatim.")
            articles.forEachIndexed { index, article ->
                appendLine()
                appendLine("REPORT ${index + 1}")
                appendLine("Headline: ${article.title}")
                appendLine("Publisher: ${article.source.ifBlank { "Publisher not identified" }}")
                appendLine("Publication time: ${dateFormat.format(Date(article.publishedAt))}")
                if (article.summary.isNotBlank()) {
                    appendLine("Verified report details: ${redactRawUrls(article.summary).take(1_200)}")
                }
            }
            appendLine()
            appendLine("FINAL ANSWER REQUIREMENTS — mandatory:")
            appendLine("- Do not output URLs, raw links, search-result dumps, or say only that links were found.")
            appendLine("- Write a detailed news briefing in the user's language, not a single paragraph.")
            appendLine("- Organize into topical sections. For every report, explain what happened using the verified report details, relevant context, and why it matters; include publisher and publication time as plain text.")
            appendLine("- Combine duplicate coverage of the same development, but cover each distinct development retrieved.")
            appendLine("- If an article has only a headline and no meaningful report details, say so plainly rather than inventing details.")
            append("- Use only the supplied reporting; do not claim unsupported facts or repeat this research context.")
        }
    }

    private suspend fun enrichArticles(articles: List<Article>): List<Article> = coroutineScope {
        articles.map { article ->
            async(Dispatchers.IO) {
                if (!article.link.startsWith("https://")) return@async article
                val pageText = ContentReaderTool.fetchUrl(article.link, maxChars = 5_000)
                    .removePrefix("[Page content is empty]")
                    .takeIf { it.length >= 250 && !it.startsWith("[Failed to fetch") && !it.startsWith("[Error fetching") }
                if (pageText.isNullOrBlank()) article
                else article.copy(summary = redactRawUrls(pageText))
            }
        }.awaitAll()
    }

    private fun extractTopic(query: String): String {
        val noise = setOf(
            "please", "give", "show", "tell", "me", "about", "what", "what's", "are", "is", "the", "for",
            "in", "on", "from", "around", "can", "could", "you", "brief", "briefing", "latest", "breaking",
            "top", "today", "now", "news", "headlines", "world", "actualités", "actualites", "dernières",
            "dernieres",
            "nouvelles", "noticias", "últimas", "ultimas", "اخبار", "أخبار", "الاخبار", "الأخبار",
            "اخر", "آخر", "أحدث", "اريد", "أريد", "اليوم", "العالم", "عاجلة", "العاجلة", "عناوين",
            "مستجدات", "من", "فضلك", "المرجو", "اعطني", "اعرض", "ما", "هي", "عن", "في", "على"
        )
        val withoutNewsIntent = query.replace(
            Regex("""(?:latest|breaking|top|today'?s|recent)\s+(?:world\s+)?(?:news|headlines)|\bnews\s+(?:today|now|latest)\b|(?:اخر|آخر)\s+(?:ال)?(?:اخبار|أخبار)|(?:مستجدات|عناوين)\s+(?:اليوم|الاخبار|الأخبار)|(?:أخبار|اخبار)\s+(?:اليوم|العالم|عاجلة)|(?:actualit[eé]s|derni[eè]res nouvelles|titres du jour)|(?:ultimas noticias|últimas noticias|noticias de hoy)""", RegexOption.IGNORE_CASE), " "
        )
        return withoutNewsIntent
            .replace(Regex("""[?!.,:؛،]+"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() && it.lowercase(Locale.ROOT) !in noise }
            .joinToString(" ")
    }

    private suspend fun fetchFeeds(queries: List<String>, locale: NewsLocale): List<Article> = coroutineScope {
        queries.map { query ->
            async(Dispatchers.IO) { fetchFeed(query, locale) }
        }.awaitAll().flatten()
    }

    private fun fetchFeed(query: String, locale: NewsLocale): List<Article> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://news.google.com/rss/search?q=$encodedQuery&hl=${locale.feedLocale}&gl=${locale.country}&ceid=${locale.country}:${locale.language}"
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (error: Exception) {
            Log.w(TAG, "Could not open news feed: ${error.message}")
            return emptyList()
        }
        return try {
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/rss+xml,application/xml,text/xml")
            connection.connectTimeout = 10_000
            connection.readTimeout = 12_000
            if (connection.responseCode !in 200..299) return emptyList()
            val parser = Xml.newPullParser()
            connection.inputStream.buffered().use { input ->
                parser.setInput(input, "UTF-8")
                parseFeed(parser)
            }
        } catch (error: Exception) {
            Log.w(TAG, "News feed retrieval/parsing failed: ${error.message}")
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    private fun parseFeed(parser: XmlPullParser): List<Article> {
        val articles = mutableListOf<Article>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && articles.size < MAX_RESULTS_PER_FEED) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("item", ignoreCase = true)) {
                parseItem(parser)?.let(articles::add)
            }
            event = parser.next()
        }
        return articles
    }

    private fun parseItem(parser: XmlPullParser): Article? {
        var title = ""
        var source = ""
        var pubDate = ""
        var link = ""
        var summary = ""
        val itemDepth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == itemDepth)) {
            if (event == XmlPullParser.START_TAG && parser.depth == itemDepth + 1) {
                val value = parser.nextText().trim()
                when (parser.name.lowercase(Locale.ROOT)) {
                    "title" -> title = cleanMarkup(value)
                    "source" -> source = cleanMarkup(value)
                    "pubdate" -> pubDate = value
                    "link" -> link = value
                    "description" -> summary = cleanMarkup(value)
                }
            }
            event = parser.next()
        }
        if (title.isBlank() || pubDate.isBlank()) return null
        val published = parseDate(pubDate) ?: return null
        val titleSource = title.substringAfterLast(" - ", "")
        return Article(
            title = title.substringBeforeLast(" - ").ifBlank { title },
            source = source.ifBlank { titleSource },
            publishedAt = published,
            link = link,
            summary = summary
        )
    }

    private fun parseDate(value: String): Long? {
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, d MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss z",
            "EEE, d MMM yyyy HH:mm:ss z"
        )
        return formats.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { isLenient = true }.parse(value)?.time
            }.getOrNull()
        }
    }

    private fun sourceRank(source: String): Int {
        val normalized = source.lowercase(Locale.ROOT)
        val index = preferredSources.indexOfFirst { normalized.contains(it) }
        return if (index < 0) 0 else preferredSources.size - index
    }

    private fun cleanMarkup(raw: String): String = raw
        .replace(Regex("""<script[\s\S]*?</script>|<style[\s\S]*?</style>""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""<[^>]*>"""), " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("""\s+"""), " ")
        .trim()

    internal fun redactRawUrls(text: String): String =
        text.replace(Regex("""https?://\S+""", RegexOption.IGNORE_CASE), "[link omitted]")
}
