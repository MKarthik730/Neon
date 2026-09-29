package com.lifevault.ui.settings

import com.lifevault.Brand
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavHostController
import com.lifevault.backup.RestoreResult
import com.lifevault.backup.StagedBackup
import com.lifevault.domain.notify.AlarmPlanner
import com.lifevault.notifications.Notifier
import com.lifevault.ui.common.ConfirmDialog
import com.lifevault.ui.common.ErrorText
import com.lifevault.ui.common.Format
import com.lifevault.ui.common.InfoText
import com.lifevault.ui.common.LocalAppContainer
import com.lifevault.ui.common.LocalGraph
import com.lifevault.ui.common.LocalSnackbar
import com.lifevault.ui.common.PassphraseField
import com.lifevault.ui.common.ScreenScaffold
import com.lifevault.ui.common.SectionCard
import com.lifevault.ui.common.SmallSpinner
import com.lifevault.ui.common.rememberExternalLauncher
import com.lifevault.ui.common.userMessage
import com.lifevault.ui.theme.LocalStatusColors
import com.lifevault.ui.vault.RecoveryCodeScreen
import com.lifevault.ui.vault.passphraseStrength
import com.lifevault.vault.crypto.VaultCrypto
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

@Composable
fun ChangePassphraseScreen(nav: NavHostController) {
    val vault = LocalAppContainer.current.vault
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ScreenScaffold(title = "Change passphrase", onBack = { nav.popBackStack() }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoText("Only the wrapped key in vault.json changes; your data is not re-encrypted, so this is quick. Your recovery code keeps working.")
            PassphraseField(current, { current = it; error = null }, "Current passphrase")
            PassphraseField(new, { new = it }, "New passphrase", supportingText = listOf("Too short", "Weak", "Fair", "Good", "Strong")[passphraseStrength(new)])
            PassphraseField(confirm, { confirm = it }, "Repeat new passphrase", isError = confirm.isNotEmpty() && confirm != new)
            error?.let { ErrorText(it) }
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching { vault.changePassphrase(current, new) }
                            .onSuccess { ok -> if (ok) { snackbar.showSnackbar("Passphrase changed."); nav.popBackStack() } else error = "The current passphrase is wrong." }
                            .onFailure { error = it.userMessage() }
                        busy = false
                    }
                },
                enabled = !busy && current.isNotEmpty() && new.length >= VaultCrypto.MIN_PASSPHRASE_LENGTH && new == confirm,
                modifier = Modifier.fillMaxWidth(),
            ) { if (busy) SmallSpinner() else Text("Change passphrase") }
        }
    }
}

