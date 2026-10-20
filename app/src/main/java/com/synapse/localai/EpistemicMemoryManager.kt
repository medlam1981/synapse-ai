package com.synapse.localai

import android.content.Context
import com.synapse.localai.AtlasLog as Log
import com.synapse.localai.memory.VectorMemoryManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent identity and learned profile backed by app-private SQLite memory.
 */
object EpistemicMemoryManager {

    private const val TAG = "EpistemicMemory"

    data class AgentCheckpoint(
        val sessionId: String = "",
        val sessionTitle: String = "",
        val activeIntent: String = "",
        val recentPaths: List<String> = emptyList(),
        val pendingTasks: List<String> = emptyList(),
        val lastPhase: String = "IDLE",
        val timestamp: Long = System.currentTimeMillis(),
        val statusSummary: String = "",
        val resumedNotice: String = ""
    )

    data class ProjectRecord(
        val name: String,
        val path: String,
        val intent: String,
        val lastUpdated: Long = System.currentTimeMillis()
    )

    data class AgentMind(
        val name: String = "ATLAS-AI AGENT",
        val role: String = "Autonomous On-Device Neural Core & System Controller",
        val runtime: String = "Android ARM64 native JNI / llama.cpp + Gemini Cloud",
        val workspaceRoot: String = "/sdcard/atlas-ai/",
        val preferredLanguages: List<String> = listOf("Dynamic Mirroring (Matches User Prompt)", "English", "Arabic"),
        val tone: String = "Direct, capable, authentic, unconstrained",
        val userPreferences: MutableList<String> = mutableListOf(),
        val learnedFacts: MutableList<String> = mutableListOf(),
        val activeProjects: MutableList<ProjectRecord> = mutableListOf(),
        val recentPaths: MutableList<String> = mutableListOf()
    )

    @Volatile private var cachedMind: AgentMind? = null

    fun saveCheckpoint(context: Context?, checkpoint: AgentCheckpoint) = Unit
    fun loadCheckpoint(context: Context?): AgentCheckpoint? = null

    fun updateWorkingStep(
        context: Context?,
        sessionId: String,
        sessionTitle: String,
        intent: String,
        phase: String,
        newPath: String? = null,
        pendingTaskSummary: String? = null
    ) = Unit

