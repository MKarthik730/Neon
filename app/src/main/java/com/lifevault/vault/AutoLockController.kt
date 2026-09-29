package com.lifevault.vault

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Locks the vault after the configured inactivity timeout, and (optionally) as soon as the app goes to
 * the background. Observes the process lifecycle. System pickers the app opens itself (folder/file pickers)
 * send the app to the background briefly; [expectExternalActivity] suppresses the background lock for them.
 */
class AutoLockController(
    private val vault: VaultManager,
    private val scope: CoroutineScope,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : DefaultLifecycleObserver {

    @Volatile private var lastInteraction = clock()
    @Volatile private var externalUntil = 0L
    private var ticker: Job? = null

    fun onUserInteraction() {
        lastInteraction = clock()
    }

    /** Call right before launching a system picker/camera intent that leaves the app. */
    fun expectExternalActivity(maxMillis: Long = 10 * 60_000L) {
        externalUntil = clock() + maxMillis
    }

    fun externalActivityFinished() {
        externalUntil = 0
        onUserInteraction()
    }

    override fun onStart(owner: LifecycleOwner) {
        onUserInteraction()
        startTicker()
    }

    override fun onStop(owner: LifecycleOwner) {
        val session = vault.currentSession ?: return
        val lockOnBackground = session.settings.cached?.security?.lockOnBackground ?: true
        if (lockOnBackground && clock() > externalUntil) vault.lock()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val session = vault.currentSession ?: continue
                val minutes = session.settings.cached?.security?.autoLockMinutes ?: 5
                if (minutes <= 0) continue
                if (clock() > externalUntil && clock() - lastInteraction >= minutes * 60_000L) vault.lock()
            }
        }
    }

    private companion object { const val TICK_MS = 10_000L }
}