@Composable
fun RotateRecoveryScreen(nav: NavHostController) {
    val vault = LocalAppContainer.current.vault
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val c = code
    if (c != null) {
        RecoveryCodeScreen(c, onDone = { nav.popBackStack() }, title = "Your new recovery code")
        return
    }
    ScreenScaffold(title = "New recovery code", onBack = { nav.popBackStack() }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoText("Create a new recovery code if you lost the old one or think someone saw it. The old code stops working immediately.")
            error?.let { ErrorText(it) }
            Button(onClick = {
                busy = true
                scope.launch {
                    runCatching { vault.rotateRecoveryCode() }.onSuccess { code = it }.onFailure { error = it.userMessage() }
                    busy = false
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { if (busy) SmallSpinner() else Text("Create new recovery code") }
        }
    }
}

@Composable
fun BackupScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val vault = LocalAppContainer.current.vault
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var staged by remember { mutableStateOf<StagedBackup?>(null) }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }

    val exporter = rememberExternalLauncher(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            runCatching { graph.backup.export(uri) { done, total -> progress = done to total } }
                .onSuccess { snackbar.showSnackbar("Encrypted backup saved ($it files).") }
                .onFailure { error = it.userMessage() }
            busy = false
            progress = null
        }
    }
    val importer = rememberExternalLauncher(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            error = null
            runCatching { graph.backup.stage(uri) }.onSuccess { staged = it }.onFailure { error = it.userMessage() }
            busy = false
        }
    }

    ScreenScaffold(title = "Backup & restore", onBack = { graph.backup.discardStaged(); nav.popBackStack() }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(title = "Your vault folder is the backup") {
                InfoText("Everything ${Brand.NAME} stores is inside \"${vault.folderName}\", already encrypted. Copy the whole folder (with a file manager, a USB cable or an SD card) to keep a backup, or to move to another phone: install ${Brand.NAME} there, choose the copied folder and unlock with your passphrase.")
            }
            SectionCard(title = "Export encrypted backup") {
                InfoText("Creates one .zip of the vault exactly as stored — still encrypted. Restoring it needs the passphrase (or recovery code) it had when exported.")
                Button(onClick = { exporter.launch("${Brand.NAME}-backup-${LocalDate.now()}.zip") }, enabled = !busy) { Text("Export backup…") }
                progress?.let { (d, t) -> LinearProgressIndicator(progress = { d.toFloat() / t.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth()) }
            }
            SectionCard(title = "Restore from backup") {
                InfoText("The backup is unpacked into the vault's .tmp folder and every file is verified with the backup's passphrase before anything is replaced. The vault then locks; unlock with the backup's passphrase.")
                val s = staged
                if (s == null) {
                    OutlinedButton(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !busy) { Text("Choose backup file…") }
                } else {
                    Text("Backup with ${s.files} file(s)" + (s.manifest?.let { " from ${Format.date(java.time.Instant.ofEpochMilli(it.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate())}" } ?: ""), fontWeight = FontWeight.SemiBold)
                    if (s.header.vaultId != graph.session.header.vaultId) InfoText("This backup is from a different vault. Restoring replaces everything currently in this vault.")
                    PassphraseField(pass, { pass = it; error = null }, "Passphrase of the backup")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { confirmRestore = true }, enabled = !busy && pass.isNotEmpty()) { Text("Verify & restore") }
                        OutlinedButton(onClick = { graph.backup.discardStaged(); staged = null; pass = "" }, enabled = !busy) { Text("Cancel") }
                    }
                }
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) { SmallSpinner(); Text("  Working…") }
            error?.let { ErrorText(it) }
        }
    }
    val s = staged
    if (confirmRestore && s != null) {
        ConfirmDialog(
            "Replace the vault with this backup?",
            "Everything currently in the vault is replaced by the backup's contents once it has been verified. This cannot be undone.",
            confirmLabel = "Restore", destructive = true,
            onConfirm = {
                scope.launch {
                    busy = true
                    runCatching { graph.backup.verifyAndRestore(s, pass) }
                        .onSuccess { r ->
                            when (r) {
                                RestoreResult.Restored -> vault.reloadAfterRestore()
                                RestoreResult.WrongPassphrase -> error = "That passphrase does not open this backup. Nothing was changed."
                                is RestoreResult.Invalid -> error = r.message
                            }
                        }
                        .onFailure { error = it.userMessage() }
                    busy = false
                }
            },
            onDismiss = { confirmRestore = false },
        )
    }
}

