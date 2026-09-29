package com.lifevault.ui.main

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lifevault.NavRequest
import com.lifevault.ui.attendance.QuickMarkDialog
import com.lifevault.ui.common.LocalAppContainer
import com.lifevault.ui.common.LocalSnackbar
import kotlinx.coroutines.flow.MutableStateFlow

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Today", Icons.Filled.Home),
    Tab(Routes.ATTENDANCE, "Attendance", Icons.Filled.CalendarMonth),
    Tab(Routes.TRACK, "Track", Icons.Filled.Wallet),
    Tab(Routes.MEDIA, "Media", Icons.Filled.PhotoLibrary),
    Tab(Routes.MORE, "More", Icons.Filled.MoreHoriz),
)

@Composable
fun MainScaffold(navRequests: MutableStateFlow<NavRequest?>) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val vault = LocalAppContainer.current.vault
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    var quickMark by remember { mutableStateOf<NavRequest.QuickMark?>(null) }
    val request by navRequests.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vault.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(request) {
        when (val r = request) {
            null -> return@LaunchedEffect
            is NavRequest.QuickMark -> quickMark = r
            is NavRequest.OpenEvent -> nav.navigate(Routes.event(r.id))
            NavRequest.MoodCheckIn -> nav.navigate(Routes.moodCheckIn())
            is NavRequest.StartJob -> nav.navigate(Routes.startJob(r.ruleSetId))
            NavRequest.Home -> nav.navigateTab(Routes.HOME)
        }
        navRequests.value = null
    }

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (tabs.any { it.route == currentRoute }) {
                    NavigationBar {
                        for (t in tabs) {
                            NavigationBarItem(
                                selected = currentRoute == t.route,
                                onClick = { nav.navigateTab(t.route) },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
            AppNavHost(nav, Modifier.padding(bottom).consumeWindowInsets(bottom))
        }
    }
    quickMark?.let { q -> QuickMarkDialog(q.date, q.session, q.status, onDismiss = { quickMark = null }) }
}

fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
