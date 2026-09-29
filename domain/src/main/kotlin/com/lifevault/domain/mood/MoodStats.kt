package com.lifevault.domain.mood

import com.lifevault.domain.model.MoodCheckIn
import com.lifevault.domain.model.MoodSettings
import com.lifevault.domain.util.Streaks
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

enum class TrendDirection { UP, DOWN, FLAT }

/** Plain observation about one tag. Never presented as a cause. */
data class TagInsight(
    val tag: String,
    val checkIns: Int,
    val averageWith: Double,
    /** Average of check-ins in the same period without this tag; null when every check-in has it. */
    val averageWithout: Double?,
)

data class DayMood(val date: LocalDate, val average: Double, val checkIns: Int)

data class MoodReport(
    val from: LocalDate,
    val to: LocalDate,
    val checkIns: Int,
    /** Mean of the daily averages; null when there are no check-ins. */
    val average: Double?,
    /** One point per day with a check-in, oldest first. */
    val days: List<DayMood>,
    /** Least-squares slope of the daily averages, in levels per day. */
    val trendSlope: Double,
    val trend: TrendDirection,
    /** Level (1..5) -> number of check-ins at that level. */
    val distribution: Map<Int, Int>,
    val bestDay: DayMood?,
    val worstDay: DayMood?,
    /** Consecutive days with a check-in, ending today (or yesterday if today is not done yet). */
    val currentStreak: Int,
    /** Days in the period up to today without any check-in. */
    val daysMissed: Int,
    val tagInsights: List<TagInsight>,
)

data class PeriodAverage(val start: LocalDate, val average: Double?, val checkIns: Int)

object MoodStats {

    /** A slope smaller than this (levels/day) is reported as flat. */
    const val FLAT_SLOPE = 0.05

    fun report(all: List<MoodCheckIn>, from: LocalDate, to: LocalDate, today: LocalDate): MoodReport {
        val inPeriod = all.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
        val days = dailyMeans(inPeriod)
        val average = if (days.isEmpty()) null else days.map { it.average }.average()
        val slope = slope(days)
        val trend = when {
            days.size < 2 -> TrendDirection.FLAT
            slope > FLAT_SLOPE -> TrendDirection.UP
            slope < -FLAT_SLOPE -> TrendDirection.DOWN
            else -> TrendDirection.FLAT
        }
        val distribution = (1..5).associateWith { level -> inPeriod.count { it.level == level } }
        // Ties: best/worst picks the most recent day.
        val best = days.sortedWith(compareByDescending<DayMood> { it.average }.thenByDescending { it.date }).firstOrNull()
        val worst = days.sortedWith(compareBy<DayMood> { it.average }.thenByDescending { it.date }).firstOrNull()
        val allDates = all.map { it.date }.toSet()
        val lastCounted = minOf(to, today)
        val daysMissed = if (lastCounted.isBefore(from)) 0 else {
            val total = lastCounted.toEpochDay() - from.toEpochDay() + 1
            val covered = days.count { !it.date.isAfter(lastCounted) }
            (total - covered).toInt()
        }
        return MoodReport(
            from = from,
            to = to,
            checkIns = inPeriod.size,
            average = average,
            days = days,
            trendSlope = slope,
            trend = trend,
            distribution = distribution,
            bestDay = best,
            worstDay = worst,
            currentStreak = Streaks.current(allDates, today),
            daysMissed = daysMissed,
            tagInsights = tagInsights(inPeriod),
        )
    }

    fun dailyMeans(entries: List<MoodCheckIn>): List<DayMood> =
        entries.groupBy { it.date }.map { (d, list) -> DayMood(d, list.map { it.level }.average(), list.size) }.sortedBy { it.date }

    fun tagInsights(entries: List<MoodCheckIn>): List<TagInsight> {
        val tags = entries.flatMap { it.tags }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        return tags.map { tag ->
            val with = entries.filter { e -> e.tags.any { it.trim().equals(tag, ignoreCase = true) } }
            val without = entries - with.toSet()
            TagInsight(
                tag = tag,
                checkIns = with.size,
                averageWith = with.map { it.level }.average(),
                averageWithout = if (without.isEmpty()) null else without.map { it.level }.average(),
            )
        }.sortedWith(compareByDescending<TagInsight> { it.checkIns }.thenBy { it.tag })
    }

    /** Weekly averages (weeks start Monday) covering [from]..[to]. */
    fun weekly(entries: List<MoodCheckIn>, from: LocalDate, to: LocalDate): List<PeriodAverage> {
        val out = ArrayList<PeriodAverage>()
        var start = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        while (!start.isAfter(to)) {
            val end = start.plusDays(6)
            val slice = entries.filter { !it.date.isBefore(start) && !it.date.isAfter(end) }
            val means = dailyMeans(slice)
            out += PeriodAverage(start, if (means.isEmpty()) null else means.map { it.average }.average(), slice.size)
            start = start.plusWeeks(1)
        }
        return out
    }

    fun monthly(entries: List<MoodCheckIn>, from: YearMonth, to: YearMonth): List<PeriodAverage> {
        val out = ArrayList<PeriodAverage>()
        var ym = from
        while (!ym.isAfter(to)) {
            val slice = entries.filter { YearMonth.from(it.date) == ym }
            val means = dailyMeans(slice)
            out += PeriodAverage(ym.atDay(1), if (means.isEmpty()) null else means.map { it.average }.average(), slice.size)
            ym = ym.plusMonths(1)
        }
        return out
    }

    /** Number of most recent consecutive check-ins at or below [lowLevel]. */
    fun lowRun(entries: List<MoodCheckIn>, lowLevel: Int): Int {
        val ordered = entries.sortedWith(compareByDescending<MoodCheckIn> { it.date }.thenByDescending { it.slot.ordinal })
        var n = 0
        for (e in ordered) { if (e.level <= lowLevel) n++ else break }
        return n
    }

    fun shouldShowGentleMessage(entries: List<MoodCheckIn>, settings: MoodSettings): Boolean =
        settings.gentleMessageEnabled && settings.lowRunLength > 0 && lowRun(entries, settings.lowLevel) >= settings.lowRunLength

    const val GENTLE_MESSAGE =
        "Your last few check-ins have been low. That happens, and it can help to talk with someone you trust " +
            "— a friend, family member, teacher or counsellor. This is a self-reflection tool, not medical advice."

    private fun slope(days: List<DayMood>): Double {
        if (days.size < 2) return 0.0
        val x0 = days.first().date.toEpochDay()
        val xs = days.map { (it.date.toEpochDay() - x0).toDouble() }
        val ys = days.map { it.average }
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) { num += (xs[i] - mx) * (ys[i] - my); den += (xs[i] - mx) * (xs[i] - mx) }
        return if (den == 0.0) 0.0 else num / den
    }
}
