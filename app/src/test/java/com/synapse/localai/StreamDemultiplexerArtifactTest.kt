package com.synapse.localai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamDemultiplexerArtifactTest {
    @Test
    fun fileArtifactIsDemultiplexedIntoStructuredBlock() {
        val result = StreamDemultiplexer.demux(
            """Created a file
<file_artifact path="/sdcard/atlas-ai/documents/report.docx" name="report.docx" format="docx">First &amp; foremost</file_artifact>"""
        )
        val artifact = result.blocks.filterIsInstance<StreamDemultiplexer.DemuxBlock.FileArtifact>().single()

        assertEquals("/sdcard/atlas-ai/documents/report.docx", artifact.path)
        assertEquals("report.docx", artifact.fileName)
        assertEquals("docx", artifact.format)
        assertEquals("First & foremost", artifact.preview)
        assertTrue(result.blocks.none { it is StreamDemultiplexer.DemuxBlock.Prose && it.text.contains("file_artifact") })
    }
}
