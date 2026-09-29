package com.lifevault.reports

import com.lifevault.Brand
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.lifevault.domain.csv.Csv
import com.lifevault.domain.model.MoodCheckIn
import com.lifevault.domain.model.MoodScale
import com.lifevault.domain.mood.MoodReport
import com.lifevault.domain.mood.PeriodAverage
import com.lifevault.domain.mood.TrendDirection
import java.io.OutputStream
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Explicit, user-initiated exports of the mood report. These are the only mood data ever written outside
 * the vault, and only to a location the user picks.
 */
object MoodExport {

    fun csv(entries: List<MoodCheckIn>, includeNotes: Boolean): String = buildString {
        appendLine(if (includeNotes) "date,slot,level,label,tags,note" else "date,slot,level,label,tags")
        for (e in entries.sortedWith(compareBy({ it.date }, { it.slot.ordinal }))) {
            val cols = mutableListOf(e.date.toString(), e.slot.label, e.level.toString(), MoodScale.label[e.level].orEmpty(), e.tags.joinToString("|"))
            if (includeNotes) cols += e.note
            appendLine(Csv.line(*cols.toTypedArray()))
        }
    }

    fun pdf(report: MoodReport, weekly: List<PeriodAverage>, out: OutputStream) {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val c = page.canvas
        val title = Paint().apply { textSize = 20f; isFakeBoldText = true; isAntiAlias = true }
        val h2 = Paint().apply { textSize = 14f; isFakeBoldText = true; isAntiAlias = true }
        val body = Paint().apply { textSize = 11f; isAntiAlias = true }
        val muted = Paint().apply { textSize = 9f; color = Color.DKGRAY; isAntiAlias = true }
        val bar = Paint().apply { color = Color.rgb(31, 78, 121) }
        val track = Paint().apply { color = Color.rgb(223, 226, 235) }
        val fmt = DateTimeFormatter.ofPattern("d MMM yyyy")
        var y = 50f
        fun line(text: String, p: Paint = body, gap: Float = 16f) { c.drawText(text, 40f, y, p); y += gap }

        line("Mood report", title, 26f)
        line("${report.from.format(fmt)} – ${report.to.format(fmt)}", body, 22f)
        line("Summary", h2, 18f)
        line("Check-ins: ${report.checkIns}    Days missed: ${report.daysMissed}    Current streak: ${report.currentStreak} day(s)")
        line("Average: ${report.average?.let { String.format(Locale.US, "%.2f", it) } ?: "—"} of 5    Trend: " +
            when (report.trend) { TrendDirection.UP -> "rising"; TrendDirection.DOWN -> "falling"; TrendDirection.FLAT -> "steady" })
        report.bestDay?.let { line("Best day: ${it.date.format(fmt)} (${String.format(Locale.US, "%.1f", it.average)})") }
        report.worstDay?.let { line("Lowest day: ${it.date.format(fmt)} (${String.format(Locale.US, "%.1f", it.average)})") }
        y += 8f

        line("Distribution", h2, 18f)
        val maxCount = (report.distribution.values.maxOrNull() ?: 0).coerceAtLeast(1)
        for (level in 5 downTo 1) {
            val n = report.distribution[level] ?: 0
            c.drawText("$level ${MoodScale.label[level]}", 40f, y + 10f, body)
            c.drawRect(150f, y, 450f, y + 12f, track)
            c.drawRect(150f, y, 150f + 300f * n / maxCount, y + 12f, bar)
            c.drawText(n.toString(), 460f, y + 10f, body)
            y += 18f
        }
        y += 8f

        if (report.days.size >= 2) {
            line("Daily average", h2, 12f)
            val top = y
            val height = 110f
            val left = 40f
            val width = 515f
            c.drawRect(left, top, left + width, top + height, track)
            val first = report.from.toEpochDay()
            val span = (report.to.toEpochDay() - first).coerceAtLeast(1)
            var prev: Pair<Float, Float>? = null
            val stroke = Paint(bar).apply { strokeWidth = 2f }
            for (d in report.days) {
                val x = left + width * (d.date.toEpochDay() - first) / span
                val yy = top + height - height * ((d.average - 1) / 4).toFloat()
                prev?.let { c.drawLine(it.first, it.second, x, yy, stroke) }
                c.drawCircle(x, yy, 2.5f, bar)
                prev = x to yy
            }
            y = top + height + 20f
        }

        if (report.tagInsights.isNotEmpty()) {
            line("Tags (observations only, not causes)", h2, 18f)
            for (t in report.tagInsights.take(10)) {
                val without = t.averageWithout?.let { String.format(Locale.US, " vs %.1f without", it) } ?: ""
                line("${t.tag}: ${String.format(Locale.US, "%.1f", t.averageWith)} on ${t.checkIns} check-in(s)$without")
            }
            y += 6f
        }

        if (weekly.isNotEmpty() && y < 760) {
            line("Weekly averages", h2, 18f)
            for (w in weekly.takeLast(8)) {
                if (y > 790) break
                line("Week of ${w.start.format(fmt)}: ${w.average?.let { String.format(Locale.US, "%.1f", it) } ?: "—"} (${w.checkIns} check-ins)")
            }
        }
        c.drawText("Made with ${Brand.NAME}. A self-reflection summary, not medical advice.", 40f, 820f, muted)
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }
}