@Composable
fun ReliabilityScreen(nav: NavHostController) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val notifLauncher = rememberExternalLauncher(ActivityResultContracts.RequestPermission()) { refresh++ }
    fun open(intent: Intent) {
        container.autoLock.expectExternalActivity()
        try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: ActivityNotFoundException) {
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    ScreenScaffold(title = "Reliability check", onBack = { nav.popBackStack() }) { padding ->
        val notificationsOk = remember(refresh) { Notifier.canPost(context) }
        val exactOk = remember(refresh) { container.scheduler.canScheduleExact() }
        val batteryOk = remember(refresh) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
        val next = remember(refresh) { container.scheduler.store.next(LocalDateTime.now()) }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CheckRow("Notifications allowed", notificationsOk, "Needed for attendance prompts and reminders.") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
            CheckRow(
                "Exact alarms allowed", exactOk,
                if (exactOk) "Reminders fire on time." else "Without this, Android may delay reminders by several minutes (they still arrive).",
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            }
            CheckRow(
                "Battery optimisation off for ${Brand.NAME}", batteryOk,
                "Some phones stop background alarms to save battery. Choose ${Brand.NAME} → \"Don't optimise\" / \"Unrestricted\".",
            ) { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            SectionCard(title = "Phone-specific tips") {
                InfoText("Xiaomi / Redmi / POCO: Settings → Apps → ${Brand.NAME} → Autostart ON, and Battery saver → No restrictions.")
                InfoText("Samsung: Settings → Battery → Background usage limits → add ${Brand.NAME} to \"Never sleeping apps\".")
                InfoText("Oppo / Realme / OnePlus / Vivo: App info → Battery → Allow background activity and Auto-launch.")
                InfoText("Huawei / Honor: Battery → App launch → ${Brand.NAME} → Manage manually, all switches on.")
            }
            SectionCard(title = "Schedule") {
                InfoText(next?.let { "Next reminder: ${Format.dateTime(it.at)} (${it.type.name.lowercase()})" } ?: "No reminders are scheduled.")
                InfoText("Reminders are planned ${AlarmPlanner.DEFAULT_HORIZON_DAYS} days ahead each time you unlock, and rebuilt after a restart or time-zone change.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { container.scheduler.scheduleNext(); scope.launch { snackbar.showSnackbar("Alarms rebuilt.") } }) { Text("Rebuild alarms") }
                    OutlinedButton(onClick = {
                        val ok = Notifier.postTest(context)
                        scope.launch { snackbar.showSnackbar(if (ok) "Test notification sent." else "Notifications are blocked.") }
                    }) { Text("Send test") }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(title: String, ok: Boolean, detail: String, fix: () -> Unit) {
    val colors = LocalStatusColors.current
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, contentDescription = if (ok) "OK" else "Needs attention", tint = if (ok) colors.good else colors.warning)
            Text("  $title", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (!ok) OutlinedButton(onClick = fix) { Text("Fix") }
        }
        InfoText(detail)
    }
}

@Composable
fun SecurityInfoScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val queued = remember { container.pendingQueue.peek().size }
    ScreenScaffold(title = "Security & privacy", onBack = { nav.popBackStack() }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(title = "Inside the vault folder (encrypted)") {
                InfoText("• data/ — settings, timetable, holidays, attendance, trackers, mood, rules, job log, events and study pages, each AES-256-GCM encrypted. File names are keyed hashes, so even the names reveal nothing.")
                InfoText("• media/ and thumbs/ — photos and recordings as random names with no extension, streamed through AES-256-GCM-HKDF. Original names, types and tags live only in the encrypted index.")
                InfoText("• vault.json — the only plaintext file: format version, Argon2id parameters and salts, and the master key wrapped by your passphrase and by your recovery code.")
            }
            SectionCard(title = "Outside the vault (app-private, never backed up)") {
                InfoText("• Which folder is the vault, and failed-unlock counters.")
                InfoText("• If enabled: the biometric copy of the key, encrypted by a hardware key that only works after your fingerprint/face and never leaves this phone.")
                InfoText("• The reminder schedule: only times, types and random ids — no titles, subjects, amounts or mood.")
                InfoText("• If enabled: notification Present/Absent taps waiting to be merged ($queued now).")
            }
            SectionCard(title = "Protections") {
                InfoText("• No internet permission; no analytics, ads or crash reporting.")
                InfoText("• Screenshots, screen recording and recent-apps previews are blocked.")
                InfoText("• Auto-lock after inactivity and when leaving the app; locking wipes keys from memory and clears .tmp/.")
                InfoText("• Growing delays after wrong passphrases.")
                InfoText("• The only way unencrypted data leaves the vault is an export you start yourself.")
            }
            SectionCard(title = "Integrity check") {
                InfoText("Decrypts every data file to confirm nothing is damaged.")
                OutlinedButton(onClick = {
                    busy = true
                    scope.launch {
                        result = runCatching {
                            val bad = graph.session.store.verifyAll()
                            if (bad.isEmpty()) "All data files decrypted successfully." else "${bad.size} file(s) could not be decrypted. Restore from a backup if data is missing."
                        }.getOrElse { it.userMessage() }
                        busy = false
                    }
                }, enabled = !busy) { if (busy) SmallSpinner() else Text("Check vault") }
                result?.let { InfoText(it) }
            }
        }
    }
}
