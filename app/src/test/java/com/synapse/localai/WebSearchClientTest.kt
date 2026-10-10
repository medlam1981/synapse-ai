package com.synapse.localai

import com.synapse.localai.tools.isLatestNewsRequest
import com.synapse.localai.tools.LatestNewsSearch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchClientTest {
    @Test
    fun identifiesLatestNewsRequestsAcrossSupportedLanguages() {
        assertTrue(isLatestNewsRequest("أريد آخر الأخبار"))
        assertTrue(isLatestNewsRequest("latest world news"))
        assertTrue(isLatestNewsRequest("What's the latest news?"))
        assertTrue(isLatestNewsRequest("Les dernières actualités du jour"))
        assertTrue(isLatestNewsRequest("Últimas noticias de hoy"))
        assertTrue(isLatestNewsRequest("أحدث مستجدات اليوم"))
        assertFalse(isLatestNewsRequest("Explain how a news website works"))
    }

    @Test
    fun redactsRawLinksFromNewsResearchText() {
        val sanitized = LatestNewsSearch.redactRawUrls(
            "Report details at https://news.example.com/story?id=123 and https://other.example/path."
        )

        assertFalse(sanitized.contains("https://"))
        assertTrue(sanitized.contains("Report details at [link omitted]"))
    }
}
