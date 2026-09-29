package com.lifevault.domain.model

import com.lifevault.domain.serial.DayOfWeekSerializer
import com.lifevault.domain.serial.LocalDateSerializer
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate

/** A holiday covering [start]..[end] inclusive (a single day when they are equal). */
@Serializable
data class Holiday(
    val id: String,
    @Serializable(with = LocalDateSerializer::class) val start: LocalDate,
    @Serializable(with = LocalDateSerializer::class) val end: LocalDate,
    val label: String,
) {
    fun contains(date: LocalDate) = !date.isBefore(start) && !date.isAfter(end)
}

@Serializable
data class HolidaysFile(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val holidays: List<Holiday> = emptyList(),
    val weeklyOffDays: List<@Serializable(with = DayOfWeekSerializer::class) DayOfWeek> = listOf(DayOfWeek.SUNDAY),
) {
    companion object { const val CURRENT_SCHEMA = 1 }

    fun isOffDay(date: LocalDate) = date.dayOfWeek in weeklyOffDays
    fun holidayOn(date: LocalDate): Holiday? = holidays.firstOrNull { it.contains(date) }

    /** True when no attendance is expected on [date] (weekly off-day or holiday). */
    fun isNonWorking(date: LocalDate) = isOffDay(date) || holidayOn(date) != null
}
