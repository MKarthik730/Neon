package com.lifevault.domain.mood

import com.lifevault.domain.model.MoodCheckIn
import com.lifevault.domain.model.MoodSettings
import com.lifevault.domain.model.MoodSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class MoodStatsTest {
    private val today = LocalDate.of(2026, 9, 28)
    private var n = 0
    private fun m(daysAgo: Long, level: Int, vararg tags: String, slot: MoodSlot = MoodSlot.DAY) =
        MoodCheckIn("m${n++}", today.minusDays(daysAgo), slot, level, tags.toList())

    @Test
    fun `empty period`() {
        val r = MoodStats.report(emptyList(), today.minusDays(6), today, today)
        assertNull(r.average)
        assertEquals(0, r.checkIns)
        assertEquals(7, r.daysMissed)
        assertEquals(0, r.currentStreak)
        assertNull(r.bestDay)
        assertEquals(TrendDirection.FLAT, r.trend)
        assertTrue(r.tagInsights.isEmpty())
        assertEquals((1..5).associateWith { 0 }, r.distribution)
    }

    @Test
    fun `single entry`() {
        val r = MoodStats.report(listOf(m(0, 4, "sleep")), today.minusDays(6), today, today)
        assertEquals(4.0, r.average!!, 1e-9)
        assertEquals(1, r.currentStreak)
        assertEquals(6, r.daysMissed)
        assertEquals(r.bestDay, r.worstDay)
        assertEquals(TrendDirection.FLAT, r.trend)
        val tag = r.tagInsights.single()
        assertEquals(4.0, tag.averageWith, 1e-9)
        assertNull(tag.averageWithout)
    }

    @Test
    fun `averages use daily means so two check-ins a day are not double weighted`() {
        val list = listOf(m(1, 1, slot = MoodSlot.MORNING), m(1, 3, slot = MoodSlot.EVENING), m(0, 5))
        val r = MoodStats.report(list, today.minusDays(1), today, today)
        assertEquals((2.0 + 5.0) / 2, r.average!!, 1e-9)
        assertEquals(3, r.checkIns)
        assertEquals(1, r.distribution[1])
        assertEquals(1, r.distribution[3])
        assertEquals(1, r.distribution[5])
    }

    @Test
    fun `best worst trend streak and missed days`() {
        val list = listOf(m(6, 1), m(5, 2), m(3, 3), m(2, 4), m(1, 5))
        val r = MoodStats.report(list, today.minusDays(6), today, today)
        assertEquals(today.minusDays(1), r.bestDay!!.date)
        assertEquals(today.minusDays(6), r.worstDay!!.date)
        assertEquals(TrendDirection.UP, r.trend)
        assertEquals(3, r.currentStreak) // yesterday, 2 and 3 days ago; today not done yet
        assertEquals(2, r.daysMissed) // 4 days ago and today
    }

    @Test
    fun `future part of the period is not counted as missed`() {
        val r = MoodStats.report(listOf(m(0, 3)), today, today.plusDays(6), today)
        assertEquals(0, r.daysMissed)
    }

    @Test
    fun `tag insights compare with and without`() {
        val list = listOf(m(3, 2, "sleep"), m(2, 2, "Sleep", "study"), m(1, 4, "study"), m(0, 5))
        val r = MoodStats.report(list, today.minusDays(6), today, today)
        val sleep = r.tagInsights.first { it.tag == "sleep" }
        assertEquals(2, sleep.checkIns)
        assertEquals(2.0, sleep.averageWith, 1e-9)
        assertEquals(4.5, sleep.averageWithout!!, 1e-9)
        val study = r.tagInsights.first { it.tag == "study" }
        assertEquals(3.0, study.averageWith, 1e-9)
    }

    @Test
    fun `weekly and monthly averages`() {
        val list = listOf(m(0, 4), m(7, 2))
        val weeks = MoodStats.weekly(list, today.minusDays(13), today)
        assertEquals(3, weeks.size) // 2026-09-14, 21, 28 weeks
        assertEquals(4.0, weeks.last().average!!, 1e-9)
        assertNull(weeks.first().average)
        val months = MoodStats.monthly(list, YearMonth.of(2026, 8), YearMonth.of(2026, 9))
        assertNull(months[0].average)
        assertEquals(3.0, months[1].average!!, 1e-9)
    }

    @Test
    fun `gentle message after a run of low check-ins`() {
        val s = MoodSettings(lowLevel = 2, lowRunLength = 3)
        val run = listOf(m(3, 4), m(2, 2), m(1, 1), m(0, 2))
        assertEquals(3, MoodStats.lowRun(run, 2))
        assertTrue(MoodStats.shouldShowGentleMessage(run, s))
        assertFalse(MoodStats.shouldShowGentleMessage(run, s.copy(gentleMessageEnabled = false)))
        assertFalse(MoodStats.shouldShowGentleMessage(run + m(-1, 4), s))
        assertFalse(MoodStats.shouldShowGentleMessage(emptyList(), s))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `levels outside 1 to 5 are rejected`() {
        m(0, 6)
    }
}
