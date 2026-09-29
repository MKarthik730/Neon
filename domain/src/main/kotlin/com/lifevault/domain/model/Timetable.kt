package com.lifevault.domain.model

import com.lifevault.domain.serial.DayOfWeekSerializer
import com.lifevault.domain.serial.LocalDateSerializer
import com.lifevault.domain.serial.LocalTimeSerializer
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** The two attendance sessions of a day. */
@Serializable
enum class Session(val label: String) {
    MORNING("Morning"),
    EVENING("Evening"),
}

/** One session on one weekday, e.g. Monday Morning 09:00-12:30, Maths + Physics. */
@Serializable
data class TimetableSlot(
    @Serializable(with = DayOfWeekSerializer::class) val day: DayOfWeek,
    val session: Session,
    @Serializable(with = LocalTimeSerializer::class) val start: LocalTime,
    @Serializable(with = LocalTimeSerializer::class) val end: LocalTime,
    val subjects: List<String>,
)

/**
 * A weekly timetable that applies between [effectiveFrom] and [effectiveTo] (inclusive, open-ended when null).
 * Adding a new version for a new semester never rewrites attendance recorded under an older version.
 */
@Serializable
data class TimetableVersion(
    val id: String,
    val name: String,
    @Serializable(with = LocalDateSerializer::class) val effectiveFrom: LocalDate,
    @Serializable(with = LocalDateSerializer::class) val effectiveTo: LocalDate? = null,
    val slots: List<TimetableSlot> = emptyList(),
) {
    fun covers(date: LocalDate): Boolean =
        !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo))

    fun slot(day: DayOfWeek, session: Session): TimetableSlot? =
        slots.firstOrNull { it.day == day && it.session == session }
}

@Serializable
data class TimetableFile(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val versions: List<TimetableVersion> = emptyList(),
) {
    companion object { const val CURRENT_SCHEMA = 1 }

    /**
     * The version in force on [date]. When ranges overlap, the one that started most recently wins;
     * between versions starting the same day, the one added last wins (a same-day re-import replaces).
     */
    fun versionOn(date: LocalDate): TimetableVersion? =
        versions.asReversed().filter { it.covers(date) }.maxByOrNull { it.effectiveFrom }

    /** Every subject mentioned by any version, sorted case-insensitively. */
    fun allSubjects(): List<String> =
        versions.flatMap { v -> v.slots.flatMap { it.subjects } }
            .map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }

    /** Earliest date any version applies from, or null when there is no timetable. */
    fun earliestStart(): LocalDate? = versions.minOfOrNull { it.effectiveFrom }
}
