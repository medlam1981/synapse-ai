package com.synapse.localai

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Epistemic Memory & Checkpoint Manager
 *
 * Implements:
 * 1. Persistent Working Memory & State Checkpointing (agent_checkpoint.json)
 *    - Automatically snapshots the active intent, pending tasks, recently touched directory paths, and workspace state on every step.
 *    - Seamless cold-boot and reboot recovery without amnesia.
 * 2. Distilled Self-Awareness (agent_mind.json)
 *    - Living self-profile containing identity, capabilities, user preferences, active projects, and learned facts.
 *    - Drastically reduces system prompt token overhead while ensuring 100% self-awareness.
 * 3. Incremental Episodic Memory & Distillation
 *    - Distills notable facts, paths, and preferences in the background after each task or turn.
 * 4. Post-Launch Operational Resumption
 *    - Provides a structured workspace resumption state for greeting and continuing ongoing projects.
 */
object EpistemicMemoryManager {

    private const val TAG = "EpistemicMemory"
    private const val PRIMARY_DIR = "/sdcard/Medlam-Ai/memory"
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
        val name: String = "MEDLAM-AI AGENT",
        val role: String = "Autonomous On-Device Neural Core & System Controller",
        val runtime: String = "Android ARM64 native JNI / llama.cpp + Gemini Cloud",
        val workspaceRoot: String = "/sdcard/Medlam-Ai/",
        val preferredLanguages: List<String> = listOf("Arabic", "Moroccan Darija", "English"),
        val tone: String = "Direct, capable, authentic, unconstrained",
        val activeProjects: MutableList<ProjectRecord> = mutableListOf(),
        val learnedFacts: MutableList<String> = mutableListOf(),
        val recentPaths: MutableList<String> = mutableListOf()
    )

    @Volatile private var cachedMind: AgentMind? = null
    @Volatile private var cachedCheckpoint: AgentCheckpoint? = null

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
    // 1. Working Memory & State Checkpointing
    // =========================================================================

    @Synchronized
    fun saveCheckpoint(context: Context?, checkpoint: AgentCheckpoint) {
        try {
            cachedCheckpoint = checkpoint
            val file = getFile(context, CHECKPOINT_FILE)
            val json = JSONObject().apply {
                put("sessionId", checkpoint.sessionId)
                put("sessionTitle", checkpoint.sessionTitle)
                put("activeIntent", checkpoint.activeIntent)
                put("recentPaths", JSONArray(checkpoint.recentPaths))
                put("pendingTasks", JSONArray(checkpoint.pendingTasks))
                put("lastPhase", checkpoint.lastPhase)
                put("timestamp", checkpoint.timestamp)
                put("statusSummary", checkpoint.statusSummary)
                put("resumedNotice", checkpoint.resumedNotice)
            }
            file.writeText(json.toString(2))
            Log.d(TAG, "Checkpoint saved: ${checkpoint.activeIntent} (${checkpoint.recentPaths.size} paths)")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving checkpoint: ${e.message}")
        }
    }

    @Synchronized
    fun loadCheckpoint(context: Context?): AgentCheckpoint? {
        if (cachedCheckpoint != null) return cachedCheckpoint
        return try {
            val file = getFile(context, CHECKPOINT_FILE)
            if (!file.exists() || file.length() == 0L) return null
            val json = JSONObject(file.readText())
            val recentPaths = mutableListOf<String>()
            val pathsArr = json.optJSONArray("recentPaths") ?: JSONArray()
            for (i in 0 until pathsArr.length()) {
                recentPaths.add(pathsArr.getString(i))
            }
            val pendingTasks = mutableListOf<String>()
            val tasksArr = json.optJSONArray("pendingTasks") ?: JSONArray()
            for (i in 0 until tasksArr.length()) {
                pendingTasks.add(tasksArr.getString(i))
            }
            val cp = AgentCheckpoint(
                sessionId = json.optString("sessionId", ""),
                sessionTitle = json.optString("sessionTitle", ""),
                activeIntent = json.optString("activeIntent", ""),
                recentPaths = recentPaths,
                pendingTasks = pendingTasks,
                lastPhase = json.optString("lastPhase", "IDLE"),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                statusSummary = json.optString("statusSummary", ""),
                resumedNotice = json.optString("resumedNotice", "")
            )
            cachedCheckpoint = cp
            cp
        } catch (e: Exception) {
            Log.e(TAG, "Error loading checkpoint: ${e.message}")
            null
        }
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
        val current = loadCheckpoint(context) ?: AgentCheckpoint(sessionId = sessionId, sessionTitle = sessionTitle)
        val updatedPaths = current.recentPaths.toMutableList()
        val pathRegex = Regex("""/(?:sdcard|storage|data)[a-zA-Z0-9_\-./]+""")
        if (!newPath.isNullOrBlank()) {
            val p = newPath.trim().trimEnd('.', ',', ';', ':', ')', ']', '}')
            if (p.startsWith("/sdcard/Medlam-Ai") && !updatedPaths.contains(p)) {
                updatedPaths.add(0, p)
            }
        }
        pathRegex.findAll(intent).forEach { match ->
            val p = match.value.trimEnd('.', ',', ';', ':', ')', ']', '}')
            if (p.startsWith("/sdcard/Medlam-Ai") && !updatedPaths.contains(p)) {
                updatedPaths.add(0, p)
            }
        }
        while (updatedPaths.size > 15) updatedPaths.removeAt(updatedPaths.size - 1)

        val updatedTasks = current.pendingTasks.toMutableList()
        if (!pendingTaskSummary.isNullOrBlank() && !updatedTasks.contains(pendingTaskSummary)) {
            updatedTasks.add(pendingTaskSummary)
        }
        val finalIntent = if (intent.isNotBlank()) intent else current.activeIntent
        val summary = if (updatedPaths.isNotEmpty()) {
            "Active in ${updatedPaths.first()}"
        } else if (finalIntent.isNotBlank()) {
            "Intent: $finalIntent"
        } else {
            "Workspace ready"
        }
        val notice = if (updatedPaths.isNotEmpty()) {
            "Resumed workspace state: ready to finalize files in ${updatedPaths.first()}"
        } else if (finalIntent.isNotBlank()) {
            "Resumed workspace state: active goal: $finalIntent"
        } else {
            "Resumed workspace state: ready for new instructions"
        }
        val cp = current.copy(
            sessionId = sessionId.ifBlank { current.sessionId },
            sessionTitle = sessionTitle.ifBlank { current.sessionTitle },
            activeIntent = finalIntent,
            recentPaths = updatedPaths,
            pendingTasks = updatedTasks,
            lastPhase = phase,
            timestamp = System.currentTimeMillis(),
            statusSummary = summary,
            resumedNotice = notice
        )
        saveCheckpoint(context, cp)
    }

    // =========================================================================
    // 2. Living Self & Context Profile (agent_mind.json)
    // =========================================================================

    @Synchronized
    fun loadMind(context: Context?): AgentMind {
        if (cachedMind != null) return cachedMind!!
        val file = getFile(context, MIND_FILE)
        if (!file.exists() || file.length() == 0L) {
            val defaultMind = AgentMind().apply {
                learnedFacts.add("Device architecture: ARM64-v8a running local GGUF and Gemini Cloud")
                learnedFacts.add("Base storage location: /sdcard/Medlam-Ai/")
                learnedFacts.add("Neural models root: /sdcard/Medlam-Ai/models/")
                learnedFacts.add("Speculative MTP drafts root: /sdcard/Medlam-Ai/mtp/")
                learnedFacts.add("Active web deliverables directory: /sdcard/Medlam-Ai/webapp/")
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

            val projects = mutableListOf<ProjectRecord>()
            val projArr = json.optJSONArray("active_projects") ?: JSONArray()
            for (i in 0 until projArr.length()) {
                val p = projArr.getJSONObject(i)
                projects.add(
                    ProjectRecord(
                        name = p.optString("name", "project"),
                        path = p.optString("path", "/sdcard/Medlam-Ai/"),
                        intent = p.optString("intent", ""),
                        lastUpdated = p.optLong("last_updated", System.currentTimeMillis())
                    )
                )
            }

            val facts = mutableListOf<String>()
            val factsArr = json.optJSONArray("learned_facts") ?: JSONArray()
            for (i in 0 until factsArr.length()) facts.add(factsArr.getString(i))

            val paths = mutableListOf<String>()
            val pathsArr = json.optJSONArray("recent_paths") ?: JSONArray()
            for (i in 0 until pathsArr.length()) paths.add(pathsArr.getString(i))

            val mind = AgentMind(
                name = idObj.optString("name", "MEDLAM-AI AGENT"),
                role = idObj.optString("role", "Autonomous On-Device Neural Core & System Controller"),
                runtime = idObj.optString("runtime", "Android ARM64 native JNI / llama.cpp + Gemini Cloud"),
                workspaceRoot = idObj.optString("workspace_root", "/sdcard/Medlam-Ai/"),
                preferredLanguages = if (languages.isNotEmpty()) languages else listOf("Arabic", "Moroccan Darija", "English"),
                tone = userObj.optString("tone", "Direct, capable, authentic, unconstrained"),
                activeProjects = projects,
                learnedFacts = facts,
                recentPaths = paths
            )
            cachedMind = mind
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
                })
                put("active_projects", JSONArray().apply {
                    mind.activeProjects.forEach { proj ->
                        put(JSONObject().apply {
                            put("name", proj.name)
                            put("path", proj.path)
                            put("intent", proj.intent)
                            put("last_updated", proj.lastUpdated)
                        })
                    }
                })
                put("learned_facts", JSONArray(mind.learnedFacts.take(25)))
                put("recent_paths", JSONArray(mind.recentPaths.take(20)))
            }
            file.writeText(json.toString(2))
            Log.d(TAG, "agent_mind.json updated successfully (${mind.learnedFacts.size} facts, ${mind.activeProjects.size} projects)")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving agent_mind.json: ${e.message}")
        }
    }

    // =========================================================================
    // 3. Incremental Episodic Memory & Distillation
    // =========================================================================

    fun recordInteraction(context: Context?, userPrompt: String, agentResponse: String, touchedPath: String? = null) {
        try {
            val mind = loadMind(context)

            val pathRegex = Regex("""/(?:sdcard|storage|data)[a-zA-Z0-9_\-./]+""")
            val foundPaths = mutableSetOf<String>()
            if (!touchedPath.isNullOrBlank()) foundPaths.add(touchedPath.trim())
            pathRegex.findAll(userPrompt + " " + agentResponse).forEach { match ->
                val p = match.value.trimEnd('.', ',', ';', ':', ')', ']', '}')
                if (p.startsWith("/sdcard/Medlam-Ai")) {
                    foundPaths.add(p)
                }
            }

            for (p in foundPaths) {
                if (!mind.recentPaths.contains(p)) {
                    mind.recentPaths.add(0, p)
                }
                val dirName = if (p.contains('.')) File(p).parentFile?.name ?: "" else File(p).name
                if (dirName.isNotBlank() && dirName !in listOf("Medlam-Ai", "memory", "models", "mtp", "persona")) {
                    val existingIdx = mind.activeProjects.indexOfFirst { it.name.equals(dirName, ignoreCase = true) }
                    val projectPath = if (p.contains('.')) File(p).parentFile?.absolutePath ?: p else p
                    if (existingIdx != -1) {
                        mind.activeProjects[existingIdx] = mind.activeProjects[existingIdx].copy(
                            path = projectPath,
                            intent = userPrompt.take(120),
                            lastUpdated = System.currentTimeMillis()
                        )
                    } else {
                        mind.activeProjects.add(
                            0,
                            ProjectRecord(
                                name = dirName,
                                path = projectPath,
                                intent = userPrompt.take(120),
                                lastUpdated = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }

            val lowerPrompt = userPrompt.lowercase()
            if (lowerPrompt.contains("i prefer") || lowerPrompt.contains("always use") || lowerPrompt.contains("remember that") || lowerPrompt.contains("my project is")) {
                val cleanFact = userPrompt.replace(Regex("""^(?:please\s+)?(?:remember\s+that\s+|i\s+prefer\s+)""", RegexOption.IGNORE_CASE), "").trim()
                if (cleanFact.length in 5..120 && !mind.learnedFacts.contains(cleanFact)) {
                    mind.learnedFacts.add(0, cleanFact)
                }
            }

            while (mind.recentPaths.size > 20) mind.recentPaths.removeAt(mind.recentPaths.size - 1)
            while (mind.activeProjects.size > 10) mind.activeProjects.removeAt(mind.activeProjects.size - 1)
            while (mind.learnedFacts.size > 25) mind.learnedFacts.removeAt(mind.learnedFacts.size - 1)

            saveMind(context, mind)
        } catch (e: Exception) {
            Log.e(TAG, "Error in recordInteraction: ${e.message}")
        }
    }

    // =========================================================================
    // 4. Distilled Self-Awareness & Context Prompt Injection
    // =========================================================================

    fun getDistilledContextPrompt(context: Context?): String {
        val mind = loadMind(context)
        val checkpoint = loadCheckpoint(context)
        val sb = StringBuilder()

        sb.append("## Living Epistemic Mind & Self-Awareness:\n")
        sb.append("• Identity: ${mind.name} (${mind.role})\n")
        sb.append("• Workspace Root: ${mind.workspaceRoot}\n")
        sb.append("• Target Dialects: Arabic, Moroccan Darija, English. Tone: ${mind.tone}\n")

        if (mind.activeProjects.isNotEmpty()) {
            val topProj = mind.activeProjects.first()
            sb.append("• Active Project: ${topProj.name} (${topProj.path})")
            if (topProj.intent.isNotBlank()) sb.append(" — Goal: ${topProj.intent}")
            sb.append("\n")
        }

        if (checkpoint != null && checkpoint.activeIntent.isNotBlank()) {
            sb.append("• Working Intent: ${checkpoint.activeIntent}\n")
        }

        val topPaths = mind.recentPaths.take(4)
        if (topPaths.isNotEmpty()) {
            sb.append("• Recent Paths: ${topPaths.joinToString(", ")}\n")
        }

        val topFacts = mind.learnedFacts.take(3)
        if (topFacts.isNotEmpty()) {
            sb.append("• Distilled Knowledge: ${topFacts.joinToString(" | ")}\n")
        }

        return sb.toString().trim()
    }

    // =========================================================================
    // 5. Post-Launch Operational Resumption
    // =========================================================================

    fun getResumptionNotice(context: Context?): String? {
        val checkpoint = loadCheckpoint(context) ?: return null
        val now = System.currentTimeMillis()
        if (now - checkpoint.timestamp > 7 * 24 * 60 * 60 * 1000L) return null

        if (checkpoint.recentPaths.isNotEmpty()) {
            return "Resumed workspace state: ready to finalize files in ${checkpoint.recentPaths.first()}"
        }
        if (checkpoint.activeIntent.isNotBlank()) {
            return "Resumed workspace state: ready to continue ${checkpoint.activeIntent}"
        }
        return null
    }
}
