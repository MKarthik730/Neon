package com.lifevault.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lifevault.LifeVaultApp
import com.lifevault.domain.model.AttendanceStatus
import com.lifevault.domain.model.Session
import java.time.LocalDate

/**
 * "Present"/"Absent" tapped on an attendance notification, with the quick-action queue enabled.
 * Writes only (date, session, status) to the pending-actions queue; it is merged into the encrypted
 * vault on the next unlock (or immediately if the vault is unlocked right now).
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MARK) return
        val date = intent.getStringExtra(NavTargets.EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        val session = intent.getStringExtra(NavTargets.EXTRA_SESSION)?.let { runCatching { Session.valueOf(it) }.getOrNull() } ?: return
        val status = intent.getStringExtra(NavTargets.EXTRA_STATUS)?.let { runCatching { AttendanceStatus.valueOf(it) }.getOrNull() } ?: return
        if (status != AttendanceStatus.PRESENT && status != AttendanceStatus.ABSENT) return

        val app = context.applicationContext as LifeVaultApp
        app.container.pendingQueue.append(PendingAttendanceAction(date, session, status, System.currentTimeMillis()))
        Notifier.cancel(context, intent.getIntExtra(EXTRA_NOTIFICATION_ID, Notifier.idFor("att:$date:$session")))
        app.container.vault.mergePendingActionsIfUnlocked()
    }

    companion object {
        const val ACTION_MARK = "com.lifevault.action.MARK_ATTENDANCE"
        const val EXTRA_NOTIFICATION_ID = "com.lifevault.NOTIFICATION_ID"
    }
}
