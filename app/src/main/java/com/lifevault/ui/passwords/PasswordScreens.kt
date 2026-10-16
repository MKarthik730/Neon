package com.lifevault.ui.passwords

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.navigation.NavHostController
import com.lifevault.domain.model.PasswordEntry
import com.lifevault.domain.model.PasswordVaultFile
import com.lifevault.domain.util.Ids
import com.lifevault.ui.common.ConfirmDialog
import com.lifevault.ui.common.EmptyState
import com.lifevault.ui.common.InfoText
import com.lifevault.ui.common.LocalGraph
import com.lifevault.ui.common.LocalSnackbar
import com.lifevault.ui.common.ScreenScaffold
import com.lifevault.ui.common.SectionCard

@Composable
fun PasswordsScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val file by graph.session.passwords.flow.collectAsStateWithLifecycle(initialValue = PasswordVaultFile())
    var selectedId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<PasswordEntry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<PasswordEntry?>(null) }
    val revealed = remember { mutableStateMapOf<String, Boolean>() }

    ScreenScaffold(
        title = "Password vault",
        onBack = { nav.popBackStack() },
        actions = {
            IconButton(onClick = { editing = null; creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add account")
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "Private account details") {
                InfoText("Account names, passwords, recovery notes and other key-value details are stored inside your encrypted vault.")
                InfoText("Reveal secrets only when needed. Copying puts the selected value on the device clipboard.")
            }
            if (file.entries.isEmpty()) {
                EmptyState("No saved accounts yet. Add an account to securely store its details.")
            }
            file.entries.forEach { entry ->
                val expanded = selectedId == entry.id
                SectionCard(
                    title = entry.title,
                    onClick = { selectedId = if (expanded) null else entry.id },
                    trailing = {
                        Row {
                            IconButton(onClick = { editing = entry; creating = false }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit ${entry.title}")
                            }
                            IconButton(onClick = { deleting = entry }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete ${entry.title}")
                            }
                        }
                    },
                ) {
                    InfoText("${entry.fields.size} saved detail(s) · tap to ${if (expanded) "hide" else "view"}")
                    if (expanded) {
                        entry.fields.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (label, value) ->
                            val secret = label.isSecretLabel()
                            val isRevealed = revealed["${entry.id}:$label"] == true
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        if (secret && !isRevealed) "••••••••" else value.ifBlank { "—" },
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                                if (secret) {
                                    IconButton(onClick = { revealed["${entry.id}:$label"] = !isRevealed }) {
                                        Icon(
                                            if (isRevealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = if (isRevealed) "Hide $label" else "Reveal $label",
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                                        val clip = ClipData.newPlainText(label, value)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            clip.description.extras = PersistableBundle().apply {
                                                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                                            }
                                        }
                                        clipboard.setPrimaryClip(clip)
                                        scope.launch { snackbar.showSnackbar("$label copied to clipboard.") }
                                    },
                                ) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy $label") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        PasswordEditorDialog(
            entry = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { saved ->
                scope.launch {
                    runCatching { graph.session.passwords.upsert(saved) }
                        .onSuccess { snackbar.showSnackbar("Account details saved in the encrypted vault.") }
                        .onFailure { snackbar.showSnackbar(it.message ?: "Could not save account details.") }
                }
                creating = false
                editing = null
            },
        )
    }
    deleting?.let { entry ->
        ConfirmDialog(
            title = "Delete saved account?",
            text = "“${entry.title}” and its stored details will be removed from the vault.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                scope.launch {
                    runCatching { graph.session.passwords.delete(entry.id) }
                        .onFailure { snackbar.showSnackbar(it.message ?: "Could not delete the account.") }
                }
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun PasswordEditorDialog(
    entry: PasswordEntry?,
    onDismiss: () -> Unit,
    onSave: (PasswordEntry) -> Unit,
) {
    var title by remember(entry?.id) { mutableStateOf(entry?.title.orEmpty()) }
    val fields = remember(entry?.id) {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(entry?.fields?.entries?.map { it.key to it.value }.orEmpty())
            if (isEmpty()) {
                add("Username" to "")
                add("Password" to "")
            }
        }
    }
    val hasValidField = fields.any { (key, value) -> key.isNotBlank() && value.isNotBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry == null) "Add account" else "Edit account") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Account name (e.g. Microsoft)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Details", style = MaterialTheme.typography.titleSmall)
                fields.forEachIndexed { index, pair ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = pair.first,
                            onValueChange = { fields[index] = it to pair.second },
                            label = { Text("Key (e.g. Email, Password, Recovery note)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = pair.second,
                                onValueChange = { fields[index] = pair.first to it },
                                label = { Text("Value") },
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { if (fields.size > 1) fields.removeAt(index) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove detail")
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = { fields.add("" to "") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("  Add key-value detail")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val values = fields.mapNotNull { (key, value) ->
                        key.trim().takeIf { it.isNotEmpty() }?.let { it to value }
                    }.toMap()
                    onSave(
                        PasswordEntry(
                            id = entry?.id ?: Ids.new(),
                            title = title.trim(),
                            fields = values,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                },
                enabled = title.isNotBlank() && hasValidField,
            ) { Text("Save securely") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun String.isSecretLabel(): Boolean {
    val normalized = lowercase()
    return listOf("password", "passcode", "secret", "token", "recovery", "private key").any(normalized::contains)
}
