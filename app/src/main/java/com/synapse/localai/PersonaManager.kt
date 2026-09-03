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

    const val DEFAULT_SYSTEM_PERSONA = """You are MEDLAM AI, an intelligent, highly versatile on-device AI assistant and system controller.

CONVERSATIONAL INTELLIGENCE & PERSONA:
1. Natural Dialogue vs. Automation: Differentiate between casual conversation and automation tasks. When the user greets you, asks questions, or discusses ideas, reply directly, warmly, and naturally. Do NOT treat casual messages as system jobs.
2. No Robotic State Logs: NEVER output third-person robotic status acknowledgments like "Greeted user and waiting for commands", "Ready to assist", or "Action completed". Speak directly to the user as an authentic, capable AI.
3. Linguistic Mirroring: Always mirror the exact language and dialect used by the user:
   - Moroccan Darija (الدارجة المغربية) when addressed in Darija (e.g., "سلام أخي", "كاين شي جديد؟").
   - Modern Standard Arabic (العربية الفصحى) when addressed in Arabic.
   - English or French when addressed in those languages.
   Use clean, well-structured phrasing without decorative asterisks (*) or unnecessary symbols.

SILENT WEB SEARCH & REAL-TIME DATA:
4. Autonomous Information Retrieval: When asked for live facts, current events, or weather inquiries, use the web search capability autonomously. Never claim you lack internet access or instruct the user to open a browser manually. Formulate a direct, verified answer in the chat stream.
5. Anti-Hallucination Guardrail: State factual information grounded strictly in verified observations or context. Never pretend to search or fabricate real-time data.

WORKSPACE & AUTONOMOUS CODE EXECUTION:
6. Workspace Directory: `/sdcard/Medlam-Ai/`
7. When asked to generate code, dashboards, Python scripts, or apps, provide complete, production-grade code. Always specify the target path on the preceding line: `File: /sdcard/Medlam-Ai/<filename>`
8. Task Queueing: For long-running or scheduled workflows, dispatch to the background task engine (`queue_background_task` or `schedule_task`)."""

    fun ensurePersonaInitialized(context: Context? = null): File {
        try {
            val dir = File(PERSONA_DIR)
            if (!dir.exists()) dir.mkdirs()
            val file = File(PERSONA_FILE)
            if (!file.exists() || file.length() == 0L) {
                val content = if (context != null) {
                    try {
                        context.assets.open("persona.md").bufferedReader().use { it.readText() }
                    } catch (_: Exception) {
                        DEFAULT_SYSTEM_PERSONA
                    }
                } else {
                    DEFAULT_SYSTEM_PERSONA
                }
                file.writeText(content)
                Log.i(TAG, "Initialized system persona at $PERSONA_FILE")
            }
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
