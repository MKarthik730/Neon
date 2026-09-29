package com.lifevault

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.lifevault.domain.model.AttendanceStatus
import com.lifevault.domain.model.Session
import com.lifevault.domain.notify.AlarmPlan
import com.lifevault.domain.notify.AlarmType
import com.lifevault.domain.notify.PlannedAlarm
import com.lifevault.notifications.AlarmReceiver
import com.lifevault.notifications.AlarmScheduler
import com.lifevault.notifications.BootReceiver
import com.lifevault.notifications.NavTargets
import com.lifevault.notifications.NotificationActionReceiver
import com.lifevault.notifications.Notifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class NotificationsInstrumentedTest {

    @get:Rule
    val notifications: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val app get() = ctx as LifeVaultApp
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)

    @After
    fun cleanUp() {
        AlarmScheduler(ctx).clear()
        app.container.pendingQueue.clear()
        nm.cancelAll()
    }

    private fun registeredAlarm(): PendingIntent? = PendingIntent.getBroadcast(
        ctx, 0,
        Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )

    @Test
    fun nextAlarmIsRegisteredAndRebuiltAfterReboot() {
        val scheduler = AlarmScheduler(ctx)
        scheduler.setPlan(AlarmPlan(alarms = listOf(PlannedAlarm("mood:t", AlarmType.MOOD, LocalDateTime.now().plusHours(2)))))
        val pi = registeredAlarm()
        assertNotNull("alarm must be registered", pi)

        // A reboot drops every alarm.
        ctx.getSystemService(android.app.AlarmManager::class.java).cancel(pi!!)
        pi.cancel()
        assertNull(registeredAlarm())

        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNotNull("boot receiver must re-register the alarm from the stored plan", registeredAlarm())

        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        assertNotNull(registeredAlarm())
    }

    @Test
    fun emptyPlanRegistersNothing() {
        AlarmScheduler(ctx).setPlan(AlarmPlan())
        val pi = registeredAlarm()
        // cancel() on AlarmManager leaves the PendingIntent object; what matters is there is nothing due.
        assertNull(AlarmScheduler(ctx).store.next(LocalDateTime.now()))
        pi?.cancel()
    }

    @Test
    fun dueAttendanceAlarmPostsAPrivateGenericNotificationWithActions() {
        val scheduler = AlarmScheduler(ctx)
        val key = "att:${LocalDate.now()}:MORNING"
        scheduler.store.save(AlarmPlan(alarms = listOf(PlannedAlarm(key, AlarmType.ATTENDANCE, LocalDateTime.now().minusSeconds(5), LocalDate.now(), Session.MORNING))))

        AlarmReceiver().onReceive(ctx, Intent(AlarmReceiver.ACTION_FIRE))

        val posted = waitFor { nm.activeNotifications.firstOrNull { it.id == Notifier.idFor(key) } }
        assertNotNull("attendance notification must be posted", posted)
        val n = posted!!.notification
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals(Brand.NAME, n.publicVersion!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Mark Morning attendance", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(listOf("Present", "Absent"), n.actions.map { it.title.toString() })
        // The alarm was consumed: it will not fire twice.
        assertTrue(scheduler.store.load().alarms.none { it.key == key })
    }

    @Test
    fun presentActionWritesOnlyDateSessionStatusToTheQueue() {
        val q = app.container.pendingQueue
        q.clear()
        NotificationActionReceiver().onReceive(ctx, markIntent("2026-09-28", "EVENING", "PRESENT"))
        val actions = q.peek()
        assertEquals(1, actions.size)
        assertEquals(LocalDate.of(2026, 9, 28), actions[0].date)
        assertEquals(Session.EVENING, actions[0].session)
        assertEquals(AttendanceStatus.PRESENT, actions[0].status)

        val raw = java.io.File(ctx.noBackupFilesDir, "pending-actions.json").readText()
        for (field in listOf("subject", "note", "mood", "level", "amount")) assertFalse(raw.contains(field))
    }

    @Test
    fun malformedOrUnexpectedActionsAreIgnored() {
        val q = app.container.pendingQueue
        q.clear()
        NotificationActionReceiver().onReceive(ctx, markIntent("not-a-date", "EVENING", "PRESENT"))
        NotificationActionReceiver().onReceive(ctx, markIntent("2026-09-28", "NIGHT", "PRESENT"))
        NotificationActionReceiver().onReceive(ctx, markIntent("2026-09-28", "MORNING", "CANCELLED"))
        NotificationActionReceiver().onReceive(ctx, Intent("some.other.action"))
        assertTrue(q.peek().isEmpty())
    }

    private fun markIntent(date: String, session: String, status: String) =
        Intent(ctx, NotificationActionReceiver::class.java)
            .setAction(NotificationActionReceiver.ACTION_MARK)
            .putExtra(NavTargets.EXTRA_DATE, date)
            .putExtra(NavTargets.EXTRA_SESSION, session)
            .putExtra(NavTargets.EXTRA_STATUS, status)

    private fun <T> waitFor(timeoutMs: Long = 3_000, block: () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            block()?.let { return it }
            Thread.sleep(100)
        }
        return block()
    }
}
