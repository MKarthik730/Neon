package com.lifevault.domain.model

import com.lifevault.domain.serial.DayOfWeekSerializer
import com.lifevault.domain.serial.LocalDateTimeSerializer
import com.lifevault.domain.serial.LocalTimeSerializer
import com.lifevault.domain.serial.YearMonthSerializer
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

@Serializable
data class Rule(
    val id: String,
    val text: String,
    val pinned: Boolean = false,
    val archived: Boolean = false,
)

/** A named, ordered list of the user's own rules, e.g. "Before studying". The app ships with none. */
@Serializable
data class RuleSet(
    val id: String,
    val name: String,
    val rules: List<Rule> = emptyList(),
    val archived: Boolean = false,
)

/** A recurring "read your rules" reminder that opens a rule set before an activity. */
@Serializable
data class RuleReminder(
    val id: String,
    val ruleSetId: String,
    val label: String,
    @Serializable(with = LocalTimeSerializer::class) val time: LocalTime,
    val days: List<@Serializable(with = DayOfWeekSerializer::class) DayOfWeek> = DayOfWeek.entries.toList(),
    val enabled: Boolean = true,
)

@Serializable
data class RulesFile(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val sets: List<RuleSet> = emptyList(),
    val reminders: List<RuleReminder> = emptyList(),
) {
    companion object { const val CURRENT_SCHEMA = 1 }
}

@Serializable
data class JobLogEntry(
    val id: String,
    val jobName: String,
    val ruleSetId: String?,
    val ruleSetName: String = "",
    @Serializable(with = LocalDateTimeSerializer::class) val startedAt: LocalDateTime,
    @Serializable(with = LocalDateTimeSerializer::class) val endedAt: LocalDateTime? = null,
    /** Ids of the active rules shown before the job. */
    val rulesShown: List<String> = emptyList(),
    /** Ids of the rules the user ticked. */
    val rulesTicked: List<String> = emptyList(),
    /** True when the rules were read (all ticked, or "I've read them" confirmed) before Begin. */
    val rulesRead: Boolean = false,
    val reflection: String = "",
)

@Serializable
data class JobLogMonth(
    val schemaVersion: Int = CURRENT_SCHEMA,
    @Serializable(with = YearMonthSerializer::class) val month: YearMonth,
    val entries: List<JobLogEntry> = emptyList(),
) {
    companion object { const val CURRENT_SCHEMA = 1 }
}
