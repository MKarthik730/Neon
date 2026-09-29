package com.lifevault.domain.model

import com.lifevault.domain.serial.LocalDateSerializer
import com.lifevault.domain.serial.LocalTimeSerializer
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Serializable
enum class EventKind(val label: String) { CLASS("Class"), TEST("Test"), CUSTOM("Event") }

@Serializable
data class Event(
    val id: String,
    val kind: EventKind = EventKind.CUSTOM,
    val title: String,
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    @Serializable(with = LocalTimeSerializer::class) val time: LocalTime? = null,
    val notes: String = "",
    val subject: String? = null,
    /** Minutes before the event to remind; null = no reminder. */
    val reminderMinutesBefore: Int? = null,
    val mediaIds: List<String> = emptyList(),
    /** Rule set to read right before this event, if any. */
    val ruleSetId: String? = null,
    val rulesPromptMinutesBefore: Int = 10,
) {
    /** Start moment; all-day events are treated as starting at 09:00 for reminders. */
    fun startsAt(): LocalDateTime = date.atTime(time ?: DEFAULT_ALL_DAY_TIME)

    companion object { val DEFAULT_ALL_DAY_TIME: LocalTime = LocalTime.of(9, 0) }
}

@Serializable
data class EventsFile(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val events: List<Event> = emptyList(),
) {
    companion object { const val CURRENT_SCHEMA = 1 }
}
