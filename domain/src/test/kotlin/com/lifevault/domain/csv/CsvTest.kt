package com.lifevault.domain.csv

import com.lifevault.domain.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class CsvTest {

    @Test
    fun `parses quotes, escaped quotes, CRLF, BOM, comments and blank lines`() {
        val text = "﻿a,b,c\r\n# comment\r\n\r\n\"x, y\",\"say \"\"hi\"\"\",z\n\"multi\nline\",2,3"
        val rows = Csv.parse(text)
        assertEquals(3, rows.size)
        assertEquals(listOf("a", "b", "c"), rows[0].fields)
        assertEquals(listOf("x, y", "say \"hi\"", "z"), rows[1].fields)
        assertEquals(4, rows[1].line)
        assertEquals("multi\nline", rows[2].fields[0])
        assertEquals(5, rows[2].line)
    }

    @Test
    fun `escape round trips`() {
        val line = Csv.line("a,b", "q\"q", "plain")
        assertEquals(listOf("a,b", "q\"q", "plain"), Csv.parse(line).single().fields)
    }

    @Test
    fun `timetable sample parses and merges subjects`() {
        val p = TimetableCsv.parse(TimetableCsv.SAMPLE + "\nMon,Morning,09:00,12:30,Chemistry")
        assertTrue(p.errors.toString(), p.errors.isEmpty())
        assertTrue(p.canSave)
        val monMorning = p.items.first { it.day == DayOfWeek.MONDAY && it.session == Session.MORNING }
        assertEquals(listOf("Maths", "Chemistry"), monMorning.subjects)
        assertEquals(LocalTime.of(12, 30), monMorning.end)
        val monEvening = p.items.first { it.day == DayOfWeek.MONDAY && it.session == Session.EVENING }
        assertEquals(listOf("Physics", "Chemistry"), monEvening.subjects)
    }

    @Test
    fun `timetable accepts lenient day, session and time formats`() {
        val p = TimetableCsv.parse("1,m,9am,12:30 pm,Maths\nthurs,E,2.00,5:00 PM,Art")
        assertTrue(p.errors.toString(), p.errors.isEmpty())
        assertEquals(LocalTime.of(9, 0), p.items[0].start)
        assertEquals(LocalTime.of(12, 30), p.items[0].end)
        assertEquals(DayOfWeek.THURSDAY, p.items[1].day)
        assertEquals(LocalTime.of(17, 0), p.items[1].end)
    }

    @Test
    fun `timetable validation reports every bad line`() {
        val text = """
            day,session,start,end,subject
            Funday,Morning,09:00,12:00,Maths
            Mon,Night,09:00,12:00,Maths
            Mon,Morning,25:00,12:00,Maths
            Mon,Morning,12:00,09:00,Maths
            Tue,Morning,09:00,12:00,
            Wed,Morning,09:00
            Thu,Morning,09:00,12:00,A
            Thu,Morning,10:00,12:00,B
        """.trimIndent()
        val p = TimetableCsv.parse(text)
        assertFalse(p.canSave)
        val lines = p.errors.map { it.line }
        assertEquals(listOf(2, 3, 4, 5, 6, 7, 9), lines)
        assertTrue(p.errors.first { it.line == 9 }.message.contains("times differ"))
    }

    @Test
    fun `timetable warns when morning overlaps evening`() {
        val p = TimetableCsv.parse("Mon,Morning,09:00,15:00,A\nMon,Evening,14:00,17:00,B")
        assertTrue(p.errors.isEmpty())
        assertEquals(1, p.warnings.size)
    }

    @Test
    fun `empty timetable file is an error`() {
        assertFalse(TimetableCsv.parse("").canSave)
        assertFalse(TimetableCsv.parse("day,session,start,end,subject\n").canSave)
    }

    @Test
    fun `timetable export re-imports identically`() {
        val p = TimetableCsv.parse(TimetableCsv.SAMPLE)
        val again = TimetableCsv.parse(TimetableCsv.export(p.items))
        assertEquals(p.items, again.items)
    }

    @Test
    fun `holidays parse single days, ranges and day-first dates`() {
        var n = 0
        val p = HolidayCsv.parse(HolidayCsv.SAMPLE + "\n25/12/2026,,Christmas", newId = { "id${n++}" })
        assertTrue(p.errors.toString(), p.errors.isEmpty())
        assertEquals(3, p.items.size)
        assertEquals(LocalDate.of(2026, 10, 2), p.items[0].end)
        assertEquals(LocalDate.of(2026, 10, 24), p.items[1].end)
        assertEquals(LocalDate.of(2026, 12, 25), p.items[2].start)
    }

    @Test
    fun `holiday validation`() {
        val p = HolidayCsv.parse("start,end,label\n2026-02-30,,Bad\n2026-10-05,2026-10-01,Backwards\n,,Empty\n2026-10-10,,")
        assertEquals(listOf(2, 3, 4), p.errors.map { it.line })
        assertEquals(1, p.items.size)
        assertEquals("Holiday", p.items[0].label)
        assertEquals(1, p.warnings.size)
    }

    @Test
    fun `holiday export re-imports identically`() {
        val p = HolidayCsv.parse(HolidayCsv.SAMPLE, newId = { "x" })
        val again = HolidayCsv.parse(HolidayCsv.export(p.items), newId = { "x" })
        assertEquals(p.items, again.items)
    }
}
