package com.synapse.localai.tools

/**
 * WebSearchTool — Backward-compatible wrapper delegating to WebSearchClient.
 */
object WebSearchTool {
    suspend fun search(query: String): String {
        return WebSearchClient.search(query)
    }
}