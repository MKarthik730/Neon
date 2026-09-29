package com.lifevault.domain.model

import com.lifevault.domain.serial.LocalDateSerializer
import com.lifevault.domain.serial.YearMonthSerializer
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth

/**
 * Stored status of a session. "Pending" is never stored: a scheduled session without a record is pending.
 * Cancelled sessions are excluded from every total.
 */
@Serializable
enum class AttendanceStatus(val label: String) {
    PRESENT("Present"),
    ABSENT("Absent"),
    CANCELLED("Cancelled"),
}

@Serializable
data class AttendanceRecord(
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val session: Session,
    val status: AttendanceStatus,
    /** Subjects taught in this session when it was marked, so a per-subject view can be derived later. */
    val subjects: List<String> = emptyList(),
    val updatedAt: Long = 0,
)

@Serializable
data class AttendanceMonth(
    val schemaVersion: Int = CURRENT_SCHEMA,
    @Serializable(with = YearMonthSerializer::class) val month: YearMonth,
    val records: List<AttendanceRecord> = emptyList(),
) {
    companion object { const val CURRENT_SCHEMA = 1 }

    fun upsert(record: AttendanceRecord): AttendanceMonth =
        copy(records = records.filterNot { it.date == record.date && it.session == record.session } + record)

    fun remove(date: LocalDate, session: Session): AttendanceMonth =
        copy(records = records.filterNot { it.date == date && it.session == session })
}

/** Headline counting mode. Records always store subjects so either view can be derived. */
@Serializable
enum class CountingMode(val label: String) {
    PER_SESSION("Per session (morning/evening)"),
    PER_SUBJECT("Per subject"),
}
