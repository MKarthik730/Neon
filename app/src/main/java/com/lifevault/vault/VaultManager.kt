package com.lifevault.vault

import com.lifevault.Brand
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lifevault.backup.BackupManager
import com.lifevault.domain.model.Settings
import com.lifevault.domain.model.TrackerSettings
import com.lifevault.notifications.PendingActionsQueue
import com.lifevault.notifications.PendingMerge
import com.lifevault.vault.crypto.Argon2Kdf
import com.lifevault.vault.crypto.VaultCrypto
import com.lifevault.vault.crypto.VaultHeader
import com.lifevault.vault.crypto.VaultKeys
import com.lifevault.vault.crypto.WrongSecretException
import com.lifevault.vault.storage.SafVaultFs
import com.lifevault.vault.storage.VaultDir
import com.lifevault.vault.storage.VaultFolderUnavailableException
import com.lifevault.vault.store.CorruptFileException
import com.lifevault.vault.store.HeaderStore
import com.lifevault.vault.store.InvalidVaultException
import com.lifevault.vault.store.RecoverySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Currency
import java.util.Locale
import javax.crypto.Cipher

sealed interface VaultState {
    data object Loading : VaultState
    data object NoFolder : VaultState
    data class FolderError(val message: String) : VaultState
    /** Folder chosen but it holds no vault yet. [hasOtherFiles] warns before creating one in a used folder. */
    data class NeedsSetup(val folderName: String, val hasOtherFiles: Boolean) : VaultState
    data class Locked(val folderName: String, val biometricEnabled: Boolean) : VaultState
    data class Unlocked(val graph: SessionGraph, val recoveryCodeToConfirm: String? = null) : VaultState
}

sealed interface UnlockResult {
    data object Success : UnlockResult
    data class Wrong(val retryInMs: Long) : UnlockResult
    data class TooSoon(val retryInMs: Long) : UnlockResult
    data class Failed(val message: String) : UnlockResult
}

/** Session-scoped objects that need Android (media, backup, alarm sync). Built by the app container. */
interface SessionGraph {
    val session: VaultSession
}

/**
 * Owns the vault lifecycle: folder selection (SAF, persisted permission), creation, unlock (passphrase,
 * recovery code, biometric), lock, passphrase change and recovery-code rotation.
 */
