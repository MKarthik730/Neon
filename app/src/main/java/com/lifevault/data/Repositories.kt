package com.lifevault.data

import com.lifevault.vault.store.EncryptedStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap

/**
 * One encrypted document (settings, timetable, rules...) cached in memory while the vault is unlocked.
 * The cache dies with the session when the vault locks.
 */
open class DocRepository<T : Any>(
    private val store: EncryptedStore,
    val logicalName: String,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
) {
    private val state = MutableStateFlow<T?>(null)
    private val loadLock = Mutex()

    /** Emits the current value (loading it on first collection) and every later change. */
    val flow: Flow<T> = state.onStart { ensureLoaded() }.filterNotNull()

    /** Last loaded value without I/O; null before the first load. */
    val cached: T? get() = state.value

    suspend fun get(): T = state.value ?: ensureLoaded()

    private suspend fun ensureLoaded(): T = loadLock.withLock {
        state.value ?: (store.read(logicalName, serializer) ?: default()).also { state.value = it }
    }

    suspend fun update(transform: (T) -> T): T {
        val next = store.update(logicalName, serializer, default, transform)
        state.value = next
        return next
    }

    suspend fun set(value: T) = update { value }

    /** Forces a re-read (e.g. after a restore). */
    suspend fun reload(): T = loadLock.withLock {
        (store.read(logicalName, serializer) ?: default()).also { state.value = it }
    }
}

/**
 * Month-partitioned documents (`<prefix>yyyy-MM`) to keep files small. Loaded months are cached;
 * [revision] increases after every write so UIs can recompute derived views.
 */
open class MonthlyRepository<M : Any>(
    private val store: EncryptedStore,
    private val prefix: String,
    private val serializer: KSerializer<M>,
    private val empty: (YearMonth) -> M,
) {
    private val cache = ConcurrentHashMap<YearMonth, M>()
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private fun name(ym: YearMonth) = prefix + ym.toString()

    suspend fun month(ym: YearMonth): M =
        cache[ym] ?: (store.read(name(ym), serializer) ?: empty(ym)).also { cache[ym] = it }

    /** All months in [from]..[to]; months without a file come back empty without any I/O. */
    suspend fun months(from: YearMonth, to: YearMonth): List<M> {
        val existing = store.existingMonths(prefix, from, to).toSet()
        val out = ArrayList<M>()
        var ym = from
        while (!ym.isAfter(to)) {
            out += if (ym in existing || cache.containsKey(ym)) month(ym) else empty(ym)
            ym = ym.plusMonths(1)
        }
        return out
    }

    /** Every month that has a file, oldest first. */
    fun existingMonths(from: YearMonth = EARLIEST, to: YearMonth = YearMonth.now().plusYears(2)): List<YearMonth> =
        (store.existingMonths(prefix, from, to) + cache.keys).distinct().sorted()

    suspend fun update(ym: YearMonth, transform: (M) -> M): M {
        val next = store.update(name(ym), serializer, { empty(ym) }, transform)
        cache[ym] = next
        _revision.value++
        return next
    }

    fun invalidate() {
        cache.clear()
        _revision.value++
    }

    companion object {
        val EARLIEST: YearMonth = YearMonth.of(2000, 1)
    }
}
