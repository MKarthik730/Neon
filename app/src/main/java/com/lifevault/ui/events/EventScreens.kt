package com.lifevault.ui.events

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.lifevault.di.UnlockedGraph
import com.lifevault.domain.model.Event
import com.lifevault.domain.model.EventKind
import com.lifevault.domain.model.EventsFile
import com.lifevault.domain.model.RuleSet
import com.lifevault.domain.util.Ids
import com.lifevault.ui.common.CollectMessages
import com.lifevault.ui.common.ConfirmDialog
import com.lifevault.ui.common.DateField
import com.lifevault.ui.common.Dropdown
import com.lifevault.ui.common.EmptyState
import com.lifevault.ui.common.ErrorText
import com.lifevault.ui.common.Format
import com.lifevault.ui.common.InfoText
import com.lifevault.ui.common.LoadingBox
import com.lifevault.ui.common.LocalGraph
import com.lifevault.ui.common.ScreenScaffold
import com.lifevault.ui.common.SectionCard
import com.lifevault.ui.common.SwitchRow
import com.lifevault.ui.common.TimeField
import com.lifevault.ui.common.VaultViewModel
import com.lifevault.ui.main.Routes
import com.lifevault.ui.media.AttachmentsEditor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class EventsViewModel(graph: UnlockedGraph) : VaultViewModel(graph) {
    val file: StateFlow<EventsFile?> = session.events.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun EventsScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val vm: EventsViewModel = viewModel { EventsViewModel(graph) }
    val file by vm.file.collectAsStateWithLifecycle()
    var showPast by remember { mutableStateOf(false) }
    ScreenScaffold(
        title = "Events & tests",
        onBack = { nav.popBackStack() },
        floatingActionButton = {
            com.lifevault.ui.common.AddFab("New event") { nav.navigate(Routes.event(Routes.NEW)) }
        },
    ) { padding ->
        val f = file
        if (f == null) {
            LoadingBox(Modifier.padding(padding))
            return@ScreenScaffold
        }
        val now = LocalDateTime.now()
        val upcoming = f.events.filter { !it.startsAt().isBefore(now.minusHours(1)) }.sortedBy { it.startsAt() }
        val past = f.events.filter { it.startsAt().isBefore(now.minusHours(1)) }.sortedByDescending { it.startsAt() }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (upcoming.isEmpty()) item { EmptyState("No upcoming events. Add class events, tests or anything else, with an optional reminder.") }
            items(upcoming, key = { it.id }) { e -> EventRow(e) { nav.navigate(Routes.event(e.id)) } }
            if (past.isNotEmpty()) {
                item { TextButton(onClick = { showPast = !showPast }) { Text(if (showPast) "Hide past events" else "Show ${past.size} past event(s)") } }
                if (showPast) items(past, key = { it.id }) { e -> EventRow(e) { nav.navigate(Routes.event(e.id)) } }
            }
        }
    }
}

@Composable
private fun EventRow(e: Event, onClick: () -> Unit) {
    SectionCard(onClick = onClick) {
        Text("${e.kind.label}: ${e.title}", style = MaterialTheme.typography.titleSmall)
        InfoText(Format.date(e.date) + (e.time?.let { " · " + Format.time(it) } ?: " · all day") + (e.subject?.let { " · $it" } ?: ""))
        if (e.reminderMinutesBefore != null || e.mediaIds.isNotEmpty() || e.ruleSetId != null) {
            InfoText(
                listOfNotNull(
                    e.reminderMinutesBefore?.let { "reminder $it min before" },
                    e.mediaIds.takeIf { it.isNotEmpty() }?.let { "${it.size} attachment(s)" },
                    e.ruleSetId?.let { "ground rules linked" },
                ).joinToString(" · "),
            )
        }
    }
}

class EventEditViewModel(graph: UnlockedGraph, private val id: String) : VaultViewModel(graph) {
    val draft = MutableStateFlow<Event?>(null)
    val subjects = MutableStateFlow<List<String>>(emptyList())
    val ruleSets = MutableStateFlow<List<RuleSet>>(emptyList())
    val done = MutableStateFlow(false)
    val isNew get() = id == Routes.NEW

