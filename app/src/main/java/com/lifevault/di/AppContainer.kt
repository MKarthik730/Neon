package com.lifevault.di

import android.app.Application
import com.lifevault.backup.BackupManager
import com.lifevault.media.MediaManager
import com.lifevault.media.VaultAudioPlayer
import com.lifevault.notifications.AlarmScheduler
import com.lifevault.notifications.AlarmSync
import com.lifevault.notifications.PendingActionsQueue
import com.lifevault.vault.AutoLockController
import com.lifevault.vault.BiometricKeys
import com.lifevault.vault.DevicePrefs
import com.lifevault.vault.SessionGraph
import com.lifevault.vault.VaultManager
import com.lifevault.vault.VaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Everything that exists while the vault is unlocked and needs Android. Dies with the session. */
class UnlockedGraph(app: Application, override val session: VaultSession, scheduler: AlarmScheduler) : SessionGraph {
    val media = MediaManager(app, session)
    val player = VaultAudioPlayer(app, session)
    val backup = BackupManager(app, session)

    init {
        AlarmSync(session, scheduler).start()
    }
}

/** Manual dependency injection: app-wide singletons. */
class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val prefs = DevicePrefs(app)
    val biometrics = BiometricKeys(app, prefs)
    val scheduler = AlarmScheduler(app)
    val pendingQueue = PendingActionsQueue(File(app.noBackupFilesDir, "pending-actions.json"))

    val vault = VaultManager(
        context = app,
        prefs = prefs,
        biometrics = biometrics,
        pendingQueue = pendingQueue,
        appScope = appScope,
        graphFactory = { session -> UnlockedGraph(app, session, scheduler) },
    )

    val autoLock = AutoLockController(vault, appScope)
}
