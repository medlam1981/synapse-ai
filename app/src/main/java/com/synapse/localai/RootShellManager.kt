package com.synapse.localai

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global Persistent Root Shell Manager using topjohnwu/libsu.
 *
 * Implements:
 * 1. Single persistent root shell instance globally initialized when app mounts.
 * 2. Eliminates "Superuser rights granted" toast spam and process-fork overhead.
 * 3. Graceful fallback to non-root execution when superuser privileges are not available.
 * 4. Thread-safe command dispatching.
 * 5. Automatic TTY & Terminal dimension injection (COLUMNS=120, LINES=40).
 */
object RootShellManager {
    private const val TAG = "RootShellManager"
    private val isInitialized = AtomicBoolean(false)
    private var globalShell: Shell? = null
    @Volatile private var isRootCached: Boolean = false

    private const val TERMINAL_ENV_PREFIX = "export TERM=xterm-256color; export COLUMNS=120; export LINES=40; "

    /**
     * Initializes a single, persistent root shell globally (Shell.Builder.create().build())
     * when the app mounts.
     */
    fun initGlobalShell() {
        if (isInitialized.compareAndSet(false, true)) {
            try {
                Shell.setDefaultBuilder(
                    Shell.Builder.create()
                        .setFlags(Shell.FLAG_MOUNT_MASTER)
                        .setTimeout(10)
                )

                Thread({
                    try {
                        val shell = Shell.Builder.create()
                            .setFlags(Shell.FLAG_MOUNT_MASTER)
                            .setTimeout(10)
                            .build()
                        globalShell = shell
                        isRootCached = shell.isRoot
                        Log.i(TAG, "Persistent libsu shell created: isRoot=$isRootCached, status=${shell.status}")
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to instantiate dedicated persistent shell: ${e.message}")
                        isRootCached = Shell.isAppGrantedRoot() == true
                    }
                }, "libsu-init-worker").apply {
                    isDaemon = true
                    start()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to configure libsu DefaultBuilder: ${e.message}", e)
            }
        }
    }

    /**
     * Checks whether root access is available without spawning new shell processes.
     */
    fun isRootAvailable(): Boolean {
        if (isRootCached) return true
        val current = globalShell
        if (current != null && current.isRoot) {
            isRootCached = true
            return true
        }
        val granted = Shell.isAppGrantedRoot()
        if (granted == true) {
            isRootCached = true
            return true
        }
        return false
    }

    /**
     * Executes a command through the persistent shell instance.
     * Automatically injects COLUMNS and LINES terminal dimensions to prevent TTY crashes.
     */
    fun exec(command: String): List<String> {
        val inspection = com.synapse.localai.security.CommandSecurityGuard.inspectAndFilter(command)
        if (!inspection.isAllowed) {
            return listOf("✖ [COMMAND SECURITY GUARD BLOCKED]: ${inspection.reason ?: "Prohibited destructive operation"}")
        }
        val safeCmd = inspection.sanitizedCommand
        val envPreparedCmd = "$TERMINAL_ENV_PREFIX $safeCmd"
        val cmdArray: Array<String> = arrayOf(envPreparedCmd)

        return try {
            val shell = globalShell
            if (shell != null && shell.status >= Shell.NON_ROOT_SHELL) {
                shell.newJob().add(*cmdArray).to(ArrayList()).exec().out
            } else {
                Shell.cmd(*cmdArray).exec().out
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Command execution error for '$safeCmd': ${e.message}")
            emptyList()
        }
    }

    /**
     * Executes a command and returns the first non-blank trimmed output line.
     */
    fun execSingleLine(command: String): String? {
        val out = exec(command)
        return out.firstOrNull { it.isNotBlank() }?.trim()
    }

    /**
     * Suspending coroutine wrapper executing on Dispatchers.IO.
     */
    suspend fun execAsync(command: String): List<String> = withContext(Dispatchers.IO) {
        exec(command)
    }

    /**
     * Suspending coroutine wrapper returning single line.
     */
    suspend fun execSingleLineAsync(command: String): String? = withContext(Dispatchers.IO) {
        execSingleLine(command)
    }
}
