package com.lifevault.domain.csv

import com.lifevault.domain.model.Session
import com.lifevault.domain.model.TimetableSlot
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Timetable CSV format (header row optional):
 *
 * ```
 * day,session,start,end,subject
 * Mon,Morning,09:00,12:30,Maths
 * Mon,Morning,09:00,12:30,Physics
 * Mon,Evening,14:00,17:00,Chemistry
 * ```
 *
 *  - `day`: Mon..Sun, full names, or 1..7 (1 = Monday).
 *  - `session`: Morning/Evening (also M/E, AM/PM).
 *  - `start`/`end`: `HH:mm` 24-hour (or `9am`, `2:30 PM`).
 *  - `subject`: one per row; repeat the row for more subjects, or separate several with `|`.
 *
 * Rows for the same day + session are merged; their start/end times must agree.
 */
object TimetableCsv {
    const val HEADER = "day,session,start,end,subject"

    val SAMPLE = """
        |$HEADER
        |Mon,Morning,09:00,12:30,Maths
        |Mon,Evening,14:00,17:00,Physics|Chemistry
        |Tue,Morning,09:00,12:30,English
    """.trimMargin()

    private data class Key(val day: DayOfWeek, val session: Session)
    private data class Acc(val line: Int, val start: LocalTime, val end: LocalTime, val subjects: MutableList<String>)

    fun parse(text: String): ImportPreview<TimetableSlot> {
        val errors = ArrayList<CsvIssue>()
        val warnings = ArrayList<CsvIssue>()
        val rows = Csv.parse(text)
        if (rows.isEmpty()) return ImportPreview(emptyList(), listOf(CsvIssue(0, "The file is empty.")))

        val acc = LinkedHashMap<Key, Acc>()
        for ((index, row) in rows.withIndex()) {
            val f = row.fields
            if (index == 0 && f.firstOrNull()?.equals("day", ignoreCase = true) == true) continue
            if (f.size < 5) {
                errors += CsvIssue(row.line, "Expected 5 columns ($HEADER), found ${f.size}.")
                continue
            }
            if (f.size > 5) warnings += CsvIssue(row.line, "Extra columns after 'subject' were ignored.")
            val day = FieldParsers.day(f[0])
            val session = FieldParsers.session(f[1])
            val start = FieldParsers.time(f[2])
            val end = FieldParsers.time(f[3])
            val subjects = f[4].split('|').map { it.trim() }.filter { it.isNotEmpty() }
            var ok = true
            if (day == null) { errors += CsvIssue(row.line, "Unknown day '${f[0]}'. Use Mon..Sun or 1..7."); ok = false }
            if (session == null) { errors += CsvIssue(row.line, "Unknown session '${f[1]}'. Use Morning or Evening."); ok = false }
            if (start == null) { errors += CsvIssue(row.line, "Invalid start time '${f[2]}'. Use HH:mm."); ok = false }
            if (end == null) { errors += CsvIssue(row.line, "Invalid end time '${f[3]}'. Use HH:mm."); ok = false }
            if (subjects.isEmpty()) { errors += CsvIssue(row.line, "Subject is empty."); ok = false }
            if (start != null && end != null && !end.isAfter(start)) {
                errors += CsvIssue(row.line, "End time ${f[3]} must be after start time ${f[2]}."); ok = false
            }
            if (!ok) continue

            val key = Key(day!!, session!!)
            val existing = acc[key]
            if (existing == null) {
                acc[key] = Acc(row.line, start!!, end!!, subjects.toMutableList())
            } else {
                if (existing.start != start || existing.end != end) {
                    errors += CsvIssue(
                        row.line,
                        "${day.display()} ${session.label} times differ from line ${existing.line} " +
                            "(${existing.start}-${existing.end} vs $start-$end).",
                    )
                    continue
                }
                for (s in subjects) {
                    if (existing.subjects.none { it.equals(s, ignoreCase = true) }) existing.subjects += s
                    else warnings += CsvIssue(row.line, "Duplicate subject '$s' for ${day.display()} ${session.label}.")
                }
            }
        }

        // Morning must finish before evening starts on the same day.
        for (day in DayOfWeek.entries) {
            val m = acc[Key(day, Session.MORNING)] ?: continue
            val e = acc[Key(day, Session.EVENING)] ?: continue
            if (m.end.isAfter(e.start)) {
                warnings += CsvIssue(e.line, "${day.display()}: Morning ends (${m.end}) after Evening starts (${e.start}).")
            }
        }

        val slots = acc.map { (k, v) -> TimetableSlot(k.day, k.session, v.start, v.end, v.subjects.toList()) }
            .sortedWith(compareBy({ it.day }, { it.session }))
        if (slots.isEmpty() && errors.isEmpty()) errors += CsvIssue(0, "No timetable rows found.")
        return ImportPreview(slots, errors, warnings)
    }

    fun export(slots: List<TimetableSlot>): String = buildString {
        appendLine(HEADER)
        for (s in slots.sortedWith(compareBy({ it.day }, { it.session }))) {
            appendLine(Csv.line(s.day.display(), s.session.label, s.start.toString(), s.end.toString(), s.subjects.joinToString("|")))
        }
    }

    private fun DayOfWeek.display() = name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
}
