package com.lifevault.ui.vault

import com.lifevault.Brand
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.lifevault.ui.common.ConfirmDialog
import com.lifevault.ui.common.ErrorText
import com.lifevault.ui.common.InfoText
import com.lifevault.ui.common.LocalAppContainer
import com.lifevault.ui.common.PassphraseField
import com.lifevault.ui.common.SectionCard
import com.lifevault.ui.common.SmallSpinner
import com.lifevault.ui.common.VSpace
import com.lifevault.ui.common.rememberExternalLauncher
import com.lifevault.vault.UnlockResult
import com.lifevault.vault.crypto.RecoveryCode
import com.lifevault.vault.crypto.VaultCrypto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
private fun CenteredPage(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String? = null, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    VSpace(24)
    if (icon == null) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.lifevault.R.drawable.ic_logo),
            contentDescription = "${Brand.NAME} logo",
            modifier = Modifier.size(72.dp),
        )
    } else {
        Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
    }
    Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    if (subtitle != null) InfoText(subtitle, Modifier.fillMaxWidth())
}

@Composable
fun rememberFolderPicker(onPicked: (Uri) -> Unit) =
    rememberExternalLauncher(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) onPicked(uri) }

@Composable
fun WelcomeScreen() {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val picker = rememberFolderPicker { uri -> scope.launch { container.vault.chooseFolder(uri) } }
    CenteredPage {
        PageHeader(
            "${Brand.NAME}",
            "Your organiser and private vault. Everything stays on this phone, inside one folder you choose, and is encrypted.",
        )
        SectionCard(title = "How it works") {
            InfoText("• Pick an empty folder to create a new vault — or a folder that already holds a ${Brand.NAME} vault to open it.")
            InfoText("• The app reads and writes only inside that folder. It has no internet access.")
            InfoText("• The folder is your backup: copy it to another phone and unlock it with your passphrase.")
            InfoText("• Tip: use a folder on the phone's own storage, e.g. Documents/${Brand.NAME} (you can create it in the picker).")
        }
        Button(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Folder, contentDescription = null)
            Text("  Choose vault folder")
        }
    }
}

@Composable
fun FolderErrorScreen(message: String) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val picker = rememberFolderPicker { uri -> scope.launch { container.vault.chooseFolder(uri) } }
    CenteredPage {
        PageHeader("Vault folder unavailable", message, Icons.Filled.Warning)
        Button(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Choose the folder again") }
        OutlinedButton(onClick = { container.vault.init() }, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
        TextButton(onClick = { container.vault.forgetFolder() }) { Text("Start over with another folder") }
    }
}

/** 0..4 rough strength score shown as guidance; the only hard rule is the minimum length. */
fun passphraseStrength(p: String): Int {
    if (p.length < VaultCrypto.MIN_PASSPHRASE_LENGTH) return 0
    var score = 1
    if (p.length >= 12) score++
    if (p.length >= 16 || p.trim().split(Regex("\\s+")).size >= 4) score++
    val classes = listOf(p.any { it.isLowerCase() }, p.any { it.isUpperCase() }, p.any { it.isDigit() }, p.any { !it.isLetterOrDigit() }).count { it }
    if (classes >= 3) score++
    return score.coerceAtMost(4)
}

@Composable
fun CreateVaultScreen(folderName: String, hasOtherFiles: Boolean) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val strength = passphraseStrength(pass)
    val mismatch = confirm.isNotEmpty() && confirm != pass
    CenteredPage {
        PageHeader("Create your vault", "Folder: $folderName")
        if (hasOtherFiles) {
            SectionCard {
                Text("This folder already contains other files.", fontWeight = FontWeight.SemiBold)
                InfoText("${Brand.NAME} will add its own folders next to them and never touch them, but an empty folder keeps things tidy.")
            }
        }
        PassphraseField(pass, { pass = it }, "Passphrase", supportingText = "At least ${VaultCrypto.MIN_PASSPHRASE_LENGTH} characters. A few random words work well.")
        LinearProgressIndicator(progress = { strength / 4f }, modifier = Modifier.fillMaxWidth())
        InfoText(listOf("Too short", "Weak", "Fair", "Good", "Strong")[strength], Modifier.fillMaxWidth())
        PassphraseField(confirm, { confirm = it }, "Repeat passphrase", isError = mismatch, supportingText = if (mismatch) "Passphrases do not match" else null)
        SectionCard {
            InfoText("There is no way to reset a forgotten passphrase. You will get a recovery code next — keep it somewhere safe.")
        }
        error?.let { ErrorText(it) }
        Button(
            onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        container.vault.create(pass)
                    } catch (e: IOException) {
                        error = e.message ?: "Could not write to the folder."
                    } catch (e: Exception) {
                        error = e.message ?: "Could not create the vault."
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && pass.length >= VaultCrypto.MIN_PASSPHRASE_LENGTH && pass == confirm,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) SmallSpinner() else Text("Create vault")
        }
        TextButton(onClick = { container.vault.forgetFolder() }) { Text("Choose a different folder") }
    }
}

