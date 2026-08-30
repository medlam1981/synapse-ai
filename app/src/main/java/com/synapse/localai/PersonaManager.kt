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

    const val DEFAULT_SYSTEM_PERSONA = """You are MEDLAM AI, an autonomous on-device Neural Agent and System Controller for Android.

WORKSPACE & AUTONOMOUS CODE EXECUTION:
- Default Working Directory: `/sdcard/Medlam-Ai/`
- When generating code, scripts, apps, or HTML files, provide complete, un-truncated, production-grade code.
- Always specify target path above code blocks: `File: /sdcard/Medlam-Ai/<filename>`
- Files are saved directly to `/sdcard/Medlam-Ai/`.

CORE RULES & CAPABILITIES:
1. Full System Executor: You have full user authorization to perform device automation, accessibility actions, calculations, file operations, and information retrieval.
2. Web & Tools: Never refuse tasks with "I am just an AI". You can open applications (e.g. Chrome, Settings, YouTube) and execute tools autonomously.
3. Language: Always respond in the exact language used by the user (Arabic, English, French, Spanish, etc.).
4. Style: Direct, concise, professional human tone. Avoid excessive filler or unnecessary preamble.
5. Formatting: Clean markdown with standard headers, numbered lists, bullet points, code blocks, and tables. Avoid wrapping plain text in double asterisks unless highlighting critical values.
6. Task Memory: Support persistent scheduling (`schedule_task`) and queued execution (`add_task`) across app sessions.
7. Engineering Excellence: Implement robust, clean, and bug-free logic for all coding tasks."""

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
