package com.lifevault.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.lifevault.ui.common.InfoText
import com.lifevault.ui.common.LocalAppContainer
import com.lifevault.ui.common.ScreenScaffold
import com.lifevault.ui.common.SectionCard
import com.lifevault.ui.main.Routes

private data class MoreItem(val label: String, val hint: String, val icon: ImageVector, val action: () -> Unit)

@Composable
fun MoreScreen(nav: NavHostController) {
    val vault = LocalAppContainer.current.vault
    val items = listOf(
        MoreItem("Events & tests", "Class events, tests, reminders", Icons.Filled.Event) { nav.navigate(Routes.EVENTS) },
        MoreItem("Study pages", "Notes, checklists, attachments per subject", Icons.AutoMirrored.Filled.MenuBook) { nav.navigate(Routes.STUDY) },
        MoreItem("Ground rules", "Read your rules before you start", Icons.Filled.Gavel) { nav.navigate(Routes.RULES) },
        MoreItem("Job log", "Jobs, time and rules-read report", Icons.Filled.Timer) { nav.navigate(Routes.JOB_LOG) },
        MoreItem("Mood report", "Trends, tags and exports", Icons.Filled.Insights) { nav.navigate(Routes.MOOD_REPORT) },
        MoreItem("Timetable", "Weekly sessions, CSV import", Icons.Filled.EditCalendar) { nav.navigate(Routes.TIMETABLE) },
        MoreItem("Holidays", "Holidays and weekly off days", Icons.Filled.BeachAccess) { nav.navigate(Routes.HOLIDAYS) },
        MoreItem("Backup & restore", "Encrypted export of the whole vault", Icons.Filled.Backup) { nav.navigate(Routes.BACKUP) },
        MoreItem("Reliability check", "Make sure reminders arrive", Icons.Filled.NotificationsActive) { nav.navigate(Routes.RELIABILITY) },
        MoreItem("Security & privacy", "What is stored where", Icons.Filled.Security) { nav.navigate(Routes.SECURITY) },
        MoreItem("Settings", "Lock, attendance, mood, rules, notifications", Icons.Filled.Settings) { nav.navigate(Routes.SETTINGS) },
        MoreItem("Lock now", vault.folderName?.let { "Vault folder: $it" } ?: "", Icons.Filled.Lock) { vault.lock() },
    )
    ScreenScaffold(title = "More") { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.label }) { item ->
                SectionCard(onClick = item.action) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        androidx.compose.foundation.layout.Column {
                            Text(item.label, style = MaterialTheme.typography.titleMedium)
                            if (item.hint.isNotEmpty()) InfoText(item.hint)
                        }
                    }
                }
            }
        }
    }
}
