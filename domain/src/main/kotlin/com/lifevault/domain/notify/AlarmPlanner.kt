package com.lifevault.domain.notify

import com.lifevault.domain.attendance.Schedule
import com.lifevault.domain.model.Event
import com.lifevault.domain.model.HolidaysFile
import com.lifevault.domain.model.RulesFile
import com.lifevault.domain.model.Session
import com.lifevault.domain.model.Settings
import com.lifevault.domain.model.TimetableFile
import com.lifevault.domain.serial.LocalDateSerializer
import com.lifevault.domain.serial.LocalDateTimeSerializer
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime

@Serializable
enum class AlarmType { ATTENDANCE, EVENT, MOOD, RULES, SUMMARY }

/**
 * One future notification. This is stored OUTSIDE the vault (app-private storage) so alarms can be rebuilt
 * after a reboot while the vault is locked. It therefore carries only times, types and opaque ids: never
 * subject names, event titles, amounts, notes, or anything about mood beyond "a check-in reminder is due".
 */
@Serializable
data class PlannedAlarm(
    /** Stable unique key, also used to derive the notification id. */
    val key: String,
    val type: AlarmType,
    @Serializable(with = LocalDateTimeSerializer::class) val at: LocalDateTime,
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate? = null,
    val session: Session? = null,
    /** Opaque id: event id for EVENT, rule set id for RULES. */
    val refId: String? = null,
)

@Serializable
data class AlarmPlan(
    val version: Int = 1,
    val alarms: List<PlannedAlarm> = emptyList(),
)

object AlarmPlanner {
    const val DEFAULT_HORIZON_DAYS = 30

    fun plan(
        now: LocalDateTime,
        timetable: TimetableFile,
        holidays: HolidaysFile,
        settings: Settings,
        events: List<Event>,
        rules: RulesFile,
        horizonDays: Int = DEFAULT_HORIZON_DAYS,
    ): AlarmPlan {
        val out = ArrayList<PlannedAlarm>()
        val schedule = Schedule(timetable, holidays)
        val today = now.toLocalDate()
        val lastDay = today.plusDays(horizonDays.toLong())
        val activeRuleSets = rules.sets.filterNot { it.archived }.map { it.id }.toSet()

        var day = today
        while (!day.isAfter(lastDay)) {
            if (settings.attendance.promptsEnabled) {
                for (s in schedule.sessionsOn(day)) {
                    val at = day.atTime(s.end).plusMinutes(settings.attendance.promptDelayMinutes.toLong())
                    out += PlannedAlarm("att:$day:${s.session}", AlarmType.ATTENDANCE, at, date = day, session = s.session)
                }
            }
            val mood = settings.mood
            if (mood.reminderEnabled && !(mood.reminderSkipsHolidays && holidays.isNonWorking(day))) {
                out += PlannedAlarm("mood:$day", AlarmType.MOOD, day.atTime(mood.reminderTime), date = day)
            }
            if (settings.notifications.dailySummaryEnabled) {
                out += PlannedAlarm("sum:$day", AlarmType.SUMMARY, day.atTime(settings.notifications.dailySummaryTime), date = day)
            }
            for (r in rules.reminders) {
                if (r.enabled && r.ruleSetId in activeRuleSets && day.dayOfWeek in r.days) {
                    out += PlannedAlarm("rulerem:${r.id}:$day", AlarmType.RULES, day.atTime(r.time), date = day, refId = r.ruleSetId)
                }
            }
            day = day.plusDays(1)
        }

        for (e in events) {
            val start = e.startsAt()
            if (start.toLocalDate().isAfter(lastDay)) continue
            val minutes = e.reminderMinutesBefore
            if (settings.notifications.eventRemindersEnabled && minutes != null) {
                out += PlannedAlarm("ev:${e.id}", AlarmType.EVENT, start.minusMinutes(minutes.toLong()), date = e.date, refId = e.id)
            }
            val set = e.ruleSetId
            if (settings.rules.promptBeforeLinkedEvents && set != null && set in activeRuleSets) {
                out += PlannedAlarm("rules-ev:${e.id}", AlarmType.RULES, start.minusMinutes(e.rulesPromptMinutesBefore.toLong()), date = e.date, refId = set)
            }
        }

        return AlarmPlan(alarms = out.filter { it.at.isAfter(now) }.sortedBy { it.at })
    }
}
