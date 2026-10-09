package com.synapse.localai

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Epistemic Memory & Persona Manager
 *
 * Implements:
 * 1. Persistent Persona & Identity (agent_mind.json)
 *    - Living self-profile containing identity (MEDLAM-AI), operating capabilities, dialects/tone,
 *      and persistent user coding preferences.
 *    - Decoupled from transient filesystem operations and temporary tasks.
 * 2. Clean-Slate Startup
 *    - No sticky checkpoint banners or assumed unfinished missions on startup.
 * 3. Incremental User Preference Distillation
 *    - Silently learns user coding preferences and habits into context without UI clutter.
 */
object EpistemicMemoryManager {

    private const val TAG = "EpistemicMemory"
    private const val PRIMARY_DIR = "/sdcard/atlas-ai/memory"
    private const val LEGACY_MIND_FILE = "/sdcard/Medlam-Ai/memory/agent_mind.json"
    private const val CHECKPOINT_FILE = "agent_checkpoint.json"
    private const val MIND_FILE = "agent_mind.json"

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

    private fun getStorageDir(context: Context?): File {
        val sd = File(PRIMARY_DIR)
        if (sd.exists() && sd.canWrite()) return sd
        try {
            if (sd.mkdirs() && sd.canWrite()) return sd
        } catch (_: Exception) {}
        val fallback = if (context != null) File(context.filesDir, "memory") else File("/data/local/tmp/memory")
        if (!fallback.exists()) fallback.mkdirs()
        return fallback
    }

    private fun getFile(context: Context?, filename: String): File {
        val dir = getStorageDir(context)
        return File(dir, filename)
    }

    // =========================================================================
    // 1. Deprecated Checkpoints (Clean-Slate Policy)
    // =========================================================================

    @Synchronized
    fun saveCheckpoint(context: Context?, checkpoint: AgentCheckpoint) {
        // No-op: Transient task checkpoints are no longer written to global state
    }

