package com.lifevault.domain.notify

import com.lifevault.domain.Fixtures
import com.lifevault.domain.model.AttendanceSettings
import com.lifevault.domain.model.Event
import com.lifevault.domain.model.Holiday
import com.lifevault.domain.model.MoodCheckIn
import com.lifevault.domain.model.MoodSettings
import com.lifevault.domain.model.RuleReminder
import com.lifevault.domain.model.RuleSet
import com.lifevault.domain.model.RulesFile
import com.lifevault.domain.model.Session
import com.lifevault.domain.model.Settings
import com.lifevault.domain.util.VaultJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class AlarmPlannerTest {
    private val mon = Fixtures.MON
    private val now = mon.atTime(8, 0)

    @Test
    fun `attendance prompts follow session end plus delay and skip holidays and off days`() {
        val holiday = Holiday("h", mon.plusDays(1), mon.plusDays(1), "Festival")
        val plan = AlarmPlanner.plan(
            now, Fixtures.weekdayTimetable(), Fixtures.holidays(holiday),
            Settings(attendance = AttendanceSettings(promptDelayMinutes = 15)), emptyList(), RulesFile(), horizonDays = 6,
        )
        val att = plan.alarms.filter { it.type == AlarmType.ATTENDANCE }
        // Mon, Wed, Thu, Fri x 2 sessions (Tue holiday, Sat/Sun off)
        assertEquals(8, att.size)
        assertEquals(mon.atTime(12, 15), att.first().at)
        assertEquals(Session.MORNING, att.first().session)
        assertFalse(att.any { it.date == mon.plusDays(1) })
        assertFalse(att.any { it.date!!.dayOfWeek == DayOfWeek.SATURDAY })
        assertEquals(plan.alarms.sortedBy { it.at }, plan.alarms)
    }

    @Test
    fun `past alarms are dropped and prompts can be disabled`() {
        val late = mon.atTime(13, 0)
        val plan = AlarmPlanner.plan(late, Fixtures.weekdayTimetable(), Fixtures.holidays(), Settings(), emptyList(), RulesFile(), 0)
        assertEquals(listOf(Session.EVENING), plan.alarms.map { it.session })
        val off = AlarmPlanner.plan(now, Fixtures.weekdayTimetable(), Fixtures.holidays(), Settings(attendance = AttendanceSettings(promptsEnabled = false)), emptyList(), RulesFile(), 3)
        assertTrue(off.alarms.isEmpty())
    }

    @Test
    fun `mood reminders optionally skip holidays`() {
        val holiday = Holiday("h", mon, mon, "Festival")
        val on = Settings(mood = MoodSettings(reminderEnabled = true, reminderTime = LocalTime.of(20, 0)))
        val tt = Fixtures.weekdayTimetable()
        val all = AlarmPlanner.plan(now, tt, Fixtures.holidays(holiday), on, emptyList(), RulesFile(), 6).alarms.filter { it.type == AlarmType.MOOD }
        assertEquals(7, all.size)
        val skipping = on.copy(mood = on.mood.copy(reminderSkipsHolidays = true))
        val some = AlarmPlanner.plan(now, tt, Fixtures.holidays(holiday), skipping, emptyList(), RulesFile(), 6).alarms.filter { it.type == AlarmType.MOOD }
        assertEquals(4, some.size) // minus Monday holiday and the weekend
    }

    @Test
    fun `event reminders and linked rule prompts`() {
        val sets = RulesFile(sets = listOf(RuleSet("rs", "Before a test")))
        val ev = Event("e1", title = "Chemistry test", date = mon.plusDays(2), time = LocalTime.of(10, 0), reminderMinutesBefore = 60, ruleSetId = "rs", rulesPromptMinutesBefore = 5)
        val plan = AlarmPlanner.plan(now, Fixtures.weekdayTimetable(), Fixtures.holidays(), Settings(), listOf(ev), sets, 6)
        val evAlarm = plan.alarms.single { it.type == AlarmType.EVENT }
        assertEquals(mon.plusDays(2).atTime(9, 0), evAlarm.at)
        assertEquals("e1", evAlarm.refId)
        val rules = plan.alarms.single { it.type == AlarmType.RULES }
        assertEquals(mon.plusDays(2).atTime(9, 55), rules.at)
        assertEquals("rs", rules.refId)
    }

    @Test
    fun `recurring rule reminders on chosen days`() {
        val sets = RulesFile(
            sets = listOf(RuleSet("rs", "Before a job")),
            reminders = listOf(RuleReminder("r", "rs", "Shift", LocalTime.of(18, 0), listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))),
        )
        val plan = AlarmPlanner.plan(now, Fixtures.weekdayTimetable(), Fixtures.holidays(), Settings(), emptyList(), sets, 6)
        assertEquals(listOf(mon, mon.plusDays(2)), plan.alarms.filter { it.type == AlarmType.RULES }.map { it.date })
    }

    @Test
    fun `the plan stored outside the vault never contains titles, subjects or mood data`() {
        val settings = Settings(mood = MoodSettings(reminderEnabled = true))
        val ev = Event("e1", title = "SECRET-TITLE", date = mon.plusDays(1), time = LocalTime.of(10, 0), notes = "SECRET-NOTE", reminderMinutesBefore = 10)
        val mood = MoodCheckIn("m", mon, level = 1, tags = listOf("SECRET-TAG"), note = "SECRET-MOOD")
        val plan = AlarmPlanner.plan(now, Fixtures.weekdayTimetable(), Fixtures.holidays(), settings, listOf(ev), RulesFile(), 6)
        val json = VaultJson.encodeToString(AlarmPlan.serializer(), plan)
        for (secret in listOf("SECRET", "Maths", "Physics", mood.note, "level")) {
            assertFalse("plan leaked '$secret'", json.contains(secret))
        }
        // The serialised shape has only these fields.
        val allowed = setOf("key", "type", "at", "date", "session", "refId")
        val fields = PlannedAlarm.serializer().descriptor.let { d -> (0 until d.elementsCount).map { d.getElementName(it) } }
        assertEquals(allowed, fields.toSet())
    }
}
