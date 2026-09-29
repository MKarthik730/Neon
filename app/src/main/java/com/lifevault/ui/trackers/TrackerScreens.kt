package com.lifevault.ui.trackers

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.lifevault.di.UnlockedGraph
import com.lifevault.domain.model.Entry
import com.lifevault.domain.model.EntryType
import com.lifevault.domain.model.Settings
import com.lifevault.domain.trackers.EntrySummaries
import com.lifevault.domain.trackers.Period
import com.lifevault.domain.util.Ids
import com.lifevault.ui.common.BarChart
import com.lifevault.ui.common.ChipSelector
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
import com.lifevault.ui.common.StatTile
import com.lifevault.ui.common.TimeField
import com.lifevault.ui.common.VaultViewModel
import com.lifevault.ui.main.Routes
import com.lifevault.ui.media.AttachmentsEditor
import com.lifevault.ui.mood.MoodTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.LocalDateTime

data class TrackerState(val settings: Settings, val entries: List<Entry>)

@OptIn(ExperimentalCoroutinesApi::class)
class TrackViewModel(graph: UnlockedGraph) : VaultViewModel(graph) {
    val type = MutableStateFlow(EntryType.MONEY)
    val period = MutableStateFlow(Period.DAY)
    val state: StateFlow<TrackerState?> = combine(session.settings.flow, session.entries.revision) { s, _ -> s }
        .mapLatest { s -> TrackerState(s, session.entries.all()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

private fun unitText(e: Entry, s: Settings): String = when (e.type) {
    EntryType.MONEY -> Format.money(e.amount, e.unit.ifBlank { s.trackers.currency })
    else -> "${Format.number(e.amount)} ${e.unit}".trim()
}

private fun amountText(type: EntryType, v: Double, s: Settings) = when (type) {
    EntryType.MONEY -> Format.money(v, s.trackers.currency)
    EntryType.FOOD -> "${Format.number(v)} items"
    EntryType.TRAVEL -> "${Format.number(v)} ${s.trackers.distanceUnit}"
}

@Composable
fun TrackScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val vm: TrackViewModel = viewModel { TrackViewModel(graph) }
    CollectMessages(vm.messages)
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Money", "Food", "Travel", "Mood")
    ScreenScaffold(
        title = "Track",
        floatingActionButton = {
            if (tab < 3) {
                val type = EntryType.entries[tab]
                com.lifevault.ui.common.AddFab("Add ${type.label.lowercase()}") { nav.navigate(Routes.entry(type)) }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            if (tab == 3) MoodTab(nav) else EntriesTab(nav, vm, EntryType.entries[tab])
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntriesTab(nav: NavHostController, vm: TrackViewModel, type: EntryType) {
    val state by vm.state.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    val st = state
    if (st == null) {
        LoadingBox()
        return
    }
    val s = st.settings
    val today = LocalDate.now()
    val list = EntrySummaries.ofType(st.entries, type)
    val week = EntrySummaries.inRange(list, EntrySummaries.periodStart(today, Period.WEEK), today)
    val month = EntrySummaries.inRange(list, EntrySummaries.periodStart(today, Period.MONTH), today)
    val todays = EntrySummaries.inRange(list, today, today)
    val last7 = EntrySummaries.dailySeries(list, today.minusDays(6), today)
    val periods = EntrySummaries.byPeriod(list, period)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when (type) {
                    EntryType.FOOD -> {
                        StatTile("Today", "${todays.size} entries", Modifier.weight(1f))
                        StatTile("Logging streak", "${EntrySummaries.foodStreak(st.entries, today)} d", Modifier.weight(1f))
                    }
                    EntryType.TRAVEL -> {
                        val t = EntrySummaries.travelTotals(st.entries, EntrySummaries.periodStart(today, Period.WEEK), today)
                        StatTile("This week", "${Format.number(t.distance)} ${s.trackers.distanceUnit}", Modifier.weight(1f), sub = "${t.trips} trips")
                        StatTile("Cost this week", Format.money(t.cost, s.trackers.currency), Modifier.weight(1f))
                    }
                    EntryType.MONEY -> {
                        StatTile("This week", amountText(type, week.sumOf { it.amount }, s), Modifier.weight(1f))
                        StatTile("This month", amountText(type, month.sumOf { it.amount }, s), Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            SectionCard(title = "Last 7 days") {
                val values = if (type == EntryType.FOOD) EntrySummaries.foodPerDay(st.entries, today.minusDays(6), today).map { it.second.toDouble() } else last7.map { it.second }
                BarChart(values, last7.map { Format.day(it.first).take(3) }, description = "${type.label} for the last 7 days")
            }
        }
        if (month.isNotEmpty()) {
            item {
                SectionCard(title = "This month by category") {
                    val cats = EntrySummaries.byCategory(month)
                    val max = cats.values.maxOrNull() ?: 1.0
                    for ((c, v) in cats) {
                        Row { Text(c, Modifier.weight(1f)); Text(amountText(type, v, s), fontWeight = FontWeight.SemiBold) }
                        com.lifevault.ui.common.PercentBar((v / max).toFloat(), MaterialTheme.colorScheme.secondary)
                    }
                    if (type != EntryType.MONEY) {
                        val cost = month.sumOf { it.cost ?: 0.0 }
                        if (cost > 0) InfoText("Cost this month: ${Format.money(cost, s.trackers.currency)}")
                    }
                }
            }
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Period.entries.forEachIndexed { i, p ->
                    SegmentedButton(selected = period == p, onClick = { vm.period.value = p }, shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size)) {
                        Text(p.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
            }
        }
        if (periods.isEmpty()) item { EmptyState("No ${type.label.lowercase()} entries yet.") }
        for (pt in periods.take(24)) {
            item(key = "p-${pt.start}") {
                val label = when (period) {
                    Period.DAY -> Format.date(pt.start)
                    Period.WEEK -> "Week of ${Format.shortDate(pt.start)}"
                    Period.MONTH -> Format.month(pt.start)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(if (type == EntryType.FOOD) "${pt.count} entries" else amountText(type, pt.total, s), fontWeight = FontWeight.SemiBold)
                }
                if (period != Period.DAY) InfoText(pt.byCategory.entries.joinToString(" · ") { "${it.key} ${if (type == EntryType.FOOD) "" else amountText(type, it.value, s)}".trim() })
                HorizontalDivider()
            }
            if (period == Period.DAY) {
                val entriesOfDay = list.filter { it.at.toLocalDate() == pt.start }
                items(entriesOfDay, key = { it.id }) { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${Format.time(e.at.toLocalTime())}  ${e.category.ifBlank { "—" }}", style = MaterialTheme.typography.bodyMedium)
                            if (e.note.isNotBlank()) InfoText(e.note)
                        }
                        Text(unitText(e, s))
                        IconButton(onClick = { nav.navigate(Routes.entry(e.type, e.id)) }) { Icon(Icons.Filled.Edit, contentDescription = "Edit entry") }
                    }
                }
            }
        }
    }
}

class EntryEditViewModel(graph: UnlockedGraph, private val type: EntryType, private val id: String) : VaultViewModel(graph) {
    val draft = MutableStateFlow<Entry?>(null)
    val amountText = MutableStateFlow("")
    val costText = MutableStateFlow("")
    val settings = MutableStateFlow<Settings?>(null)
    val done = MutableStateFlow(false)
    private var original: Entry? = null
    val isNew get() = id == Routes.NEW

    init {
        launchSafe {
            val s = session.settings.get()
            settings.value = s
            val existing = if (isNew) null else session.entries.all().firstOrNull { it.id == id }
            original = existing
            val e = existing ?: Entry(Ids.new(), type, LocalDateTime.now().withSecond(0).withNano(0), 0.0, s.trackers.defaultUnit(type))
            draft.value = e
            amountText.value = if (existing == null) "" else Format.number(e.amount)
            costText.value = e.cost?.let { Format.number(it) } ?: ""
        }
    }

    fun update(f: (Entry) -> Entry) { draft.value = draft.value?.let(f) }

    fun parsedAmount(): Double? = amountText.value.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }
    fun parsedCost(): Double? = costText.value.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }

    fun save() = launchSafe {
        val e = draft.value ?: return@launchSafe
        val amount = parsedAmount() ?: return@launchSafe
        session.entries.upsert(e.copy(amount = amount, cost = parsedCost(), category = e.category.trim(), note = e.note.trim()), original)
        done.value = true
    }

    fun delete() = launchSafe { original?.let { session.entries.delete(it) }; done.value = true }
}

@Composable
fun EntryEditScreen(nav: NavHostController, type: EntryType, id: String) {
    val graph = LocalGraph.current
    val vm: EntryEditViewModel = viewModel { EntryEditViewModel(graph, type, id) }
    CollectMessages(vm.messages)
    val draft by vm.draft.collectAsStateWithLifecycle()
    val amount by vm.amountText.collectAsStateWithLifecycle()
    val cost by vm.costText.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(done) { if (done) nav.popBackStack() }
    ScreenScaffold(
        title = (if (vm.isNew) "New " else "Edit ") + type.label.lowercase(),
        onBack = { nav.popBackStack() },
        actions = { if (!vm.isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete entry") } },
    ) { padding ->
        val e = draft
        val s = settings
        if (e == null || s == null) {
            LoadingBox(Modifier.padding(padding))
            return@ScreenScaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField("Date", e.at.toLocalDate(), { d -> vm.update { it.copy(at = d.atTime(it.at.toLocalTime())) } })
                TimeField("Time", e.at.toLocalTime(), { t -> vm.update { it.copy(at = it.at.toLocalDate().atTime(t)) } })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    amount, { vm.amountText.value = it }, label = { Text(e.type.amountLabel) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(2f),
                    isError = amount.isNotEmpty() && vm.parsedAmount() == null,
                )
                OutlinedTextField(e.unit, { u -> vm.update { it.copy(unit = u.trim()) } }, label = { Text(if (e.type == EntryType.MONEY) "Currency" else "Unit") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Text("Category", style = MaterialTheme.typography.titleSmall)
            ChipSelector(s.trackers.categoriesFor(e.type), setOf(e.category), onToggle = { c -> vm.update { it.copy(category = if (it.category == c) "" else c) } })
            OutlinedTextField(e.category, { c -> vm.update { it.copy(category = c) } }, label = { Text("…or type a category") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (e.type != EntryType.MONEY) {
                OutlinedTextField(
                    cost, { vm.costText.value = it }, label = { Text("Cost (${s.trackers.currency}, optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(e.note, { n -> vm.update { it.copy(note = n) } }, label = { Text("Note") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            AttachmentsEditor(nav, e.mediaIds) { ids -> vm.update { it.copy(mediaIds = ids) } }
            if (amount.isNotEmpty() && vm.parsedAmount() == null) ErrorText("Enter a valid number.")
            Button(onClick = { vm.save() }, enabled = vm.parsedAmount() != null, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
    if (confirmDelete) ConfirmDialog("Delete this entry?", "This cannot be undone.", confirmLabel = "Delete", destructive = true, onConfirm = { vm.delete() }, onDismiss = { confirmDelete = false })
}

