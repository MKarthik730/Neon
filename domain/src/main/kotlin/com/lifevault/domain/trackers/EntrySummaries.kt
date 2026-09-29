package com.lifevault.domain.trackers

import com.lifevault.domain.model.Entry
import com.lifevault.domain.model.EntryType
import com.lifevault.domain.util.Streaks
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

enum class Period { DAY, WEEK, MONTH }

data class PeriodTotal(
    val start: LocalDate,
    val total: Double,
    val count: Int,
    val byCategory: Map<String, Double>,
    val cost: Double = 0.0,
)

/** Per-type summaries for the shared [Entry] model. Amounts are summed as-is (one currency/unit per tracker). */
object EntrySummaries {

    fun ofType(entries: List<Entry>, type: EntryType) = entries.filter { it.type == type }

    fun inRange(entries: List<Entry>, from: LocalDate, to: LocalDate) =
        entries.filter { val d = it.at.toLocalDate(); !d.isBefore(from) && !d.isAfter(to) }

    fun periodStart(date: LocalDate, period: Period): LocalDate = when (period) {
        Period.DAY -> date
        Period.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        Period.MONTH -> YearMonth.from(date).atDay(1)
    }

    /** Totals per day/week/month, newest first. Only periods that have entries are returned. */
    fun byPeriod(entries: List<Entry>, period: Period): List<PeriodTotal> =
        entries.groupBy { periodStart(it.at.toLocalDate(), period) }
            .map { (start, list) -> total(start, list) }
            .sortedByDescending { it.start }

    fun total(start: LocalDate, list: List<Entry>) = PeriodTotal(
        start = start,
        total = list.sumOf { it.amount },
        count = list.size,
        byCategory = byCategory(list),
        cost = list.sumOf { it.cost ?: 0.0 },
    )

    fun byCategory(list: List<Entry>): Map<String, Double> =
        list.groupBy { it.category.ifBlank { "Uncategorised" } }
            .mapValues { (_, l) -> l.sumOf { it.amount } }
            .entries.sortedByDescending { it.value }
            .associate { it.key to it.value }

    /** Money spent in the Monday-based week containing [today]. */
    fun spendThisWeek(entries: List<Entry>, today: LocalDate): Double {
        val start = periodStart(today, Period.WEEK)
        return inRange(ofType(entries, EntryType.MONEY), start, start.plusDays(6)).sumOf { it.amount }
    }

    /** Consecutive days with at least one food entry. */
    fun foodStreak(entries: List<Entry>, today: LocalDate): Int =
        Streaks.current(ofType(entries, EntryType.FOOD).map { it.at.toLocalDate() }.toSet(), today)

    /** Food entries per day in the range, including days with zero. */
    fun foodPerDay(entries: List<Entry>, from: LocalDate, to: LocalDate): List<Pair<LocalDate, Int>> {
        val counts = ofType(entries, EntryType.FOOD).groupingBy { it.at.toLocalDate() }.eachCount()
        val out = ArrayList<Pair<LocalDate, Int>>()
        var d = from
        while (!d.isAfter(to)) { out += d to (counts[d] ?: 0); d = d.plusDays(1) }
        return out
    }

    data class TravelTotals(val distance: Double, val cost: Double, val trips: Int)

    fun travelTotals(entries: List<Entry>, from: LocalDate, to: LocalDate): TravelTotals {
        val list = inRange(ofType(entries, EntryType.TRAVEL), from, to)
        return TravelTotals(list.sumOf { it.amount }, list.sumOf { it.cost ?: 0.0 }, list.size)
    }

    /** Daily totals for a chart, including zero days. */
    fun dailySeries(entries: List<Entry>, from: LocalDate, to: LocalDate): List<Pair<LocalDate, Double>> {
        val sums = entries.groupBy { it.at.toLocalDate() }.mapValues { (_, l) -> l.sumOf { it.amount } }
        val out = ArrayList<Pair<LocalDate, Double>>()
        var d = from
        while (!d.isAfter(to)) { out += d to (sums[d] ?: 0.0); d = d.plusDays(1) }
        return out
    }
}
