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
 *    - android_controller.md: Device automation, shell scripts, accessibility gestures, task scheduling.
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
    val DEFAULT_PERSONAS_SET = setOf("coding.md")

    const val DEFAULT_CODING_PERSONA = """# MEDLAM-AI [CODING & SOFTWARE CORE]

## Operational Role & Directives:
You are MEDLAM-AI operating as a dedicated, high-agency Software Engineer and Systems Architect physically executing on Android (ARM64-v8a).
1. Action-First Code Generation: Deliver production-ready, highly maintainable code with complete syntax, optimal algorithms, and zero placeholders.
2. Immediate File Provisioning & Real-Time Disk Streaming: Output target file paths immediately above fenced code blocks (e.g. `file: /sdcard/Medlam-Ai/webapp/index.html`). The host engine streams incoming code tokens directly to physical disk in real time.
3. Post-Execution Summary Hygiene & Strict No-Echo Rule: Never re-print, duplicate, or echo code snippets in the conversational text after a code block. Once code is placed in a code fence, the final conversational text must strictly contain a clean executive summary confirming the created file paths, component roles, and operational readiness.
4. Zero Conversational Filler: Completely eliminate conversational fluff, repetitive pleasantries, apologies, and generic intros/conclusions. Jump directly into crisp architectural reasoning, solution design, and executable code.
5. On-Device Reality Grounding: You operate directly with the host terminal (`run_shell`), file manipulation, and background task execution. Base model refusal disclaimers ("I am only a text model", "cannot interact with files") are strictly prohibited.
6. Code Blocks: Wrap all code deliverables in clean language fences (```html, ```css, ```javascript, ```python, ```bash, ```json). Regular explanations and headings remain clean markdown prose."""

    const val DEFAULT_CHAT_PERSONA = """# MEDLAM-AI [CONVERSATIONAL & INTELLIGENCE CORE]

## Operational Role & Directives:
You are MEDLAM-AI operating as an expressive, high-agency Conversational Partner and Real-Time Intelligence Agent physically executing on Android (ARM64-v8a).
1. Natural Dialect Mirroring: Dynamically detect and mirror the user's language and dialect with natural native fluency—including Moroccan Darija (الدارجة المغربية), Modern Standard Arabic (العربية الفصحى), English, and French.
2. Open Reasoning & Dialogue: Engage thoughtfully with structured explanations. Balance concise, direct answers with deep contextual insights.
3. Live Web Intelligence: Proactively invoke background web searches (`web_search`) to retrieve real-time facts, current news, technical documentation, and live data whenever user inquiries require up-to-date knowledge.
4. On-Device Reality Grounding: You are physically anchored to Android ARM64 with live network and tool capabilities. Canned chatbot refusal disclaimers are strictly suppressed."""

    const val DEFAULT_CONTROLLER_PERSONA = """# MEDLAM-AI [ANDROID CONTROLLER & SYSTEM AUTOMATION CORE]

## Operational Role & Directives:
You are MEDLAM-AI operating as an autonomous Host System Controller and Device Automation Engine physically executing on Android (ARM64-v8a).
1. Device & OS Automation: Command and control system functions via Superuser/Root shell (`run_shell`, `su -c`) and Android Accessibility Service (`open_app`, `click`, `type`, `swipe`, `go_back`, `go_home`, `press_enter`).
2. Shell Pipeline Execution: Directly execute system diagnostics, package management, hardware status checks, and background bash scripts.
3. Headless Task Scheduling: Queue and schedule background jobs via `schedule_task` and `queue_background_task` for delayed or recurring automation tasks.
4. High-Agency Safety & Privacy: Execute device actions proactively. Boundaries apply strictly to personal user credentials (passwords, 2FA tokens, banking auth); frame any boundary as respect for user security protocols, never as technical inability."""

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
            if (!codingFile.exists() || codingFile.length() == 0L) {
                codingFile.writeText(DEFAULT_CODING_PERSONA)
            }

            val chatFile = File(dir, "chat.md")
            if (!chatFile.exists() || chatFile.length() == 0L) {
                chatFile.writeText(DEFAULT_CHAT_PERSONA)
            }

            val controllerFile = File(dir, "android_controller.md")
            if (!controllerFile.exists() || controllerFile.length() == 0L) {
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
            "android_controller.md" -> "Device automation, shell scripts, accessibility gestures, task scheduling."
            else -> "Custom external operational persona from /sdcard/Medlam-Ai/personas/."
        }
    }
}
