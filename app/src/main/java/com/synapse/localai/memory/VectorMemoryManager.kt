package com.synapse.localai.memory

import android.content.Context
import com.synapse.localai.ChatDatabaseHelper
import com.synapse.localai.AtlasLog as Log
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
 * across working sessions in the app-private SQLite database with FTS indexing.
 */
object VectorMemoryManager {

    private const val TAG = "VectorMemory"
    private val legacyFiles = listOf(
        "/sdcard/atlas-ai/memory/vector_store.json",
        "/sdcard/Medlam-Ai/memory/vector_store.json"
    )

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

    /**
     * Stores a new memory record, calculating its embedding vector on the fly.
     */
    fun store(
        content: String,
        category: String = "SEMANTIC_KNOWLEDGE",
        metadata: Map<String, String> = emptyMap()
    ): VectorRecord {
        val cleanContent = content.trim()
        require(cleanContent.isNotEmpty()) { "Memory content cannot be blank." }
        val normalizedCategory = when (category.trim().uppercase()) {
            "CORE", "CORE_MEMORY" -> "CORE_MEMORY"
            "EPISODIC", "EPISODIC_MEMORY", "CONVERSATION" -> "EPISODIC_MEMORY"
            "SEMANTIC", "SEMANTIC_MEMORY", "SEMANTIC_KNOWLEDGE", "FACT" -> "SEMANTIC_KNOWLEDGE"
            "USER_PREFERENCE", "PREFERENCE" -> "USER_PREFERENCE"
            "PROJECT_CONTEXT", "RECURRING_SCRIPT" -> category.trim().uppercase()
            else -> "SEMANTIC_KNOWLEDGE"
        }
        val vector = LightweightEmbeddingEngine.embed(cleanContent)
        val record = VectorRecord(
            content = cleanContent,
            category = normalizedCategory,
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
        val lexicalIds = appContext?.let { context ->
            ChatDatabaseHelper(context).searchMemoryRecords(query, topK * 4).mapTo(mutableSetOf()) { it.id }
        }.orEmpty()
        val matches = mutableListOf<Pair<VectorRecord, Float>>()

        synchronized(records) {
            for (rec in records) {
                if (categoryFilter != null && !rec.category.equals(categoryFilter, ignoreCase = true)) {
                    continue
                }
                val sim = LightweightEmbeddingEngine.cosineSimilarity(queryVector, rec.vector)
                if (sim >= minSimilarity || rec.id in lexicalIds) {
                    matches.add(rec to (sim + if (rec.id in lexicalIds) 0.15f else 0f))
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
                "CORE_MEMORY" -> "Core Memory"
                "EPISODIC_MEMORY" -> "Episodic Memory"
                "SEMANTIC_KNOWLEDGE" -> "Semantic Knowledge"
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
            val context = appContext ?: return
            val database = ChatDatabaseHelper(context)
            val storedRecords = database.getMemoryRecords()
            if (storedRecords.isNotEmpty()) {
                synchronized(records) {
                    records.clear()
                    records.addAll(storedRecords)
                }
                return
            }
            val file = legacyFiles.asSequence().map(::File).firstOrNull { it.isFile } ?: return
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
                if (records.isNotEmpty()) database.replaceMemoryRecords(records.toList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to load persistent memory", e)
        }
    }

    private fun saveRecords() {
        val context = appContext ?: throw IllegalStateException("Persistent memory database is not initialized.")
        synchronized(records) {
            ChatDatabaseHelper(context).replaceMemoryRecords(records.toList())
        }
    }
}
