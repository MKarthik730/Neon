package com.lifevault.domain.csv

/** A parsed CSV record with its 1-based line number in the source text. */
data class CsvRow(val line: Int, val fields: List<String>)

/** A problem found while importing, tied to a source line (0 = whole file). */
data class CsvIssue(val line: Int, val message: String) {
    override fun toString() = if (line > 0) "Line $line: $message" else message
}

/** Result of parsing an import file: shown on a preview screen before anything is saved. */
data class ImportPreview<T>(
    val items: List<T>,
    val errors: List<CsvIssue>,
    val warnings: List<CsvIssue> = emptyList(),
) {
    val canSave: Boolean get() = errors.isEmpty() && items.isNotEmpty()
}

/**
 * Minimal RFC 4180 reader: comma separated, double-quoted fields with "" escapes, CRLF or LF line ends.
 * Blank lines and lines starting with '#' are skipped. A UTF-8 BOM is ignored.
 */
object Csv {
    fun parse(text: String): List<CsvRow> {
        val src = text.removePrefix("﻿")
        val rows = ArrayList<CsvRow>()
        val field = StringBuilder()
        var fields = ArrayList<String>()
        var inQuotes = false
        var line = 1
        var rowStartLine = 1
        var i = 0
        var rowHasContent = false

        fun endRow() {
            fields.add(field.toString())
            field.setLength(0)
            val isBlank = fields.all { it.isBlank() }
            val isComment = fields.firstOrNull()?.trimStart()?.startsWith("#") == true && !rowHasContent
            if (!isBlank && !isComment) rows += CsvRow(rowStartLine, fields.map { it.trim() })
            fields = ArrayList()
            rowHasContent = false
        }

        while (i < src.length) {
            val c = src[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < src.length && src[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> { if (c == '\n') line++; field.append(c) }
                }
            } else {
                when (c) {
                    '"' -> { inQuotes = true; rowHasContent = true }
                    ',' -> { fields.add(field.toString()); field.setLength(0) }
                    '\r' -> {}
                    '\n' -> { endRow(); line++; rowStartLine = line }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || fields.isNotEmpty()) endRow()
        return rows
    }

    /** Quotes a value only when needed. */
    fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    fun line(vararg values: String): String = values.joinToString(",") { escape(it) }
}
