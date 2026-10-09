package com.synapse.localai

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/**
 * Keeps app-private before-images for writes so a task's file changes can be reversed.
 */
class FileChangeJournal(private val rootDir: File) {

    data class WriteResult(val transactionId: String, val file: File)

    data class RollbackResult(val success: Boolean, val message: String)

    fun write(transactionId: String?, target: File, content: String): WriteResult =
        writeBytes(transactionId, target, content.toByteArray(StandardCharsets.UTF_8))

    @Synchronized
    fun writeBytes(transactionId: String?, target: File, content: ByteArray): WriteResult {
        val id = transactionId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val transactionDir = ensureTransactionDirectory(id)
        val parent = target.absoluteFile.parentFile
            ?: throw IllegalArgumentException("Target file has no parent directory.")
        if (target.exists() && !target.isFile) {
            throw IllegalArgumentException("Target path is not a regular file: ${target.absolutePath}")
        }
        var rootBacked = target.exists() && !target.canRead()
        if (!parent.exists() && !parent.mkdirs()) {
            if (!RootShellManager.isRootAvailable()) {
                throw IllegalStateException(
                    "Could not create target directory: ${parent.absolutePath}. " +
                        "Choose a writable path under /sdcard/atlas-ai or grant root access."
                )
            }
            runRootChecked("mkdir -p ${shellQuote(parent.absolutePath)}", "Could not create target directory")
            rootBacked = true
        } else if (parent.exists() && !parent.canWrite()) {
            if (!RootShellManager.isRootAvailable()) {
                throw IllegalStateException(
                    "No write permission for target directory: ${parent.absolutePath}. " +
                        "Choose a writable path under /sdcard/atlas-ai or grant root access."
                )
            }
            rootBacked = true
        }

        recordBeforeImage(id, target, rootBacked)

        val index = nextEntryIndex(transactionDir) - 1
        if (rootBacked) {
            val payload = File(transactionDir, "payload_$index")
            FileOutputStream(payload).use { output ->
                output.write(content)
                output.fd.sync()
            }
            val temp = File(parent, ".${target.name}.atlas-$index.tmp")
            try {
                runRootChecked(
                    "cp ${shellQuote(payload.absolutePath)} ${shellQuote(temp.absolutePath)} && " +
                        "mv -f ${shellQuote(temp.absolutePath)} ${shellQuote(target.absolutePath)}",
                    "Could not write target file"
                )
            } finally {
                payload.delete()
            }
        } else {
            val temp = File(parent, ".${target.name}.atlas-$index.tmp")
            try {
                FileOutputStream(temp).use { output ->
                    output.write(content)
                    output.fd.sync()
                }
                replaceAtomically(temp, target)
            } finally {
                if (temp.exists()) temp.delete()
            }
        }
        transactionDir.setLastModified(System.currentTimeMillis())
        return WriteResult(id, target)
    }

    @Synchronized
    fun checkpoint(transactionId: String, target: File): String {
        require(transactionId.isNotBlank()) { "A transaction ID is required." }
        recordBeforeImage(transactionId, target)
        return transactionId
    }

    @Synchronized
    fun rollback(transactionId: String): RollbackResult {
        if (transactionId.isBlank()) {
            return RollbackResult(false, "A transaction ID is required.")
        }
        val transactionDir = transactionDirectory(transactionId)
        if (!transactionDir.isDirectory) {
            return RollbackResult(false, "No saved file changes for transaction $transactionId.")
        }
        val entries = transactionDir.listFiles { file ->
            file.isFile && file.name.matches(Regex("""entry_\d+\.properties"""))
        }?.sortedByDescending { it.name.removePrefix("entry_").removeSuffix(".properties").toIntOrNull() ?: -1 }
            ?: return RollbackResult(false, "Could not read the transaction journal.")
        if (entries.isEmpty()) {
            return RollbackResult(false, "No saved file changes for transaction $transactionId.")
        }

        val failures = mutableListOf<String>()
        for (entryFile in entries) {
            try {
                val metadata = Properties().apply {
                    entryFile.inputStream().use(::load)
                }
                val target = File(metadata.getProperty("path")
                    ?: throw IllegalStateException("Missing target path in ${entryFile.name}"))
                if (metadata.getProperty("rootBacked").toBoolean()) {
                    if (metadata.getProperty("existed").toBoolean()) {
                        val backup = File(transactionDir, metadata.getProperty("backup")
                            ?: throw IllegalStateException("Missing backup reference in ${entryFile.name}"))
                        if (!backup.isFile) throw IllegalStateException("Backup is missing for ${target.absolutePath}")
                        val parent = target.absoluteFile.parentFile
                            ?: throw IllegalStateException("Target has no parent directory.")
                        val temp = File(parent, ".${target.name}.atlas-restore.tmp")
                        runRootChecked(
                            "cp ${shellQuote(backup.absolutePath)} ${shellQuote(temp.absolutePath)} && " +
                                "mv -f ${shellQuote(temp.absolutePath)} ${shellQuote(target.absolutePath)}",
                            "Could not restore ${target.absolutePath}"
                        )
                    } else {
                        runRootChecked("rm -f ${shellQuote(target.absolutePath)}", "Could not remove ${target.absolutePath}")
                    }
                    continue
                }
                if (metadata.getProperty("existed").toBoolean()) {
                    val backup = File(transactionDir, metadata.getProperty("backup")
                        ?: throw IllegalStateException("Missing backup reference in ${entryFile.name}"))
                    if (!backup.isFile) throw IllegalStateException("Backup is missing for ${target.absolutePath}")
                    val parent = target.absoluteFile.parentFile
                        ?: throw IllegalStateException("Target has no parent directory.")
                    if (!parent.exists() && !parent.mkdirs()) {
                        throw IllegalStateException("Could not restore parent directory ${parent.absolutePath}")
                    }
                    val temp = File(parent, ".${target.name}.atlas-restore.tmp")
                    try {
                        backup.copyTo(temp, overwrite = true)
                        replaceAtomically(temp, target)
                    } finally {
                        if (temp.exists()) temp.delete()
                    }
                } else if (target.exists() && !target.delete()) {
                    throw IllegalStateException("Could not remove newly created file ${target.absolutePath}")
                }
            } catch (e: Exception) {
                failures.add(e.message ?: e.javaClass.simpleName)
            }
        }

        if (failures.isNotEmpty()) {
            return RollbackResult(false, "Rollback incomplete: ${failures.joinToString("; ")}")
        }
        if (!transactionDir.deleteRecursively()) {
            return RollbackResult(false, "Files were restored, but the rollback journal could not be removed.")
        }
        return RollbackResult(true, "Restored ${entries.size} file change(s) from transaction $transactionId.")
    }

