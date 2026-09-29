package com.lifevault.notifications

import com.lifevault.Brand
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lifevault.MainActivity
import com.lifevault.R
import com.lifevault.domain.model.AttendanceStatus
import com.lifevault.domain.notify.AlarmType
import com.lifevault.domain.notify.PlannedAlarm

/** Where a notification tap should take the user after unlocking. */
object NavTargets {
    const val EXTRA_NAV = "com.lifevault.NAV"
    const val EXTRA_DATE = "com.lifevault.DATE"
    const val EXTRA_SESSION = "com.lifevault.SESSION"
    const val EXTRA_STATUS = "com.lifevault.STATUS"
    const val EXTRA_REF = "com.lifevault.REF"

    const val QUICK_MARK = "quick_mark"
    const val EVENT = "event"
    const val MOOD = "mood"
    const val START_JOB = "start_job"
    const val HOME = "home"
}

/**
 * Posts local notifications. All text is generic and lock-screen safe: notifications are built while the
 * vault may be locked, from the alarm plan alone, so they cannot contain subject names, event titles,
 * amounts or mood data. They are VISIBILITY_PRIVATE with a generic public version as well.
 */
object Notifier {
    const val CH_ATTENDANCE = "attendance"
    const val CH_REMINDERS = "reminders"
    const val CH_MOOD = "mood"
    const val CH_RULES = "rules"
    const val CH_SUMMARY = "summary"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channels = listOf(
            NotificationChannel(CH_ATTENDANCE, "Attendance prompts", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_REMINDERS, "Event and test reminders", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_MOOD, "Daily check-in", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_RULES, "Ground rules prompts", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_SUMMARY, "Daily summary", NotificationManager.IMPORTANCE_LOW),
        )
        for (c in channels) c.lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        nm.createNotificationChannels(channels)
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun idFor(key: String) = key.hashCode() and 0x7fffffff

    data class Content(val channel: String, val title: String, val text: String)

    /** The visible text for each alarm type. Pure, so tests can check it never leaks vault data. */
    fun contentFor(alarm: PlannedAlarm): Content = when (alarm.type) {
        AlarmType.ATTENDANCE -> {
            val s = alarm.session?.label ?: "session"
            Content(CH_ATTENDANCE, "Mark $s attendance", "Were you present for today's ${s.lowercase()} session?")
        }
        AlarmType.EVENT -> Content(CH_REMINDERS, "Upcoming event", "You have something coming up. Open ${Brand.NAME} to see it.")
        AlarmType.MOOD -> Content(CH_MOOD, "Time for your daily check-in", "How is your mood today?")
        AlarmType.RULES -> Content(CH_RULES, "Read your ground rules", "Take a moment with your rules before you start.")
        AlarmType.SUMMARY -> Content(CH_SUMMARY, "Your daily summary is ready", "Open ${Brand.NAME} to see today's progress.")
    }

    fun post(context: Context, alarm: PlannedAlarm, quickActionsQueue: Boolean) {
        if (!canPost(context)) return
        val content = contentFor(alarm)
        val id = idFor(alarm.key)
        val builder = baseBuilder(context, content.channel)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setContentIntent(activityIntent(context, id, openIntent(context, alarm)))
            .setPublicVersion(publicVersion(context, content.channel))

        val date = alarm.date
        val session = alarm.session
        if (alarm.type == AlarmType.ATTENDANCE && date != null && session != null) {
            for (status in listOf(AttendanceStatus.PRESENT, AttendanceStatus.ABSENT)) {
                val pi = if (quickActionsQueue) {
                    PendingIntent.getBroadcast(
                        context, requestCode(alarm.key, status),
                        Intent(context, NotificationActionReceiver::class.java)
                            .setAction(NotificationActionReceiver.ACTION_MARK)
                            .putExtra(NavTargets.EXTRA_DATE, date.toString())
                            .putExtra(NavTargets.EXTRA_SESSION, session.name)
                            .putExtra(NavTargets.EXTRA_STATUS, status.name)
                            .putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, id),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                } else {
                    activityIntent(context, requestCode(alarm.key, status), quickMarkIntent(context, alarm, status))
                }
                builder.addAction(0, status.label, pi)
            }
        }
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    fun postTest(context: Context): Boolean {
        if (!canPost(context)) return false
        val n = baseBuilder(context, CH_SUMMARY)
            .setContentTitle("Notifications work")
            .setContentText("${Brand.NAME} can show reminders on this phone.")
            .setPublicVersion(publicVersion(context, CH_SUMMARY))
            .build()
        NotificationManagerCompat.from(context).notify(1, n)
        return true
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    private fun baseBuilder(context: Context, channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)

    private fun publicVersion(context: Context, channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("${Brand.NAME}")
        .setContentText("You have a reminder")
        .build()

    private fun openIntent(context: Context, alarm: PlannedAlarm): Intent {
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        when (alarm.type) {
            AlarmType.ATTENDANCE -> i.putExtra(NavTargets.EXTRA_NAV, NavTargets.QUICK_MARK)
                .putExtra(NavTargets.EXTRA_DATE, alarm.date?.toString())
                .putExtra(NavTargets.EXTRA_SESSION, alarm.session?.name)
            AlarmType.EVENT -> i.putExtra(NavTargets.EXTRA_NAV, NavTargets.EVENT).putExtra(NavTargets.EXTRA_REF, alarm.refId)
            AlarmType.MOOD -> i.putExtra(NavTargets.EXTRA_NAV, NavTargets.MOOD)
            AlarmType.RULES -> i.putExtra(NavTargets.EXTRA_NAV, NavTargets.START_JOB).putExtra(NavTargets.EXTRA_REF, alarm.refId)
            AlarmType.SUMMARY -> i.putExtra(NavTargets.EXTRA_NAV, NavTargets.HOME)
        }
        return i
    }

    private fun quickMarkIntent(context: Context, alarm: PlannedAlarm, status: AttendanceStatus) =
        openIntent(context, alarm).putExtra(NavTargets.EXTRA_STATUS, status.name)

    private fun activityIntent(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun requestCode(key: String, status: AttendanceStatus) = (key + status.name).hashCode()
}
