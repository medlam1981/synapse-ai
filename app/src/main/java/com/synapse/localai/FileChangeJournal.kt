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

    @Synchronized
    fun write(transactionId: String?, target: File, content: String): WriteResult {
        val id = transactionId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val transactionDir = ensureTransactionDirectory(id)
        val parent = target.absoluteFile.parentFile
            ?: throw IllegalArgumentException("Target file has no parent directory.")
        if (target.exists() && !target.isFile) {
            throw IllegalArgumentException("Target path is not a regular file: ${target.absolutePath}")
        }
        if (!parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("Could not create target directory: ${parent.absolutePath}")
        }

        recordBeforeImage(id, target)

        val index = nextEntryIndex(transactionDir) - 1
        val temp = File(parent, ".${target.name}.atlas-$index.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(content.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            replaceAtomically(temp, target)
        } finally {
            if (temp.exists()) temp.delete()
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

    private fun recordBeforeImage(transactionId: String, target: File) {
        val transactionDir = ensureTransactionDirectory(transactionId)
        if (target.exists() && !target.isFile) {
            throw IllegalArgumentException("Target path is not a regular file: ${target.absolutePath}")
        }
        val index = nextEntryIndex(transactionDir)
        val backupName = "before_$index"
        val backup = File(transactionDir, backupName)
        val existed = target.exists()
        if (existed) target.copyTo(backup, overwrite = false)
        val metadata = Properties().apply {
            setProperty("path", target.absolutePath)
            setProperty("existed", existed.toString())
            setProperty("backup", backupName)
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
}
