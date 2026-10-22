package com.synapse.localai.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextSanitizerTest {
    @Test
    fun removesCodeMarkupCommandsAndPathsButKeepsNaturalTextAndNumbers() {
        val input = """
            الإصدار 2.1 يعمل الآن.
            ```bash
            adb shell pm list packages
            ```
            افتح الملف `/sdcard/atlas-ai/voice/model.onnx` ثم راجع [الدليل](https://example.com).
            <system>internal data</system>
            $ ./gradlew assembleDebug
        """.trimIndent()

        assertEquals(
            "الإصدار 2.1 يعمل الآن. افتح الملف ثم راجع الدليل.",
            SpeechTextSanitizer.sanitize(input)
        )
    }

    @Test
    fun removesUnclosedCodeFenceAndNormalizesWhitespace() {
        val input = "The answer is 42. ```python\nprint('secret')"

        assertEquals("The answer is 42.", SpeechTextSanitizer.sanitize(input))
    }

    @Test
    fun doesNotSpeakTechnicalCommandLines() {
        val input = "Done successfully.\ncd /sdcard/atlas-ai\nNow restart the app."

        assertEquals("Done successfully. Now restart the app.", SpeechTextSanitizer.sanitize(input))
    }
}