@Composable
fun RecoveryCodeScreen(code: String, onDone: () -> Unit, title: String = "Save your recovery code") {
    var saved by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf("") }
    var fileMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val lastGroup = code.takeLast(4)
    val saver = rememberExternalLauncher(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            fileMessage = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write("${Brand.NAME} recovery code\n$code\n".toByteArray()) }
                "Saved. Keep that file somewhere private (it can unlock your vault)."
            }.getOrElse { "Could not save the file: ${it.message}" }
        }
    }
    CenteredPage {
        PageHeader(title, "This code is the only way back in if you forget your passphrase. It is shown once.")
        SectionCard {
            Text(
                code,
                fontFamily = FontFamily.Monospace,
                fontSize = 22.sp,
                lineHeight = 32.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        InfoText("Write it on paper and keep it safe. Screenshots are blocked in ${Brand.NAME}.")
        OutlinedButton(onClick = { saver.launch("${Brand.NAME.lowercase()}-recovery-code.txt") }) { Text("Save as a text file…") }
        fileMessage?.let { InfoText(it) }
        com.lifevault.ui.common.CheckboxRow(saved, { saved = it }, "I have saved my recovery code")
        OutlinedTextField(
            value = check,
            onValueChange = { check = it.uppercase().take(4) },
            label = { Text("Type the last 4 characters to confirm") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onDone, enabled = saved && RecoveryCode.normalize("0".repeat(28) + check) == RecoveryCode.normalize("0".repeat(28) + lastGroup), modifier = Modifier.fillMaxWidth()) {
            Text("Continue")
        }
    }
}

@Composable
fun UnlockScreen(folderName: String, biometricEnabled: Boolean) {
    val container = LocalAppContainer.current
    val vault = container.vault
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as FragmentActivity
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var waitUntil by remember { mutableLongStateOf(System.currentTimeMillis() + vault.retryDelay()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var recovering by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }

    LaunchedEffect(waitUntil) {
        while (System.currentTimeMillis() < waitUntil) { now = System.currentTimeMillis(); delay(250) }
        now = System.currentTimeMillis()
    }
    val waitSec = ((waitUntil - now + 999) / 1000).coerceAtLeast(0)

    fun handle(r: UnlockResult) {
        when (r) {
            UnlockResult.Success -> {}
            is UnlockResult.Wrong -> { error = "Wrong passphrase."; waitUntil = System.currentTimeMillis() + r.retryInMs }
            is UnlockResult.TooSoon -> { error = "Too many attempts."; waitUntil = System.currentTimeMillis() + r.retryInMs }
            is UnlockResult.Failed -> error = r.message
        }
    }

    fun biometric() {
        val id = vault.vaultId ?: return
        val cipher = vault.biometrics.decryptCipher(id)
        if (cipher == null) {
            error = "Biometric unlock was reset (for example after new fingerprints were added). Use your passphrase."
            return
        }
        BiometricUi.authenticate(activity, cipher, "Unlock ${Brand.NAME}", folderName, onSuccess = { c ->
            busy = true
            scope.launch { handle(vault.unlockWithBiometric(c)); busy = false }
        }, onError = { error = it })
    }

    LaunchedEffect(Unit) { if (biometricEnabled && vault.retryDelay() == 0L) biometric() }

    if (recovering) {
        RecoverScreen(onCancel = { recovering = false })
        return
    }
    CenteredPage {
        PageHeader("Vault locked", folderName)
        PassphraseField(pass, { pass = it; error = null }, "Passphrase", isError = error != null)
        error?.let { ErrorText(it) }
        if (waitSec > 0) InfoText("Try again in $waitSec s")
        Button(
            onClick = {
                busy = true
                scope.launch {
                    handle(vault.unlock(pass))
                    busy = false
                }
            },
            enabled = !busy && pass.isNotEmpty() && waitSec == 0L,
            modifier = Modifier.fillMaxWidth(),
        ) { if (busy) SmallSpinner() else Text("Unlock") }
        if (biometricEnabled) {
            OutlinedButton(onClick = { biometric() }, enabled = !busy && waitSec == 0L, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null)
                Text("  Unlock with biometrics")
            }
        }
        TextButton(onClick = { recovering = true }) { Text("Forgot passphrase? Use recovery code") }
        TextButton(onClick = { confirmForget = true }) { Text("Use a different folder") }
    }
    if (confirmForget) {
        ConfirmDialog(
            title = "Use a different folder?",
            text = "${Brand.NAME} will forget this folder. Nothing in it is deleted; you can open it again later.",
            confirmLabel = "Forget folder",
            onConfirm = { vault.forgetFolder() },
            onDismiss = { confirmForget = false },
        )
    }
}

@Composable
fun RecoverScreen(onCancel: () -> Unit) {
    val vault = LocalAppContainer.current.vault
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val codeValid = RecoveryCode.normalize(code) != null
    CenteredPage {
        PageHeader("Recover your vault", "Enter your recovery code, then choose a new passphrase.")
        OutlinedTextField(
            value = code,
            onValueChange = { code = it; error = null },
            label = { Text("Recovery code") },
            supportingText = { Text("32 characters; dashes and spaces are ignored") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        PassphraseField(pass, { pass = it }, "New passphrase", supportingText = "At least ${VaultCrypto.MIN_PASSPHRASE_LENGTH} characters")
        PassphraseField(confirm, { confirm = it }, "Repeat new passphrase", isError = confirm.isNotEmpty() && confirm != pass)
        error?.let { ErrorText(it) }
        Button(
            onClick = {
                busy = true
                scope.launch {
                    when (val r = vault.recover(code, pass)) {
                        UnlockResult.Success -> {}
                        is UnlockResult.Wrong -> error = "That recovery code does not match this vault."
                        is UnlockResult.TooSoon -> error = "Too many attempts. Try again in ${(r.retryInMs + 999) / 1000} s."
                        is UnlockResult.Failed -> error = r.message
                    }
                    busy = false
                }
            },
            enabled = !busy && codeValid && pass.length >= VaultCrypto.MIN_PASSPHRASE_LENGTH && pass == confirm,
            modifier = Modifier.fillMaxWidth(),
        ) { if (busy) SmallSpinner() else Text("Recover and set passphrase") }
        TextButton(onClick = onCancel) { Text("Back") }
    }
}
