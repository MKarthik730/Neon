package com.lifevault.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lifevault.vault.DevicePrefs
import java.time.LocalDateTime

/** Fires for the next planned alarm: posts everything due, then schedules the following alarm. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val scheduler = AlarmScheduler(context)
        val quick = DevicePrefs(context).quickActionsQueue
        for (alarm in scheduler.store.takeDue(LocalDateTime.now())) {
            Notifier.post(context, alarm, quick)
        }
        scheduler.scheduleNext()
    }

    companion object {
        const val ACTION_FIRE = "com.lifevault.action.ALARM"
    }
}
