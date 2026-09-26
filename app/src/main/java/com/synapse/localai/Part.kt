package com.synapse.localai

import com.google.ai.client.generativeai.type.FunctionResponsePart
import org.json.JSONObject

/**
 * Multi-Turn Tool Bridge Helper:
 * Factory for creating FunctionResponsePart for Gemini Generative AI SDK.
 */
object Part {
    fun fromFunctionResponse(name: String, response: Map<String, Any?>): FunctionResponsePart {
        val json = JSONObject()
        response.forEach { (k, v) -> json.put(k, v) }
        return FunctionResponsePart(name, json)
    }
}
