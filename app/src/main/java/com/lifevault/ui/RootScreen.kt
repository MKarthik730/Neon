package com.lifevault.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.lifevault.NavRequest
import com.lifevault.di.UnlockedGraph
import com.lifevault.ui.common.LoadingBox
import com.lifevault.ui.common.LocalAppContainer
import com.lifevault.ui.common.LocalGraph
import com.lifevault.ui.main.MainScaffold
import com.lifevault.ui.vault.CreateVaultScreen
import com.lifevault.ui.vault.FolderErrorScreen
import com.lifevault.ui.vault.RecoveryCodeScreen
import com.lifevault.ui.vault.UnlockScreen
import com.lifevault.ui.vault.WelcomeScreen
import com.lifevault.vault.VaultState
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun RootScreen(navRequests: MutableStateFlow<NavRequest?>) {
    val vault = LocalAppContainer.current.vault
    val state by vault.state.collectAsStateWithLifecycle()
    when (val s = state) {
        VaultState.Loading -> LoadingBox()
        VaultState.NoFolder -> WelcomeScreen()
        is VaultState.FolderError -> FolderErrorScreen(s.message)
        is VaultState.NeedsSetup -> CreateVaultScreen(s.folderName, s.hasOtherFiles)
        is VaultState.Locked -> UnlockScreen(s.folderName, s.biometricEnabled)
        is VaultState.Unlocked -> {
            val code = s.recoveryCodeToConfirm
            if (code != null) {
                RecoveryCodeScreen(code, onDone = { vault.confirmRecoveryCodeSaved() })
            } else {
                SessionScope(s.graph as UnlockedGraph) { MainScaffold(navRequests) }
            }
        }
    }
}

/**
 * Gives the unlocked UI its own ViewModelStore. When the vault locks this composable leaves the
 * composition and every ViewModel (and the NavController's back-stack ViewModels) is cleared, so no
 * decrypted state survives a lock.
 */
@Composable
private fun SessionScope(graph: UnlockedGraph, content: @Composable () -> Unit) {
    val owner = remember(graph.session.id) {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalGraph provides graph) { content() }
}