class VaultManager(
    private val context: Context,
    private val prefs: DevicePrefs,
    val biometrics: BiometricKeys,
    private val pendingQueue: PendingActionsQueue,
    private val appScope: CoroutineScope,
    private val graphFactory: (VaultSession) -> SessionGraph,
) {
    private val _state = MutableStateFlow<VaultState>(VaultState.Loading)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** One-off notices for the UI (recovered files, merged notification marks...). */
    val messages: SharedFlow<String> = _messages

    private val guard = BruteForceGuard(prefs)
    private val mutex = Mutex()
    private var fs: SafVaultFs? = null
    private var header: VaultHeader? = null

    val folderName: String? get() = fs?.displayName
    val vaultId: String? get() = header?.vaultId
    val currentSession: VaultSession? get() = (state.value as? VaultState.Unlocked)?.graph?.session

    fun init() {
        appScope.launch {
            val saved = prefs.treeUri
            if (saved == null) _state.value = VaultState.NoFolder else attach(Uri.parse(saved), fromPicker = false)
        }
    }

    /** Called with the tree URI returned by ACTION_OPEN_DOCUMENT_TREE. */
    suspend fun chooseFolder(uri: Uri) = attach(uri, fromPicker = true)

    private suspend fun attach(uri: Uri, fromPicker: Boolean) = mutex.withLock {
        lockLocked()
        _state.value = withContext(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                if (fromPicker) {
                    resolver.takePersistableUriPermission(uri, flags)
                    // Hold permission to exactly one folder.
                    for (p in resolver.persistedUriPermissions) {
                        if (p.uri != uri) runCatching { resolver.releasePersistableUriPermission(p.uri, flags) }
                    }
                } else if (resolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission && it.isWritePermission }) {
                    return@withContext VaultState.FolderError("${Brand.NAME} lost permission to the vault folder. Choose the folder again.")
                }
                val newFs = SafVaultFs(context, uri)
                newFs.checkAccessible()
                BackupManager.resumeInterruptedRestore(newFs)
                runCatching { newFs.wipe(VaultDir.TMP) }
                fs = newFs
                prefs.treeUri = uri.toString()
                val h = HeaderStore(newFs).read()
                header = h
                if (h == null) {
                    val others = newFs.list(VaultDir.ROOT).isNotEmpty() || VaultDir.subdirs.any { newFs.list(it).isNotEmpty() }
                    VaultState.NeedsSetup(newFs.displayName, others)
                } else {
                    if (prefs.vaultId != h.vaultId) {
                        biometrics.disable(prefs.vaultId)
                        prefs.vaultId = h.vaultId
                    }
                    VaultState.Locked(newFs.displayName, biometrics.isEnabled(h.vaultId))
                }
            } catch (e: VaultFolderUnavailableException) {
                VaultState.FolderError(e.message ?: "The vault folder is unavailable.")
            } catch (e: InvalidVaultException) {
                VaultState.FolderError(e.message ?: "This folder does not contain a ${Brand.NAME} vault.")
            } catch (e: CorruptFileException) {
                VaultState.FolderError("vault.json is damaged and no backup copy could be read.")
            } catch (e: SecurityException) {
                VaultState.FolderError("${Brand.NAME} cannot access that folder. Choose another one.")
            } catch (e: Exception) {
                VaultState.FolderError("Could not open the vault folder: ${e.message}")
            }
        }
    }

    /** Creates a new vault in the chosen empty folder. Returns the recovery code to show once. */
    suspend fun create(passphrase: String): String = inAppScope { createLocked(passphrase) }

    private suspend fun createLocked(passphrase: String): String = mutex.withLock {
        val f = fs ?: throw IllegalStateException("No folder chosen")
        require(passphrase.length >= VaultCrypto.MIN_PASSPHRASE_LENGTH) { "Passphrase too short" }
        val created = withContext(Dispatchers.Default) { VaultCrypto.create(passphrase, Argon2Kdf.calibrate()) }
        withContext(Dispatchers.IO) {
            f.ensureLayout()
            HeaderStore(f).write(created.header)
        }
        header = created.header
        biometrics.disable(prefs.vaultId)
        prefs.vaultId = created.header.vaultId
        guard.recordSuccess()
        openSession(f, created.header, created.keys, isNew = true, recoveryCode = created.recoveryCode)
        created.recoveryCode
    }

    fun confirmRecoveryCodeSaved() {
        val s = state.value as? VaultState.Unlocked ?: return
        _state.value = s.copy(recoveryCodeToConfirm = null)
    }

    /** Milliseconds until another unlock attempt is allowed. */
    fun retryDelay(): Long = guard.remainingDelay()

    suspend fun unlock(passphrase: String): UnlockResult = inAppScope { unlockWith { h -> VaultCrypto.unlockWithPassphrase(h, passphrase) } }

    /** Unlocks with the recovery code and immediately sets a new passphrase. */
    suspend fun recover(code: String, newPassphrase: String): UnlockResult {
        require(newPassphrase.length >= VaultCrypto.MIN_PASSPHRASE_LENGTH) { "Passphrase too short" }
        return inAppScope {
            unlockWith(afterUnlock = { keys, h ->
            val newHeader = VaultCrypto.rewrapPassphrase(h, keys, newPassphrase, Argon2Kdf.calibrate())
            HeaderStore(fs!!).write(newHeader)
            header = newHeader
            biometrics.disable(h.vaultId) // re-enrol after recovery
            newHeader
            }) { h -> VaultCrypto.unlockWithRecovery(h, code) }
        }
    }

    /** [cipher] must have been authenticated by BiometricPrompt. */
    suspend fun unlockWithBiometric(cipher: Cipher): UnlockResult = inAppScope {
        unlockWith { h -> VaultKeys.fromMaterial(biometrics.unwrap(h.vaultId, cipher)) }
    }

    /**
     * Runs [block] in the app scope and waits for it. If the calling screen is disposed (e.g. because the
     * vault state just changed), the operation still completes instead of being cancelled half-way.
     */
    private suspend fun <T> inAppScope(block: suspend () -> T): T = appScope.async { block() }.await()

    private suspend fun unlockWith(
        afterUnlock: (suspend (VaultKeys, VaultHeader) -> VaultHeader)? = null,
        derive: (VaultHeader) -> VaultKeys,
    ): UnlockResult = mutex.withLock {
        val f = fs ?: return UnlockResult.Failed("No vault folder")
        val h = header ?: return UnlockResult.Failed("No vault in this folder")
        val wait = guard.remainingDelay()
        if (wait > 0) return UnlockResult.TooSoon(wait)
        val keys = try {
            withContext(Dispatchers.Default) { derive(h) }
        } catch (e: WrongSecretException) {
            guard.recordFailure()
            return UnlockResult.Wrong(guard.remainingDelay())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return UnlockResult.Failed(e.message ?: "Unlock failed")
        }
        guard.recordSuccess()
        return try {
            val finalHeader = afterUnlock?.let { withContext(Dispatchers.Default) { it(keys, h) } } ?: h
            withContext(Dispatchers.IO) { f.checkAccessible() }
            openSession(f, finalHeader, keys, isNew = false, recoveryCode = null)
            UnlockResult.Success
        } catch (e: VaultFolderUnavailableException) {
            keys.destroy()
            _state.value = VaultState.FolderError(e.message ?: "The vault folder is unavailable.")
            UnlockResult.Failed(e.message ?: "The vault folder is unavailable.")
        } catch (e: Exception) {
            keys.destroy()
            if (e is CancellationException) throw e
            UnlockResult.Failed(e.message ?: "Could not open the vault")
        }
    }

    private suspend fun openSession(f: SafVaultFs, h: VaultHeader, keys: VaultKeys, isNew: Boolean, recoveryCode: String?) {
        val session = withContext(Dispatchers.IO) {
            f.refresh()
            f.ensureLayout()
            val s = VaultSession(f, h, keys)
            s.store.recoverAll()
            if (isNew) {
                s.settings.set(Settings(vaultCreatedOn = LocalDate.now(), trackers = TrackerSettings(currency = localCurrency())))
            } else {
                s.settings.get()
            }
            s
        }
        session.scope.launch {
            session.store.recoveries.collect { ev ->
                _messages.tryEmit(
                    if (ev.source == RecoverySource.BACKUP) "A damaged file was restored from its backup copy."
                    else "An interrupted save was completed.",
                )
            }
        }
        val graph = graphFactory(session)
        _state.value = VaultState.Unlocked(graph, recoveryCode)
        // Session-scoped: survives the unlock screen leaving composition, stops when the vault locks.
        session.scope.launch { mergePendingActions(session) }
    }

    /** Locks now: stops session work, wipes `.tmp/`, destroys keys and drops every cache. */
    fun lock() {
        appScope.launch { mutex.withLock { lockLocked() } }
    }

    private fun lockLocked() {
        val unlocked = _state.value as? VaultState.Unlocked ?: return
        val f = fs
        _state.value = VaultState.Locked(f?.displayName ?: "Vault", header?.let { biometrics.isEnabled(it.vaultId) } ?: false)
        unlocked.graph.session.close()
    }

    suspend fun changePassphrase(current: String, new: String): Boolean = mutex.withLock {
        val session = currentSession ?: return false
        require(new.length >= VaultCrypto.MIN_PASSPHRASE_LENGTH)
        val h = session.header
        val ok = withContext(Dispatchers.Default) {
            try { VaultCrypto.unlockWithPassphrase(h, current).destroy(); true } catch (e: WrongSecretException) { false }
        }
        if (!ok) return false
        val newHeader = withContext(Dispatchers.Default) { VaultCrypto.rewrapPassphrase(h, session.keys, new, Argon2Kdf.calibrate()) }
        withContext(Dispatchers.IO) { session.replaceHeader(newHeader) }
        header = newHeader
        true
    }

    suspend fun rotateRecoveryCode(): String? = mutex.withLock {
        val session = currentSession ?: return null
        val (newHeader, code) = withContext(Dispatchers.Default) { VaultCrypto.rotateRecovery(session.header, session.keys, Argon2Kdf.calibrate()) }
        withContext(Dispatchers.IO) { session.replaceHeader(newHeader) }
        header = newHeader
        code
    }

    /** [cipher] must have been authenticated by BiometricPrompt (encrypt mode). */
    suspend fun enableBiometric(cipher: Cipher) {
        val session = currentSession ?: return
        withContext(Dispatchers.Default) { biometrics.store(session.header.vaultId, cipher, session.keys.exportMaterial()) }
    }

    fun disableBiometric() = biometrics.disable(header?.vaultId)

    fun forgetFolder() {
        appScope.launch {
            mutex.withLock {
                lockLocked()
                val uri = prefs.treeUri
                if (uri != null) {
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                }
                biometrics.disable(prefs.vaultId)
                prefs.treeUri = null
                prefs.vaultId = null
                fs = null
                header = null
                _state.value = VaultState.NoFolder
            }
        }
    }

    /** After a restore replaced the vault's contents: lock, then re-read vault.json (runs in the app scope). */
    fun reloadAfterRestore() {
        appScope.launch {
            val uri = prefs.treeUri ?: return@launch
            attach(Uri.parse(uri), fromPicker = false)
        }
    }

    fun mergePendingActionsIfUnlocked() {
        val session = currentSession ?: return
        session.scope.launch { mergePendingActions(session) }
    }

    /** Applies Present/Absent taps made from notifications while locked, then clears them from the queue. */
    private suspend fun mergePendingActions(session: VaultSession) {
        try {
            val n = PendingMerge.merge(session, pendingQueue)
            if (n > 0) _messages.tryEmit(if (n == 1) "Added 1 attendance mark from a notification." else "Added $n attendance marks from notifications.")
        } catch (e: CancellationException) {
            throw e // vault locked meanwhile: the queue is kept and merged next time
        } catch (e: Exception) {
            _messages.tryEmit("Could not add attendance marks from notifications: ${e.message}. They will be retried next unlock.")
        }
    }

    private fun localCurrency(): String = runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrDefault("INR")
}
