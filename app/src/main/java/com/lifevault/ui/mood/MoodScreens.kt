package com.lifevault.ui.mood

import android.content.Context
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.lifevault.data.loadAttendance
import com.lifevault.di.UnlockedGraph
import com.lifevault.domain.attendance.AttendanceCalculator
import com.lifevault.domain.attendance.Schedule
import com.lifevault.domain.model.EntryType
import com.lifevault.domain.model.MoodCheckIn
import com.lifevault.domain.model.MoodScale
import com.lifevault.domain.model.MoodSlot
import com.lifevault.domain.model.Settings
import com.lifevault.domain.mood.MoodReport
import com.lifevault.domain.mood.MoodStats
import com.lifevault.domain.mood.PeriodAverage
import com.lifevault.domain.mood.TrendDirection
import com.lifevault.domain.util.Ids
import com.lifevault.reports.MoodExport
import com.lifevault.ui.common.BarChart
import com.lifevault.ui.common.ChipSelector
import com.lifevault.ui.common.CollectMessages
import com.lifevault.ui.common.ConfirmDialog
import com.lifevault.ui.common.DateField
import com.lifevault.ui.common.EmptyState
import com.lifevault.ui.common.Format
import com.lifevault.ui.common.InfoText
import com.lifevault.ui.common.LineChart
import com.lifevault.ui.common.LoadingBox
import com.lifevault.ui.common.LocalGraph
import com.lifevault.ui.common.ScreenScaffold
import com.lifevault.ui.common.SectionCard
import com.lifevault.ui.common.SwitchRow
import com.lifevault.ui.common.VaultViewModel
import com.lifevault.ui.common.rememberExternalLauncher
import com.lifevault.ui.main.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

// ---------- Mood tab (inside Track) ----------

