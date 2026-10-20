package com.synapse.localai.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorRecordTest {

    @Test
    fun int8QuantizationUsesOneQuarterOfFloatStorageAndKeepsSimilarity() {
        val source = LightweightEmbeddingEngine.embed("Atlas remembers user preferences and project context")
        val (quantized, scale) = VectorRecord.quantize(source)
        val restored = VectorRecord.dequantize(quantized, scale)

        assertEquals(128, quantized.size)
        assertEquals(128, restored.size)
        assertEquals(128, source.size)
        assertTrue(
            LightweightEmbeddingEngine.cosineSimilarity(source, restored) > 0.99f
        )
        assertEquals(source.size * java.lang.Float.BYTES / 4, quantized.size)
    }
}
