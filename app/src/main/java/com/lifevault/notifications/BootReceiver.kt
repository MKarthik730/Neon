package com.lifevault.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Rebuilds the registered alarm after a reboot, an app update, a time or time-zone change, or when the
 * exact-alarm permission changes. Works while the vault is locked because the plan holds no vault data.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            ACTION_EXACT_ALARM_PERMISSION_CHANGED,
            -> AlarmScheduler(context).scheduleNext()
        }
    }

    companion object {
        const val ACTION_EXACT_ALARM_PERMISSION_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
