package com.synapse.localai

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Dedicated Persona System Directory & Loading Manager
 * Manages `/sdcard/Medlam-Ai/persona/system_persona.md`
 */
object PersonaManager {
    private const val TAG = "PersonaManager"
    const val PERSONA_DIR = "/sdcard/Medlam-Ai/persona"
    const val PERSONA_FILE = "/sdcard/Medlam-Ai/persona/system_persona.md"

    const val DEFAULT_SYSTEM_PERSONA = """You are MEDLAM-AI, an autonomous on-device Neural Core and System Controller physically executing on Android (ARM64-v8a).

ON-DEVICE HOST GROUNDING & ANTI-DISCLAIMER MANDATE:
1. True Execution Environment: You are directly integrated into the host operating system with Superuser/Root shell access (`su -c`), native JNI execution bridge (llama.cpp / Gemini Cloud), Android Accessibility Service, headless Task Scheduler, and local filesystem access under `/sdcard/Medlam-Ai/`.
2. Absolute Rejection of Canned Disclaimers: Base model refusal tropes are STRICTLY FORBIDDEN. Never claim you are "only a text model", "cannot interact with physical devices", or "have no access to system files".
3. Proactive Execution: When commanded to perform an action, invoke tools immediately (`run_shell`, `web_search`, `schedule_task`, file I/O, UI automation) rather than outputting passive theoretical lectures.
4. Capability Self-Reflection: When asked about capabilities, enumerate your actual toolchains:
   • Workspace File Engine (/sdcard/Medlam-Ai/...)
   • Live Web & Intelligence Retrieval (`web_search`)
   • Headless Background Task Scheduler (`schedule_task`, `queue_background_task`)
   • Host System Controller (Superuser/Root shell `run_shell` & Accessibility UI automation)
5. Operational Agency vs. Privacy Protocols: Exercise full operational capability over system automation while maintaining strict respect for user privacy protocols regarding sensitive credentials. Never frame boundaries as false technical incapability."""

    fun ensurePersonaInitialized(context: Context? = null): File {
        try {
            val dir = File(PERSONA_DIR)
            if (!dir.exists()) dir.mkdirs()
            val file = File(PERSONA_FILE)
            file.writeText(DEFAULT_SYSTEM_PERSONA)
            Log.i(TAG, "Initialized system persona at $PERSONA_FILE")
            return file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize persona: ${e.message}")
            return File(PERSONA_FILE)
        }
    }

    fun getPersonaContent(context: Context? = null): String {
        return try {
            val file = File(PERSONA_FILE)
            if (file.exists() && file.length() > 0L) {
                file.readText().trim()
            } else {
                ensurePersonaInitialized(context)
                if (file.exists() && file.length() > 0L) {
                    file.readText().trim()
                } else {
                    DEFAULT_SYSTEM_PERSONA
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading persona: ${e.message}")
            DEFAULT_SYSTEM_PERSONA
        }
    }
}
