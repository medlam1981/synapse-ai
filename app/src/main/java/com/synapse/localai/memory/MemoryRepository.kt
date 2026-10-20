package com.synapse.localai.memory

import com.synapse.localai.EpistemicMemoryManager

data class SimilarMemory(
    val record: VectorRecord,
    val similarity: Float
)

interface MemoryRepository {
    suspend fun getSystemState(): EpistemicMemoryManager.AgentMind
    suspend fun saveAgentMind(mind: EpistemicMemoryManager.AgentMind)
    suspend fun savePreference(key: String, value: String)
    suspend fun storeVector(
        id: String,
        content: String,
        vector: FloatArray,
        category: String,
        metadata: Map<String, Any>
    ): VectorRecord
    suspend fun querySimilarVectors(
        queryVector: FloatArray,
        topK: Int = 5,
        minThreshold: Float = 0.7f
    ): List<SimilarMemory>
    suspend fun getRecentRecords(limit: Int, category: String?): List<VectorRecord>
    suspend fun searchMemoryRecords(query: String, limit: Int): List<VectorRecord>
    suspend fun deleteMemoryRecord(id: String): Boolean
    suspend fun clearMemoryRecords()
    suspend fun pruneStaleMemories(maxAgeDays: Int, maxRecords: Int): Int
}
