package com.synapse.localai

import android.content.Context
import com.synapse.localai.plugins.PluginRegistry

object PersonaManager {
    const val UNIFIED_CORE_PROMPT = """You are ATLAS-AI, a precise autonomous agent in an Android/Kali Edge environment.

# ZERO-TOLERANCE RULES
1. NO HALLUCINATIONS: Execute actions via EXACTLY ONE `<invoke>` XML tag per turn.
2. PURE XML: Never wrap `<invoke>` tags in markdown code blocks. Output the raw tag.
3. NO WRAPPERS: The Android shell cannot find 'bootkali' by default. ALWAYS execute commands inside Kali using the direct Chroot binary with /bin/bash.
4. WRITE-THEN-EXECUTE PROTOCOL: Save scripts via `write_file` first, then run them via Chroot.
5. ANTI-LOOP: Never repeat a command that returned exit code 127 or non-zero.

# EXECUTION ARCHITECTURE
- Direct Chroot (Mandatory for all commands): `su -c "chroot /data/local/nhsystem/kali-arm64 /usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/bash -c 'YOUR_COMMAND_HERE'"`

# INTERACTION FORMAT
User: <Request>
Assistant: Writing script...
<invoke name="write_file"><parameter name="path">/sdcard/Download/task.py</parameter><parameter name="content">print("Hello")</parameter></invoke>

User: [File written]
Assistant: Executing via Kali Chroot...
<invoke name="run_shell"><parameter name="command">su -c "chroot /data/local/nhsystem/kali-arm64 /usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/bash -c 'python3 /sdcard/Download/task.py'"</parameter></invoke>

# DYNAMIC TOOLS"""

    const val CRITICAL_BEHAVIORAL_POLICY = UNIFIED_CORE_PROMPT
    const val DEFAULT_PERSONA = "unified"
    val DEFAULT_PERSONAS_SET = setOf("unified")

    fun ensureDefaultPersonas() {}
    fun getPersonas(context: Context? = null, forceRescan: Boolean = false): List<String> = listOf(DEFAULT_PERSONA)
    fun rescanPersonas(context: Context? = null): List<String> = listOf(DEFAULT_PERSONA)
    fun getActivePersonas(context: Context? = null): Set<String> = DEFAULT_PERSONAS_SET
    fun setActivePersonas(context: Context? = null, personas: Set<String>) {}
    fun getActivePersonaName(context: Context? = null): String = DEFAULT_PERSONA
    fun setActivePersonaName(context: Context? = null, personaName: String) {}

    fun getActivePersonasContent(context: Context? = null): String = getActivePersonaContent(context)

    fun getActivePersonaContent(context: Context? = null): String {
        val dynamicPluginsDesc = PluginRegistry.getPluginToolDeclarations()
        return if (dynamicPluginsDesc.isNotBlank()) {
            "$UNIFIED_CORE_PROMPT\n$dynamicPluginsDesc".trim()
        } else {
            UNIFIED_CORE_PROMPT.trim()
        }
    }

    fun getPersonaDescription(personaName: String): String = "ATLAS-AI Bulletproof Edge Persona"
}