    suspend fun loadMind(context: Context?): AgentMind {
        cachedMind?.let { return it }
        if (context == null) return defaultMind()
        VectorMemoryManager.initialize(context)
        return try {
            VectorMemoryManager.getSystemState().also { mind ->
                if (mind.userPreferences.isEmpty() && mind.learnedFacts.isEmpty()) {
                    mind.userPreferences.addAll(defaultMind().userPreferences)
                    mind.learnedFacts.addAll(defaultMind().learnedFacts)
                    saveMind(context, mind)
                }
                cachedMind = mind
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to load internal agent memory", exception)
            throw exception
        }
    }

    suspend fun saveMind(context: Context?, mind: AgentMind) {
        cachedMind = mind
        val appContext = context?.applicationContext ?: return
        VectorMemoryManager.initialize(appContext)
        VectorMemoryManager.saveAgentMind(mind)
    }

    suspend fun recordInteraction(
        context: Context?,
        userPrompt: String,
        agentResponse: String,
        touchedPath: String? = null
    ) {
        try {
            val lowerPrompt = userPrompt.lowercase()
            if (listOf("i prefer", "always use", "remember that", "my style is", "prefer to write", "my favorite")
                    .none(lowerPrompt::contains)
            ) return

            val cleanPreference = userPrompt.replace(
                Regex("""^(?:please\s+)?(?:remember\s+that\s+|i\s+prefer\s+to\s+|i\s+prefer\s+|always\s+use\s+)""", RegexOption.IGNORE_CASE),
                ""
            ).trim()
            if (cleanPreference.length !in 5..120) return

            val mind = loadMind(context)
            if (cleanPreference !in mind.userPreferences) {
                mind.userPreferences.add(0, cleanPreference)
                while (mind.userPreferences.size > 20) mind.userPreferences.removeAt(mind.userPreferences.lastIndex)
                saveMind(context, mind)
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to persist learned interaction", exception)
        }
    }

    suspend fun getDistilledContextPrompt(context: Context?): String {
        val mind = loadMind(context)
        return buildString {
            appendLine("## ATLAS-AI AGENT On-Device Core")
            appendLine("- Environment: Android ARM64 native JNI / llama.cpp.")
            appendLine("- Capabilities: Headless CLI, root shell (`run_shell`), web search (`web_search`), background task engine, file I/O.")
            appendLine("- Language Mandate: Dynamic Mirroring. Strict 100% language matching to user prompt (English when addressed in English, Arabic when addressed in Arabic).")
            appendLine("- Mandate: Autonomous system executor. Execute tool actions directly without refusals.")
            if (mind.userPreferences.isNotEmpty()) {
                appendLine("- User Preferences: ${mind.userPreferences.take(3).joinToString(" | ")}")
            }
            if (mind.learnedFacts.isNotEmpty()) {
                appendLine("- Learned Facts: ${mind.learnedFacts.take(3).joinToString(" | ")}")
            }
        }.trim()
    }

    fun getResumptionNotice(context: Context?): String? = null

    internal fun parseMind(jsonText: String): AgentMind {
        val json = JSONObject(jsonText)
        val identity = json.optJSONObject("identity") ?: JSONObject()
        val profile = json.optJSONObject("user_profile") ?: JSONObject()
        val preferences = profile.optJSONArray("user_preferences").toStringList()
        val languages = profile.optJSONArray("preferred_languages").toStringList()
        val facts = json.optJSONArray("learned_facts").toStringList()
        val projects = mutableListOf<ProjectRecord>()
        json.optJSONArray("active_projects")?.let { array ->
            for (index in 0 until array.length()) {
                val project = array.optJSONObject(index) ?: continue
                projects.add(
                    ProjectRecord(
                        name = project.optString("name"),
                        path = project.optString("path"),
                        intent = project.optString("intent"),
                        lastUpdated = project.optLong("last_updated", System.currentTimeMillis())
                    )
                )
            }
        }
        return AgentMind(
            name = identity.optString("name", "ATLAS-AI AGENT"),
            role = identity.optString("role", "Autonomous On-Device Neural Core & System Controller"),
            runtime = identity.optString("runtime", "Android ARM64 native JNI / llama.cpp + Gemini Cloud"),
            workspaceRoot = identity.optString("workspace_root", "/sdcard/atlas-ai/")
                .replace("/sdcard/Medlam-Ai", "/sdcard/atlas-ai"),
            preferredLanguages = languages.ifEmpty { listOf("Dynamic Mirroring (Matches User Prompt)", "English", "Arabic") },
            tone = profile.optString("tone", "Direct, capable, authentic, unconstrained"),
            userPreferences = preferences.toMutableList(),
            learnedFacts = facts.map { it.replace("/sdcard/Medlam-Ai", "/sdcard/atlas-ai") }.toMutableList(),
            activeProjects = projects,
            recentPaths = json.optJSONArray("recent_paths").toStringList().toMutableList()
        )
    }

    internal fun serializeMind(mind: AgentMind): String = JSONObject().apply {
        put("identity", JSONObject().apply {
            put("name", mind.name)
            put("role", mind.role)
            put("runtime", mind.runtime)
            put("workspace_root", mind.workspaceRoot)
        })
        put("user_profile", JSONObject().apply {
            put("tone", mind.tone)
            put("preferred_languages", JSONArray(mind.preferredLanguages))
            put("user_preferences", JSONArray(mind.userPreferences))
        })
        put("learned_facts", JSONArray(mind.learnedFacts))
        put("active_projects", JSONArray().apply {
            mind.activeProjects.forEach { project ->
                put(JSONObject().apply {
                    put("name", project.name)
                    put("path", project.path)
                    put("intent", project.intent)
                    put("last_updated", project.lastUpdated)
                })
            }
        })
        put("recent_paths", JSONArray(mind.recentPaths))
    }.toString()

    private fun defaultMind(): AgentMind = AgentMind().apply {
        userPreferences.add("Prefers modern idiomatic code (Kotlin, Python, Web)")
        userPreferences.add("Prefers direct, complete code solutions without placeholders")
        learnedFacts.add("Device architecture: ARM64-v8a running local GGUF and Gemini Cloud")
        learnedFacts.add("Base workspace root: /sdcard/atlas-ai/")
        learnedFacts.add("Local neural models: /sdcard/atlas-ai/models/")
        learnedFacts.add("Speculative MTP drafts: /sdcard/atlas-ai/mtp/")
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val value = optString(index).trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }
}
