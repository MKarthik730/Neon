package com.lifevault

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.lifevault.di.AppContainer
import com.lifevault.notifications.Notifier

class LifeVaultApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifier.ensureChannels(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.autoLock)
        container.vault.init()
        container.scheduler.scheduleNext()
    }
}
