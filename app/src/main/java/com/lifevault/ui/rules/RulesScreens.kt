package com.lifevault.ui.rules

import com.lifevault.Brand
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.lifevault.di.UnlockedGraph
import com.lifevault.domain.model.Rule
import com.lifevault.domain.model.RuleReminder
import com.lifevault.domain.model.RuleSet
import com.lifevault.domain.model.RulesFile
import com.lifevault.domain.rules.RuleOrdering
import com.lifevault.domain.util.Ids
import com.lifevault.ui.attendance.short
import com.lifevault.ui.common.ChipSelector
import com.lifevault.ui.common.CollectMessages
import com.lifevault.ui.common.ConfirmDialog
import com.lifevault.ui.common.EmptyState
import com.lifevault.ui.common.Format
import com.lifevault.ui.common.InfoText
import com.lifevault.ui.common.LoadingBox
import com.lifevault.ui.common.LocalGraph
import com.lifevault.ui.common.ScreenScaffold
import com.lifevault.ui.common.SectionCard
import com.lifevault.ui.common.TimeField
import com.lifevault.ui.common.VaultViewModel
import com.lifevault.ui.main.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalTime

class RulesViewModel(graph: UnlockedGraph) : VaultViewModel(graph) {
    val file: StateFlow<RulesFile?> = session.rules.flow.stateIn(viewModelScope, SharingStarted.Eagerly, session.rules.cached)

    fun create(name: String, open: (String) -> Unit) = launchSafe {
        val set = RuleSet(Ids.new(), name.trim())
        session.rules.upsertSet(set)
        open(set.id)
    }
}

@Composable
fun RuleSetsScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val vm: RulesViewModel = viewModel { RulesViewModel(graph) }
    CollectMessages(vm.messages)
    val file by vm.file.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    ScreenScaffold(
        title = "Ground rules",
        onBack = { nav.popBackStack() },
        actions = { TextButton(onClick = { nav.navigate(Routes.JOB_LOG) }) { Text("Job log") } },
        floatingActionButton = { com.lifevault.ui.common.AddFab("Rule set") { creating = true } },
    ) { padding ->
        val f = file
        if (f == null) {
            LoadingBox(Modifier.padding(padding))
            return@ScreenScaffold
        }
        val active = f.sets.filterNot { it.archived }
        val archived = f.sets.filter { it.archived }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                InfoText("Write your own rules and read them before you start a job. Named sets like \"Before studying\" or \"Before a test\". ${Brand.NAME} ships with none.")
            }
            item {
                AssistChip(onClick = { nav.navigate(Routes.startJob()) }, label = { Text("Start job") }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) })
            }
            if (active.isEmpty()) item { EmptyState("No rule sets yet.") }
            items(active, key = { it.id }) { set ->
                SectionCard(
                    title = set.name,
                    onClick = { nav.navigate(Routes.ruleSet(set.id)) },
                    trailing = { IconButton(onClick = { nav.navigate(Routes.startJob(set.id)) }) { Icon(Icons.Filled.PlayArrow, contentDescription = "Start job with ${set.name}") } },
                ) {
                    val rules = RuleOrdering.activeOrdered(set)
                    InfoText("${rules.size} rule(s)" + (f.reminders.count { it.ruleSetId == set.id }.takeIf { it > 0 }?.let { " · $it reminder(s)" } ?: ""))
                    for (r in rules.take(3)) Text("• ${r.text}", style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                }
            }
            if (archived.isNotEmpty()) {
                item { TextButton(onClick = { showArchived = !showArchived }) { Text(if (showArchived) "Hide archived" else "Show ${archived.size} archived") } }
                if (showArchived) items(archived, key = { it.id }) { set ->
                    SectionCard(title = set.name, onClick = { nav.navigate(Routes.ruleSet(set.id)) }) { InfoText("Archived") }
                }
            }
        }
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New rule set") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. Before studying") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.create(name) { nav.navigate(Routes.ruleSet(it)) }; creating = false }, enabled = name.isNotBlank()) { Text("Create") } },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
}

class RuleSetEditViewModel(graph: UnlockedGraph, private val id: String) : VaultViewModel(graph) {
    val file: StateFlow<RulesFile?> = session.rules.flow.stateIn(viewModelScope, SharingStarted.Eagerly, session.rules.cached)
    val deleted = MutableStateFlow(false)

    private fun edit(f: (RuleSet) -> RuleSet) = launchSafe {
        val set = session.rules.get().sets.firstOrNull { it.id == id } ?: return@launchSafe
        session.rules.upsertSet(f(set))
    }

    fun rename(name: String) = edit { it.copy(name = name.trim()) }
    fun addRule(text: String) = edit { it.copy(rules = it.rules + Rule(Ids.new(), text.trim())) }
    fun editRule(ruleId: String, text: String) = edit { s -> s.copy(rules = s.rules.map { if (it.id == ruleId) it.copy(text = text.trim()) else it }) }
    fun togglePin(ruleId: String) = edit { s -> s.copy(rules = s.rules.map { if (it.id == ruleId) it.copy(pinned = !it.pinned) else it }) }
    fun toggleArchiveRule(ruleId: String) = edit { s -> s.copy(rules = s.rules.map { if (it.id == ruleId) it.copy(archived = !it.archived) else it }) }
    fun deleteRule(ruleId: String) = edit { s -> s.copy(rules = s.rules.filterNot { it.id == ruleId }) }
    fun move(ruleId: String, delta: Int) = edit { RuleOrdering.moveBy(it, ruleId, delta) }
    fun toggleArchiveSet() = edit { it.copy(archived = !it.archived) }
    fun deleteSet() = launchSafe { session.rules.deleteSet(id); deleted.value = true }
    fun saveReminder(r: RuleReminder) = launchSafe { session.rules.upsertReminder(r) }
    fun deleteReminder(rid: String) = launchSafe { session.rules.deleteReminder(rid) }
}

