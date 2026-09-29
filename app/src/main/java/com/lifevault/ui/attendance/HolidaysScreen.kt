package com.lifevault.ui.attendance

import android.content.Context
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.lifevault.di.UnlockedGraph
import com.lifevault.domain.csv.HolidayCsv
import com.lifevault.domain.csv.ImportPreview
import com.lifevault.domain.model.Holiday
import com.lifevault.domain.model.HolidaysFile
import com.lifevault.domain.util.Ids
import com.lifevault.ui.common.ChipSelector
import com.lifevault.ui.common.CollectMessages
import com.lifevault.ui.common.DateField
import com.lifevault.ui.common.EmptyState
import com.lifevault.ui.common.ErrorText
import com.lifevault.ui.common.Format
import com.lifevault.ui.common.InfoText
import com.lifevault.ui.common.LoadingBox
import com.lifevault.ui.common.LocalGraph
import com.lifevault.ui.common.ScreenScaffold
import com.lifevault.ui.common.SectionCard
import com.lifevault.ui.common.VaultViewModel
import com.lifevault.ui.common.rememberExternalLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate

class HolidaysViewModel(graph: UnlockedGraph) : VaultViewModel(graph) {
    val file: StateFlow<HolidaysFile?> = session.holidays.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val importPreview = MutableStateFlow<ImportPreview<Holiday>?>(null)

    fun save(h: Holiday) = launchSafe { session.holidays.upsert(h) }
    fun delete(id: String) = launchSafe { session.holidays.delete(id) }
    fun toggleOffDay(d: DayOfWeek) = launchSafe {
        val current = session.holidays.get().weeklyOffDays.toSet()
        session.holidays.setOffDays(if (d in current) current - d else current + d)
    }
    fun loadCsv(context: Context, uri: Uri) = launchSafe { importPreview.value = HolidayCsv.parse(readText(context, uri)) }
    fun confirmImport() = launchSafe {
        val p = importPreview.value ?: return@launchSafe
        if (p.canSave) {
            session.holidays.addAll(p.items)
            say("Imported ${p.items.size} holiday(s)")
        }
        importPreview.value = null
    }
    fun export(context: Context, uri: Uri) = launchSafe { writeText(context, uri, HolidayCsv.export(session.holidays.get().holidays)); say("Exported") }
}

@Composable
fun HolidaysScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val vm: HolidaysViewModel = viewModel { HolidaysViewModel(graph) }
    CollectMessages(vm.messages)
    val context = LocalContext.current
    val file by vm.file.collectAsStateWithLifecycle()
    val preview by vm.importPreview.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Holiday?>(null) }
    val picker = rememberExternalLauncher(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.loadCsv(context, uri) }
    val exporter = rememberExternalLauncher(ActivityResultContracts.CreateDocument("text/csv")) { uri -> if (uri != null) vm.export(context, uri) }
    ScreenScaffold(
        title = "Holidays",
        onBack = { nav.popBackStack() },
        actions = {
            IconButton(onClick = { picker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) }) {
                Icon(Icons.Filled.UploadFile, contentDescription = "Import CSV")
            }
            IconButton(onClick = { exporter.launch("holidays.csv") }) { Icon(Icons.Filled.Download, contentDescription = "Export CSV") }
        },
        floatingActionButton = {
            com.lifevault.ui.common.AddFab("Add holiday") { editing = Holiday(Ids.new(), LocalDate.now(), LocalDate.now(), "") }
        },
    ) { padding ->
        val f = file
        if (f == null) {
            LoadingBox(Modifier.padding(padding))
            return@ScreenScaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(title = "Weekly off days") {
                    InfoText("No sessions or attendance prompts on these days.")
                    ChipSelector(DayOfWeek.entries.map { it.short() }, f.weeklyOffDays.map { it.short() }.toSet(), onToggle = { label ->
                        DayOfWeek.entries.firstOrNull { it.short() == label }?.let(vm::toggleOffDay)
                    })
                }
            }
            item {
                InfoText("Holidays remove both sessions of the day from attendance totals and from notifications. CSV format: start,end,label (end optional), dates as yyyy-MM-dd.")
            }
            if (f.holidays.isEmpty()) item { EmptyState("No holidays added.") }
            val today = LocalDate.now()
            val (upcoming, past) = f.holidays.partition { !it.end.isBefore(today) }
            if (upcoming.isNotEmpty()) item { Text("Upcoming", style = MaterialTheme.typography.titleMedium) }
            items(upcoming, key = { it.id }) { h -> HolidayRow(h, onEdit = { editing = h }, onDelete = { vm.delete(h.id) }) }
            if (past.isNotEmpty()) item { Text("Past", style = MaterialTheme.typography.titleMedium) }
            items(past.reversed(), key = { it.id }) { h -> HolidayRow(h, onEdit = { editing = h }, onDelete = { vm.delete(h.id) }) }
        }
    }
    editing?.let { h -> HolidayDialog(h, onSave = { vm.save(it); editing = null }, onDismiss = { editing = null }) }
    preview?.let { p ->
        AlertDialog(
            onDismissRequest = { vm.importPreview.value = null },
            title = { Text("Import holidays") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ImportPreviewCard(p) { list -> for (h in list) Text("${Format.shortDate(h.start)}${if (h.end != h.start) " – " + Format.shortDate(h.end) else ""}: ${h.label}") }
                }
            },
            confirmButton = { TextButton(onClick = { vm.confirmImport() }, enabled = p.canSave) { Text("Import") } },
            dismissButton = { TextButton(onClick = { vm.importPreview.value = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HolidayRow(h: Holiday, onEdit: () -> Unit, onDelete: () -> Unit) {
    SectionCard(onClick = onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(h.label, style = MaterialTheme.typography.titleSmall)
                InfoText(if (h.start == h.end) Format.date(h.start) else "${Format.date(h.start)} – ${Format.date(h.end)}")
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${h.label}") }
        }
    }
}

@Composable
private fun HolidayDialog(initial: Holiday, onSave: (Holiday) -> Unit, onDismiss: () -> Unit) {
    var h by remember { mutableStateOf(initial) }
    val error = when {
        h.label.isBlank() -> "Add a label."
        h.end.isBefore(h.start) -> "The end date is before the start date."
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Holiday") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(h.label, { h = h.copy(label = it) }, label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DateField("From", h.start, { d -> h = h.copy(start = d, end = if (h.end.isBefore(d)) d else h.end) })
                DateField("To", h.end, { h = h.copy(end = it) })
                error?.let { ErrorText(it) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(h.copy(label = h.label.trim())) }, enabled = error == null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
