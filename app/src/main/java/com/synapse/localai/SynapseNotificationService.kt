package com.synapse.localai

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class SynapseNotificationService : NotificationListenerService() {
    
    companion object {
        private const val TAG = "SynapseNotif"
        val recentNotifications = mutableListOf<String>()
        
        fun getRecentAndClear(): String {
            synchronized(recentNotifications) {
                val res = recentNotifications.joinToString("\n")
                recentNotifications.clear()
                return res.ifEmpty { "No new notifications." }
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val pkg = sbn.packageName
            // Filter only messaging apps to avoid spam
            if (!pkg.contains("whatsapp") && !pkg.contains("telegram") && !pkg.contains("mms") && !pkg.contains("messaging")) return
            
            val extras = sbn.notification.extras
            val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            
            if (title.isNotBlank() && text.isNotBlank()) {
                val entry = "[$pkg] $title: $text"
                Log.d(TAG, "Captured: $entry")
                synchronized(recentNotifications) {
                    recentNotifications.add(entry)
                    if (recentNotifications.size > 20) {
                        recentNotifications.removeAt(0)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}
}
