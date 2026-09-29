package com.lifevault.domain.csv

import com.lifevault.domain.model.Holiday
import com.lifevault.domain.util.Ids

/**
 * Holiday CSV format (header row optional):
 *
 * ```
 * start,end,label
 * 2026-10-02,,Gandhi Jayanti
 * 2026-10-20,2026-10-24,Diwali break
 * ```
 *
 *  - `start`: `yyyy-MM-dd` (ISO) or day-first `dd-MM-yyyy` / `dd/MM/yyyy`.
 *  - `end`: optional; empty means a single day.
 *  - `label`: free text.
 */
object HolidayCsv {
    const val HEADER = "start,end,label"

    val SAMPLE = """
        |$HEADER
        |2026-10-02,,Gandhi Jayanti
        |2026-10-20,2026-10-24,Diwali break
    """.trimMargin()

    fun parse(text: String, newId: () -> String = Ids::new): ImportPreview<Holiday> {
        val errors = ArrayList<CsvIssue>()
        val warnings = ArrayList<CsvIssue>()
        val rows = Csv.parse(text)
        if (rows.isEmpty()) return ImportPreview(emptyList(), listOf(CsvIssue(0, "The file is empty.")))
        val out = ArrayList<Holiday>()
        for ((index, row) in rows.withIndex()) {
            val f = row.fields
            if (index == 0 && f.firstOrNull()?.lowercase() in setOf("start", "date")) continue
            if (f.isEmpty() || f[0].isBlank()) { errors += CsvIssue(row.line, "Missing start date."); continue }
            val start = FieldParsers.date(f[0])
            if (start == null) { errors += CsvIssue(row.line, "Invalid date '${f[0]}'. Use yyyy-MM-dd."); continue }
            val endRaw = f.getOrNull(1).orEmpty()
            val end = if (endRaw.isBlank()) start else FieldParsers.date(endRaw)
            if (end == null) { errors += CsvIssue(row.line, "Invalid end date '$endRaw'. Use yyyy-MM-dd."); continue }
            if (end.isBefore(start)) { errors += CsvIssue(row.line, "End date $end is before start date $start."); continue }
            if (end.toEpochDay() - start.toEpochDay() > 366) warnings += CsvIssue(row.line, "Range is longer than a year.")
            val label = f.getOrNull(2).orEmpty().ifBlank {
                warnings += CsvIssue(row.line, "No label; using 'Holiday'.")
                "Holiday"
            }
            if (f.size > 3) warnings += CsvIssue(row.line, "Extra columns after 'label' were ignored.")
            if (out.any { it.start == start && it.end == end }) {
                warnings += CsvIssue(row.line, "Duplicate of an earlier row; skipped.")
                continue
            }
            out += Holiday(newId(), start, end, label)
        }
        if (out.isEmpty() && errors.isEmpty()) errors += CsvIssue(0, "No holiday rows found.")
        return ImportPreview(out.sortedBy { it.start }, errors, warnings)
    }

    fun export(holidays: List<Holiday>): String = buildString {
        appendLine(HEADER)
        for (h in holidays.sortedBy { it.start }) {
            appendLine(Csv.line(h.start.toString(), if (h.end == h.start) "" else h.end.toString(), h.label))
        }
    }
}