    init {
        launchSafe {
            val settings = session.settings.get()
            subjects.value = session.timetable.get().allSubjects()
            ruleSets.value = session.rules.get().sets.filterNot { it.archived }
            draft.value = session.events.get().events.firstOrNull { it.id == id }
                ?: Event(Ids.new(), EventKind.CUSTOM, "", LocalDate.now(), LocalTime.of(10, 0), reminderMinutesBefore = settings.notifications.defaultEventReminderMinutes)
        }
    }

    fun update(f: (Event) -> Event) { draft.value = draft.value?.let(f) }
    fun save() = launchSafe {
        val e = draft.value ?: return@launchSafe
        session.events.upsert(e.copy(title = e.title.trim()))
        done.value = true
    }
    fun delete() = launchSafe { session.events.delete(id); done.value = true }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditScreen(nav: NavHostController, id: String) {
    val graph = LocalGraph.current
    val vm: EventEditViewModel = viewModel { EventEditViewModel(graph, id) }
    CollectMessages(vm.messages)
    val draft by vm.draft.collectAsStateWithLifecycle()
    val subjects by vm.subjects.collectAsStateWithLifecycle()
    val ruleSets by vm.ruleSets.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(done) { if (done) nav.popBackStack() }
    ScreenScaffold(
        title = if (vm.isNew) "New event" else "Edit event",
        onBack = { nav.popBackStack() },
        actions = { if (!vm.isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete event") } },
    ) { padding ->
        val e = draft
        if (e == null) {
            LoadingBox(Modifier.padding(padding))
            return@ScreenScaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                EventKind.entries.forEachIndexed { i, k ->
                    SegmentedButton(selected = e.kind == k, onClick = { vm.update { it.copy(kind = k) } }, shape = SegmentedButtonDefaults.itemShape(i, EventKind.entries.size)) { Text(k.label) }
                }
            }
            OutlinedTextField(e.title, { t -> vm.update { it.copy(title = t) } }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            DateField("Date", e.date, { d -> vm.update { it.copy(date = d) } })
            SwitchRow("All day", e.time == null, { allDay -> vm.update { it.copy(time = if (allDay) null else LocalTime.of(10, 0)) } }, subtitle = if (e.time == null) "Reminders are based on 09:00" else null)
            e.time?.let { t -> TimeField("Time", t, { nt -> vm.update { it.copy(time = nt) } }) }
            if (subjects.isNotEmpty()) {
                Dropdown("Subject", listOf<String?>(null) + subjects, e.subject, { it ?: "None" }, { s -> vm.update { it.copy(subject = s) } })
            }
            OutlinedTextField(e.notes, { n -> vm.update { it.copy(notes = n) } }, label = { Text("Notes") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            SectionCard(title = "Reminder") {
                val options = listOf<Int?>(null, 5, 10, 15, 30, 60, 120, 24 * 60)
                Dropdown("Remind me", options, e.reminderMinutesBefore, { m -> m?.let { if (it >= 60) "${it / 60} h before" else "$it min before" } ?: "No reminder" }, { m -> vm.update { it.copy(reminderMinutesBefore = m) } })
                InfoText("Notifications only say \"Upcoming event\" — the title stays inside the vault.")
            }
            SectionCard(title = "Ground rules before this event") {
                if (ruleSets.isEmpty()) {
                    InfoText("Create a rule set under More → Ground rules to link it here.")
                } else {
                    Dropdown("Rule set", listOf<RuleSet?>(null) + ruleSets, ruleSets.firstOrNull { it.id == e.ruleSetId }, { it?.name ?: "None" }, { rs -> vm.update { it.copy(ruleSetId = rs?.id) } })
                    if (e.ruleSetId != null) {
                        Dropdown("Prompt", listOf(0, 5, 10, 15, 30, 60), e.rulesPromptMinutesBefore, { if (it == 0) "at start" else "$it min before" }, { m -> vm.update { it.copy(rulesPromptMinutesBefore = m) } })
                        OutlinedButton(onClick = { nav.navigate(Routes.startJob(e.ruleSetId, e.title)) }) { Text("Read rules and start now") }
                    }
                }
            }
            AttachmentsEditor(nav, e.mediaIds) { ids -> vm.update { it.copy(mediaIds = ids) } }
            if (e.title.isBlank()) ErrorText("Add a title.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.save() }, enabled = e.title.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Save") }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete this event?", "Its attachments stay in the media vault.", confirmLabel = "Delete", destructive = true, onConfirm = { vm.delete() }, onDismiss = { confirmDelete = false })
    }
}
