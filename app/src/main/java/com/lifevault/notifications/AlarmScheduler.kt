package com.lifevault.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.lifevault.domain.notify.AlarmPlan
import com.lifevault.domain.notify.PlannedAlarm
import com.lifevault.domain.util.VaultJson
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/** Persists the alarm plan (times, types and opaque ids only) in app-private storage. */
class AlarmPlanStore(private val file: File) {
    @Synchronized
    fun load(): AlarmPlan = try {
        if (file.isFile) VaultJson.decodeFromString(AlarmPlan.serializer(), file.readText()) else AlarmPlan()
    } catch (e: Exception) {
        AlarmPlan()
    }

    @Synchronized
    fun save(plan: AlarmPlan) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(VaultJson.encodeToString(AlarmPlan.serializer(), plan))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /**
     * Removes and returns alarms that are due at [now]. Alarms older than [staleAfterMinutes]
     * (e.g. the phone was off) are dropped silently instead of firing late.
     */
    @Synchronized
    fun takeDue(now: LocalDateTime, staleAfterMinutes: Long = 360): List<PlannedAlarm> {
        val plan = load()
        val dueCutoff = now.plusSeconds(30)
        val staleCutoff = now.minusMinutes(staleAfterMinutes)
        val (due, rest) = plan.alarms.partition { !it.at.isAfter(dueCutoff) }
        if (due.isNotEmpty()) save(plan.copy(alarms = rest))
        return due.filter { it.at.isAfter(staleCutoff) }
    }

    fun next(now: LocalDateTime): PlannedAlarm? = load().alarms.filter { it.at.isAfter(now) }.minByOrNull { it.at }
}

/**
 * Keeps exactly one AlarmManager alarm registered: the next planned notification. When it fires,
 * [AlarmReceiver] posts everything due and schedules the following one. Plans are stored as local
 * date-times and converted with the current zone every time, so time-zone changes are handled.
 */
class AlarmScheduler(private val context: Context) {
    val store = AlarmPlanStore(File(context.noBackupFilesDir, "alarm-plan.json"))
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun setPlan(plan: AlarmPlan) {
        store.save(plan)
        scheduleNext()
    }

    fun clear() {
        store.save(AlarmPlan())
        alarms.cancel(pendingIntent())
    }

    /** Exact alarms need user consent on Android 12+ unless USE_EXACT_ALARM applies (13+). */
    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    fun scheduleNext() {
        val next = store.next(LocalDateTime.now())
        val pi = pendingIntent()
        if (next == null) {
            alarms.cancel(pi)
            return
        }
        val trigger = next.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (canScheduleExact()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        } else {
            // Graceful fallback: may be delayed by a few minutes by Doze.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        }
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
