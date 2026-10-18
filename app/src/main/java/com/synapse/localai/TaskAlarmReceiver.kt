package com.synapse.localai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import com.synapse.localai.AtlasLog as Log

class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra("TASK_ID")
        Log.i("TaskAlarmReceiver", "Alarm fired for task: $taskId")
        
        // Play notification sound
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone.play()
        } catch (e: Exception) {
            Log.e("TaskAlarmReceiver", "Failed to play sound", e)
        }
        
        // Wake up app/service
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(launchIntent)
        
        // Notify BackgroundTaskEngine (it will pick up due tasks automatically in its loop once started)
    }

    companion object {
        fun scheduleTaskAlarm(context: Context, taskId: String, executeAtMillis: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, TaskAlarmReceiver::class.java).apply {
                putExtra("TASK_ID", taskId)
            }
            
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            
            val pendingIntent = PendingIntent.getBroadcast(context, taskId.hashCode(), intent, flags)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                Log.w("TaskAlarmReceiver", "Cannot schedule exact alarms. Using window.")
                alarmManager.setWindow(AlarmManager.RTC_WAKEUP, executeAtMillis, 60000L, pendingIntent)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, executeAtMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, executeAtMillis, pendingIntent)
            }
            Log.i("TaskAlarmReceiver", "Scheduled alarm for task $taskId at $executeAtMillis")
        }
    }
}
