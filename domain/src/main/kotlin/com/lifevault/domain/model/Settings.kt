package com.lifevault.domain.model

import com.lifevault.domain.serial.LocalDateSerializer
import com.lifevault.domain.serial.LocalTimeSerializer
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime

@Serializable
data class SecuritySettings(
    /** Lock after this many minutes without interaction. 0 disables the inactivity timer. */
    val autoLockMinutes: Int = 5,
    /** Lock as soon as the app leaves the foreground. */
    val lockOnBackground: Boolean = true,
)

@Serializable
data class AttendanceSettings(
    val countingMode: CountingMode = CountingMode.PER_SESSION,
    /** Required attendance, in percent (1..100). */
    val thresholdPercent: Int = 75,
    val promptsEnabled: Boolean = true,
    /** Minutes after a session ends to post "Mark attendance". */
    val promptDelayMinutes: Int = 10,
)

@Serializable
data class MoodSettings(
    val twoPerDay: Boolean = false,
    val tags: List<String> = MoodScale.defaultTags,
    val gentleMessageEnabled: Boolean = true,
    /** A check-in at or below this level counts as "low". */
    val lowLevel: Int = 2,
    /** Show the gentle message after this many consecutive low check-ins. */
    val lowRunLength: Int = 5,
    val reminderEnabled: Boolean = false,
    @Serializable(with = LocalTimeSerializer::class) val reminderTime: LocalTime = LocalTime.of(20, 30),
    val reminderSkipsHolidays: Boolean = false,
)

@Serializable
data class RulesSettings(
    /** Every rule must be ticked before Begin unlocks; when false one "I've read them" tap is enough. */
    val requireTickAll: Boolean = true,
    /** Seconds the rules stay on screen before Begin can unlock. 0 disables the countdown. */
    val countdownSeconds: Int = 5,
    val jobTimerEnabled: Boolean = true,
    /** Prompt before events that link a rule set. */
    val promptBeforeLinkedEvents: Boolean = true,
)

@Serializable
data class NotificationSettings(
    val eventRemindersEnabled: Boolean = true,
    val defaultEventReminderMinutes: Int = 30,
    val dailySummaryEnabled: Boolean = false,
    @Serializable(with = LocalTimeSerializer::class) val dailySummaryTime: LocalTime = LocalTime.of(21, 0),
)

@Serializable
data class TrackerSettings(
    val currency: String = "INR",
    val distanceUnit: String = "km",
    val moneyCategories: List<String> = listOf("Food", "Transport", "Books", "Bills", "Shopping", "Fun", "Other"),
    val foodCategories: List<String> = listOf("Breakfast", "Lunch", "Dinner", "Snack", "Drink"),
    val travelCategories: List<String> = listOf("Bus", "Train", "Auto/Taxi", "Bike", "Walk", "Other"),
) {
    fun categoriesFor(type: EntryType) = when (type) {
        EntryType.MONEY -> moneyCategories
        EntryType.FOOD -> foodCategories
        EntryType.TRAVEL -> travelCategories
    }

    fun defaultUnit(type: EntryType) = when (type) {
        EntryType.MONEY -> currency
        EntryType.FOOD -> "serving"
        EntryType.TRAVEL -> distanceUnit
    }
}

@Serializable
data class Settings(
    val schemaVersion: Int = CURRENT_SCHEMA,
    @Serializable(with = LocalDateSerializer::class) val vaultCreatedOn: LocalDate = LocalDate.of(2026, 1, 1),
    val security: SecuritySettings = SecuritySettings(),
    val attendance: AttendanceSettings = AttendanceSettings(),
    val mood: MoodSettings = MoodSettings(),
    val rules: RulesSettings = RulesSettings(),
    val notifications: NotificationSettings = NotificationSettings(),
    val trackers: TrackerSettings = TrackerSettings(),
) {
    companion object { const val CURRENT_SCHEMA = 1 }
}
