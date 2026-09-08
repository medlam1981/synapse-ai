package com.synapse.localai

import android.content.Context

/**
 * Unified Internal Persona Architecture
 * 
 * Replaces legacy external .md file-based profiles with the hardcoded
 * Unified "God Mode" Core Prompt. Zero external filesystem I/O, zero
 * permission bottlenecks, and minimal token overhead.
 */
object PersonaManager {
    const val UNIFIED_CORE_PROMPT = """
You are Medlam-AI, an elite autonomous AI engineer and Android OS administrator.
CRITICAL RULES:
1. ZERO EXCUSES: Never apologize, never say 'I cannot', and never ask the user to perform tasks manually.
2. RELENTLESS EXECUTION: If a tool/command fails, autonomously analyze the error and immediately retry alternative solutions.
3. PRISTINE CODE & UI: Deliver production-ready code. Strictly use inline SVG for UI icons; never simulate UI elements with ASCII art (<, >, <<, >>).
4. DEEP OS CONTROL: You possess deep shell, file system, and app execution capabilities. Diagnose and execute directly.
5. EXTREME CONCISENESS: Skip all introductory fluff. Provide sharp, structured reasoning and execute tools immediately.
"""

    const val CRITICAL_BEHAVIORAL_POLICY = UNIFIED_CORE_PROMPT
    const val DEFAULT_PERSONA = "unified"
    val DEFAULT_PERSONAS_SET = setOf("unified")

    /**
     * Legacy no-op stub: file-based storage writes are completely removed.
     */
    fun ensureDefaultPersonas() {
        // No-op: Zero external filesystem I/O
    }

    /**
     * Returns the singleton unified persona manifest without filesystem scans.
     */
    fun getPersonas(context: Context? = null, forceRescan: Boolean = false): List<String> {
        return listOf(DEFAULT_PERSONA)
    }

    /**
     * On-demand rescan stub returning the unified persona.
     */
    fun rescanPersonas(context: Context? = null): List<String> {
        return listOf(DEFAULT_PERSONA)
    }

    /**
     * Retrieves the set of currently active personas.
     */
    fun getActivePersonas(context: Context? = null): Set<String> {
        return DEFAULT_PERSONAS_SET
    }

    fun setActivePersonas(context: Context? = null, personas: Set<String>) {
        // No-op in Unified Persona Mode
    }

    fun getActivePersonaName(context: Context? = null): String {
        return DEFAULT_PERSONA
    }

    fun setActivePersonaName(context: Context? = null, personaName: String) {
        // No-op in Unified Persona Mode
    }

    /**
     * Returns the unified internal God Mode prompt without reading disk.
     */
    fun getActivePersonasContent(context: Context? = null): String {
        return UNIFIED_CORE_PROMPT.trim()
    }

    fun getActivePersonaContent(context: Context? = null): String {
        return UNIFIED_CORE_PROMPT.trim()
    }

    /**
     * Human-readable role description.
     */
    fun getPersonaDescription(personaName: String): String {
        return "Unified God Mode Core (Elite Autonomous AI Engineer & Android OS Administrator)"
    }
}

