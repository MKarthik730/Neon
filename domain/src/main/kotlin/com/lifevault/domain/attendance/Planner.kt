package com.lifevault.domain.attendance

/**
 * Attendance planner, with p = present, t = conducted (marked) sessions and threshold r = thresholdPercent / 100.
 *
 *  - Sessions you can still skip while staying >= r:        floor(p / r - t), min 0
 *  - Consecutive sessions to attend to recover to >= r:      ceil((r*t - p) / (1 - r)), min 0
 *
 * Integer arithmetic on the percentage avoids floating-point error at exact thresholds
 * (e.g. 3/4 at 75% must give "skip 0" and "attend 0", not an off-by-one).
 */
object Planner {

    fun canSkip(present: Int, conducted: Int, thresholdPercent: Int): Int {
        requireInputs(present, conducted, thresholdPercent)
        // floor(p*100/R - t) = floor((100p - R t) / R)
        val numerator = 100L * present - thresholdPercent.toLong() * conducted
        if (numerator <= 0) return 0
        return (numerator / thresholdPercent).toInt()
    }

    /**
     * Returns the number of consecutive sessions needed, or null when the threshold can never be reached
     * again (only possible with a 100% threshold after any absence).
     */
    fun mustAttend(present: Int, conducted: Int, thresholdPercent: Int): Int? {
        requireInputs(present, conducted, thresholdPercent)
        // ceil((R t - 100 p) / (100 - R))
        val numerator = thresholdPercent.toLong() * conducted - 100L * present
        if (numerator <= 0) return 0
        val denominator = 100L - thresholdPercent
        if (denominator == 0L) return null
        return ((numerator + denominator - 1) / denominator).toInt()
    }

    fun forTally(tally: Tally, thresholdPercent: Int): PlannerResult = PlannerResult(
        canSkip = canSkip(tally.present, tally.marked, thresholdPercent),
        mustAttend = mustAttend(tally.present, tally.marked, thresholdPercent),
        unmarked = tally.pending,
    )

    private fun requireInputs(present: Int, conducted: Int, thresholdPercent: Int) {
        require(thresholdPercent in 1..100) { "Threshold must be 1..100%" }
        require(present >= 0 && conducted >= 0) { "Counts must be non-negative" }
        require(present <= conducted) { "Present ($present) cannot exceed conducted ($conducted)" }
    }
}

/** Planner output. [unmarked] pending sessions are not included in the maths and are shown separately. */
data class PlannerResult(val canSkip: Int, val mustAttend: Int?, val unmarked: Int)
