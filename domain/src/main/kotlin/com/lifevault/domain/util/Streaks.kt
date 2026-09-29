package com.lifevault.domain.util

import java.time.LocalDate

object Streaks {
    /**
     * Number of consecutive days ending today that are in [days]. If today is not in the set yet,
     * the streak is still "alive" and is counted up to yesterday.
     */
    fun current(days: Set<LocalDate>, today: LocalDate): Int {
        var cursor = if (today in days) today else today.minusDays(1)
        var count = 0
        while (cursor in days) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    /** Longest run of consecutive days in [days]. */
    fun longest(days: Set<LocalDate>): Int {
        var best = 0
        for (d in days) {
            if (d.minusDays(1) in days) continue
            var len = 0
            var c = d
            while (c in days) { len++; c = c.plusDays(1) }
            if (len > best) best = len
        }
        return best
    }
}
