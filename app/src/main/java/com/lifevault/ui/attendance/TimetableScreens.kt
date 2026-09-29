package com.lifevault.ui.attendance

import android.content.Context
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.lifevault.di.UnlockedGraph
import com.lifevault.domain.csv.CsvIssue
import com.lifevault.domain.csv.ImportPreview
import com.lifevault.domain.csv.TimetableCsv
import com.lifevault.domain.model.Session
import com.lifevault.domain.model.TimetableFile
import com.lifevault.domain.model.TimetableSlot
import com.lifevault.domain.model.TimetableVersion
import com.lifevault.ui.common.CollectMessages
import com.lifevault.ui.common.ConfirmDialog
import com.lifevault.ui.common.DateField
import com.lifevault.ui.common.EmptyState
import com.lifevault.ui.common.ErrorText
import com.lifevault.ui.common.Format
import com.lifevault.ui.common.InfoText
import com.lifevault.ui.common.LoadingBox
import com.lifevault.ui.common.LocalGraph
import com.lifevault.ui.common.ScreenScaffold
import com.lifevault.ui.common.SectionCard
import com.lifevault.ui.common.TimeField
import com.lifevault.ui.common.VaultViewModel
import com.lifevault.ui.common.rememberExternalLauncher
import com.lifevault.ui.main.Routes
import com.lifevault.ui.theme.LocalStatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

fun DayOfWeek.label(): String = getDisplayName(TextStyle.FULL, Locale.getDefault())
fun DayOfWeek.short(): String = getDisplayName(TextStyle.SHORT, Locale.getDefault())

/** Reads a small text file the user picked (CSV imports). */
suspend fun readText(context: Context, uri: Uri, maxBytes: Int = 1 shl 20): String = withContext(Dispatchers.IO) {
    val bytes = (context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the file")).use { input ->
        val buf = input.readNBytesCompat(maxBytes + 1)
        if (buf.size > maxBytes) throw IOException("The file is too large for an import (over 1 MB).")
        buf
    }
    bytes.toString(Charsets.UTF_8)
}

private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (total < n) {
        val r = read(buf, 0, minOf(buf.size, n - total))
        if (r < 0) break
        out.write(buf, 0, r)
        total += r
    }
    return out.toByteArray()
}

suspend fun writeText(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
    (context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write the file")).use { it.write(text.toByteArray()) }
}

class TimetableViewModel(graph: UnlockedGraph) : VaultViewModel(graph) {
    val file: StateFlow<TimetableFile?> = session.timetable.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun delete(id: String) = launchSafe { session.timetable.deleteVersion(id); say("Timetable deleted") }
    fun export(context: Context, uri: Uri, v: TimetableVersion) = launchSafe { writeText(context, uri, TimetableCsv.export(v.slots)); say("Exported") }
}

@Composable
fun TimetableScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val vm: TimetableViewModel = viewModel { TimetableViewModel(graph) }
    CollectMessages(vm.messages)
    val file by vm.file.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var exporting by remember { mutableStateOf<TimetableVersion?>(null) }
    var deleting by remember { mutableStateOf<TimetableVersion?>(null) }
    val exporter = rememberExternalLauncher(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val v = exporting
        if (uri != null && v != null) vm.export(context, uri, v)
        exporting = null
    }
    ScreenScaffold(
        title = "Timetable",
        onBack = { nav.popBackStack() },
        actions = { IconButton(onClick = { nav.navigate(Routes.TIMETABLE_IMPORT) }) { Icon(Icons.Filled.UploadFile, contentDescription = "Import CSV") } },
        floatingActionButton = {
            com.lifevault.ui.common.AddFab("New timetable") { nav.navigate(Routes.timetableEdit(Routes.NEW)) }
        },
    ) { padding ->
        val f = file
        if (f == null) {
            LoadingBox(Modifier.padding(padding))
            return@ScreenScaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                InfoText("Each timetable applies from its start date. Adding a new one for a new semester closes the previous one the day before, so past attendance keeps its original schedule.")
            }
            if (f.versions.isEmpty()) item { EmptyState("No timetable yet.", actionLabel = "Import from CSV", onAction = { nav.navigate(Routes.TIMETABLE_IMPORT) }) }
            items(f.versions.sortedByDescending { it.effectiveFrom }, key = { it.id }) { v ->
                SectionCard(
                    title = v.name,
                    trailing = {
                        Row {
                            IconButton(onClick = { exporting = v; exporter.launch("timetable-${v.name.replace(' ', '-')}.csv") }) { Icon(Icons.Filled.Download, contentDescription = "Export ${v.name} as CSV") }
                            IconButton(onClick = { nav.navigate(Routes.timetableEdit(v.id)) }) { Icon(Icons.Filled.Edit, contentDescription = "Edit ${v.name}") }
                            IconButton(onClick = { deleting = v }) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${v.name}") }
                        }
                    },
                ) {
                    InfoText("From ${Format.date(v.effectiveFrom)}" + (v.effectiveTo?.let { " to ${Format.date(it)}" } ?: " (open-ended)"))
                    SlotSummary(v.slots)
                }
            }
        }
    }
    deleting?.let { v ->
        ConfirmDialog(
            title = "Delete ${v.name}?",
            text = "Attendance records are kept, but days covered only by this timetable will no longer count.",
            confirmLabel = "Delete", destructive = true,
            onConfirm = { vm.delete(v.id) }, onDismiss = { deleting = null },
        )
    }
}

