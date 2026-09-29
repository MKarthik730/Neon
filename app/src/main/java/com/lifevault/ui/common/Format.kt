package com.lifevault.ui.common

import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

object Format {
    private val dateFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
    private val shortDate = DateTimeFormatter.ofPattern("d MMM")
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy")

    fun date(d: LocalDate): String = d.format(dateFmt)
    fun shortDate(d: LocalDate): String = d.format(shortDate)
    fun day(d: LocalDate): String = d.format(dayFmt)
    fun time(t: LocalTime): String = t.format(timeFmt)
    fun dateTime(dt: LocalDateTime): String = "${shortDate(dt.toLocalDate())}, ${time(dt.toLocalTime())}"
    fun month(d: LocalDate): String = d.format(monthFmt)

    fun percent(p: Double?): String = p?.let { String.format(Locale.getDefault(), "%.1f%%", it) } ?: "—"

    fun money(amount: Double, currency: String): String = runCatching {
        NumberFormat.getCurrencyInstance().apply { this.currency = Currency.getInstance(currency) }.format(amount)
    }.getOrElse { String.format(Locale.getDefault(), "%.2f %s", amount, currency) }

    fun number(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else String.format(Locale.getDefault(), "%.2f", v)

    fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    fun minutes(m: Long): String = if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"

    fun bytes(b: Long): String = when {
        b < 0 -> "?"
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", b / 1024.0)
        else -> String.format(Locale.getDefault(), "%.1f MB", b / (1024.0 * 1024))
    }
}