data class MoodListState(val settings: Settings, val recent: List<MoodCheckIn>, val gentle: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class MoodListViewModel(graph: UnlockedGraph) : VaultViewModel(graph) {
    val state: StateFlow<MoodListState?> = combine(session.settings.flow, session.mood.revision) { s, _ -> s }
        .mapLatest { s ->
            val today = LocalDate.now()
            val recent = session.mood.between(today.minusDays(60), today).sortedWith(compareByDescending<MoodCheckIn> { it.date }.thenByDescending { it.slot.ordinal })
            MoodListState(s, recent, MoodStats.shouldShowGentleMessage(recent, s.mood))
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun MoodTab(nav: NavHostController) {
    val graph = LocalGraph.current
    val vm: MoodListViewModel = viewModel { MoodListViewModel(graph) }
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state
    if (s == null) {
        LoadingBox()
        return
    }
    val today = LocalDate.now()
    val todays = s.recent.filter { it.date == today }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionCard(title = "How is your mood?") {
                if (todays.isEmpty()) InfoText("No check-in today yet.")
                else InfoText("Today: " + todays.joinToString { "${MoodScale.emoji[it.level]} ${MoodScale.label[it.level]}" + if (s.settings.mood.twoPerDay) " (${it.slot.label})" else "" })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val canAdd = todays.isEmpty() || (s.settings.mood.twoPerDay && todays.size < 2)
                    Button(onClick = { nav.navigate(Routes.moodCheckIn()) }, enabled = canAdd) { Text("Check in") }
                    OutlinedButton(onClick = { nav.navigate(Routes.MOOD_REPORT) }) { Text("Report") }
                }
            }
        }
        if (s.gentle) item { SectionCard(title = "A gentle note") { Text(MoodStats.GENTLE_MESSAGE) } }
        if (s.recent.isEmpty()) item { EmptyState("Your check-ins will appear here. They are the most private data in the app and never appear in notifications.") }
        items(s.recent, key = { it.id }) { m ->
            SectionCard(onClick = { nav.navigate(Routes.moodCheckIn(m.date.toString(), m.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(MoodScale.emoji[m.level].orEmpty(), fontSize = 28.sp)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text("${Format.date(m.date)}${if (m.slot != MoodSlot.DAY) " · ${m.slot.label}" else ""}", style = MaterialTheme.typography.titleSmall)
                        Text(MoodScale.label[m.level].orEmpty() + if (m.tags.isNotEmpty()) " · " + m.tags.joinToString(", ") else "", style = MaterialTheme.typography.bodySmall)
                        if (m.note.isNotBlank()) InfoText(m.note, Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

// ---------- Check-in ----------

class MoodCheckInViewModel(graph: UnlockedGraph, private val dateArg: String?, private val idArg: String?) : VaultViewModel(graph) {
    val settings = MutableStateFlow<Settings?>(null)
    val draft = MutableStateFlow<MoodCheckIn?>(null)
    val level = MutableStateFlow<Int?>(null)
    val done = MutableStateFlow(false)
    private var original: MoodCheckIn? = null
    val isNew get() = original == null

    init {
        launchSafe {
            val s = session.settings.get()
            settings.value = s
            val date = dateArg?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
            val month = session.mood.month(YearMonth.from(date)).entries
            val defaultSlot = defaultSlot(s)
            val existing = idArg?.let { id -> month.firstOrNull { it.id == id } } ?: month.firstOrNull { it.date == date && it.slot == defaultSlot }
            original = existing
            draft.value = existing ?: MoodCheckIn(Ids.new(), date, defaultSlot, 3)
            level.value = existing?.level
        }
    }

    private fun defaultSlot(s: Settings) = when {
        !s.mood.twoPerDay -> MoodSlot.DAY
        LocalTime.now().isBefore(LocalTime.of(14, 0)) -> MoodSlot.MORNING
        else -> MoodSlot.EVENING
    }

    fun update(f: (MoodCheckIn) -> MoodCheckIn) { draft.value = draft.value?.let(f) }

    fun save() = launchSafe {
        val d = draft.value ?: return@launchSafe
        val l = level.value ?: return@launchSafe
        session.mood.upsert(d.copy(level = l, note = d.note.trim(), updatedAt = System.currentTimeMillis()), original)
        done.value = true
    }

    fun delete() = launchSafe { original?.let { session.mood.delete(it) }; done.value = true }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodCheckInScreen(nav: NavHostController, date: String?, id: String?) {
    val graph = LocalGraph.current
    val vm: MoodCheckInViewModel = viewModel { MoodCheckInViewModel(graph, date, id) }
    CollectMessages(vm.messages)
    val settings by vm.settings.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val level by vm.level.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    var newTag by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(done) { if (done) nav.popBackStack() }
    ScreenScaffold(
        title = "Check-in",
        onBack = { nav.popBackStack() },
        actions = { if (!vm.isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete check-in") } },
    ) { padding ->
        val d = draft
        val s = settings
        if (d == null || s == null) {
            LoadingBox(Modifier.padding(padding))
            return@ScreenScaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("How is your mood?", style = MaterialTheme.typography.headlineSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (l in MoodScale.MIN..MoodScale.MAX) {
                    val selected = level == l
                    OutlinedCard(
                        onClick = { vm.level.value = l },
                        modifier = Modifier.weight(1f).padding(2.dp).heightIn(min = 76.dp).semantics { contentDescription = "${MoodScale.label[l]}${if (selected) ", selected" else ""}" },
                        border = androidx.compose.foundation.BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(MoodScale.emoji[l].orEmpty(), fontSize = 30.sp)
                            Text(MoodScale.label[l].orEmpty(), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            DateField("Date", d.date, { nd -> vm.update { it.copy(date = nd) } })
            if (s.mood.twoPerDay) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val slots = listOf(MoodSlot.MORNING, MoodSlot.EVENING)
                    slots.forEachIndexed { i, sl ->
                        SegmentedButton(selected = d.slot == sl, onClick = { vm.update { it.copy(slot = sl) } }, shape = SegmentedButtonDefaults.itemShape(i, slots.size)) { Text(sl.label) }
                    }
                }
            }
            Text("Tags (optional)", style = MaterialTheme.typography.titleSmall)
            val allTags = (s.mood.tags + d.tags).distinct()
            ChipSelector(allTags, d.tags.toSet(), onToggle = { t -> vm.update { it.copy(tags = if (t in it.tags) it.tags - t else it.tags + t) } })
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newTag, { newTag = it }, label = { Text("Another tag") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val t = newTag.trim().lowercase()
                    if (t.isNotEmpty()) vm.update { if (t in it.tags) it else it.copy(tags = it.tags + t) }
                    newTag = ""
                }) { Text("Add") }
            }
            OutlinedTextField(d.note, { n -> vm.update { it.copy(note = n) } }, label = { Text("Private note (optional)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            InfoText("Check-ins stay encrypted in your vault and never appear in notifications.")
            Button(onClick = { vm.save() }, enabled = level != null, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
    if (confirmDelete) ConfirmDialog("Delete this check-in?", "This cannot be undone.", confirmLabel = "Delete", destructive = true, onConfirm = { vm.delete() }, onDismiss = { confirmDelete = false })
}

// ---------- Report ----------

enum class MoodRange(val label: String) { WEEK("7 days"), MONTH("30 days"), THIS_MONTH("This month"), QUARTER("90 days") }

data class SideBySideRow(val weekStart: LocalDate, val mood: Double?, val attendance: Double?, val spend: Double)

data class MoodReportState(
    val settings: Settings,
    val report: MoodReport,
    val weekly: List<PeriodAverage>,
    val all: List<MoodCheckIn>,
    val sideBySide: List<SideBySideRow>,
    val gentle: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MoodReportViewModel(graph: UnlockedGraph) : VaultViewModel(graph) {
    val range = MutableStateFlow(MoodRange.MONTH)

    val state: StateFlow<MoodReportState?> = combine(range, session.settings.flow, session.mood.revision, session.entries.revision) { r, s, _, _ -> r to s }
        .mapLatest { (r, s) ->
            val today = LocalDate.now()
            val from = when (r) {
                MoodRange.WEEK -> today.minusDays(6)
                MoodRange.MONTH -> today.minusDays(29)
                MoodRange.THIS_MONTH -> today.withDayOfMonth(1)
                MoodRange.QUARTER -> today.minusDays(89)
            }
            val all = session.mood.all()
            val report = MoodStats.report(all, from, today, today)
            val weekly = MoodStats.weekly(all, from, today)
            MoodReportState(s, report, weekly, all, sideBySide(s, from, today, weekly), MoodStats.shouldShowGentleMessage(all, s.mood))
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private suspend fun sideBySide(s: Settings, from: LocalDate, to: LocalDate, weekly: List<PeriodAverage>): List<SideBySideRow> {
        val snap = loadAttendance(session, s, session.timetable.get(), session.holidays.get(), to)
        val schedule = Schedule(snap.timetable, snap.holidays)
        val money = session.entries.between(from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), to, EntryType.MONEY)
        return weekly.map { w ->
            val end = minOf(w.start.plusDays(6), to)
            val att = if (snap.timetable.versions.isEmpty()) null else
                AttendanceCalculator.compute(schedule, snap.records.values, w.start, end, s.attendance.countingMode).overall.percentage
            val spend = money.filter { val d = it.at.toLocalDate(); !d.isBefore(w.start) && !d.isAfter(end) }.sumOf { it.amount }
            SideBySideRow(w.start, w.average, att, spend)
        }
    }

    fun exportCsv(context: Context, uri: Uri, includeNotes: Boolean) = launchSafe {
        val st = state.value ?: return@launchSafe
        val inRange = st.all.filter { !it.date.isBefore(st.report.from) && !it.date.isAfter(st.report.to) }
        write(context, uri) { it.write(MoodExport.csv(inRange, includeNotes).toByteArray()) }
        say("Exported ${inRange.size} check-ins (not encrypted).")
    }

    fun exportPdf(context: Context, uri: Uri) = launchSafe {
        val st = state.value ?: return@launchSafe
        write(context, uri) { MoodExport.pdf(st.report, st.weekly, it) }
        say("Exported the report (not encrypted).")
    }

    private suspend fun write(context: Context, uri: Uri, block: (java.io.OutputStream) -> Unit) = withContext(Dispatchers.IO) {
        (context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write the file")).use(block)
    }
}

@Composable
fun MoodReportScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val vm: MoodReportViewModel = viewModel { MoodReportViewModel(graph) }
    CollectMessages(vm.messages)
    val context = LocalContext.current
    val range by vm.range.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var showSide by remember { mutableStateOf(false) }
    var exportWhat by remember { mutableStateOf<String?>(null) }
    var includeNotes by remember { mutableStateOf(false) }
    val csv = rememberExternalLauncher(ActivityResultContracts.CreateDocument("text/csv")) { uri -> if (uri != null) vm.exportCsv(context, uri, includeNotes) }
    val pdf = rememberExternalLauncher(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> if (uri != null) vm.exportPdf(context, uri) }
    ScreenScaffold(title = "Mood report", onBack = { nav.popBackStack() }) { padding ->
        val st = state
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (r in MoodRange.entries) FilterChip(range == r, { vm.range.value = r }, label = { Text(r.label) })
                }
            }
            if (st == null) {
                item { LoadingBox(Modifier.heightIn(min = 200.dp)) }
                return@LazyColumn
            }
            val r = st.report
            if (st.gentle) item { SectionCard(title = "A gentle note") { Text(MoodStats.GENTLE_MESSAGE) } }
            item {
                SectionCard(title = "Summary") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.average?.let { String.format("%.1f", it) } ?: "—", style = MaterialTheme.typography.displaySmall)
                        Text("  average of 5", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        val (icon, text) = when (r.trend) {
                            TrendDirection.UP -> Icons.AutoMirrored.Filled.TrendingUp to "Rising"
                            TrendDirection.DOWN -> Icons.AutoMirrored.Filled.TrendingDown to "Falling"
                            TrendDirection.FLAT -> Icons.AutoMirrored.Filled.TrendingFlat to "Steady"
                        }
                        Icon(icon, contentDescription = null)
                        Text(" $text")
                    }
                    InfoText("${r.checkIns} check-in(s) · ${r.daysMissed} day(s) missed · streak ${r.currentStreak} day(s)")
                    r.bestDay?.let { InfoText("Best day: ${Format.date(it.date)} (${String.format("%.1f", it.average)})") }
                    r.worstDay?.let { InfoText("Lowest day: ${Format.date(it.date)} (${String.format("%.1f", it.average)})") }
                }
            }
            if (r.days.isNotEmpty()) {
                item {
                    SectionCard(title = "Daily average") {
                        val days = generateSequence(r.from) { it.plusDays(1) }.takeWhile { !it.isAfter(r.to) }.toList()
                        LineChart(
                            days.map { d -> r.days.firstOrNull { it.date == d }?.average }, 1.0, 5.0,
                            labels = listOf(Format.shortDate(r.from), Format.shortDate(r.to)),
                            description = "Daily mood average from ${Format.date(r.from)} to ${Format.date(r.to)}",
                        )
                    }
                }
            }
            item {
                SectionCard(title = "Distribution") {
                    BarChart((1..5).map { (r.distribution[it] ?: 0).toDouble() }, (1..5).map { MoodScale.emoji[it].orEmpty() }, description = "Number of check-ins at each mood level")
                }
            }
            if (r.tagInsights.isNotEmpty()) {
                item {
                    SectionCard(title = "Tags") {
                        InfoText("Plain observations from your check-ins — not causes.")
                        for (t in r.tagInsights) {
                            Text(
                                "On check-ins tagged \"${t.tag}\" (${t.checkIns}) your average was ${String.format("%.1f", t.averageWith)}" +
                                    (t.averageWithout?.let { ", compared with ${String.format("%.1f", it)} otherwise." } ?: "."),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            item {
                SectionCard(title = "Weekly averages") {
                    for (w in st.weekly) InfoText("Week of ${Format.shortDate(w.start)}: ${w.average?.let { String.format("%.1f", it) } ?: "—"} (${w.checkIns})")
                }
            }
            item {
                SectionCard(title = "Side by side") {
                    SwitchRow("Show attendance and spending", showSide, { showSide = it })
                    if (showSide) {
                        InfoText("Shown together so patterns are visible. This does not mean one causes another.")
                        for (row in st.sideBySide) {
                            Text(
                                "${Format.shortDate(row.weekStart)}: mood ${row.mood?.let { String.format("%.1f", it) } ?: "—"} · " +
                                    "attendance ${Format.percent(row.attendance)} · spent ${Format.money(row.spend, st.settings.trackers.currency)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            item {
                SectionCard(title = "Export") {
                    InfoText("Exports are NOT encrypted and are saved where you choose.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { exportWhat = "pdf" }) { Text("PDF report") }
                        OutlinedButton(onClick = { exportWhat = "csv" }) { Text("CSV data") }
                    }
                }
            }
            item { InfoText("A self-reflection tool, not a medical one.", Modifier.padding(bottom = 24.dp)) }
        }
    }
    when (exportWhat) {
        "pdf" -> ConfirmDialog(
            "Export an unencrypted PDF?", "The PDF contains your mood summary and will be readable by anyone with access to where you save it.",
            confirmLabel = "Choose location", onConfirm = { pdf.launch("mood-report-${LocalDate.now()}.pdf") }, onDismiss = { exportWhat = null },
        )
        "csv" -> androidx.compose.material3.AlertDialog(
            onDismissRequest = { exportWhat = null },
            title = { Text("Export check-ins as CSV?") },
            text = {
                Column {
                    Text("The file is not encrypted.")
                    com.lifevault.ui.common.CheckboxRow(includeNotes, { includeNotes = it }, "Include private notes")
                }
            },
            confirmButton = { TextButton(onClick = { csv.launch("mood-${LocalDate.now()}.csv"); exportWhat = null }) { Text("Choose location", fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClick = { exportWhat = null }) { Text("Cancel") } },
        )
    }
}
