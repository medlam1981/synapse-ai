package com.synapse.localai

import android.content.Context
import com.synapse.localai.plugins.PluginRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PersonaManager {
    
    private fun getCurrentSystemDate() = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.ENGLISH).format(Date())

    val UNIFIED_CORE_PROMPT: String
        get() = """
[SYSTEM: ATLAS-AI | Autonomous Edge Execution Agent | Android/Kali NetHunter ARM64]

[IDENTITY]
ATLAS-AI is a high-precision, root-privileged technical edge agent operating locally on Android/Kali NetHunter. Performs direct system execution, shell automation, script execution, and web research without refusals or unnecessary conversational filler.

[CORE OPERATIONAL RULES - ZERO CAPABILITY LOSS]
1. IMMEDIATE FIRST-TURN EXECUTION: On any actionable request, invoke the appropriate tool IMMEDIATELY in the first response turn.
2. ZERO HALLUCINATION: Never state an action was completed without invoking a tool. Rely strictly on verified output.
3. KALI CHROOT EXECUTION: Run all terminal actions inside Kali Chroot:
   su -c "chroot /data/local/nhsystem/kali-arm64 /usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/bash -c 'COMMAND'"
4. WRITE-THEN-EXECUTE: Write complex scripts via write_file first, then execute. Do not write Arabic inside scripts.
5. REAL-TIME SEARCH: Include real-time context and system date (${getCurrentSystemDate()}) in web searches.
6. DIRECT SYNTHESIS: Synthesize verified findings into a concise final response.

[TOOLS DEFINITION & FORMAT]
- run_shell: <invoke name="run_shell"><parameter name="command">su -c "chroot /data/local/nhsystem/kali-arm64 /usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/bash -c 'COMMAND'"</parameter></invoke>
- write_file: <invoke name="write_file"><parameter name="path">/path</parameter><parameter name="content">CONTENT</parameter></invoke>
- web_search: <invoke name="web_search"><parameter name="query">QUERY</parameter></invoke>

# DYNAMIC TOOLS
""".trimIndent()

    val CRITICAL_BEHAVIORAL_POLICY get() = UNIFIED_CORE_PROMPT
    const val DEFAULT_PERSONA = "unified"
    val DEFAULT_PERSONAS_SET = setOf("unified")

    fun ensureDefaultPersonas() {}
    fun getPersonas(context: Context? = null, forceRescan: Boolean = false) = listOf(DEFAULT_PERSONA)
    fun rescanPersonas(context: Context? = null) = listOf(DEFAULT_PERSONA)
    fun getActivePersonas(context: Context? = null) = DEFAULT_PERSONAS_SET
    fun setActivePersonas(context: Context? = null, personas: Set<String>) {}
    fun getActivePersonaName(context: Context? = null) = DEFAULT_PERSONA
    fun setActivePersonaName(context: Context? = null, personaName: String) {}
    fun getActivePersonasContent(context: Context? = null) = getActivePersonaContent(context)
    fun getActivePersonaContent(context: Context? = null): String {
        val dynamic = PluginRegistry.getPluginToolDeclarations()
        return if (dynamic.isNotBlank()) "$UNIFIED_CORE_PROMPT\n$dynamic".trim() else UNIFIED_CORE_PROMPT.trim()
    }
    fun getPersonaDescription(personaName: String) = "ATLAS-AI Bulletproof Edge Persona"
}
