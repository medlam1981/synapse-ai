package com.synapse.localai.memory

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Collections

/**
 * Persistent Vector Memory Engine (Long-Term Context & Semantic Recall).
 *
 * Persists user preferences, recurring scripts, project context, and knowledge
 * across working sessions in `/sdcard/Medlam-Ai/memory/vector_store.json`.
 */
object VectorMemoryManager {

    private const val TAG = "VectorMemory"
    private const val PRIMARY_DIR = "/sdcard/Medlam-Ai/memory"
    private const val VECTOR_FILE = "vector_store.json"

    private val records = Collections.synchronizedList(mutableListOf<VectorRecord>())
    @Volatile private var isLoaded = false
    private var appContext: Context? = null

    fun init(context: Context? = null) {
        appContext = context?.applicationContext
        if (!isLoaded) {
            loadRecords()
            isLoaded = true
        }
    }

    private fun getStorageFile(): File {
        val primary = File(PRIMARY_DIR)
        if (!primary.exists()) {
            try { primary.mkdirs() } catch (_: Exception) {}
        }
        if (primary.exists() && primary.canWrite()) {
            return File(primary, VECTOR_FILE)
        }
        val fallbackDir = appContext?.filesDir ?: File("/data/local/tmp/memory")
        if (!fallbackDir.exists()) fallbackDir.mkdirs()
        return File(fallbackDir, VECTOR_FILE)
    }

    /**
     * Stores a new memory record, calculating its embedding vector on the fly.
     */
    fun store(
        content: String,
        category: String = "FACT",
        metadata: Map<String, String> = emptyMap()
    ): VectorRecord {
        val cleanContent = content.trim()
        val vector = LightweightEmbeddingEngine.embed(cleanContent)
        val record = VectorRecord(
            content = cleanContent,
            category = category,
            metadata = metadata,
            vector = vector
        )

        synchronized(records) {
            // Remove duplicate if same content already exists
            records.removeAll { it.content.equals(cleanContent, ignoreCase = true) }
            records.add(0, record)
            saveRecords()
        }

        Log.i(TAG, "Stored vector memory [${record.category}]: \"${cleanContent.take(50)}\" (${record.id})")
        return record
    }

    /**
     * Searches stored memories semantically using cosine similarity.
     */
    fun search(
        query: String,
        topK: Int = 3,
        minSimilarity: Float = 0.25f,
        categoryFilter: String? = null
    ): List<Pair<VectorRecord, Float>> {
        val queryVector = LightweightEmbeddingEngine.embed(query)
        val matches = mutableListOf<Pair<VectorRecord, Float>>()

        synchronized(records) {
            for (rec in records) {
                if (categoryFilter != null && !rec.category.equals(categoryFilter, ignoreCase = true)) {
                    continue
                }
                val sim = LightweightEmbeddingEngine.cosineSimilarity(queryVector, rec.vector)
                if (sim >= minSimilarity) {
                    matches.add(rec to sim)
                }
            }
        }

        matches.sortByDescending { it.second }
        val topMatches = matches.take(topK)

        // Update access stats for top matches
        val now = System.currentTimeMillis()
        topMatches.forEach { (rec, _) ->
            rec.accessCount++
            rec.lastAccessedAt = now
        }

        return topMatches
    }

    /**
     * Retrieves recent records regardless of vector match.
     */
    fun getRecentRecords(limit: Int = 10, category: String? = null): List<VectorRecord> {
        synchronized(records) {
            val filtered = if (category != null) {
                records.filter { it.category.equals(category, ignoreCase = true) }
            } else records
            return filtered.sortedByDescending { it.lastAccessedAt }.take(limit)
        }
    }

    /**
     * Deletes a record by ID.
     */
    fun delete(id: String): Boolean {
        synchronized(records) {
            val removed = records.removeAll { it.id == id }
            if (removed) saveRecords()
            return removed
        }
    }

    /**
     * Clears all stored memories.
     */
    fun clearAll() {
        synchronized(records) {
            records.clear()
            saveRecords()
        }
    }

    /**
     * Automatically retrieves and formats relevant long-term memories and preferences
     * to inject into the system prompt for the given user prompt.
     */
    fun formatRelevantContextForPrompt(userPrompt: String): String {
        if (records.isEmpty()) return ""

        val topMatches = search(userPrompt, topK = 4, minSimilarity = 0.28f)
        if (topMatches.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("\n*** PERSISTENT LONG-TERM CONTEXT & RECALLED MEMORIES ***")
        sb.appendLine("The following relevant user preferences, scripts, or project facts were recalled from persistent vector memory:")
        topMatches.forEach { (rec, score) ->
            val label = when (rec.category.uppercase()) {
                "USER_PREFERENCE" -> "User Preference"
                "RECURRING_SCRIPT" -> "Recurring Script"
                "PROJECT_CONTEXT" -> "Project Context"
                else -> "Learned Fact"
            }
            sb.appendLine("• [$label] ${rec.content}")
        }
        return sb.toString()
    }

    private fun loadRecords() {
        try {
            val file = getStorageFile()
            if (!file.exists()) {
                seedInitialPreferences()
                return
            }
            val content = file.readText(StandardCharsets.UTF_8).trim()
            if (content.isEmpty()) return

            val array = JSONArray(content)
            synchronized(records) {
                records.clear()
                for (i in 0 until array.length()) {
                    try {
                        records.add(VectorRecord.fromJson(array.getJSONObject(i)))
                    } catch (e: Exception) {
                        Log.w(TAG, "Corrupt vector record at index $i: ${e.message}")
                    }
                }
            }
            Log.i(TAG, "Loaded ${records.size} vector records from ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading vector records: ${e.message}", e)
        }
    }

    private fun saveRecords() {
        try {
            val file = getStorageFile()
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }

            val array = JSONArray()
            synchronized(records) {
                records.forEach { array.put(it.toJson()) }
            }
            file.writeText(array.toString(2), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving vector records: ${e.message}", e)
        }
    }

    private fun seedInitialPreferences() {
        store(
            content = "Target Android architecture is arm64-v8a with full root superuser privileges.",
            category = "PROJECT_CONTEXT"
        )
        store(
            content = "User prefers concise system diagnostic metrics with human-readable numbers (-h).",
            category = "USER_PREFERENCE"
        )
        store(
            content = "Kali Linux NetHunter chroot environment is available locally for penetration testing and system maintenance.",
            category = "PROJECT_CONTEXT"
        )
    }
}
