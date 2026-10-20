package com.synapse.localai.memory

import android.content.Context
import com.synapse.localai.ChatDatabaseHelper
import com.synapse.localai.EpistemicMemoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Facade for app-private SQLite-backed semantic memory.
 *
 * Runtime reads and writes use the app-private database and quantized int8 vectors.
 */
object VectorMemoryManager {

    private val initializationMutex = Mutex()
    @Volatile private var appContext: Context? = null
    @Volatile private var repository: MemoryRepository? = null
    @Volatile private var initialized = false

    fun init(context: Context? = null) {
        context?.applicationContext?.let { appContext = it }
    }

    suspend fun initialize(context: Context? = null) {
        init(context)
        ensureInitialized()
    }

    suspend fun getSystemState(): EpistemicMemoryManager.AgentMind {
        ensureInitialized()
        return repositoryOrThrow().getSystemState()
    }

    suspend fun store(
        content: String,
        category: String = "SEMANTIC_KNOWLEDGE",
        metadata: Map<String, String> = emptyMap()
    ): VectorRecord {
        val cleanContent = content.trim()
        require(cleanContent.isNotEmpty()) { "Memory content cannot be blank." }
        ensureInitialized()
        val vector = withContext(Dispatchers.Default) {
            LightweightEmbeddingEngine.embed(cleanContent)
        }
        return repositoryOrThrow().storeVector(
            id = java.util.UUID.randomUUID().toString(),
            content = cleanContent,
            vector = vector,
            category = normalizeCategory(category),
            metadata = metadata
        )
    }

    suspend fun search(
        query: String,
        topK: Int = 3,
        minSimilarity: Float = 0.25f,
        categoryFilter: String? = null
    ): List<Pair<VectorRecord, Float>> {
        if (query.isBlank() || topK <= 0) return emptyList()
        ensureInitialized()
        val repository = repositoryOrThrow()
        val queryVector = withContext(Dispatchers.Default) {
            LightweightEmbeddingEngine.embed(query)
        }
        val vectorMatches = repository.querySimilarVectors(
            queryVector,
            topK = (topK * 4).coerceAtLeast(topK),
            minThreshold = minSimilarity
        )
        val lexicalRecords = repository.searchMemoryRecords(query, topK * 4)
        val lexicalIds = lexicalRecords.mapTo(mutableSetOf()) { it.id }
        val scoresById = vectorMatches.associate { it.record.id to it.similarity }.toMutableMap()
        lexicalRecords.forEach { record ->
            scoresById.putIfAbsent(
                record.id,
                LightweightEmbeddingEngine.cosineSimilarity(queryVector, record.vector)
            )
        }
        val recordsById = (vectorMatches.map { it.record } + lexicalRecords).associateBy { it.id }
        return scoresById
            .asSequence()
            .mapNotNull { (id, similarity) ->
                recordsById[id]?.let { it to similarity }
            }
            .filter { categoryFilter == null || it.first.category.equals(categoryFilter, ignoreCase = true) }
            .map { (record, similarity) ->
                record to (similarity + if (record.id in lexicalIds) 0.15f else 0f)
            }
            .filter { it.second >= minSimilarity || it.first.id in lexicalIds }
            .sortedByDescending { it.second }
            .take(topK)
            .toList()
    }

    suspend fun getRecentRecords(limit: Int = 10, category: String? = null): List<VectorRecord> {
        ensureInitialized()
        return repositoryOrThrow().getRecentRecords(limit, category)
    }

    suspend fun delete(id: String): Boolean {
        ensureInitialized()
        return repositoryOrThrow().deleteMemoryRecord(id)
    }

    suspend fun clearAll() {
        ensureInitialized()
        repositoryOrThrow().clearMemoryRecords()
    }

    suspend fun savePreference(key: String, value: String) {
        require(key.isNotBlank()) { "Preference key cannot be blank." }
        ensureInitialized()
        repositoryOrThrow().savePreference(key, value)
    }

    suspend fun pruneStaleMemories(maxAgeDays: Int = 180, maxRecords: Int = 10_000): Int {
        require(maxAgeDays >= 0) { "maxAgeDays cannot be negative." }
        require(maxRecords >= 0) { "maxRecords cannot be negative." }
        ensureInitialized()
        return repositoryOrThrow().pruneStaleMemories(maxAgeDays, maxRecords)
    }

    suspend fun saveAgentMind(mind: EpistemicMemoryManager.AgentMind) {
        ensureInitialized()
        repositoryOrThrow().saveAgentMind(mind)
        mind.userPreferences.take(20).forEach { store(it, category = "USER_PREFERENCE") }
        mind.learnedFacts.take(20).forEach { store(it, category = "CORE_MEMORY") }
    }

