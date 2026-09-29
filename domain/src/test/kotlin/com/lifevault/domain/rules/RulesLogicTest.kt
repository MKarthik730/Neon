package com.lifevault.domain.rules

import com.lifevault.domain.model.JobLogEntry
import com.lifevault.domain.model.Rule
import com.lifevault.domain.model.RuleSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class RulesLogicTest {

    private val set = RuleSet(
        "s", "Before studying",
        listOf(
            Rule("a", "Phone in another room"),
            Rule("b", "Water bottle filled", pinned = true),
            Rule("c", "Old rule", archived = true),
            Rule("d", "Write the goal down"),
        ),
    )

    @Test
    fun `active rules are pinned first then user order, archived hidden`() {
        assertEquals(listOf("b", "a", "d"), RuleOrdering.activeOrdered(set).map { it.id })
    }

    @Test
    fun `reordering moves rules and ignores bad indices`() {
        assertEquals(listOf("d", "a", "b", "c"), RuleOrdering.move(set, 3, 0).rules.map { it.id })
        assertEquals(set, RuleOrdering.move(set, 0, 9))
        assertEquals(listOf("b", "a", "c", "d"), RuleOrdering.moveBy(set, "a", 1).rules.map { it.id })
        assertEquals(listOf("a", "b", "c", "d"), RuleOrdering.moveBy(set, "a", -1).rules.map { it.id })
    }

    @Test
    fun `begin unlocks only after all rules ticked and countdown done`() {
        var g = StartJobGate(listOf("a", "b"), requireTickAll = true, countdownSeconds = 3)
        assertFalse(g.canBegin)
        g = g.toggle("a").toggle("b")
        assertFalse("countdown still running", g.canBegin)
        g = g.tick().tick()
        assertEquals(1, g.countdownRemaining)
        assertFalse(g.canBegin)
        g = g.tick()
        assertTrue(g.canBegin)
        g = g.toggle("b")
        assertFalse("un-ticking locks Begin again", g.canBegin)
        g = g.acknowledge()
        assertFalse("acknowledge is not enough in tick-all mode", g.canBegin)
    }

    @Test
    fun `read-once mode needs a single confirmation`() {
        var g = StartJobGate(listOf("a", "b"), requireTickAll = false, countdownSeconds = 0)
        assertFalse(g.canBegin)
        g = g.acknowledge()
        assertTrue(g.canBegin)
        assertTrue(g.rulesRead)
    }

    @Test
    fun `unknown rule ids cannot be ticked and empty sets can begin`() {
        val g = StartJobGate(listOf("a"), requireTickAll = true, countdownSeconds = 0).toggle("zzz")
        assertTrue(g.ticked.isEmpty())
        assertTrue(StartJobGate(emptyList(), requireTickAll = true, countdownSeconds = 0).canBegin)
    }

    private val today = LocalDate.of(2026, 9, 28) // Monday
    private fun job(daysAgo: Long, read: Boolean, minutes: Long, shown: List<String>, ticked: List<String>) = JobLogEntry(
        id = "j$daysAgo$read$minutes", jobName = "Study", ruleSetId = "s",
        startedAt = today.minusDays(daysAgo).atTime(10, 0),
        endedAt = today.minusDays(daysAgo).atTime(10, 0).plusMinutes(minutes),
        rulesShown = shown, rulesTicked = ticked, rulesRead = read,
    )

    @Test
    fun `rules report percentages streak time and misses`() {
        val logs = listOf(
            job(0, true, 30, listOf("a", "b"), listOf("a", "b")),
            job(1, true, 45, listOf("a", "b"), listOf("a")),
            job(2, false, 60, listOf("a", "b"), emptyList()),
            job(3, true, 20, listOf("a", "b"), listOf("a", "b")),
        )
        val r = RulesStats.report(logs, today, today.atTime(12, 0))
        assertEquals(4, r.totalJobs)
        assertEquals(3, r.jobsWithRulesRead)
        assertEquals(75.0, r.readPercent!!, 1e-9)
        assertEquals(2, r.readStreakDays)
        assertEquals(30L, r.minutesThisWeek)
        assertEquals(125L, r.minutesByWeek[today.minusDays(7)])
        assertEquals("b", r.mostUnticked.first().ruleId)
        assertEquals(2, r.mostUnticked.first().timesUnticked)
        assertEquals(4, r.mostUnticked.first().timesShown)
    }

    @Test
    fun `empty log`() {
        val r = RulesStats.report(emptyList(), today)
        assertNull(r.readPercent)
        assertEquals(0, r.readStreakDays)
        assertEquals(0L, r.minutesThisWeek)
    }

    @Test
    fun `running job counts up to now`() {
        val running = JobLogEntry("r", "Job", null, startedAt = LocalDateTime.of(2026, 9, 28, 10, 0))
        assertEquals(15, RulesStats.minutes(running, LocalDateTime.of(2026, 9, 28, 10, 15)))
    }
}
