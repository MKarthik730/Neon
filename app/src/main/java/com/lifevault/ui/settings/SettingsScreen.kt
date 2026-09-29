package com.lifevault.ui.settings

import com.lifevault.Brand
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.lifevault.BuildConfig
import com.lifevault.di.AppContainer
import com.lifevault.di.UnlockedGraph
import com.lifevault.domain.model.CountingMode
import com.lifevault.domain.model.EntryType
import com.lifevault.domain.model.Settings
import com.lifevault.domain.model.TrackerSettings
import com.lifevault.ui.common.CollectMessages
import com.lifevault.ui.common.ConfirmDialog
import com.lifevault.ui.common.InfoText
import com.lifevault.ui.common.LoadingBox
import com.lifevault.ui.common.LocalAppContainer
import com.lifevault.ui.common.LocalGraph
import com.lifevault.ui.common.ScreenScaffold
import com.lifevault.ui.common.SectionCard
import com.lifevault.ui.common.Stepper
import com.lifevault.ui.common.SwitchRow
import com.lifevault.ui.common.TimeField
import com.lifevault.ui.common.VaultViewModel
import com.lifevault.ui.main.Routes
import com.lifevault.ui.vault.BiometricUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class SettingsViewModel(graph: UnlockedGraph, private val container: AppContainer) : VaultViewModel(graph) {
    val settings: StateFlow<Settings?> = session.settings.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val quickQueue = MutableStateFlow(container.prefs.quickActionsQueue)
    val biometricEnabled = MutableStateFlow(container.biometrics.isEnabled(session.header.vaultId))
    val biometricAvailable = container.biometrics.isHardwareReady()

    fun update(f: (Settings) -> Settings) = launchSafe { session.settings.update(f) }

    fun setQuickQueue(on: Boolean) {
        container.prefs.quickActionsQueue = on
        quickQueue.value = on
        if (!on) {
            // Anything still queued is merged now; nothing stays outside the vault.
            container.vault.mergePendingActionsIfUnlocked()
        }
    }

    fun enableBiometric(activity: FragmentActivity) {
        val cipher = runCatching { container.biometrics.encryptCipher(session.header.vaultId) }.getOrElse {
            say("Biometric unlock is not available: ${it.message}")
            return
        }
        BiometricUi.authenticate(activity, cipher, "Enable biometric unlock", "Confirm to store a device-only key", onSuccess = { c ->
            launchSafe {
                container.vault.enableBiometric(c)
                biometricEnabled.value = true
                say("Biometric unlock enabled on this phone.")
            }
        }, onError = { say(it) })
    }

    fun disableBiometric() {
        container.vault.disableBiometric()
        biometricEnabled.value = false
    }
}

