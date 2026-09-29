package com.lifevault.data

import com.lifevault.domain.attendance.AttendanceCalculator
import com.lifevault.domain.attendance.AttendanceReport
import com.lifevault.domain.attendance.Schedule
import com.lifevault.domain.model.AttendanceRecord
import com.lifevault.domain.model.HolidaysFile
import com.lifevault.domain.model.Session
import com.lifevault.domain.model.Settings
import com.lifevault.domain.model.TimetableFile
import com.lifevault.vault.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import java.time.LocalDate

/** Everything the attendance screens need, recomputed whenever the underlying files change. */
data class AttendanceSnapshot(
    val today: LocalDate,
    val settings: Settings,
    val timetable: TimetableFile,
    val holidays: HolidaysFile,
    val schedule: Schedule,
    /** Null when no timetable exists yet. */
    val report: AttendanceReport?,
    val records: Map<Pair<LocalDate, Session>, AttendanceRecord>,
) {
    fun record(date: LocalDate, session: Session) = records[date to session]
}

private data class AttendanceInputs(val settings: Settings, val timetable: TimetableFile, val holidays: HolidaysFile)

@OptIn(ExperimentalCoroutinesApi::class)
fun VaultSession.attendanceSnapshots(): Flow<AttendanceSnapshot> =
    combine(settings.flow, timetable.flow, holidays.flow, attendance.revision) { s, t, h, _ -> AttendanceInputs(s, t, h) }
        .mapLatest { (s, t, h) -> loadAttendance(this, s, t, h) }
        .flowOn(Dispatchers.Default)

suspend fun loadAttendance(
    session: VaultSession,
    settings: Settings,
    timetable: TimetableFile,
    holidays: HolidaysFile,
    today: LocalDate = LocalDate.now(),
): AttendanceSnapshot {
    val schedule = Schedule(timetable, holidays)
    val from = schedule.firstDate()
    val records = if (from == null) {
        // Still show marks for the current month (e.g. merged from notifications) even without a timetable.
        session.attendance.between(today.withDayOfMonth(1), today)
    } else {
        session.attendance.between(minOf(from, today), today.plusMonths(1))
    }
    val report = from?.let {
        AttendanceCalculator.compute(schedule, records, it, today, settings.attendance.countingMode)
    }
    return AttendanceSnapshot(today, settings, timetable, holidays, schedule, report, records.associateBy { it.date to it.session })
}
