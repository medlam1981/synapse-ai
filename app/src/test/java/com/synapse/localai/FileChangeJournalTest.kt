package com.synapse.localai

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileChangeJournalTest {

    private lateinit var root: File
    private lateinit var workspace: File
    private lateinit var journal: FileChangeJournal

    @Before
    fun setUp() {
        val temp = Files.createTempDirectory("atlas-file-journal").toFile()
        root = File(temp, "private-journal")
        workspace = File(temp, "workspace")
        journal = FileChangeJournal(root)
    }

    @After
    fun tearDown() {
        root.parentFile?.deleteRecursively()
    }

    @Test
    fun rollbackRestoresOriginalContents() {
        val target = File(workspace, "existing.txt")
        workspace.mkdirs()
        target.writeText("original")

        journal.write("task-1", target, "updated")
        val result = journal.rollback("task-1")

        assertTrue(result.success)
        assertEquals("original", target.readText())
    }

    @Test
    fun rollbackRemovesFilesCreatedByTransaction() {
        val target = File(workspace, "new.txt")

        journal.write("task-2", target, "created")
        val result = journal.rollback("task-2")

        assertTrue(result.success)
        assertFalse(target.exists())
    }

    @Test
    fun multipleWritesRollbackInReverseOrder() {
        val target = File(workspace, "same.txt")
        workspace.mkdirs()
        target.writeText("before")

        journal.write("task-3", target, "first")
        journal.write("task-3", target, "second")
        val result = journal.rollback("task-3")

        assertTrue(result.success)
        assertEquals("before", target.readText())
    }

    @Test
    fun transactionCanBeRolledBackAfterJournalIsRecreated() {
        val target = File(workspace, "resumable.txt")
        workspace.mkdirs()
        target.writeText("original")

        journal.write("task-persisted", target, "changed")
        val result = FileChangeJournal(root).rollback("task-persisted")

        assertTrue(result.success)
        assertEquals("original", target.readText())
    }

    @Test
    fun failedWriteToDirectoryDoesNotDeleteTheDirectory() {
        val target = File(workspace, "directory")
        target.mkdirs()

        val error = runCatching { journal.write("task-4", target, "invalid") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(target.isDirectory)
    }
}
