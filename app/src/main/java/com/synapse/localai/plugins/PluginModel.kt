package com.synapse.localai.plugins

import org.json.JSONArray
import org.json.JSONObject

enum class PluginExecutionType {
    SHELL,
    PYTHON,
    INTERNAL
}

data class PluginParameter(
    val name: String,
    val type: String = "string",
    val description: String = "",
    val required: Boolean = false,
    val defaultValue: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("type", type)
        put("description", description)
        put("required", required)
        defaultValue?.let { put("default", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): PluginParameter {
            return PluginParameter(
                name = json.optString("name", ""),
                type = json.optString("type", "string"),
                description = json.optString("description", ""),
                required = json.optBoolean("required", false),
                defaultValue = if (json.has("default")) json.getString("default") else null
            )
        }
    }
}

data class PluginManifest(
    val id: String,
    val name: String,
    val description: String,
    val version: String = "1.0.0",
    val category: String = "General",
    val executionType: PluginExecutionType = PluginExecutionType.SHELL,
    val parameters: List<PluginParameter> = emptyList(),
    val script: String,
    val author: String = "Developer",
    val isEnabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("version", version)
        put("category", category)
        put("executionType", executionType.name)
        val paramsArray = JSONArray()
        parameters.forEach { paramsArray.put(it.toJson()) }
        put("parameters", paramsArray)
        put("script", script)
        put("author", author)
        put("isEnabled", isEnabled)
    }

    companion object {
        fun fromJson(json: JSONObject): PluginManifest {
            val paramsList = mutableListOf<PluginParameter>()
            val paramsArray = json.optJSONArray("parameters")
            if (paramsArray != null) {
                for (i in 0 until paramsArray.length()) {
                    paramsList.add(PluginParameter.fromJson(paramsArray.getJSONObject(i)))
                }
            }
            val execTypeStr = json.optString("executionType", "SHELL").uppercase()
            val execType = try {
                PluginExecutionType.valueOf(execTypeStr)
            } catch (_: Exception) {
                PluginExecutionType.SHELL
            }

            return PluginManifest(
                id = json.optString("id", json.optString("name")),
                name = json.optString("name", "unknown_plugin"),
                description = json.optString("description", "Dynamic Custom Plugin"),
                version = json.optString("version", "1.0.0"),
                category = json.optString("category", "General"),
                executionType = execType,
                parameters = paramsList,
                script = json.optString("script", ""),
                author = json.optString("author", "Developer"),
                isEnabled = json.optBoolean("isEnabled", true)
            )
        }
    }
}
