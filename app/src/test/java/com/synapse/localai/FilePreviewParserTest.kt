package com.synapse.localai

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePreviewParserTest {

    @Test
    fun csvParserKeepsQuotedCommasAndEscapedQuotes() {
        val file = File.createTempFile("atlas-preview", ".csv")
        try {
            file.writeText("name,description\nAtlas,\"local, private\"\nAgent,\"says \"\"hello\"\"\"\n")
            val sheet = FilePreviewParser.parse(file).sheets.single()

            assertEquals(listOf("name", "description"), sheet.rows[0])
            assertEquals(listOf("Atlas", "local, private"), sheet.rows[1])
            assertEquals(listOf("Agent", "says \"hello\""), sheet.rows[2])
            assertFalse(sheet.wasTruncated)
        } finally {
            file.delete()
        }
    }

    @Test
    fun csvParserSupportsNewlinesInsideQuotedFields() {
        val file = File.createTempFile("atlas-preview", ".csv")
        try {
            file.writeText("id,text\n1,\"first line\nsecond line\"\n")
            val sheet = FilePreviewParser.parse(file).sheets.single()

            assertEquals("first line\nsecond line", sheet.rows[1][1])
        } finally {
            file.delete()
        }
    }

    @Test
    fun csvParserRejectsUnclosedQuotedField() {
        val file = File.createTempFile("atlas-preview", ".csv")
        try {
            file.writeText("id,text\n1,\"unterminated\n")
            assertTrue(runCatching {
                FilePreviewParser.parse(file)
            }.isFailure)
        } finally {
            file.delete()
        }
    }
}
