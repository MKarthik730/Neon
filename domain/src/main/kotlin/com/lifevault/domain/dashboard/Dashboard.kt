package com.lifevault.domain.dashboard

import com.lifevault.domain.attendance.AttendanceReport
import com.lifevault.domain.model.Entry
import com.lifevault.domain.model.Event
import com.lifevault.domain.model.JobLogEntry
import com.lifevault.domain.model.MoodCheckIn
import com.lifevault.domain.mood.DayMood
import com.lifevault.domain.mood.MoodStats
import com.lifevault.domain.rules.RulesStats
import com.lifevault.domain.trackers.EntrySummaries
import java.time.LocalDate
import java.time.LocalDateTime

/** Everything on the progress screen. Always derived from stored data, never stored itself. */
data class DashboardSummary(
    val attendancePercent: Double?,
    val attendanceBelowThreshold: Boolean,
    val pendingSessions: Int,
    val spendThisWeek: Double,
    val spendLast7Days: List<Pair<LocalDate, Double>>,
    val foodStreak: Int,
    val upcomingEvents: List<Event>,
    val mediaCount: Int,
    val moodWeek: List<DayMood>,
    val moodWeekAverage: Double?,
    val rulesReadStreak: Int,
)

object DashboardCalculator {
    fun compute(
        today: LocalDate,
        now: LocalDateTime,
        attendance: AttendanceReport?,
        thresholdPercent: Int,
        entries: List<Entry>,
        events: List<Event>,
        mediaCount: Int,
        mood: List<MoodCheckIn>,
        jobs: List<JobLogEntry>,
        upcomingLimit: Int = 3,
    ): DashboardSummary {
        val weekFrom = today.minusDays(6)
        val moodReport = MoodStats.report(mood, weekFrom, today, today)
        val money = EntrySummaries.ofType(entries, com.lifevault.domain.model.EntryType.MONEY)
        return DashboardSummary(
            attendancePercent = attendance?.overall?.percentage,
            attendanceBelowThreshold = attendance?.overall?.isBelow(thresholdPercent) ?: false,
            pendingSessions = attendance?.pending?.size ?: 0,
            spendThisWeek = EntrySummaries.spendThisWeek(entries, today),
            spendLast7Days = EntrySummaries.dailySeries(money, weekFrom, today),
            foodStreak = EntrySummaries.foodStreak(entries, today),
            upcomingEvents = events.filter { !it.startsAt().isBefore(now) }.sortedBy { it.startsAt() }.take(upcomingLimit),
            mediaCount = mediaCount,
            moodWeek = moodReport.days,
            moodWeekAverage = moodReport.average,
            rulesReadStreak = RulesStats.report(jobs, today, now).readStreakDays,
        )
    }
}
