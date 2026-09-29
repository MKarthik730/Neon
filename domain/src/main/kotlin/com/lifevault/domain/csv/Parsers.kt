package com.lifevault.domain.csv

import com.lifevault.domain.model.Session
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/** Lenient parsers shared by the CSV importers. Each returns null when the value is not understood. */
object FieldParsers {

    private val dayNames: Map<String, DayOfWeek> = buildMap {
        for (d in DayOfWeek.entries) {
            val full = d.name.lowercase()
            put(full, d)
            put(full.take(3), d)
            put(d.value.toString(), d)
        }
        put("tues", DayOfWeek.TUESDAY)
        put("weds", DayOfWeek.WEDNESDAY)
        put("thur", DayOfWeek.THURSDAY)
        put("thurs", DayOfWeek.THURSDAY)
    }

    fun day(raw: String): DayOfWeek? = dayNames[raw.trim().lowercase()]

    fun session(raw: String): Session? = when (raw.trim().lowercase()) {
        "morning", "m", "am", "mor" -> Session.MORNING
        "evening", "e", "pm", "eve", "afternoon" -> Session.EVENING
        else -> null
    }

    private val time12 = Regex("""^(\d{1,2})(?:[:.](\d{2}))?\s*([ap])\.?m\.?$""", RegexOption.IGNORE_CASE)
    private val time24 = Regex("""^(\d{1,2})[:.](\d{2})$""")

    /** Accepts `9:00`, `09:00`, `9.00`, `9am`, `2:30 PM`. */
    fun time(raw: String): LocalTime? {
        val s = raw.trim()
        time24.matchEntire(s)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            return if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
        }
        time12.matchEntire(s)?.let { m ->
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (h !in 1..12 || min !in 0..59) return null
            val pm = m.groupValues[3].equals("p", ignoreCase = true)
            if (h == 12) h = 0
            if (pm) h += 12
            return LocalTime.of(h, min)
        }
        return null
    }

    private val dateFormats = listOf("uuuu-MM-dd", "dd-MM-uuuu", "dd/MM/uuuu", "d-M-uuuu", "d/M/uuuu")
        .map { DateTimeFormatter.ofPattern(it).withResolverStyle(ResolverStyle.STRICT) }

    /** Accepts ISO `2026-10-02` or day-first `02-10-2026` / `2/10/2026`. */
    fun date(raw: String): LocalDate? {
        val s = raw.trim()
        for (f in dateFormats) {
            try { return LocalDate.parse(s, f) } catch (_: DateTimeParseException) {}
        }
        return null
    }
}
