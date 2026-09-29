package com.lifevault.domain.model

import com.lifevault.domain.serial.LocalDateTimeSerializer
import com.lifevault.domain.serial.YearMonthSerializer
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.YearMonth

@Serializable
enum class EntryType(val label: String, val amountLabel: String) {
    MONEY("Money", "Amount"),
    FOOD("Food", "Quantity"),
    TRAVEL("Travel", "Distance"),
}

/**
 * One shared model for the money, food and travel trackers.
 *  - MONEY:  [amount] = money spent, [unit] = currency code.
 *  - FOOD:   [amount] = quantity (servings, kcal...), [unit] = unit, [cost] optional.
 *  - TRAVEL: [amount] = distance, [unit] = km/mi, [cost] optional fare or fuel.
 */
@Serializable
data class Entry(
    val id: String,
    val type: EntryType,
    @Serializable(with = LocalDateTimeSerializer::class) val at: LocalDateTime,
    val amount: Double = 0.0,
    val unit: String = "",
    val category: String = "",
    val note: String = "",
    val cost: Double? = null,
    val mediaIds: List<String> = emptyList(),
)

@Serializable
data class EntriesMonth(
    val schemaVersion: Int = CURRENT_SCHEMA,
    @Serializable(with = YearMonthSerializer::class) val month: YearMonth,
    val entries: List<Entry> = emptyList(),
) {
    companion object { const val CURRENT_SCHEMA = 1 }
}
