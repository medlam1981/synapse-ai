package com.synapse.localai.tools

import org.json.JSONArray
import org.json.JSONObject

object VisualArtifactTool {

    private const val MAX_INPUT_CHARS = 24_000

    fun render(type: String, schema: String, data: String): String {
        require(schema.length <= MAX_INPUT_CHARS && data.length <= MAX_INPUT_CHARS) {
            "Artifact schema/data exceeds the size limit."
        }
        val normalizedType = type.trim().lowercase()
        require(normalizedType in setOf("bar_chart", "line_chart", "table", "mermaid")) {
            "Unsupported artifact type. Use bar_chart, line_chart, table, or mermaid."
        }
        val parsedSchema = if (schema.isBlank()) JSONObject() else JSONObject(schema)
        val payloadData = when (normalizedType) {
            "bar_chart", "line_chart" -> validateChart(JSONObject(data))
            "table" -> validateTable(JSONObject(data))
            "mermaid" -> validateGraph(JSONObject(data))
            else -> error("Unsupported artifact type.")
        }
        val payload = JSONObject()
            .put("schema", parsedSchema)
            .put("data", payloadData)
        val title = payloadData.optString("title", normalizedType.replace('_', ' '))
            .take(80)
        return """<visual_artifact type="$normalizedType" title="${escapeXml(title)}">${escapeXml(payload.toString())}</visual_artifact>"""
    }

    private fun validateChart(data: JSONObject): JSONObject {
        val labels = data.optJSONArray("labels") ?: error("Chart data needs a labels array.")
        val values = data.optJSONArray("values") ?: error("Chart data needs a values array.")
        require(labels.length() in 1..12 && labels.length() == values.length()) {
            "Chart labels and values must have the same length (1-12)."
        }
        val cleanLabels = JSONArray()
        val cleanValues = JSONArray()
        for (index in 0 until labels.length()) {
            val label = labels.optString(index).trim()
            val value = values.optDouble(index, Double.NaN)
            require(label.isNotEmpty() && value.isFinite() && value >= 0.0) {
                "Chart labels must be non-empty and values must be finite non-negative numbers."
            }
            cleanLabels.put(label.take(48))
            cleanValues.put(value)
        }
        return JSONObject()
            .put("title", data.optString("title", "Chart").take(80))
            .put("unit", data.optString("unit").take(16))
            .put("labels", cleanLabels)
            .put("values", cleanValues)
    }

    private fun validateTable(data: JSONObject): JSONObject {
        val columns = data.optJSONArray("columns") ?: error("Table data needs a columns array.")
        val rows = data.optJSONArray("rows") ?: error("Table data needs a rows array.")
        require(columns.length() in 1..8 && rows.length() <= 30) {
            "Table supports 1-8 columns and at most 30 rows."
        }
        val cleanColumns = JSONArray()
        for (index in 0 until columns.length()) {
            val column = columns.optString(index).trim()
            require(column.isNotEmpty()) { "Table column names cannot be blank." }
            cleanColumns.put(column.take(48))
        }
        val cleanRows = JSONArray()
        for (rowIndex in 0 until rows.length()) {
            val row = rows.optJSONArray(rowIndex) ?: error("Each table row must be an array.")
            require(row.length() == columns.length()) { "Every table row must match the column count." }
            cleanRows.put(JSONArray().apply {
                for (cellIndex in 0 until row.length()) put(row.optString(cellIndex).take(120))
            })
        }
        return JSONObject()
            .put("title", data.optString("title", "Data table").take(80))
            .put("columns", cleanColumns)
            .put("rows", cleanRows)
    }

    private fun validateGraph(data: JSONObject): JSONObject {
        val nodes = data.optJSONArray("nodes") ?: error("Graph data needs a nodes array.")
        val edges = data.optJSONArray("edges") ?: error("Graph data needs an edges array.")
        require(nodes.length() in 1..12 && edges.length() <= 20) {
            "Graph supports 1-12 nodes and at most 20 edges."
        }
        val cleanNodes = JSONArray()
        val nodeIds = mutableSetOf<String>()
        for (index in 0 until nodes.length()) {
            val node = nodes.optJSONObject(index) ?: error("Each graph node must be an object.")
            val id = node.optString("id").trim().take(24)
            val label = node.optString("label").trim().take(60)
            require(id.isNotEmpty() && label.isNotEmpty() && nodeIds.add(id)) {
                "Graph node IDs and labels must be unique and non-empty."
            }
            cleanNodes.put(JSONObject().put("id", id).put("label", label))
        }
        val cleanEdges = JSONArray()
        for (index in 0 until edges.length()) {
            val edge = edges.optJSONArray(index) ?: error("Each graph edge must be a [from, to] array.")
            require(edge.length() == 2) { "Graph edges must contain two node IDs." }
            val from = edge.optString(0)
            val to = edge.optString(1)
            require(from in nodeIds && to in nodeIds) { "Graph edge references an unknown node." }
            cleanEdges.put(JSONArray().put(from).put(to))
        }
        return JSONObject()
            .put("title", data.optString("title", "Process diagram").take(80))
            .put("direction", data.optString("direction", "TD").take(2))
            .put("nodes", cleanNodes)
            .put("edges", cleanEdges)
    }

    private fun escapeXml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
