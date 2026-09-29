package com.lifevault.vault.store

import com.lifevault.domain.migration.NewerSchemaException
import com.lifevault.vault.storage.VaultFs
import com.lifevault.vault.storage.VaultPath
import java.io.IOException

/** A data file (and its .new/.bak copies) could not be read or verified. */
class CorruptFileException(val file: String, message: String) : IOException(message)

/** Where a read had to recover data from after an interrupted or damaged write. */
enum class RecoverySource { NEW, BACKUP }

data class RecoveryEvent(val file: String, val source: RecoverySource)

/**
 * Crash-safe replace for SAF, which has no atomic rename-over:
 *  1. write `<name>.new`, 2. read it back and verify, 3. `<name>` -> `<name>.bak` (replacing the old .bak),
 *  4. `<name>.new` -> `<name>`.
 * Reads repair every interrupted state:
 *  - `<name>` valid: stray `.new` is deleted (its write never completed from the caller's view).
 *  - `<name>` corrupt: quarantined as `.corrupt`, then `.new` or `.bak` is promoted.
 *  - `<name>` missing: `.new` (crash between steps 3 and 4) or `.bak` is promoted.
 * Callers serialise access per file (see [EncryptedStore]).
 */
class AtomicFiles(private val fs: VaultFs) {

    fun write(path: VaultPath, bytes: ByteArray, verify: (ByteArray) -> Boolean) {
        val new = path.sibling(path.name + NEW)
        val bak = path.sibling(path.name + BAK)
        if (fs.exists(new)) fs.delete(new)
        fs.writeBytes(new, bytes)
        val readBack = fs.readBytes(new)
        if (!readBack.contentEquals(bytes) || !verify(readBack)) {
            fs.delete(new)
            throw IOException("Write verification failed for ${path.relative}")
        }
        if (fs.exists(path)) {
            if (fs.exists(bak)) fs.delete(bak)
            fs.rename(path, bak.name)
        }
        fs.rename(new, path.name)
    }

    /**
     * Returns the decoded content, or null when the file does not exist at all.
     * [decode] returns null (or throws) for content that fails verification.
     */
    fun <T : Any> read(path: VaultPath, onRecovered: (RecoverySource) -> Unit = {}, decode: (ByteArray) -> T?): T? {
        val new = path.sibling(path.name + NEW)
        val bak = path.sibling(path.name + BAK)

        fun attempt(p: VaultPath): T? {
            if (!fs.exists(p)) return null
            return try {
                decode(fs.readBytes(p))
            } catch (e: NewerSchemaException) {
                throw e // readable, just newer: never "repair" (and lose) it
            } catch (e: InvalidVaultException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

        if (fs.exists(path)) {
            attempt(path)?.let { value ->
                if (fs.exists(new)) fs.delete(new)
                return value
            }
            val (source, value) = attempt(new)?.let { RecoverySource.NEW to it }
                ?: attempt(bak)?.let { RecoverySource.BACKUP to it }
                ?: throw CorruptFileException(path.name, "A vault file is damaged and no backup copy could be read.")
            val corrupt = path.sibling(path.name + CORRUPT)
            if (fs.exists(corrupt)) fs.delete(corrupt)
            fs.rename(path, corrupt.name)
            promote(if (source == RecoverySource.NEW) new else bak, path)
            onRecovered(source)
            return value
        }

        attempt(new)?.let { value ->
            promote(new, path)
            return value
        }
        attempt(bak)?.let { value ->
            promote(bak, path)
            onRecovered(RecoverySource.BACKUP)
            return value
        }
        if (fs.exists(new) || fs.exists(bak)) {
            throw CorruptFileException(path.name, "A vault file is missing and its backup copy is damaged.")
        }
        return null
    }

    fun delete(path: VaultPath) {
        for (p in listOf(path, path.sibling(path.name + NEW), path.sibling(path.name + BAK))) {
            if (fs.exists(p)) fs.delete(p)
        }
    }

    private fun promote(from: VaultPath, to: VaultPath) {
        if (from.name.endsWith(BAK)) {
            // Keep the backup: copy it into place rather than moving it.
            fs.writeBytes(to, fs.readBytes(from))
        } else {
            fs.rename(from, to.name)
        }
    }

    companion object {
        const val NEW = ".new"
        const val BAK = ".bak"
        const val CORRUPT = ".corrupt"

        /** True for names that are working copies rather than primary files. */
        fun isAuxiliary(name: String) = name.endsWith(NEW) || name.endsWith(BAK) || name.endsWith(CORRUPT)
        fun baseName(name: String) = name.removeSuffix(NEW).removeSuffix(BAK).removeSuffix(CORRUPT)
    }
}
