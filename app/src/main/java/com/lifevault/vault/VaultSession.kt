package com.lifevault.vault

import com.lifevault.data.AttendanceRepository
import com.lifevault.data.EntryRepository
import com.lifevault.data.EventRepository
import com.lifevault.data.HolidayRepository
import com.lifevault.data.JobLogRepository
import com.lifevault.data.MediaIndexRepository
import com.lifevault.data.MoodRepository
import com.lifevault.data.RulesRepository
import com.lifevault.data.SettingsRepository
import com.lifevault.data.StudyRepository
import com.lifevault.data.TimetableRepository
import com.lifevault.vault.crypto.VaultHeader
import com.lifevault.vault.crypto.VaultKeys
import com.lifevault.vault.storage.VaultDir
import com.lifevault.vault.storage.VaultFs
import com.lifevault.vault.store.BlobStore
import com.lifevault.vault.store.EncryptedStore
import com.lifevault.vault.store.HeaderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Everything that exists only while the vault is unlocked: keys, stores, repositories and their
 * in-memory caches. [close] (on lock) runs registered hooks (stop playback, clear bitmap caches...),
 * cancels all session work, wipes `.tmp/` and destroys the keys.
 */
class VaultSession(
    val fs: VaultFs,
    header: VaultHeader,
    val keys: VaultKeys,
) {
    val id: String = UUID.randomUUID().toString()

    @Volatile var header: VaultHeader = header
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = EncryptedStore(fs, keys)
    val blobs = BlobStore(fs, keys)
    val headerStore = HeaderStore(fs)

    val settings = SettingsRepository(store)
    val timetable = TimetableRepository(store)
    val holidays = HolidayRepository(store)
    val attendance = AttendanceRepository(store)
    val entries = EntryRepository(store)
    val mood = MoodRepository(store)
    val rules = RulesRepository(store)
    val jobLog = JobLogRepository(store)
    val events = EventRepository(store)
    val study = StudyRepository(store)
    val media = MediaIndexRepository(store)

    private val closeHooks = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var isClosed = false
        private set

    /** Registers work to run when the vault locks (e.g. release the player, clear caches). */
    fun onClose(hook: () -> Unit) {
        if (isClosed) hook() else closeHooks += hook
    }

    fun replaceHeader(newHeader: VaultHeader) {
        headerStore.write(newHeader)
        header = newHeader
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        for (hook in closeHooks.reversed()) runCatching { hook() }
        closeHooks.clear()
        scope.cancel()
        runCatching { fs.wipe(VaultDir.TMP) }
        keys.destroy()
    }
}
