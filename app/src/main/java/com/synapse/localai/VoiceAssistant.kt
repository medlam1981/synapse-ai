package com.synapse.localai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import androidx.core.content.ContextCompat

object VoiceAssistantContract {
    const val ACTION_SUBMIT_COMMAND = "com.synapse.localai.action.SUBMIT_VOICE_COMMAND"
    const val ACTION_REQUEST_MIC_PERMISSION = "com.synapse.localai.action.REQUEST_MIC_PERMISSION"
    const val ACTION_ASSIST = "android.intent.action.ASSIST"
    const val ACTION_VOICE_COMMAND = "android.intent.action.VOICE_COMMAND"
    const val EXTRA_COMMAND = "com.synapse.localai.extra.VOICE_COMMAND"
}

internal class VoiceAudioRoute(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val originalMode = audioManager.mode
    private val originalCommunicationDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        audioManager.communicationDevice
    } else {
        null
    }
    private var active = false

    fun activate(): Boolean {
        if (active) return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (
                    ContextCompat.checkSelfPermission(
                        appContext,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return false
                }
                val bluetoothDevice = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                } ?: return false
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (!audioManager.setCommunicationDevice(bluetoothDevice)) {
                    audioManager.mode = originalMode
                    return false
                }
                active = true
                true
            } else {
                if (!audioManager.isBluetoothScoAvailableOffCall) return false
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                run { audioManager.isBluetoothScoOn = true }
                active = true
                true
            }
        } catch (e: SecurityException) {
            android.util.Log.w("SynapseVoice", "Bluetooth microphone routing permission was denied", e)
            audioManager.mode = originalMode
            false
        } catch (e: IllegalStateException) {
            android.util.Log.w("SynapseVoice", "Bluetooth microphone route is unavailable", e)
            audioManager.mode = originalMode
            false
        }
    }

    override fun close() {
        if (!active) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
                originalCommunicationDevice?.let(audioManager::setCommunicationDevice)
            } else {
                @Suppress("DEPRECATION")
                run { audioManager.isBluetoothScoOn = false }
                audioManager.stopBluetoothSco()
            }
        } catch (e: SecurityException) {
            android.util.Log.w("SynapseVoice", "Could not restore previous Bluetooth audio route", e)
        } finally {
            audioManager.mode = originalMode
            active = false
        }
    }
}

class SynapseVoiceInteractionService : VoiceInteractionService()

class SynapseVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        SynapseVoiceInteractionSession(this)
}

private class SynapseVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onCreateContentView(): View = View(context)

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startVoiceActivity(
            Intent(context, MainActivity::class.java)
                .setAction(VoiceAssistantContract.ACTION_ASSIST)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }
}
