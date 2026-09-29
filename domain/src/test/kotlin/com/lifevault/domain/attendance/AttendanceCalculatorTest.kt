package com.lifevault.domain.attendance

import com.lifevault.domain.Fixtures
import com.lifevault.domain.Fixtures.MON
import com.lifevault.domain.Fixtures.rec
import com.lifevault.domain.model.AttendanceStatus.ABSENT
import com.lifevault.domain.model.AttendanceStatus.CANCELLED
import com.lifevault.domain.model.AttendanceStatus.PRESENT
import com.lifevault.domain.model.CountingMode
import com.lifevault.domain.model.Holiday
import com.lifevault.domain.model.Session.EVENING
import com.lifevault.domain.model.Session.MORNING
import com.lifevault.domain.model.TimetableFile
import com.lifevault.domain.model.TimetableVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class AttendanceCalculatorTest {

    private val schedule = Schedule(Fixtures.weekdayTimetable(), Fixtures.holidays())

    @Test
    fun `unmarked sessions are pending and never counted as absent`() {
        val r = AttendanceCalculator.compute(schedule, emptyList(), MON, MON.plusDays(4), CountingMode.PER_SESSION)
        assertEquals(10, r.overall.pending)
        assertEquals(0, r.overall.absent)
        assertEquals(10, r.overall.conducted)
        assertNull("0 marked must not be shown as 0%", r.overall.percentage)
        assertEquals(10, r.pending.size)
    }

    @Test
    fun `percentage is present over present plus absent`() {
        val records = listOf(
            rec(MON, MORNING, PRESENT), rec(MON, EVENING, PRESENT),
            rec(MON.plusDays(1), MORNING, PRESENT), rec(MON.plusDays(1), EVENING, ABSENT),
        )
        val r = AttendanceCalculator.compute(schedule, records, MON, MON.plusDays(1), CountingMode.PER_SESSION)
        assertEquals(3, r.overall.present)
        assertEquals(1, r.overall.absent)
        assertEquals(75.0, r.overall.percentage!!, 1e-9)
        assertFalse(r.overall.isBelow(75))
        assertTrue(r.overall.isBelow(76))
    }

    @Test
    fun `holidays remove both sessions even if a record exists`() {
        val holiday = Holiday("h", MON.plusDays(1), MON.plusDays(1), "Festival")
        val s = Schedule(Fixtures.weekdayTimetable(), Fixtures.holidays(holiday))
        val records = listOf(rec(MON.plusDays(1), MORNING, ABSENT), rec(MON, MORNING, PRESENT))
        val r = AttendanceCalculator.compute(s, records, MON, MON.plusDays(1), CountingMode.PER_SESSION)
        assertEquals(1, r.overall.present)
        assertEquals(0, r.overall.absent)
        assertEquals(1, r.overall.pending) // Monday evening
        assertEquals(2, r.overall.conducted)
    }

    @Test
    fun `holiday ranges cover every day inclusive`() {
        val holiday = Holiday("h", MON, MON.plusDays(2), "Break")
        val s = Schedule(Fixtures.weekdayTimetable(), Fixtures.holidays(holiday))
        assertEquals(0, s.expand(MON, MON.plusDays(2)).size)
        assertEquals(2, s.expand(MON.plusDays(3), MON.plusDays(3)).size)
    }

    @Test
    fun `cancelled sessions are excluded from totals`() {
        val records = listOf(rec(MON, MORNING, CANCELLED), rec(MON, EVENING, PRESENT))
        val r = AttendanceCalculator.compute(schedule, records, MON, MON, CountingMode.PER_SESSION)
        assertEquals(1, r.overall.cancelled)
        assertEquals(1, r.overall.conducted)
        assertEquals(100.0, r.overall.percentage!!, 1e-9)
    }

    @Test
    fun `weekend off days have no sessions`() {
        val sat = MON.plusDays(5)
        assertTrue(schedule.dayKind(sat) is DayKind.OffDay)
        assertEquals(10, schedule.expand(MON, MON.plusDays(6)).size)
    }

    @Test
    fun `nothing counted after today`() {
        val r = AttendanceCalculator.compute(schedule, emptyList(), MON, MON, CountingMode.PER_SESSION)
        assertEquals(2, r.overall.conducted)
    }

    @Test
    fun `range before timetable start is empty`() {
        val r = AttendanceCalculator.compute(schedule, emptyList(), MON.minusDays(7), MON.minusDays(1), CountingMode.PER_SESSION)
        assertEquals(0, r.overall.conducted)
        assertNull(r.overall.percentage)
    }

    @Test
    fun `per subject counts each subject of a session`() {
        val tt = TimetableFile(
            versions = listOf(
                TimetableVersion("v", "S", MON, null, listOf(Fixtures.slot(MON.dayOfWeek, MORNING, "Maths", "Physics"))),
            ),
        )
        val s = Schedule(tt, Fixtures.holidays(off = emptyList()))
        val records = listOf(rec(MON, MORNING, PRESENT, "Maths", "Physics"), rec(MON.plusDays(7), MORNING, ABSENT))
        val perSubject = AttendanceCalculator.compute(s, records, MON, MON.plusDays(7), CountingMode.PER_SUBJECT)
        assertEquals(2, perSubject.overall.present)
        assertEquals(2, perSubject.overall.absent)
        assertEquals(1, perSubject.bySubject.getValue("Maths").present)
        assertEquals(1, perSubject.bySubject.getValue("Physics").absent)
        val perSession = AttendanceCalculator.compute(s, records, MON, MON.plusDays(7), CountingMode.PER_SESSION)
        assertEquals(1, perSession.overall.present)
        assertEquals(1, perSession.overall.absent)
    }

    @Test
    fun `record subjects win over current timetable subjects`() {
        val records = listOf(rec(MON, MORNING, PRESENT, "Guest lecture"))
        val r = AttendanceCalculator.compute(schedule, records, MON, MON, CountingMode.PER_SUBJECT)
        assertEquals(1, r.bySubject.getValue("Guest lecture").present)
        assertEquals(1, r.bySubject.getValue("Physics").pending)
    }

    @Test
    fun `per month buckets`() {
        val lastSep = YearMonth.of(2026, 9).atEndOfMonth() // Wed 30 Sep
        val records = listOf(rec(lastSep, MORNING, PRESENT), rec(lastSep.plusDays(1), MORNING, ABSENT))
        val r = AttendanceCalculator.compute(schedule, records, lastSep, lastSep.plusDays(1), CountingMode.PER_SESSION)
        assertEquals(1, r.byMonth.getValue(YearMonth.of(2026, 9)).present)
        assertEquals(1, r.byMonth.getValue(YearMonth.of(2026, 10)).absent)
    }

    @Test
    fun `new timetable version does not rewrite the past`() {
        val oldV = TimetableVersion("old", "Sem 1", MON, MON.plusDays(4), Fixtures.weekdayTimetable().versions[0].slots)
        val newV = TimetableVersion(
            "new", "Sem 2", MON.plusDays(7), null,
            listOf(Fixtures.slot(MON.dayOfWeek, MORNING, "Biology")),
        )
        val s = Schedule(TimetableFile(versions = listOf(oldV, newV)), Fixtures.holidays())
        assertEquals(listOf("Maths"), s.session(MON, MORNING)!!.subjects)
        assertEquals(listOf("Biology"), s.session(MON.plusDays(7), MORNING)!!.subjects)
        assertNull(s.session(MON.plusDays(7), EVENING))
    }

    @Test
    fun `overlapping versions pick the most recent start`() {
        val a = TimetableVersion("a", "A", MON, null, listOf(Fixtures.slot(MON.dayOfWeek, MORNING, "A")))
        val b = TimetableVersion("b", "B", MON.plusDays(7), null, listOf(Fixtures.slot(MON.dayOfWeek, MORNING, "B")))
        val tt = TimetableFile(versions = listOf(b, a))
        assertEquals("a", tt.versionOn(MON)!!.id)
        assertEquals("b", tt.versionOn(MON.plusDays(7))!!.id)
    }

    @Test
    fun `same day versions - the one added last wins`() {
        val first = TimetableVersion("first", "A", MON, null, listOf(Fixtures.slot(MON.dayOfWeek, MORNING, "Old")))
        val second = TimetableVersion("second", "B", MON, null, listOf(Fixtures.slot(MON.dayOfWeek, MORNING, "New")))
        assertEquals("second", TimetableFile(versions = listOf(first, second)).versionOn(MON)!!.id)
    }
}
