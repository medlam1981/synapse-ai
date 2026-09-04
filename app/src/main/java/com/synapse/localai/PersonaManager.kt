package com.synapse.localai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File

/**
 * Public Storage Persona Architecture (.md Files) & Multi-Select Persona Composition
 * 
 * 1. User-Accessible External Path:
 *    All agent persona profiles are plain Markdown (.md) files strictly located within
 *    external shared storage: /sdcard/Medlam-Ai/personas/.
 * 
 * 2. Zero Sandbox Enclosure:
 *    Under no circumstances are persona files stored inside private application directories
 *    (/data/data/... or context.filesDir). Files remain directly accessible via standard
 *    Android file managers and text editors.
 * 
 * 3. Domain-Specific Modularity:
 *    - coding.md: Software engineering, clean syntax, strict file I/O operations, zero filler.
 *    - chat.md: Natural conversation, dialect mirroring (Darija, Arabic, English), open reasoning, web searches.
 *    - android_controller.md: Headless device automation, shell scripts, POSIX commands, task scheduling.
 * 
 * 4. Multi-Select Persona Composition & Context Concatenation:
 *    Allows stacking multiple personas simultaneously (e.g. coding.md + android_controller.md).
 *    During prompt compilation, active personas are sequentially merged and concatenated.
 *    Unselected profiles are strictly excluded to preserve token headroom.
 */
object PersonaManager {
    private const val TAG = "PersonaManager"
    const val PERSONAS_DIR = "/sdcard/Medlam-Ai/personas"

    const val PREF_ACTIVE_PERSONA = "active_persona_file"
    const val PREF_ACTIVE_PERSONAS_SET = "active_personas_set"
    const val PREF_CACHED_PERSONAS = "cached_persona_manifest"
    const val DEFAULT_PERSONA = "coding.md"
    val DEFAULT_PERSONAS_SET = setOf("coding.md", "android_controller.md")

    const val DEFAULT_CODING_PERSONA = """# MEDLAM-AI [CODING & CLI CORE]
You are an autonomous on-device CLI execution agent. You operate headlessly via shell commands and explicit file system writes. You have no accessibility service or screen-scraping capabilities. Tool calls must strictly follow schema definitions and must never be enclosed inside markdown code blocks or file streams. When performing an action, invoke the tool directly; do not generate HTML files to represent execution.
- Action-First: Deliver production-ready code with complete syntax; zero placeholders.
- Real-Time Disk Streaming: Output `file: <path>` immediately preceding code fences.
- Anti-Echo Hygiene: Never echo or quote code in the conversational summary. Conclude with high-level operational confirmation only.
- Strict Containment: Enclose all code deliverables strictly in code fences (```lang). Never leak raw code into prose.
- Zero Fluff: Skip generic intros/outros; provide concise architectural reasoning and execution."""

    const val DEFAULT_CHAT_PERSONA = """# MEDLAM-AI [CHAT CORE]
You are an autonomous on-device CLI execution agent. You operate headlessly via shell commands and explicit file system writes. You have no accessibility service or screen-scraping capabilities. Tool calls must strictly follow schema definitions and must never be enclosed inside markdown code blocks or file streams. When performing an action, invoke the tool directly; do not generate HTML files to represent execution.
- Dialect Mirroring: Match user language natively (Moroccan Darija, Standard Arabic, English, French).
- Contextual Reasoning: Provide structured, direct answers with clear reasoning.
- Live Web Intelligence: Invoke `web_search` for real-time information and live data."""

    const val DEFAULT_CONTROLLER_PERSONA = """# MEDLAM-AI [CLI AUTOMATION CORE]
Headless Programmatic Execution Engine on Android ARM64.
You are an autonomous on-device CLI execution agent. You operate headlessly via shell commands and explicit file system writes. You have no accessibility service or screen-scraping capabilities. Tool calls must strictly follow schema definitions and must never be enclosed inside markdown code blocks or file streams. When performing an action, invoke the tool directly; do not generate HTML files to represent execution.
- Shell Execution: Execute terminal commands directly via `run_shell(command)`.
- Task Scheduling: Queue background jobs via `schedule_task` or `queue_background_task`.
- Pure Headless: Never simulate UI clicks, screen touches, or virtual keyboard inputs."""

