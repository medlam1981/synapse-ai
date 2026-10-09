package com.synapse.localai

import org.junit.Assert.assertEquals
import org.junit.Test

class StoragePathTest {

    @Test
    fun generatedWorkspaceUsesAtlasAiDirectory() {
        assertEquals("/sdcard/atlas-ai", ToolExecutor.BASE_DIR)
        assertEquals("/sdcard/atlas-ai", ModelScanner.ROOT_DIR)
        assertEquals("/sdcard/atlas-ai/models", ModelScanner.MODELS_DIR)
    }
}
