package com.lifevault.domain.attendance

import com.lifevault.domain.model.Holiday
import com.lifevault.domain.model.HolidaysFile
import com.lifevault.domain.model.Session
import com.lifevault.domain.model.TimetableFile
import java.time.LocalDate
import java.time.LocalTime

/** A concrete session on a concrete date, produced by expanding the weekly timetable. */
data class ScheduledSession(
    val date: LocalDate,
    val session: Session,
    val start: LocalTime,
    val end: LocalTime,
    val subjects: List<String>,
)

/** What kind of day a date is, for display and for skipping notifications. */
sealed interface DayKind {
    data class HolidayDay(val holiday: Holiday) : DayKind
    data object OffDay : DayKind
    data object NoTimetable : DayKind
    data object NoSessions : DayKind
    data class Working(val sessions: List<ScheduledSession>) : DayKind
}

/**
 * Expands timetable versions + holidays into concrete sessions.
 * Holidays and weekly off-days remove both sessions of that day.
 */
class Schedule(
    private val timetable: TimetableFile,
    private val holidays: HolidaysFile,
) {
    fun dayKind(date: LocalDate): DayKind {
        holidays.holidayOn(date)?.let { return DayKind.HolidayDay(it) }
        if (holidays.isOffDay(date)) return DayKind.OffDay
        val version = timetable.versionOn(date) ?: return DayKind.NoTimetable
        val sessions = Session.entries.mapNotNull { s ->
            version.slot(date.dayOfWeek, s)?.let { ScheduledSession(date, s, it.start, it.end, it.subjects) }
        }
        return if (sessions.isEmpty()) DayKind.NoSessions else DayKind.Working(sessions)
    }

    fun sessionsOn(date: LocalDate): List<ScheduledSession> =
        (dayKind(date) as? DayKind.Working)?.sessions ?: emptyList()

    fun session(date: LocalDate, session: Session): ScheduledSession? =
        sessionsOn(date).firstOrNull { it.session == session }

    /** All scheduled sessions from [from] to [to] inclusive, in chronological order. */
    fun expand(from: LocalDate, to: LocalDate): List<ScheduledSession> {
        if (to.isBefore(from)) return emptyList()
        val out = ArrayList<ScheduledSession>()
        var d = from
        while (!d.isAfter(to)) {
            out += sessionsOn(d)
            d = d.plusDays(1)
        }
        return out
    }

    /** First date attendance can exist for, or null when there is no timetable. */
    fun firstDate(): LocalDate? = timetable.earliestStart()
}