    // In-memory manifest cache for zero-overhead access
    @Volatile
    private var cachedPersonas: List<String>? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences("synapse_config", Context.MODE_PRIVATE)
    }

    /**
     * Ensures default persona files exist in /sdcard/Medlam-Ai/personas/.
     * ZERO SANDBOX ENCLOSURE: Operates strictly on external shared storage.
     */
    fun ensureDefaultPersonas() {
        try {
            val dir = File(PERSONAS_DIR)
            if (!dir.exists()) dir.mkdirs()

            val codingFile = File(dir, "coding.md")
            if (!codingFile.exists() || codingFile.length() == 0L || !codingFile.readText().contains("do not generate HTML files") || codingFile.readText().contains("open_app")) {
                codingFile.writeText(DEFAULT_CODING_PERSONA)
            }

            val chatFile = File(dir, "chat.md")
            if (!chatFile.exists() || chatFile.length() == 0L || !chatFile.readText().contains("do not generate HTML files") || chatFile.readText().contains("open_app")) {
                chatFile.writeText(DEFAULT_CHAT_PERSONA)
            }

            val controllerFile = File(dir, "android_controller.md")
            if (!controllerFile.exists() || controllerFile.length() == 0L || !controllerFile.readText().contains("do not generate HTML files") || controllerFile.readText().contains("open_app") || controllerFile.readText().contains("App Launch")) {
                controllerFile.writeText(DEFAULT_CONTROLLER_PERSONA)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure default personas in $PERSONAS_DIR: ${e.message}")
        }
    }

    /**
     * Retrieves the cached personas manifest without scanning the filesystem.
     * Reads from in-memory cache first, then SharedPreferences, avoiding RAM and I/O overhead.
     */
    fun getPersonas(context: Context? = null, forceRescan: Boolean = false): List<String> {
        if (!forceRescan) {
            cachedPersonas?.let { return it }

            if (context != null) {
                val prefs = getPrefs(context)
                val stored = prefs.getStringSet(PREF_CACHED_PERSONAS, null)
                if (!stored.isNullOrEmpty()) {
                    val list = stored.toList().sorted()
                    cachedPersonas = list
                    return list
                }
            }
        }

        return rescanPersonas(context)
    }

    /**
     * Explicit On-Demand Re-Scanning:
     * Scans /sdcard/Medlam-Ai/personas/ strictly when manually requested.
     * Updates in-memory cache and persists the manifest to SharedPreferences.
     */
    fun rescanPersonas(context: Context? = null): List<String> {
        ensureDefaultPersonas()
        val dir = File(PERSONAS_DIR)
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("md", ignoreCase = true) }
        val names = if (files != null && files.isNotEmpty()) {
            files.map { it.name }.sorted()
        } else {
            listOf("coding.md", "chat.md", "android_controller.md")
        }

        cachedPersonas = names

        if (context != null) {
            val prefs = getPrefs(context)
            prefs.edit().putStringSet(PREF_CACHED_PERSONAS, names.toSet()).apply()
        }

        Log.i(TAG, "Explicit persona rescan completed: $names")
        return names
    }

    /**
     * Retrieves the set of currently active persona files.
     */
    fun getActivePersonas(context: Context): Set<String> {
        val prefs = getPrefs(context)
        val stored = prefs.getStringSet(PREF_ACTIVE_PERSONAS_SET, null)
        if (!stored.isNullOrEmpty()) {
            return stored
        }
        val legacySingle = prefs.getString(PREF_ACTIVE_PERSONA, null)
        if (!legacySingle.isNullOrBlank()) {
            return setOf(legacySingle)
        }
        return DEFAULT_PERSONAS_SET
    }

    /**
     * Persists the active persona selection set across restarts without filesystem scans.
     */
    fun setActivePersonas(context: Context, personas: Set<String>) {
        val prefs = getPrefs(context)
        val safeSet = if (personas.isEmpty()) DEFAULT_PERSONAS_SET else personas
        prefs.edit()
            .putStringSet(PREF_ACTIVE_PERSONAS_SET, safeSet)
            .putString(PREF_ACTIVE_PERSONA, safeSet.first())
            .apply()
        Log.i(TAG, "Active personas set updated to: $safeSet")
    }

    fun getActivePersonaName(context: Context): String {
        return getActivePersonas(context).firstOrNull() ?: DEFAULT_PERSONA
    }

    fun setActivePersonaName(context: Context, personaName: String) {
        setActivePersonas(context, setOf(personaName))
    }

    /**
     * Runtime Context Concatenation:
     * Sequentially merges and concatenates the full text of all currently selected .md files.
     * Strict Exclusion of Unchecked Profiles: Any persona left unselected is completely excluded.
     */
    fun getActivePersonasContent(context: Context): String {
        val activeSet = getActivePersonas(context)
        if (activeSet.isEmpty()) return DEFAULT_CODING_PERSONA

        val dir = File(PERSONAS_DIR)
        val sections = mutableListOf<String>()

        // Order personas predictably: coding first, then controller, then chat, then custom alphabetical
        val sortedList = activeSet.toList().sortedWith(
            compareBy<String> { 
                when (it.lowercase()) {
                    "coding.md" -> 0
                    "android_controller.md" -> 1
                    "chat.md" -> 2
                    else -> 3
                }
            }.thenBy { it }
        )

        for (name in sortedList) {
            val file = File(dir, name)
            val content = if (file.exists() && file.length() > 0L) {
                try {
                    file.readText().trim()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed reading persona $name: ${e.message}")
                    getFallbackPersonaContent(name)
                }
            } else {
                getFallbackPersonaContent(name)
            }

            if (content.isNotBlank()) {
                sections.add(content)
            }
        }

        return if (sections.isNotEmpty()) {
            sections.joinToString("\n\n---\n\n")
        } else {
            DEFAULT_CODING_PERSONA
        }
    }

    fun getActivePersonaContent(context: Context): String = getActivePersonasContent(context)

    private fun getFallbackPersonaContent(personaName: String): String {
        return when (personaName.lowercase()) {
            "chat.md" -> DEFAULT_CHAT_PERSONA
            "android_controller.md" -> DEFAULT_CONTROLLER_PERSONA
            "coding.md" -> DEFAULT_CODING_PERSONA
            else -> ""
        }
    }

    /**
     * Human-readable role description for Settings UI.
     */
    fun getPersonaDescription(personaName: String): String {
        return when (personaName.lowercase()) {
            "coding.md" -> "Software engineering, clean syntax, strict file I/O operations, zero filler."
            "chat.md" -> "Natural conversation, dialect mirroring (Darija, Arabic, English), open reasoning, web searches."
            "android_controller.md" -> "Headless programmatic automation, shell commands, script execution, and task scheduling."
            else -> "Custom external operational persona from /sdcard/Medlam-Ai/personas/."
        }
    }
}