@Composable
fun RuleSetEditScreen(nav: NavHostController, id: String) {
    val graph = LocalGraph.current
    val vm: RuleSetEditViewModel = viewModel { RuleSetEditViewModel(graph, id) }
    CollectMessages(vm.messages)
    val file by vm.file.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    var newRule by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Rule?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reminder by remember { mutableStateOf<RuleReminder?>(null) }
    LaunchedEffect(deleted) { if (deleted) nav.popBackStack() }
    val set = file?.sets?.firstOrNull { it.id == id }
    ScreenScaffold(
        title = set?.name ?: "Rule set",
        onBack = { nav.popBackStack() },
        actions = {
            if (set != null) {
                TextButton(onClick = { renaming = true }) { Text("Rename") }
                IconButton(onClick = { vm.toggleArchiveSet() }) {
                    Icon(if (set.archived) Icons.Filled.Unarchive else Icons.Filled.Archive, contentDescription = if (set.archived) "Restore rule set" else "Archive rule set")
                }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete rule set") }
            }
        },
    ) { padding ->
        if (set == null) {
            if (file == null) LoadingBox(Modifier.padding(padding)) else EmptyState("This rule set no longer exists.", Modifier.padding(padding))
            return@ScreenScaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoText("Pinned rules are shown first. Archived rules are kept but not shown before jobs.")
            set.rules.forEachIndexed { index, rule ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        rule.text,
                        modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (rule.archived) TextDecoration.LineThrough else null,
                    )
                    IconButton(onClick = { vm.togglePin(rule.id) }) {
                        Icon(if (rule.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, contentDescription = if (rule.pinned) "Unpin" else "Pin")
                    }
                    IconButton(onClick = { vm.move(rule.id, -1) }, enabled = index > 0) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up") }
                    IconButton(onClick = { vm.move(rule.id, 1) }, enabled = index < set.rules.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down") }
                }
                Row {
                    TextButton(onClick = { editing = rule }) { Text("Edit") }
                    TextButton(onClick = { vm.toggleArchiveRule(rule.id) }) { Text(if (rule.archived) "Restore" else "Archive") }
                    TextButton(onClick = { vm.deleteRule(rule.id) }) { Text("Delete") }
                }
                HorizontalDivider()
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newRule, { newRule = it }, label = { Text("New rule") }, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (newRule.isNotBlank()) vm.addRule(newRule); newRule = "" }) { Text("Add") }
            }
            SectionCard(title = "Recurring reminders") {
                val reminders = file?.reminders?.filter { it.ruleSetId == id }.orEmpty()
                if (reminders.isEmpty()) InfoText("Get a \"Read your ground rules\" notification before a regular activity (e.g. a shift).")
                for (r in reminders) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${r.label} · ${Format.time(r.time)}", style = MaterialTheme.typography.bodyLarge)
                            InfoText(r.days.joinToString(" ") { it.short() } + if (!r.enabled) " (off)" else "")
                        }
                        TextButton(onClick = { reminder = r }) { Text("Edit") }
                        IconButton(onClick = { vm.deleteReminder(r.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete reminder") }
                    }
                }
                TextButton(onClick = { reminder = RuleReminder(Ids.new(), id, set.name, LocalTime.of(9, 0)) }) { Text("Add reminder") }
            }
            TextButton(onClick = { nav.navigate(Routes.startJob(id)) }) { Text("Start a job with these rules") }
        }
    }
    editing?.let { r ->
        var text by remember(r.id) { mutableStateOf(r.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit rule") },
            text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { vm.editRule(r.id, text); editing = null }, enabled = text.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
    if (renaming && set != null) {
        var name by remember { mutableStateOf(set.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(name); renaming = false }, enabled = name.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    reminder?.let { r ->
        var draft by remember(r.id) { mutableStateOf(r) }
        AlertDialog(
            onDismissRequest = { reminder = null },
            title = { Text("Reminder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(draft.label, { draft = draft.copy(label = it) }, label = { Text("Label (kept in the vault)") }, singleLine = true)
                    TimeField("Time", draft.time, { draft = draft.copy(time = it) })
                    ChipSelector(DayOfWeek.entries.map { it.short() }, draft.days.map { it.short() }.toSet(), onToggle = { lbl ->
                        val d = DayOfWeek.entries.first { it.short() == lbl }
                        draft = draft.copy(days = if (d in draft.days) draft.days - d else (draft.days + d).sorted())
                    })
                    com.lifevault.ui.common.SwitchRow("Enabled", draft.enabled, { draft = draft.copy(enabled = it) })
                }
            },
            confirmButton = { TextButton(onClick = { vm.saveReminder(draft); reminder = null }, enabled = draft.days.isNotEmpty()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { reminder = null }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        ConfirmDialog("Delete this rule set?", "Its reminders are removed too. Past job logs keep the set's name.", confirmLabel = "Delete", destructive = true, onConfirm = { vm.deleteSet() }, onDismiss = { confirmDelete = false })
    }
}