@Composable
fun SlotSummary(slots: List<TimetableSlot>) {
    for (day in DayOfWeek.entries) {
        val daySlots = slots.filter { it.day == day }.sortedBy { it.session }
        if (daySlots.isEmpty()) continue
        Text(
            day.short() + ": " + daySlots.joinToString("  ·  ") { "${it.session.label} ${Format.time(it.start)}–${Format.time(it.end)} ${it.subjects.joinToString(", ")}" },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

internal data class SlotDraft(val enabled: Boolean, val start: LocalTime, val end: LocalTime, val subjects: String)

internal data class TimetableDraft(
    val name: String,
    val from: LocalDate,
    val to: LocalDate?,
    val slots: Map<Pair<DayOfWeek, Session>, SlotDraft>,
)

class TimetableEditViewModel(graph: UnlockedGraph, private val id: String) : VaultViewModel(graph) {
    private val _draft = MutableStateFlow<TimetableDraft?>(null)
    internal val draft: StateFlow<TimetableDraft?> = _draft
    val saved = MutableStateFlow(false)

    init {
        launchSafe {
            val v = if (id == Routes.NEW) null else session.timetable.get().versions.firstOrNull { it.id == id }
            val slots = buildMap {
                for (d in DayOfWeek.entries) for (s in Session.entries) {
                    val existing = v?.slot(d, s)
                    put(
                        d to s,
                        SlotDraft(
                            enabled = existing != null,
                            start = existing?.start ?: if (s == Session.MORNING) LocalTime.of(9, 0) else LocalTime.of(14, 0),
                            end = existing?.end ?: if (s == Session.MORNING) LocalTime.of(12, 30) else LocalTime.of(17, 0),
                            subjects = existing?.subjects?.joinToString(", ") ?: "",
                        ),
                    )
                }
            }
            _draft.value = TimetableDraft(v?.name ?: "Semester ${LocalDate.now().year}", v?.effectiveFrom ?: LocalDate.now(), v?.effectiveTo, slots)
        }
    }

    internal fun update(f: (TimetableDraft) -> TimetableDraft) { _draft.value = _draft.value?.let(f) }

    internal fun errors(d: TimetableDraft): List<String> = buildList {
        if (d.name.isBlank()) add("Give the timetable a name.")
        if (d.to != null && d.to.isBefore(d.from)) add("The end date is before the start date.")
        for ((k, s) in d.slots) {
            if (!s.enabled) continue
            val label = "${k.first.short()} ${k.second.label}"
            if (!s.end.isAfter(s.start)) add("$label: end must be after start.")
            if (s.subjects.split(',').none { it.isNotBlank() }) add("$label: add at least one subject.")
        }
        if (d.slots.values.none { it.enabled }) add("Turn on at least one session.")
    }

    fun save() = launchSafe {
        val d = _draft.value ?: return@launchSafe
        if (errors(d).isNotEmpty()) return@launchSafe
        val slots = d.slots.filter { it.value.enabled }.map { (k, s) ->
            TimetableSlot(k.first, k.second, s.start, s.end, s.subjects.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        }.sortedWith(compareBy({ it.day }, { it.session }))
        if (id == Routes.NEW) {
            session.timetable.addVersion(d.name, d.from, slots, d.to)
        } else {
            val old = session.timetable.get().versions.first { it.id == id }
            session.timetable.saveVersion(old.copy(name = d.name, effectiveFrom = d.from, effectiveTo = d.to, slots = slots))
        }
        saved.value = true
    }
}

@Composable
fun TimetableEditScreen(nav: NavHostController, id: String) {
    val graph = LocalGraph.current
    val vm: TimetableEditViewModel = viewModel { TimetableEditViewModel(graph, id) }
    CollectMessages(vm.messages)
    val draft by vm.draft.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    LaunchedEffect(saved) { if (saved) nav.popBackStack() }
    ScreenScaffold(title = if (id == Routes.NEW) "New timetable" else "Edit timetable", onBack = { nav.popBackStack() }) { padding ->
        val d = draft
        if (d == null) {
            LoadingBox(Modifier.padding(padding))
            return@ScreenScaffold
        }
        val errors = vm.errors(d)
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(d.name, { v -> vm.update { it.copy(name = v) } }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            DateField("Starts", d.from, { v -> vm.update { it.copy(from = v) } })
            Row(verticalAlignment = Alignment.CenterVertically) {
                DateField("Ends", d.to, { v -> vm.update { it.copy(to = v) } }, placeholder = "open-ended")
                if (d.to != null) TextButton(onClick = { vm.update { it.copy(to = null) } }) { Text("Clear") }
            }
            InfoText("Separate several subjects with commas.")
            for (day in DayOfWeek.entries) {
                SectionCard(title = day.label()) {
                    for (s in Session.entries) {
                        val slot = d.slots.getValue(day to s)
                        fun set(n: SlotDraft) = vm.update { it.copy(slots = it.slots + ((day to s) to n)) }
                        com.lifevault.ui.common.CheckboxRow(slot.enabled, { set(slot.copy(enabled = it)) }, s.label, style = MaterialTheme.typography.titleSmall)
                        if (slot.enabled) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TimeField("Start", slot.start, { set(slot.copy(start = it)) })
                                TimeField("End", slot.end, { set(slot.copy(end = it)) })
                            }
                            OutlinedTextField(
                                slot.subjects, { set(slot.copy(subjects = it)) },
                                label = { Text("Subjects") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            )
                        }
                    }
                }
            }
            for (e in errors) ErrorText(e)
            Button(onClick = { vm.save() }, enabled = errors.isEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Save timetable") }
        }
    }
}

class TimetableImportViewModel(graph: UnlockedGraph) : VaultViewModel(graph) {
    val text = MutableStateFlow("")
    val preview = MutableStateFlow<ImportPreview<TimetableSlot>?>(null)
    val name = MutableStateFlow("Semester ${LocalDate.now().year}")
    val from = MutableStateFlow(LocalDate.now())
    val saved = MutableStateFlow(false)

    fun load(context: Context, uri: Uri) = launchSafe {
        val t = readText(context, uri)
        text.value = t
        parse()
    }

    fun parse() { preview.value = TimetableCsv.parse(text.value) }

    fun save() = launchSafe {
        val p = preview.value ?: return@launchSafe
        if (!p.canSave) return@launchSafe
        session.timetable.addVersion(name.value, from.value, p.items)
        saved.value = true
    }
}

@Composable
fun TimetableImportScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val vm: TimetableImportViewModel = viewModel { TimetableImportViewModel(graph) }
    CollectMessages(vm.messages)
    val context = LocalContext.current
    val text by vm.text.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val name by vm.name.collectAsStateWithLifecycle()
    val from by vm.from.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    LaunchedEffect(saved) { if (saved) nav.popBackStack() }
    val picker = rememberExternalLauncher(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.load(context, uri) }
    ScreenScaffold(title = "Import timetable", onBack = { nav.popBackStack() }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "CSV format") {
                InfoText("Columns: day, session, start, end, subject. One subject per row (repeat the row for more), or separate several with |. Days: Mon..Sun or 1..7. Sessions: Morning / Evening. Times: HH:mm.")
                Text(TimetableCsv.SAMPLE, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.text.value = TimetableCsv.SAMPLE; vm.parse() }) { Text("Use the sample") }
            }
            OutlinedButton(onClick = { picker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Choose CSV file…")
            }
            OutlinedTextField(
                value = text, onValueChange = { vm.text.value = it }, label = { Text("…or paste CSV here") },
                minLines = 4, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            OutlinedButton(onClick = { vm.parse() }, enabled = text.isNotBlank()) { Text("Check") }
            preview?.let { p -> ImportPreviewCard(p) { SlotSummary(it) } }
            if (preview?.canSave == true) {
                OutlinedTextField(name, { vm.name.value = it }, label = { Text("Timetable name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DateField("Applies from", from, { vm.from.value = it })
                Button(onClick = { vm.save() }, modifier = Modifier.fillMaxWidth()) { Text("Save ${preview?.items?.size ?: 0} sessions") }
            }
        }
    }
}

/** Shared preview for CSV imports: errors block saving, warnings do not. */
@Composable
fun <T> ImportPreviewCard(p: ImportPreview<T>, items: @Composable (List<T>) -> Unit) {
    val colors = LocalStatusColors.current
    SectionCard(title = "Preview") {
        if (p.errors.isEmpty()) {
            Text("${p.items.size} row(s) ready.", color = colors.good, fontWeight = FontWeight.SemiBold)
        } else {
            Text("${p.errors.size} problem(s) — fix them and check again. Nothing has been saved.", color = colors.warning, fontWeight = FontWeight.SemiBold)
            for (e in p.errors) IssueText(e, colors.warning)
        }
        for (w in p.warnings) IssueText(w, colors.pending)
        if (p.items.isNotEmpty()) {
            HorizontalDivider()
            items(p.items)
        }
    }
}

@Composable
private fun IssueText(issue: CsvIssue, color: androidx.compose.ui.graphics.Color) =
    Text(issue.toString(), color = color, style = MaterialTheme.typography.bodySmall)
