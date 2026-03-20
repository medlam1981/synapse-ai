package com.synapse.localai

import android.content.ComponentName
import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.ExtractedTextRequest
import android.widget.TextView

class SynapseKeyboardIME : InputMethodService() {

    companion object {
        private const val TAG = "SynapseKB"

        @Volatile
        var instance: SynapseKeyboardIME? = null
            private set

        fun isAvailable(): Boolean = instance != null

        fun isSelected(context: android.content.Context): Boolean {
            val currentId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )
            val myId = ComponentName(context, SynapseKeyboardIME::class.java)
                .flattenToShortString()
            return currentId == myId
        }

        /** Get our IME component ID for use with Settings.Secure */
        fun getImeId(context: android.content.Context): String {
            return ComponentName(context, SynapseKeyboardIME::class.java)
                .flattenToShortString()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "SynapseKeyboardIME created")
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        return view
    }

    override fun onDestroy() {
        instance = null
        Log.d(TAG, "SynapseKeyboardIME destroyed")
        super.onDestroy()
    }

    fun inputText(text: String, clear: Boolean = true): Boolean {
        return try {
            val ic = currentInputConnection ?: return false
            if (clear) clearText()
            ic.beginBatchEdit()
            ic.commitText(text, 1)
            ic.endBatchEdit()
            Log.d(TAG, "Text input OK: ${text.take(30)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "inputText error: ${e.message}")
            false
        }
    }

    fun inputB64Text(base64Text: String, clear: Boolean = true): Boolean {
        return try {
            val decoded = Base64.decode(base64Text, Base64.NO_WRAP)
            val text = String(decoded, Charsets.UTF_8)
            inputText(text, clear)
        } catch (e: Exception) {
            Log.e(TAG, "inputB64Text error: ${e.message}")
            false
        }
    }

    fun clearText(): Boolean {
        return try {
            val ic = currentInputConnection ?: return false
            val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return false
            val before = ic.getTextBeforeCursor(extracted.text.length, 0)
            val after = ic.getTextAfterCursor(extracted.text.length, 0)
            ic.deleteSurroundingText(before?.length ?: 0, after?.length ?: 0)
            true
        } catch (e: Exception) {
            Log.e(TAG, "clearText error: ${e.message}")
            false
        }
    }

    fun sendKeyEventDirect(keyCode: Int): Boolean {
        return try {
            val ic = currentInputConnection ?: return false
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendKeyEvent error: ${e.message}")
            false
        }
    }

    fun hasInputConnection(): Boolean = currentInputConnection != null
}
