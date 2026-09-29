package com.lifevault.domain.model

import com.lifevault.domain.serial.LocalDateSerializer
import com.lifevault.domain.serial.YearMonthSerializer
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth

/** Which check-in of the day. With one check-in per day, [DAY] is used. */
@Serializable
enum class MoodSlot(val label: String) { DAY("Today"), MORNING("Morning"), EVENING("Evening") }

@Serializable
data class MoodCheckIn(
    val id: String,
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val slot: MoodSlot = MoodSlot.DAY,
    /** 1 (very low) .. 5 (great). */
    val level: Int,
    val tags: List<String> = emptyList(),
    val note: String = "",
    val updatedAt: Long = 0,
) {
    init { require(level in MoodScale.MIN..MoodScale.MAX) { "Mood level must be 1..5" } }
}

@Serializable
data class MoodMonth(
    val schemaVersion: Int = CURRENT_SCHEMA,
    @Serializable(with = YearMonthSerializer::class) val month: YearMonth,
    val entries: List<MoodCheckIn> = emptyList(),
) {
    companion object { const val CURRENT_SCHEMA = 1 }
}

object MoodScale {
    const val MIN = 1
    const val MAX = 5
    val emoji = mapOf(1 to "😞", 2 to "😕", 3 to "😐", 4 to "🙂", 5 to "😄")
    val label = mapOf(1 to "Very low", 2 to "Low", 3 to "Okay", 4 to "Good", 5 to "Great")
    val defaultTags = listOf("study", "family", "health", "sleep", "money", "friends")
}