    private fun nextEntryIndex(transactionDir: File): Int =
        (transactionDir.listFiles()?.mapNotNull {
            Regex("""entry_(\d+)\.properties""").matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull()
        }?.maxOrNull() ?: -1) + 1

    private fun recordBeforeImage(transactionId: String, target: File, rootBacked: Boolean = false) {
        val transactionDir = ensureTransactionDirectory(transactionId)
        val exists = if (rootBacked) {
            val result = runRootChecked(
                "if [ -e ${shellQuote(target.absolutePath)} ]; then " +
                    "if [ -f ${shellQuote(target.absolutePath)} ]; then echo FILE; else echo NOT_FILE; fi; " +
                    "else echo MISSING; fi",
                "Could not inspect target path"
            )
            when (result.trim().lineSequence().lastOrNull()) {
                "FILE" -> true
                "MISSING" -> false
                "NOT_FILE" -> throw IllegalArgumentException("Target path is not a regular file: ${target.absolutePath}")
                else -> throw IllegalStateException("Could not determine target file state: ${target.absolutePath}")
            }
        } else {
            if (target.exists() && !target.isFile) {
                throw IllegalArgumentException("Target path is not a regular file: ${target.absolutePath}")
            }
            target.exists()
        }
        if (exists && !rootBacked && !target.isFile) {
            throw IllegalArgumentException("Target path is not a regular file: ${target.absolutePath}")
        }
        val index = nextEntryIndex(transactionDir)
        val backupName = "before_$index"
        val backup = File(transactionDir, backupName)
        if (exists) {
            if (rootBacked) {
                runRootChecked(
                    "cp ${shellQuote(target.absolutePath)} ${shellQuote(backup.absolutePath)}",
                    "Could not back up existing target file"
                )
            } else {
                target.copyTo(backup, overwrite = false)
            }
        }
        val metadata = Properties().apply {
            setProperty("path", target.absolutePath)
            setProperty("existed", exists.toString())
            setProperty("backup", backupName)
            setProperty("rootBacked", rootBacked.toString())
        }
        writePropertiesAtomically(File(transactionDir, "entry_$index.properties"), metadata)
        transactionDir.setLastModified(System.currentTimeMillis())
    }

    private fun ensureTransactionDirectory(transactionId: String): File {
        val transactionDir = transactionDirectory(transactionId)
        if (!transactionDir.exists() && !transactionDir.mkdirs()) {
            throw IllegalStateException("Could not create file-change journal.")
        }
        return transactionDir
    }

    private fun transactionDirectory(transactionId: String): File {
        val safePrefix = transactionId.replace(Regex("""[^A-Za-z0-9_-]"""), "_").take(48).ifBlank { "change" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(transactionId.toByteArray(StandardCharsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it) }
        return File(rootDir, "${safePrefix}_$digest")
    }

    private fun writePropertiesAtomically(target: File, properties: Properties) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                properties.store(output, null)
                output.fd.sync()
            }
            replaceAtomically(temp, target)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun replaceAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun runRootChecked(command: String, action: String): String {
        if (!RootShellManager.isRootAvailable()) {
            throw IllegalStateException("$action: root access is unavailable.")
        }
        val (exitCode, lines) = RootShellManager.execWithExitCode(command)
        if (exitCode != 0) {
            val detail = lines.joinToString("\n").trim()
            throw IllegalStateException(
                if (detail.isBlank()) "$action (exit code $exitCode)." else "$action (exit code $exitCode): $detail"
            )
        }
        return lines.joinToString("\n")
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
