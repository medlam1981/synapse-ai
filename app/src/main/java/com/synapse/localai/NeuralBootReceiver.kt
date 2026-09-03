package com.synapse.localai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class NeuralBootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "NeuralBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d(TAG, "Device boot completed / package replaced. Starting neural priming...")
            NeuralPrimingManager.primeNeuralCore(context)
        }
    }
}
