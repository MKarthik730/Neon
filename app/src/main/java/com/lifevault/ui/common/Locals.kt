package com.lifevault.ui.common

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.lifevault.di.AppContainer
import com.lifevault.di.UnlockedGraph

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
val LocalGraph = staticCompositionLocalOf<UnlockedGraph> { error("Vault is locked") }

/**
 * Launcher for system pickers/permission screens that briefly leave the app. It tells auto-lock not to
 * lock on this background trip, and resets the inactivity timer when the result comes back.
 */
@Composable
fun <I, O> rememberExternalLauncher(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): ExternalLauncher<I, O> {
    val container = LocalAppContainer.current
    val launcher = rememberLauncherForActivityResult(contract) { result ->
        container.autoLock.externalActivityFinished()
        onResult(result)
    }
    return ExternalLauncher(launcher) { container.autoLock.expectExternalActivity() }
}

class ExternalLauncher<I, O>(
    private val launcher: ManagedActivityResultLauncher<I, O>,
    private val beforeLaunch: () -> Unit,
) {
    fun launch(input: I) {
        beforeLaunch()
        launcher.launch(input)
    }
}
