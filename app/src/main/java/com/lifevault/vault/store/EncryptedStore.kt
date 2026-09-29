package com.lifevault.vault.store

import com.lifevault.domain.migration.VaultSchemas
import com.lifevault.domain.util.VaultJson
import com.lifevault.vault.crypto.VaultKeys
import com.lifevault.vault.storage.VaultDir
import com.lifevault.vault.storage.VaultFs
import com.lifevault.vault.storage.VaultPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap

/**
 * Encrypted JSON documents in `data/`, addressed by logical name (e.g. `attendance-2026-09`).
 *
 *  - On-disk names are HMAC(logical name): nothing about the content is visible in the folder.
 *  - Content is AES-256-GCM (Tink AEAD) with the physical name as associated data, so files cannot be
 *    swapped or renamed without detection.
 *  - Writes are atomic and verified ([AtomicFiles]); one [Mutex] per file serialises writers.
 *  - Every document carries `schemaVersion` and is migrated on read ([VaultSchemas]).
 */
class EncryptedStore(
    private val fs: VaultFs,
    private val keys: VaultKeys,
    private val json: Json = VaultJson,
) {
    private val atomic = AtomicFiles(fs)
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val _recoveries = MutableSharedFlow<RecoveryEvent>(replay = 8, extraBufferCapacity = 16)

    /** Files that had to be restored from `.new`/`.bak` after an interrupted or damaged write. */
    val recoveries: SharedFlow<RecoveryEvent> = _recoveries

    fun physicalName(logical: String): String = keys.nameFor(logical)

    private fun pathOf(logical: String) = VaultPath.of(VaultDir.DATA, physicalName(logical))
    private fun aad(physical: String) = (AAD_PREFIX + physical).toByteArray(Charsets.UTF_8)
    private fun mutex(logical: String) = mutexes.getOrPut(logical) { Mutex() }

    fun exists(logical: String): Boolean {
        val p = pathOf(logical)
        return fs.exists(p) || fs.exists(p.sibling(p.name + AtomicFiles.NEW)) || fs.exists(p.sibling(p.name + AtomicFiles.BAK))
    }

    suspend fun <T> read(logical: String, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        mutex(logical).withLock { readLocked(logical, serializer) }
    }

    suspend fun <T> write(logical: String, serializer: KSerializer<T>, value: T) = withContext(Dispatchers.IO) {
        mutex(logical).withLock { writeLocked(logical, serializer, value) }
    }

    /** Read-modify-write under one lock, so concurrent updates to the same file never lose data. */
    suspend fun <T> update(logical: String, serializer: KSerializer<T>, default: () -> T, transform: (T) -> T): T =
        withContext(Dispatchers.IO) {
            mutex(logical).withLock {
                val current = readLocked(logical, serializer) ?: default()
                val next = transform(current)
                if (next != current) writeLocked(logical, serializer, next)
                next
            }
        }

    suspend fun delete(logical: String) = withContext(Dispatchers.IO) {
        mutex(logical).withLock { atomic.delete(pathOf(logical)) }
    }

    /** Months in [from]..[to] for which a `<prefix>yyyy-MM` file exists. Uses the cached listing only. */
    fun existingMonths(prefix: String, from: YearMonth, to: YearMonth): List<YearMonth> {
        val names = fs.list(VaultDir.DATA).map { AtomicFiles.baseName(it) }.toSet()
        val out = ArrayList<YearMonth>()
        var ym = from
        while (!ym.isAfter(to)) {
            if (physicalName(prefix + ym) in names) out += ym
            ym = ym.plusMonths(1)
        }
        return out
    }

    /**
     * Startup scan: repairs every interrupted write in `data/` (promotes `.new`/`.bak`, removes stray temp copies).
     * Returns the number of files checked.
     */
    suspend fun recoverAll(): Int = withContext(Dispatchers.IO) {
        val bases = fs.list(VaultDir.DATA).map { AtomicFiles.baseName(it) }.toSet()
        for (base in bases) {
            val path = VaultPath.of(VaultDir.DATA, base)
            runCatching {
                atomic.read(path, onRecovered = { _recoveries.tryEmit(RecoveryEvent(base, it)) }) { bytes -> decrypt(base, bytes).also { it.fill(0) } }
            }
        }
        bases.size
    }

    /** Verifies that every data file decrypts; returns the names that do not. Used by backup restore. */
    suspend fun verifyAll(): List<String> = withContext(Dispatchers.IO) {
        fs.list(VaultDir.DATA).filterNot { AtomicFiles.isAuxiliary(it) }.filter { name ->
            runCatching { decrypt(name, fs.readBytes(VaultPath.of(VaultDir.DATA, name))).fill(0) }.isFailure
        }
    }

    private fun <T> readLocked(logical: String, serializer: KSerializer<T>): T? {
        val path = pathOf(logical)
        val migrator = VaultSchemas.migratorFor(logical)
        var migrated = false
        val value = atomic.read(path, onRecovered = { _recoveries.tryEmit(RecoveryEvent(logical, it)) }) { bytes ->
            val plain = decrypt(path.name, bytes)
            try {
                val element = json.parseToJsonElement(plain.toString(Charsets.UTF_8))
                val upgraded = if (element is JsonObject && migrator.needsMigration(element)) {
                    migrated = true
                    migrator.migrate(element)
                } else {
                    element
                }
                Holder(json.decodeFromJsonElement(serializer, upgraded))
            } finally {
                plain.fill(0)
            }
        } ?: return null
        if (migrated) writeLocked(logical, serializer, value.value)
        return value.value
    }

    private fun <T> writeLocked(logical: String, serializer: KSerializer<T>, value: T) {
        val path = pathOf(logical)
        val plain = json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
        try {
            val cipher = keys.aead.encrypt(plain, aad(path.name))
            atomic.write(path, cipher) { readBack ->
                val check = decrypt(path.name, readBack)
                try { json.parseToJsonElement(check.toString(Charsets.UTF_8)); true } finally { check.fill(0) }
            }
        } finally {
            plain.fill(0)
        }
    }

    private fun decrypt(physical: String, bytes: ByteArray): ByteArray = keys.aead.decrypt(bytes, aad(physical))

    /** Boxes nullable generic values so "file missing" (null) differs from a decoded value. */
    private class Holder<T>(val value: T)

    companion object {
        const val AAD_PREFIX = "lifevault/data/v1/"
    }
}
