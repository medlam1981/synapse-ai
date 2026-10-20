package com.synapse.localai.memory

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

data class VectorRecord(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val category: String = "FACT", // USER_PREFERENCE, RECURRING_SCRIPT, PROJECT_CONTEXT, FACT
    val metadata: Map<String, String> = emptyMap(),
    var vector: FloatArray = FloatArray(128),
    val createdAt: Long = System.currentTimeMillis(),
    var lastAccessedAt: Long = System.currentTimeMillis(),
    var accessCount: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VectorRecord
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    fun toJson(includeVector: Boolean = true): JSONObject = JSONObject().apply {
        put("id", id)
        put("content", content)
        put("category", category)
        val metaObj = JSONObject()
        metadata.forEach { (k, v) -> metaObj.put(k, v) }
        put("metadata", metaObj)

        if (includeVector) {
            val vecArray = JSONArray()
            vector.forEach { vecArray.put(it.toDouble()) }
            put("vector", vecArray)
        }

        put("createdAt", createdAt)
        put("lastAccessedAt", lastAccessedAt)
        put("accessCount", accessCount)
    }

    companion object {
        fun quantize(vector: FloatArray): Pair<ByteArray, Float> {
            val maxAbs = vector.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
            val scale = if (maxAbs > 0f) maxAbs / 127f else 1f
            val quantized = ByteArray(vector.size) { index ->
                (vector[index] / scale).roundToInt().coerceIn(-127, 127).toByte()
            }
            return quantized to scale
        }

        fun dequantize(vector: ByteArray, scale: Float): FloatArray =
            FloatArray(vector.size) { index -> vector[index].toInt() * scale }

        fun fromJson(json: JSONObject): VectorRecord {
            val metaMap = mutableMapOf<String, String>()
            val metaObj = json.optJSONObject("metadata")
            metaObj?.keys()?.forEach { k ->
                metaMap[k] = metaObj.getString(k)
            }

            val vecArray = json.optJSONArray("vector")
            val vec = FloatArray(128)
            if (vecArray != null) {
                val len = minOf(vec.size, vecArray.length())
                for (i in 0 until len) {
                    vec[i] = vecArray.getDouble(i).toFloat()
                }
            }

            return VectorRecord(
                id = json.optString("id", UUID.randomUUID().toString()),
                content = json.optString("content", ""),
                category = json.optString("category", "FACT"),
                metadata = metaMap,
                vector = vec,
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                lastAccessedAt = json.optLong("lastAccessedAt", System.currentTimeMillis()),
                accessCount = json.optInt("accessCount", 0)
            )
        }
    }
}
