package com.lifevault.vault

/** Persisted failed-attempt counters (app-private, non-sensitive). */
interface AttemptStore {
    var failures: Int
    var lastFailureAt: Long
}

/**
 * Exponential delay after repeated failed unlock attempts: the first [FREE_ATTEMPTS] failures cost nothing
 * extra (Argon2 already takes ~0.4 s), then 5 s, 10 s, 20 s ... capped at 15 minutes. Counters survive
 * app restarts, and a clock moved backwards does not shorten the wait.
 */
class BruteForceGuard(private val store: AttemptStore, private val clock: () -> Long = System::currentTimeMillis) {

    /** Milliseconds until the next attempt is allowed (0 = now). */
    fun remainingDelay(): Long {
        val delay = delayFor(store.failures)
        if (delay == 0L) return 0
        val now = clock()
        val last = store.lastFailureAt
        if (now < last) return delay // clock went backwards: make the user wait the full delay
        return (last + delay - now).coerceAtLeast(0)
    }

    fun recordFailure() {
        store.failures = store.failures + 1
        store.lastFailureAt = clock()
    }

    fun recordSuccess() {
        store.failures = 0
        store.lastFailureAt = 0
    }

    companion object {
        const val FREE_ATTEMPTS = 3
        const val BASE_DELAY_MS = 5_000L
        const val MAX_DELAY_MS = 15 * 60_000L

        fun delayFor(failures: Int): Long {
            if (failures <= FREE_ATTEMPTS) return 0
            val exp = (failures - FREE_ATTEMPTS - 1).coerceAtMost(20)
            return (BASE_DELAY_MS shl exp).coerceAtMost(MAX_DELAY_MS)
        }
    }
}