    suspend fun formatRelevantContextForPrompt(userPrompt: String): String {
        if (userPrompt.isBlank()) return ""
        val topMatches = search(userPrompt, topK = 4, minSimilarity = 0.28f)
        if (topMatches.isEmpty()) return ""

        return buildString {
            appendLine("\n*** PERSISTENT LONG-TERM CONTEXT & RECALLED MEMORIES ***")
            appendLine("The following relevant user preferences, scripts, or project facts were recalled from persistent vector memory:")
            topMatches.forEach { (record, _) ->
                val label = when (record.category.uppercase()) {
                    "CORE_MEMORY" -> "Core Memory"
                    "EPISODIC_MEMORY" -> "Episodic Memory"
                    "SEMANTIC_KNOWLEDGE" -> "Semantic Knowledge"
                    "USER_PREFERENCE" -> "User Preference"
                    "RECURRING_SCRIPT" -> "Recurring Script"
                    "PROJECT_CONTEXT" -> "Project Context"
                    else -> "Learned Fact"
                }
                appendLine("• [$label] ${record.content}")
            }
        }.trimEnd()
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        initializationMutex.withLock {
            if (initialized) return
            val context = requireContext()
            repository = SqliteMemoryRepository(context)
            initialized = true
        }
    }

    private fun repositoryOrThrow(): MemoryRepository =
        repository ?: throw IllegalStateException("Memory repository has not been initialized.")

    private fun requireContext(): Context =
        appContext ?: throw IllegalStateException("Memory repository requires an application context.")

    private fun normalizeCategory(category: String): String =
        when (category.trim().uppercase()) {
            "CORE", "CORE_MEMORY" -> "CORE_MEMORY"
            "EPISODIC", "EPISODIC_MEMORY", "CONVERSATION" -> "EPISODIC_MEMORY"
            "SEMANTIC", "SEMANTIC_MEMORY", "SEMANTIC_KNOWLEDGE", "FACT" -> "SEMANTIC_KNOWLEDGE"
            "USER_PREFERENCE", "PREFERENCE" -> "USER_PREFERENCE"
            "PROJECT_CONTEXT", "RECURRING_SCRIPT" -> category.trim().uppercase()
            else -> "SEMANTIC_KNOWLEDGE"
        }
}

private class SqliteMemoryRepository(context: Context) : MemoryRepository {

    private val appContext = context.applicationContext
    private val databaseHelper = ChatDatabaseHelper(appContext)

    override suspend fun getSystemState(): EpistemicMemoryManager.AgentMind =
        withContext(Dispatchers.IO) {
            val stored = database().getAgentState("agent_mind")
            if (stored.isNullOrBlank()) {
                EpistemicMemoryManager.AgentMind()
            } else {
                EpistemicMemoryManager.parseMind(stored)
            }
        }

    override suspend fun saveAgentMind(mind: EpistemicMemoryManager.AgentMind) = withContext(Dispatchers.IO) {
        database().saveAgentMind(
            json = EpistemicMemoryManager.serializeMind(mind),
            preferences = mind.userPreferences,
            facts = mind.learnedFacts
        )
    }

    override suspend fun savePreference(key: String, value: String) = withContext(Dispatchers.IO) {
        database().saveMemoryPreference(key, value)
    }

    override suspend fun storeVector(
        id: String,
        content: String,
        vector: FloatArray,
        category: String,
        metadata: Map<String, Any>
    ): VectorRecord = withContext(Dispatchers.IO) {
        require(vector.isNotEmpty()) { "Embedding vector cannot be empty." }
        val record = VectorRecord(
            id = id,
            content = content,
            category = category,
            metadata = metadata.mapValues { it.value.toString() },
            vector = vector.copyOf(),
            lastAccessedAt = System.currentTimeMillis()
        )
        database().upsertMemoryRecord(record)
        record
    }

    override suspend fun querySimilarVectors(
        queryVector: FloatArray,
        topK: Int,
        minThreshold: Float
    ): List<SimilarMemory> = withContext(Dispatchers.IO) {
        if (queryVector.isEmpty() || topK <= 0) return@withContext emptyList()
        database().searchSimilarMemoryRecords(queryVector, topK, minThreshold)
            .map { (record, similarity) -> SimilarMemory(record, similarity) }
    }

    override suspend fun getRecentRecords(limit: Int, category: String?): List<VectorRecord> =
        withContext(Dispatchers.IO) {
            databaseHelper.getMemoryRecords()
                .asSequence()
                .filter { category == null || it.category.equals(category, ignoreCase = true) }
                .sortedByDescending { it.lastAccessedAt }
                .take(limit.coerceAtLeast(0))
                .toList()
        }

    override suspend fun searchMemoryRecords(query: String, limit: Int): List<VectorRecord> =
        withContext(Dispatchers.IO) { databaseHelper.searchMemoryRecords(query, limit) }

    override suspend fun deleteMemoryRecord(id: String): Boolean =
        withContext(Dispatchers.IO) { databaseHelper.deleteMemoryRecord(id) }

    override suspend fun clearMemoryRecords() =
        withContext(Dispatchers.IO) { databaseHelper.clearMemoryRecords() }

    override suspend fun pruneStaleMemories(maxAgeDays: Int, maxRecords: Int): Int =
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - maxAgeDays.toLong() * 24L * 60L * 60L * 1000L
            database().pruneMemoryRecords(cutoff, maxRecords)
        }

    private fun database() = databaseHelper
}
