package com.synapse.localai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.regex.Pattern

object YouTubeTool {
    private val client = OkHttpClient()

    /**
     * Extracts the video ID from various YouTube URL formats.
     */
    fun extractVideoId(url: String): String? {
        val pattern = Pattern.compile("(?:v=|youtu\\.be/|embed/)([^&?#\\n]+)")
        val matcher = pattern.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    /**
     * Checks if the message contains a YouTube link.
     */
    fun containsYouTubeLink(text: String): Boolean {
        return text.contains("youtube.com") || text.contains("youtu.be")
    }

    /**
     * Fetches the video title and a heavily compressed version of the transcript to save tokens.
     */
    suspend fun fetchVideoContext(videoId: String): String = withContext(Dispatchers.IO) {
        try {
            // Fetch the YouTube page HTML
            val request = Request.Builder()
                .url("https://www.youtube.com/watch?v=$videoId")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext "Failed to load video page."

            // Extract Title
            val titleRegex = Pattern.compile("<title>(.*?)</title>")
            val titleMatcher = titleRegex.matcher(html)
            val title = if (titleMatcher.find()) titleMatcher.group(1).replace(" - YouTube", "") else "Unknown Title"

            // Extract Transcript JSON URL
            // YouTube embeds player response JSON containing caption tracks
            val jsonRegex = Pattern.compile("\"captions\":\\{(.*?)\"videoDetails\"")
            val jsonMatcher = jsonRegex.matcher(html)
            
            var transcriptUrl = ""
            
            if (jsonMatcher.find()) {
                val captionsJsonStr = "{\"captions\":{\"${jsonMatcher.group(1).dropLast(1)}" // Reconstruct valid JSON chunk
                try {
                    val captionsObj = JSONObject(captionsJsonStr)
                    val tracks = captionsObj.getJSONObject("captions")
                        .getJSONObject("playerCaptionsTracklistRenderer")
                        .getJSONArray("captionTracks")
                    
                    if (tracks.length() > 0) {
                        // Prefer English or auto-generated
                        var selectedTrack = tracks.getJSONObject(0)
                        for (i in 0 until tracks.length()) {
                            val track = tracks.getJSONObject(i)
                            if (track.getString("languageCode").startsWith("en")) {
                                selectedTrack = track
                                break
                            }
                        }
                        transcriptUrl = selectedTrack.getString("baseUrl")
                    }
                } catch (e: Exception) {
                    // JSON parsing failed, fallback
                }
            }

            if (transcriptUrl.isEmpty()) {
                return@withContext "Title: $title\n\n[Transcript unavailable or disabled for this video. Ask the model to infer from the title.]"
            }

            // Fetch the actual XML transcript
            val transcriptRequest = Request.Builder().url(transcriptUrl).build()
            val transcriptResponse = client.newCall(transcriptRequest).execute()
            val xml = transcriptResponse.body?.string() ?: return@withContext "Title: $title\n\n[Failed to download transcript data.]"

            // Parse XML and compress text to save Quota
            val textRegex = Pattern.compile("<text[^>]*>(.*?)</text>")
            val textMatcher = textRegex.matcher(xml)
            val fullText = StringBuilder()
            
            while (textMatcher.find()) {
                val decoded = textMatcher.group(1)
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&#39;", "'")
                    .replace("&quot;", "\"")
                fullText.append(decoded).append(" ")
            }

            // Compress to save tokens: Take every Nth word or summarize locally if too long.
            // For now, limit the total words to ~2000 (roughly 2500 tokens) to ensure it fits comfortably and cheaply.
            val words = fullText.toString().split("\\s+".toRegex())
            val compressedText = if (words.size > 1500) {
                // If it's a long video, take the first 500 words, middle 500, and last 500
                val firstPart = words.take(500).joinToString(" ")
                val middleIdx = words.size / 2
                val midPart = words.subList(middleIdx - 250, middleIdx + 250).joinToString(" ")
                val lastPart = words.takeLast(500).joinToString(" ")
                "$firstPart ... [MIDDLE COMPRESSED TO SAVE TOKENS] ... $midPart ... [END COMPRESSED] ... $lastPart"
            } else {
                fullText.toString()
            }

            return@withContext "Title: $title\n\n[VIDEO TRANSCRIPT]:\n$compressedText\n\n[End of Transcript. Please summarize or answer the user's question based on this text.]"

        } catch (e: Exception) {
            return@withContext "Error analyzing video: ${e.message}"
        }
    }
}