private fun csvList(text: String) = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val container = LocalAppContainer.current
    val vm: SettingsViewModel = viewModel { SettingsViewModel(graph, container) }
    CollectMessages(vm.messages)
    val settings by vm.settings.collectAsStateWithLifecycle()
    val quickQueue by vm.quickQueue.collectAsStateWithLifecycle()
    val bioEnabled by vm.biometricEnabled.collectAsStateWithLifecycle()
    val activity = LocalContext.current as FragmentActivity
    var confirmForget by remember { mutableStateOf(false) }
    ScreenScaffold(title = "Settings", onBack = { nav.popBackStack() }) { padding ->
        val s = settings
        if (s == null) {
            LoadingBox(Modifier.padding(padding))
            return@ScreenScaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(title = "Security") {
                Stepper("Auto-lock after (min, 0 = off)", s.security.autoLockMinutes, 0..60, { m -> vm.update { it.copy(security = it.security.copy(autoLockMinutes = m)) } })
                SwitchRow("Lock when the app goes to the background", s.security.lockOnBackground, { b -> vm.update { it.copy(security = it.security.copy(lockOnBackground = b)) } })
                SwitchRow(
                    "Biometric quick-unlock", bioEnabled,
                    { on -> if (on) vm.enableBiometric(activity) else vm.disableBiometric() },
                    subtitle = if (vm.biometricAvailable) "A device-only copy of the key, protected by your fingerprint/face. The passphrase always works too." else "No strong biometric is set up on this phone.",
                    enabled = vm.biometricAvailable || bioEnabled,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nav.navigate(Routes.PASSPHRASE) }) { Text("Change passphrase") }
                    OutlinedButton(onClick = { nav.navigate(Routes.RECOVERY) }) { Text("New recovery code") }
                }
            }

            SectionCard(title = "Attendance") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    CountingMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(selected = s.attendance.countingMode == m, onClick = { vm.update { it.copy(attendance = it.attendance.copy(countingMode = m)) } }, shape = SegmentedButtonDefaults.itemShape(i, CountingMode.entries.size)) {
                            Text(if (m == CountingMode.PER_SESSION) "Per session" else "Per subject")
                        }
                    }
                }
                Stepper("Required attendance", s.attendance.thresholdPercent, 1..100, { v -> vm.update { it.copy(attendance = it.attendance.copy(thresholdPercent = v)) } }, suffix = "%")
                SwitchRow("\"Mark attendance\" notifications", s.attendance.promptsEnabled, { b -> vm.update { it.copy(attendance = it.attendance.copy(promptsEnabled = b)) } })
                if (s.attendance.promptsEnabled) {
                    Stepper("Minutes after session ends", s.attendance.promptDelayMinutes, 0..180, { v -> vm.update { it.copy(attendance = it.attendance.copy(promptDelayMinutes = v)) } }, step = 5)
                }
                SwitchRow(
                    "Mark from the notification without unlocking",
                    quickQueue, vm::setQuickQueue,
                    subtitle = "Convenient, but a Present/Absent tap (date, session and status only — no subject or notes) waits in the app's private storage until you next unlock, then moves into the vault. When off, the buttons open ${Brand.NAME} and you confirm after unlocking, so every byte stays in the vault.",
                )
            }

            SectionCard(title = "Mood") {
                SwitchRow("Two check-ins a day (morning & evening)", s.mood.twoPerDay, { b -> vm.update { it.copy(mood = it.mood.copy(twoPerDay = b)) } })
                SwitchRow("Daily check-in reminder", s.mood.reminderEnabled, { b -> vm.update { it.copy(mood = it.mood.copy(reminderEnabled = b)) } }, subtitle = "The notification only says \"Time for your daily check-in\".")
                if (s.mood.reminderEnabled) {
                    TimeField("Reminder time", s.mood.reminderTime, { t -> vm.update { it.copy(mood = it.mood.copy(reminderTime = t)) } })
                    SwitchRow("Skip holidays and off days", s.mood.reminderSkipsHolidays, { b -> vm.update { it.copy(mood = it.mood.copy(reminderSkipsHolidays = b)) } })
                }
                SwitchRow("Gentle message after a run of low check-ins", s.mood.gentleMessageEnabled, { b -> vm.update { it.copy(mood = it.mood.copy(gentleMessageEnabled = b)) } })
                if (s.mood.gentleMessageEnabled) {
                    Stepper("Low means at or below", s.mood.lowLevel, 1..4, { v -> vm.update { it.copy(mood = it.mood.copy(lowLevel = v)) } })
                    Stepper("After this many in a row", s.mood.lowRunLength, 2..30, { v -> vm.update { it.copy(mood = it.mood.copy(lowRunLength = v)) } })
                }
                ListEditor("Tags", s.mood.tags) { list -> vm.update { it.copy(mood = it.mood.copy(tags = list)) } }
            }

            SectionCard(title = "Ground rules") {
                SwitchRow(
                    "Tick every rule before Begin", s.rules.requireTickAll, { b -> vm.update { it.copy(rules = it.rules.copy(requireTickAll = b)) } },
                    subtitle = if (s.rules.requireTickAll) "Each rule must be ticked." else "One \"I've read them\" tap is enough.",
                )
                Stepper("Countdown before Begin", s.rules.countdownSeconds, 0..60, { v -> vm.update { it.copy(rules = it.rules.copy(countdownSeconds = v)) } }, suffix = "s")
                SwitchRow("Job timer", s.rules.jobTimerEnabled, { b -> vm.update { it.copy(rules = it.rules.copy(jobTimerEnabled = b)) } })
                SwitchRow("Prompt before events with linked rules", s.rules.promptBeforeLinkedEvents, { b -> vm.update { it.copy(rules = it.rules.copy(promptBeforeLinkedEvents = b)) } })
            }

            SectionCard(title = "Notifications") {
                SwitchRow("Event & test reminders", s.notifications.eventRemindersEnabled, { b -> vm.update { it.copy(notifications = it.notifications.copy(eventRemindersEnabled = b)) } })
                Stepper("Default reminder (min before)", s.notifications.defaultEventReminderMinutes, 0..1440, { v -> vm.update { it.copy(notifications = it.notifications.copy(defaultEventReminderMinutes = v)) } }, step = 5)
                SwitchRow("Daily summary", s.notifications.dailySummaryEnabled, { b -> vm.update { it.copy(notifications = it.notifications.copy(dailySummaryEnabled = b)) } })
                if (s.notifications.dailySummaryEnabled) TimeField("Summary time", s.notifications.dailySummaryTime, { t -> vm.update { it.copy(notifications = it.notifications.copy(dailySummaryTime = t)) } })
                InfoText("Notification text is always generic and hidden on the lock screen.")
                TextButton(onClick = { nav.navigate(Routes.RELIABILITY) }) { Text("Reliability check…") }
            }

            SectionCard(title = "Trackers") {
                TextSetting("Currency code (e.g. INR, USD)", s.trackers.currency) { c -> vm.update { it.copy(trackers = it.trackers.copy(currency = c.uppercase().take(3))) } }
                TextSetting("Distance unit", s.trackers.distanceUnit) { u -> vm.update { it.copy(trackers = it.trackers.copy(distanceUnit = u)) } }
                for (t in EntryType.entries) {
                    ListEditor("${t.label} categories", s.trackers.categoriesFor(t)) { list -> vm.update { it.copy(trackers = it.trackers.withCategories(t, list)) } }
                }
            }

            SectionCard(title = "Vault") {
                InfoText("Folder: ${container.vault.folderName ?: "—"}")
                TextButton(onClick = { nav.navigate(Routes.BACKUP) }) { Text("Backup & restore…") }
                TextButton(onClick = { nav.navigate(Routes.SECURITY) }) { Text("Security & privacy…") }
                TextButton(onClick = { confirmForget = true }) { Text("Forget this folder", color = MaterialTheme.colorScheme.error) }
            }
            InfoText("${Brand.NAME} ${BuildConfig.VERSION_NAME} · works fully offline (no internet permission)", Modifier.padding(bottom = 24.dp))
        }
    }
    if (confirmForget) {
        ConfirmDialog(
            "Forget this folder?", "${Brand.NAME} locks and forgets the vault folder. Nothing is deleted — you can open the same folder again with your passphrase.",
            confirmLabel = "Forget", destructive = true, onConfirm = { container.vault.forgetFolder() }, onDismiss = { confirmForget = false },
        )
    }
}

private fun TrackerSettings.withCategories(t: EntryType, list: List<String>) = when (t) {
    EntryType.MONEY -> copy(moneyCategories = list)
    EntryType.FOOD -> copy(foodCategories = list)
    EntryType.TRAVEL -> copy(travelCategories = list)
}

@Composable
private fun TextSetting(label: String, value: String, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
        if (text.trim() != value && text.isNotBlank()) TextButton(onClick = { onSave(text.trim()) }) { Text("Save") }
    }
}

@Composable
private fun ListEditor(label: String, values: List<String>, onSave: (List<String>) -> Unit) {
    val joined = values.joinToString(", ")
    var text by remember(joined) { mutableStateOf(joined) }
    Column {
        OutlinedTextField(text, { text = it }, label = { Text("$label (comma separated)") }, modifier = Modifier.fillMaxWidth())
        if (csvList(text) != values) TextButton(onClick = { onSave(csvList(text)) }) { Text("Save $label") }
    }
}