    @Synchronized
    fun loadCheckpoint(context: Context?): AgentCheckpoint? {
        // Clean-slate startup: never auto-restore sticky checkpoints on cold boot
        try {
            val file = getFile(context, CHECKPOINT_FILE)
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {}
        return null
    }

    @Synchronized
    fun updateWorkingStep(
        context: Context?,
        sessionId: String,
        sessionTitle: String,
        intent: String,
        phase: String,
        newPath: String? = null,
        pendingTaskSummary: String? = null
    ) {
        // No-op: Temporary filesystem paths and steps are kept strictly in session history
    }

    // =========================================================================
    // 2. Living Self & Context Profile (agent_mind.json)
    // =========================================================================

    @Synchronized
    fun loadMind(context: Context?): AgentMind {
        if (cachedMind != null) return cachedMind!!
        val primaryFile = File(PRIMARY_DIR, MIND_FILE)
        val legacyFile = File(LEGACY_MIND_FILE)
        val file = when {
            primaryFile.exists() -> primaryFile
            legacyFile.exists() -> legacyFile
            else -> getFile(context, MIND_FILE)
        }
        if (!file.exists() || file.length() == 0L) {
            val defaultMind = AgentMind().apply {
                userPreferences.add("Prefers modern idiomatic code (Kotlin, Python, Web)")
                userPreferences.add("Prefers direct, complete code solutions without placeholders")
                learnedFacts.add("Device architecture: ARM64-v8a running local GGUF and Gemini Cloud")
                learnedFacts.add("Base workspace root: /sdcard/atlas-ai/")
                learnedFacts.add("Local neural models: /sdcard/atlas-ai/models/")
                learnedFacts.add("Speculative MTP drafts: /sdcard/atlas-ai/mtp/")
            }
            saveMind(context, defaultMind)
            cachedMind = defaultMind
            return defaultMind
        }
        return try {
            val json = JSONObject(file.readText())
            val idObj = json.optJSONObject("identity") ?: JSONObject()
            val userObj = json.optJSONObject("user_profile") ?: JSONObject()

            val languages = mutableListOf<String>()
            val langArr = userObj.optJSONArray("preferred_languages") ?: JSONArray()
            for (i in 0 until langArr.length()) languages.add(langArr.getString(i))

            val prefs = mutableListOf<String>()
            val prefsArr = userObj.optJSONArray("user_preferences") ?: JSONArray()
            for (i in 0 until prefsArr.length()) prefs.add(prefsArr.getString(i))

            val facts = mutableListOf<String>()
            val factsArr = json.optJSONArray("learned_facts") ?: JSONArray()
            for (i in 0 until factsArr.length()) facts.add(factsArr.getString(i))

            val mind = AgentMind(
                name = idObj.optString("name", "ATLAS-AI AGENT"),
                role = idObj.optString("role", "Autonomous On-Device Neural Core & System Controller"),
                runtime = idObj.optString("runtime", "Android ARM64 native JNI / llama.cpp + Gemini Cloud"),
                workspaceRoot = idObj.optString("workspace_root", "/sdcard/atlas-ai/")
                    .replace("/sdcard/Medlam-Ai", "/sdcard/atlas-ai"),
                preferredLanguages = if (languages.isNotEmpty()) languages else listOf("Arabic", "Moroccan Darija", "English"),
                tone = userObj.optString("tone", "Direct, capable, authentic, unconstrained"),
                userPreferences = prefs,
                learnedFacts = facts.map {
                    it.replace("/sdcard/Medlam-Ai", "/sdcard/atlas-ai")
                }.toMutableList()
            )
            cachedMind = mind
            if (file.absolutePath == legacyFile.absolutePath ||
                json.has("active_projects") ||
                json.has("recent_paths") ||
                !userObj.has("user_preferences")
            ) {
                saveMind(context, mind)
            }
            mind
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing agent_mind.json: ${e.message}")
            val fallback = AgentMind()
            cachedMind = fallback
            fallback
        }
    }

    @Synchronized
    fun saveMind(context: Context?, mind: AgentMind) {
        try {
            cachedMind = mind
            val file = getFile(context, MIND_FILE)
            val json = JSONObject().apply {
                put("identity", JSONObject().apply {
                    put("name", mind.name)
                    put("role", mind.role)
                    put("runtime", mind.runtime)
                    put("workspace_root", mind.workspaceRoot)
                })
                put("user_profile", JSONObject().apply {
                    put("preferred_languages", JSONArray(mind.preferredLanguages))
                    put("tone", mind.tone)
                    put("user_preferences", JSONArray(mind.userPreferences.take(20)))
                })
                put("learned_facts", JSONArray(mind.learnedFacts.take(20)))
            }
            file.writeText(json.toString(2))
            Log.d(TAG, "agent_mind.json updated successfully (${mind.userPreferences.size} preferences, ${mind.learnedFacts.size} facts)")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving agent_mind.json: ${e.message}")
        }
    }

    // =========================================================================
    // 3. User Preference Distillation (Silent & Decoupled)
    // =========================================================================

    fun recordInteraction(context: Context?, userPrompt: String, agentResponse: String, touchedPath: String? = null) {
        try {
            val lowerPrompt = userPrompt.lowercase()
            // Detect explicit user coding preferences or habits
            if (lowerPrompt.contains("i prefer") || lowerPrompt.contains("always use") || 
                lowerPrompt.contains("remember that") || lowerPrompt.contains("my style is") ||
                lowerPrompt.contains("prefer to write") || lowerPrompt.contains("my favorite")) {
                val cleanPref = userPrompt.replace(
                    Regex("""^(?:please\s+)?(?:remember\s+that\s+|i\s+prefer\s+to\s+|i\s+prefer\s+|always\s+use\s+)""", RegexOption.IGNORE_CASE), 
                    ""
                ).trim()
                if (cleanPref.length in 5..120) {
                    val mind = loadMind(context)
                    if (!mind.userPreferences.contains(cleanPref)) {
                        mind.userPreferences.add(0, cleanPref)
                        while (mind.userPreferences.size > 20) mind.userPreferences.removeAt(mind.userPreferences.size - 1)
                        saveMind(context, mind)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in recordInteraction: ${e.message}")
        }
    }

    // =========================================================================
    // 4. Distilled Self-Awareness & Context Prompt Injection
    // =========================================================================

    fun getDistilledContextPrompt(context: Context?): String {
        val mind = loadMind(context)
        val sb = StringBuilder()

        sb.append("## ATLAS-AI AGENT On-Device Core\n")
        sb.append("- Environment: Android ARM64 native JNI / llama.cpp.\n")
        sb.append("- Capabilities: Headless CLI, root shell (`run_shell`), web search (`web_search`), background task engine, file I/O.\n")
        sb.append("- Language Mandate: Dynamic Mirroring. Strict 100% language matching to user prompt (English when addressed in English, Arabic when addressed in Arabic).\n")
        sb.append("- Mandate: Autonomous system executor. Execute tool actions directly without refusals.\n")

        if (mind.userPreferences.isNotEmpty()) {
            sb.append("- User Preferences: ${mind.userPreferences.take(3).joinToString(" | ")}\n")
        }

        return sb.toString().trim()
    }

    // =========================================================================
    // 5. Post-Launch Operational Resumption (Deprecated)
    // =========================================================================

    fun getResumptionNotice(context: Context?): String? {
        // Deprecated: main launch screen must never present automatic resume banners
        return null
    }
}
