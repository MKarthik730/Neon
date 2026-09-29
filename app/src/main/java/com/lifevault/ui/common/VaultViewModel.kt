package com.lifevault.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifevault.di.UnlockedGraph
import com.lifevault.domain.migration.NewerSchemaException
import com.lifevault.vault.crypto.VaultLockedException
import com.lifevault.vault.storage.VaultFolderUnavailableException
import com.lifevault.vault.store.CorruptFileException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** Base for screens inside the unlocked vault. Errors are never swallowed: they become user messages. */
abstract class VaultViewModel(protected val graph: UnlockedGraph) : ViewModel() {
    protected val session get() = graph.session

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    protected fun say(message: String) { _messages.tryEmit(message) }

    protected fun launchSafe(onError: (Throwable) -> Unit = {}, block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            say(e.userMessage())
            onError(e)
        }
    }
}

fun Throwable.userMessage(): String = when (this) {
    is CorruptFileException -> "A vault file is damaged and could not be recovered. Restore from a backup if the problem persists."
    is VaultFolderUnavailableException -> message ?: "The vault folder is unavailable."
    is NewerSchemaException -> message ?: "This data was saved by a newer version of the app."
    is VaultLockedException -> "The vault is locked."
    is SecurityException -> "Permission denied: ${message ?: "access was refused"}"
    else -> message?.takeIf { it.isNotBlank() } ?: "Something went wrong (${this::class.simpleName})."
}
