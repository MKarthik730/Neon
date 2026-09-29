package com.lifevault

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.fragment.app.FragmentActivity
import com.lifevault.domain.model.AttendanceStatus
import com.lifevault.domain.model.Session
import com.lifevault.notifications.NavTargets
import com.lifevault.ui.RootScreen
import com.lifevault.ui.common.LocalAppContainer
import com.lifevault.ui.theme.LifeVaultTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

/** Where a notification asked to go; handled after the vault is unlocked. */
sealed interface NavRequest {
    data class QuickMark(val date: LocalDate, val session: Session, val status: AttendanceStatus?) : NavRequest
    data class OpenEvent(val id: String) : NavRequest
    data object MoodCheckIn : NavRequest
    data class StartJob(val ruleSetId: String?) : NavRequest
    data object Home : NavRequest
}

/**
 * Single activity. FragmentActivity is required by BiometricPrompt.
 * FLAG_SECURE blocks screenshots, screen recording and the recents thumbnail on every screen.
 */
class MainActivity : FragmentActivity() {
    val navRequests = MutableStateFlow<NavRequest?>(null)
    private val container get() = (application as LifeVaultApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                LifeVaultTheme { RootScreen(navRequests) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        container.autoLock.onUserInteraction()
    }

    private fun handle(intent: Intent?) {
        val nav = intent?.getStringExtra(NavTargets.EXTRA_NAV) ?: return
        navRequests.value = when (nav) {
            NavTargets.QUICK_MARK -> {
                val date = intent.getStringExtra(NavTargets.EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val session = intent.getStringExtra(NavTargets.EXTRA_SESSION)?.let { runCatching { Session.valueOf(it) }.getOrNull() }
                val status = intent.getStringExtra(NavTargets.EXTRA_STATUS)?.let { runCatching { AttendanceStatus.valueOf(it) }.getOrNull() }
                if (date != null && session != null) NavRequest.QuickMark(date, session, status) else NavRequest.Home
            }
            NavTargets.EVENT -> intent.getStringExtra(NavTargets.EXTRA_REF)?.let { NavRequest.OpenEvent(it) } ?: NavRequest.Home
            NavTargets.MOOD -> NavRequest.MoodCheckIn
            NavTargets.START_JOB -> NavRequest.StartJob(intent.getStringExtra(NavTargets.EXTRA_REF))
            else -> NavRequest.Home
        }
        intent.removeExtra(NavTargets.EXTRA_NAV)
    }
}
