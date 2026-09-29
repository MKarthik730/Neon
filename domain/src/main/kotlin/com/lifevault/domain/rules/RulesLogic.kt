package com.lifevault.domain.rules

import com.lifevault.domain.model.JobLogEntry
import com.lifevault.domain.model.Rule
import com.lifevault.domain.model.RuleSet
import com.lifevault.domain.util.Streaks
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

object RuleOrdering {

    /** Rules shown before a job: archived rules hidden, pinned first, otherwise in the user's order. */
    fun activeOrdered(set: RuleSet): List<Rule> {
        val active = set.rules.filterNot { it.archived }
        return active.filter { it.pinned } + active.filterNot { it.pinned }
    }

    /** Moves the rule at [from] to [to] in the stored order (indices into [RuleSet.rules]). */
    fun move(set: RuleSet, from: Int, to: Int): RuleSet {
        if (from !in set.rules.indices || to !in set.rules.indices || from == to) return set
        val list = set.rules.toMutableList()
        val r = list.removeAt(from)
        list.add(to, r)
        return set.copy(rules = list)
    }

    fun moveBy(set: RuleSet, ruleId: String, delta: Int): RuleSet {
        val i = set.rules.indexOfFirst { it.id == ruleId }
        if (i < 0) return set
        return move(set, i, (i + delta).coerceIn(0, set.rules.lastIndex))
    }
}

/**
 * State of the "read your rules" gate before a job.
 * Begin unlocks only when the countdown has finished AND either every active rule is ticked
 * ([requireTickAll]) or the user confirmed "I've read them".
 */
data class StartJobGate(
    val ruleIds: List<String>,
    val requireTickAll: Boolean,
    val countdownSeconds: Int,
    val elapsedSeconds: Int = 0,
    val ticked: Set<String> = emptySet(),
    val acknowledged: Boolean = false,
) {
    val countdownRemaining: Int get() = (countdownSeconds - elapsedSeconds).coerceAtLeast(0)
    val allTicked: Boolean get() = ruleIds.all { it in ticked }

    val canBegin: Boolean
        get() = countdownRemaining == 0 && if (requireTickAll) allTicked else (acknowledged || allTicked)

    /** Whether the reading requirement itself is satisfied (independent of the countdown). */
    val rulesRead: Boolean get() = if (requireTickAll) allTicked else (acknowledged || allTicked)

    fun toggle(ruleId: String): StartJobGate =
        if (ruleId !in ruleIds) this else copy(ticked = if (ruleId in ticked) ticked - ruleId else ticked + ruleId)

    fun acknowledge(): StartJobGate = copy(acknowledged = true)
    fun tick(seconds: Int = 1): StartJobGate = copy(elapsedSeconds = elapsedSeconds + seconds)
}

data class RuleMiss(val ruleId: String, val timesUnticked: Int, val timesShown: Int)

data class RulesReport(
    val totalJobs: Int,
    val jobsWithRulesRead: Int,
    /** Null when there are no jobs. */
    val readPercent: Double?,
    /** Consecutive days (ending today/yesterday) with at least one job started after reading the rules. */
    val readStreakDays: Int,
    val minutesThisWeek: Long,
    /** Week start (Monday) -> total job minutes. */
    val minutesByWeek: Map<LocalDate, Long>,
    /** Rules most often left unticked, worst first. */
    val mostUnticked: List<RuleMiss>,
)

object RulesStats {
    fun report(logs: List<JobLogEntry>, today: LocalDate, now: LocalDateTime = today.atTime(23, 59)): RulesReport {
        val total = logs.size
        val read = logs.count { it.rulesRead }
        val readDays = logs.filter { it.rulesRead }.map { it.startedAt.toLocalDate() }.toSet()
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val byWeek = logs.groupBy { it.startedAt.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
            .mapValues { (_, l) -> l.sumOf { minutes(it, now) } }
            .toSortedMap()
        val shown = HashMap<String, Int>()
        val missed = HashMap<String, Int>()
        for (l in logs) {
            for (id in l.rulesShown) {
                shown[id] = (shown[id] ?: 0) + 1
                if (id !in l.rulesTicked) missed[id] = (missed[id] ?: 0) + 1
            }
        }
        val most = missed.map { (id, n) -> RuleMiss(id, n, shown[id] ?: n) }
            .sortedWith(compareByDescending<RuleMiss> { it.timesUnticked }.thenBy { it.ruleId })
        return RulesReport(
            totalJobs = total,
            jobsWithRulesRead = read,
            readPercent = if (total == 0) null else read * 100.0 / total,
            readStreakDays = Streaks.current(readDays, today),
            minutesThisWeek = byWeek[weekStart] ?: 0,
            minutesByWeek = byWeek,
            mostUnticked = most,
        )
    }

    /** Job duration in whole minutes; a job still running counts up to [now]. */
    fun minutes(entry: JobLogEntry, now: LocalDateTime): Long {
        val end = entry.endedAt ?: now
        return Duration.between(entry.startedAt, end).toMinutes().coerceAtLeast(0)
    }
}
