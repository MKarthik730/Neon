package com.lifevault.domain

import com.lifevault.domain.model.AttendanceRecord
import com.lifevault.domain.model.AttendanceStatus
import com.lifevault.domain.model.Holiday
import com.lifevault.domain.model.HolidaysFile
import com.lifevault.domain.model.Session
import com.lifevault.domain.model.TimetableFile
import com.lifevault.domain.model.TimetableSlot
import com.lifevault.domain.model.TimetableVersion
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

object Fixtures {
    /** Monday 2026-09-07. */
    val MON: LocalDate = LocalDate.of(2026, 9, 7)

    fun slot(day: DayOfWeek, session: Session, vararg subjects: String) = TimetableSlot(
        day, session,
        if (session == Session.MORNING) LocalTime.of(9, 0) else LocalTime.of(14, 0),
        if (session == Session.MORNING) LocalTime.of(12, 0) else LocalTime.of(17, 0),
        subjects.toList(),
    )

    /** Mon-Fri, morning (Maths) and evening (Physics). */
    fun weekdayTimetable(from: LocalDate = MON, to: LocalDate? = null) = TimetableFile(
        versions = listOf(
            TimetableVersion(
                id = "v1", name = "Sem 1", effectiveFrom = from, effectiveTo = to,
                slots = (DayOfWeek.MONDAY..DayOfWeek.FRIDAY).flatMap {
                    listOf(slot(it, Session.MORNING, "Maths"), slot(it, Session.EVENING, "Physics"))
                },
            ),
        ),
    )

    fun holidays(vararg h: Holiday, off: List<DayOfWeek> = listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) =
        HolidaysFile(holidays = h.toList(), weeklyOffDays = off)

    fun rec(date: LocalDate, session: Session, status: AttendanceStatus, vararg subjects: String) =
        AttendanceRecord(date, session, status, subjects.toList())

    private operator fun DayOfWeek.rangeTo(o: DayOfWeek) = DayOfWeek.entries.filter { it.value in value..o.value }
}
