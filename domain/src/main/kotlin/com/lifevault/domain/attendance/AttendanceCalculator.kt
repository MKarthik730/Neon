package com.lifevault.domain.attendance

import com.lifevault.domain.model.AttendanceRecord
import com.lifevault.domain.model.AttendanceStatus
import com.lifevault.domain.model.CountingMode
import com.lifevault.domain.model.Session
import java.time.LocalDate
import java.time.YearMonth

/**
 * Counts for one bucket (overall, a subject, a month...).
 *  - `conducted` = scheduled - holidays - cancelled, up to today (so it includes pending sessions).
 *  - `percentage` = present / (present + absent). Pending sessions never count as absent.
 */
data class Tally(
    val present: Int = 0,
    val absent: Int = 0,
    val pending: Int = 0,
    val cancelled: Int = 0,
) {
    val marked: Int get() = present + absent
    val conducted: Int get() = present + absent + pending

    /** Null when nothing has been marked yet (0/0 is not 0%). */
    val percentage: Double? get() = if (marked == 0) null else present * 100.0 / marked

    fun isBelow(thresholdPercent: Int): Boolean = percentage?.let { it < thresholdPercent } ?: false

    operator fun plus(o: Tally) = Tally(present + o.present, absent + o.absent, pending + o.pending, cancelled + o.cancelled)

    fun add(status: AttendanceStatus?): Tally = when (status) {
        AttendanceStatus.PRESENT -> copy(present = present + 1)
        AttendanceStatus.ABSENT -> copy(absent = absent + 1)
        AttendanceStatus.CANCELLED -> copy(cancelled = cancelled + 1)
        null -> copy(pending = pending + 1)
    }
}

data class AttendanceReport(
    val mode: CountingMode,
    /** Headline numbers in the configured counting mode. */
    val overall: Tally,
    /** Always per session, regardless of mode. */
    val bySession: Tally,
    val bySubject: Map<String, Tally>,
    /** Per month, in the configured counting mode. */
    val byMonth: Map<YearMonth, Tally>,
    /** Scheduled sessions up to today that have no mark yet, oldest first. */
    val pending: List<ScheduledSession>,
)

object AttendanceCalculator {

    /**
     * Computes attendance for every scheduled, non-holiday session in [from]..[today].
     * Records for dates that are holidays/off-days or have no scheduled session are ignored,
     * so adding a holiday later removes it from the totals without touching stored records.
     */
    fun compute(
        schedule: Schedule,
        records: Collection<AttendanceRecord>,
        from: LocalDate,
        today: LocalDate,
        mode: CountingMode,
    ): AttendanceReport {
        val byKey: Map<Pair<LocalDate, Session>, AttendanceRecord> = records.associateBy { it.date to it.session }
        var bySession = Tally()
        val bySubject = linkedMapOf<String, Tally>()
        val byMonthSession = linkedMapOf<YearMonth, Tally>()
        val byMonthSubject = linkedMapOf<YearMonth, Tally>()
        val pending = ArrayList<ScheduledSession>()

        for (s in schedule.expand(from, today)) {
            val rec = byKey[s.date to s.session]
            val status = rec?.status
            if (status == null) pending += s
            bySession = bySession.add(status)
            val ym = YearMonth.from(s.date)
            byMonthSession[ym] = (byMonthSession[ym] ?: Tally()).add(status)
            val subjects = rec?.subjects?.takeIf { it.isNotEmpty() } ?: s.subjects
            for (subject in subjects.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
                bySubject[subject] = (bySubject[subject] ?: Tally()).add(status)
                byMonthSubject[ym] = (byMonthSubject[ym] ?: Tally()).add(status)
            }
        }

        val overall = when (mode) {
            CountingMode.PER_SESSION -> bySession
            CountingMode.PER_SUBJECT -> bySubject.values.fold(Tally()) { a, b -> a + b }
        }
        val byMonth = when (mode) {
            CountingMode.PER_SESSION -> byMonthSession
            CountingMode.PER_SUBJECT -> byMonthSubject
        }
        return AttendanceReport(
            mode = mode,
            overall = overall,
            bySession = bySession,
            bySubject = bySubject.toSortedMap(String.CASE_INSENSITIVE_ORDER),
            byMonth = byMonth.toSortedMap(),
            pending = pending,
        )
    }
}
