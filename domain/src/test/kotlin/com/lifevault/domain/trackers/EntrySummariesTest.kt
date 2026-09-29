package com.lifevault.domain.trackers

import com.lifevault.domain.model.Entry
import com.lifevault.domain.model.EntryType
import com.lifevault.domain.util.Streaks
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class EntrySummariesTest {
    private val today = LocalDate.of(2026, 9, 30) // Wednesday
    private var n = 0
    private fun e(type: EntryType, daysAgo: Long, amount: Double, category: String = "", cost: Double? = null) =
        Entry("e${n++}", type, today.minusDays(daysAgo).atTime(12, 0), amount, category = category, cost = cost)

    @Test
    fun `spend this week uses monday start`() {
        val list = listOf(e(EntryType.MONEY, 0, 100.0), e(EntryType.MONEY, 2, 50.0), e(EntryType.MONEY, 3, 999.0), e(EntryType.FOOD, 0, 1.0))
        assertEquals(150.0, EntrySummaries.spendThisWeek(list, today), 1e-9)
    }

    @Test
    fun `by period and by category`() {
        val list = listOf(
            e(EntryType.MONEY, 0, 10.0, "Food"), e(EntryType.MONEY, 0, 5.0, "Bus"),
            e(EntryType.MONEY, 1, 20.0, "Food"), e(EntryType.MONEY, 40, 7.0, ""),
        )
        val days = EntrySummaries.byPeriod(list, Period.DAY)
        assertEquals(today, days.first().start)
        assertEquals(15.0, days.first().total, 1e-9)
        assertEquals(mapOf("Food" to 10.0, "Bus" to 5.0), days.first().byCategory)
        val months = EntrySummaries.byPeriod(list, Period.MONTH)
        assertEquals(2, months.size)
        assertEquals(35.0, months.first().total, 1e-9)
        assertEquals(mapOf("Uncategorised" to 7.0), months.last().byCategory)
    }

    @Test
    fun `food streak and per day counts`() {
        val list = listOf(e(EntryType.FOOD, 1, 1.0), e(EntryType.FOOD, 1, 1.0), e(EntryType.FOOD, 2, 1.0), e(EntryType.FOOD, 4, 1.0))
        assertEquals(2, EntrySummaries.foodStreak(list, today))
        val perDay = EntrySummaries.foodPerDay(list, today.minusDays(2), today)
        assertEquals(listOf(1, 2, 0), perDay.map { it.second })
    }

    @Test
    fun `travel totals`() {
        val list = listOf(e(EntryType.TRAVEL, 0, 12.5, cost = 30.0), e(EntryType.TRAVEL, 1, 7.5), e(EntryType.TRAVEL, 20, 100.0))
        val t = EntrySummaries.travelTotals(list, today.minusDays(6), today)
        assertEquals(20.0, t.distance, 1e-9)
        assertEquals(30.0, t.cost, 1e-9)
        assertEquals(2, t.trips)
    }

    @Test
    fun `streak helpers`() {
        val d = setOf(today, today.minusDays(1), today.minusDays(3), today.minusDays(4), today.minusDays(5))
        assertEquals(2, Streaks.current(d, today))
        assertEquals(0, Streaks.current(d, today.plusDays(2)))
        assertEquals(3, Streaks.longest(d))
        assertEquals(0, Streaks.longest(emptySet()))
    }
}
