package com.lifevault.ui.rules

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.lifevault.di.UnlockedGraph
import com.lifevault.domain.model.JobLogEntry
import com.lifevault.domain.model.Rule
import com.lifevault.domain.model.RuleSet
import com.lifevault.domain.model.RulesSettings
import com.lifevault.domain.rules.RuleOrdering
import com.lifevault.domain.rules.RulesReport
import com.lifevault.domain.rules.RulesStats
import com.lifevault.domain.rules.StartJobGate
import com.lifevault.domain.util.Ids
import com.lifevault.ui.common.BarChart
import com.lifevault.ui.common.CollectMessages
import com.lifevault.ui.common.EmptyState
import com.lifevault.ui.common.Format
import com.lifevault.ui.common.InfoText
import com.lifevault.ui.common.LoadingBox
import com.lifevault.ui.common.LocalGraph
import com.lifevault.ui.common.ScreenScaffold
import com.lifevault.ui.common.SectionCard
import com.lifevault.ui.common.StatTile
import com.lifevault.ui.common.VaultViewModel
import com.lifevault.ui.main.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

enum class JobStep { PICK, READ, RUNNING, REFLECT }

/**
 * Start-job flow: pick a rule set -> read the rules full screen (tick each / "I've read them", with an
 * optional countdown) -> Begin (job timer) -> End with an optional reflection. Everything goes to the job log.
 */
class StartJobViewModel(graph: UnlockedGraph, presetSet: String?, presetJob: String?) : VaultViewModel(graph) {
    val sets = MutableStateFlow<List<RuleSet>>(emptyList())
    val settings = MutableStateFlow(RulesSettings())
    val step = MutableStateFlow(JobStep.PICK)
    val selected = MutableStateFlow<RuleSet?>(null)
    val jobName = MutableStateFlow(presetJob ?: "")
    val gate = MutableStateFlow(StartJobGate(emptyList(), true, 0))
    val rules = MutableStateFlow<List<Rule>>(emptyList())
    val entry = MutableStateFlow<JobLogEntry?>(null)
    val reflection = MutableStateFlow("")
    val finished = MutableStateFlow(false)

    init {
        // Rules are cached in memory while unlocked, so this is instant.
        launchSafe {
            val file = session.rules.get()
            settings.value = session.settings.get().rules
            sets.value = file.sets.filterNot { it.archived }
            presetSet?.let { id -> sets.value.firstOrNull { it.id == id } }?.let { choose(it) }
        }
    }

    fun choose(set: RuleSet) {
        selected.value = set
        if (jobName.value.isBlank()) jobName.value = set.name.removePrefix("Before ").replaceFirstChar { it.uppercase() }
        val active = RuleOrdering.activeOrdered(set)
        rules.value = active
        gate.value = StartJobGate(active.map { it.id }, settings.value.requireTickAll, settings.value.countdownSeconds)
        step.value = JobStep.READ
        viewModelScope.launch {
            while (isActive && step.value == JobStep.READ && gate.value.countdownRemaining > 0) {
                delay(1000)
                gate.value = gate.value.tick()
            }
        }
    }

    fun toggle(ruleId: String) { gate.value = gate.value.toggle(ruleId) }
    fun acknowledge() { gate.value = gate.value.acknowledge() }

    /** Begin (rules read) or skip (logged as not read). */
    fun begin(skipped: Boolean = false) = launchSafe {
        val g = gate.value
        if (!skipped && !g.canBegin) return@launchSafe
        val set = selected.value
        val e = JobLogEntry(
            id = Ids.new(),
            jobName = jobName.value.ifBlank { set?.name ?: "Job" }.trim(),
            ruleSetId = set?.id,
            ruleSetName = set?.name.orEmpty(),
            startedAt = LocalDateTime.now().withNano(0),
            rulesShown = g.ruleIds,
            rulesTicked = g.ruleIds.filter { it in g.ticked },
            rulesRead = !skipped && g.rulesRead,
        )
        session.jobLog.save(e)
        entry.value = e
        step.value = if (settings.value.jobTimerEnabled) JobStep.RUNNING else JobStep.REFLECT
    }

    /** Start without any rule set. */
    fun beginWithoutRules() {
        gate.value = StartJobGate(emptyList(), true, 0)
        selected.value = null
        begin(skipped = true)
    }

    fun end() { step.value = JobStep.REFLECT }

    fun finish() = launchSafe {
        val e = entry.value ?: return@launchSafe
        session.jobLog.save(e.copy(endedAt = e.endedAt ?: LocalDateTime.now().withNano(0), reflection = reflection.value.trim()))
        finished.value = true
    }
}

@Composable
fun StartJobScreen(nav: NavHostController, setId: String?, jobName: String?) {
    val graph = LocalGraph.current
    val vm: StartJobViewModel = viewModel { StartJobViewModel(graph, setId, jobName) }
    CollectMessages(vm.messages)
    val step by vm.step.collectAsStateWithLifecycle()
    val finished by vm.finished.collectAsStateWithLifecycle()
    LaunchedEffect(finished) { if (finished) nav.popBackStack() }
    ScreenScaffold(title = when (step) { JobStep.PICK -> "Start job"; JobStep.READ -> "Read before you start"; JobStep.RUNNING -> "Job running"; JobStep.REFLECT -> "Job done" }, onBack = { nav.popBackStack() }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (step) {
                JobStep.PICK -> PickStep(vm, nav)
                JobStep.READ -> ReadStep(vm)
                JobStep.RUNNING -> RunningStep(vm)
                JobStep.REFLECT -> ReflectStep(vm)
            }
        }
    }
}

@Composable
private fun PickStep(vm: StartJobViewModel, nav: NavHostController) {
    val sets by vm.sets.collectAsStateWithLifecycle()
    val name by vm.jobName.collectAsStateWithLifecycle()
    OutlinedTextField(name, { vm.jobName.value = it }, label = { Text("What are you starting?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text("Choose your rules", style = MaterialTheme.typography.titleMedium)
    if (sets.isEmpty()) {
        EmptyState("You have no rule sets yet.", actionLabel = "Write some rules", onAction = { nav.navigate(Routes.RULES) })
    }
    for (s in sets) {
        SectionCard(title = s.name, onClick = { vm.choose(s) }) { InfoText("${RuleOrdering.activeOrdered(s).size} rule(s)") }
    }
    TextButton(onClick = { vm.beginWithoutRules() }) { Text("Start without rules") }
}

@Composable
private fun ReadStep(vm: StartJobViewModel) {
    val gate by vm.gate.collectAsStateWithLifecycle()
    val rules by vm.rules.collectAsStateWithLifecycle()
    val set by vm.selected.collectAsStateWithLifecycle()
    Text(set?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall)
    if (rules.isEmpty()) InfoText("This set has no active rules.")
    for (r in rules) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { vm.toggle(r.id) }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = r.id in gate.ticked, onCheckedChange = null)
            Text(r.text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
            if (r.pinned) Icon(Icons.Filled.PushPin, contentDescription = "Pinned")
        }
        HorizontalDivider()
    }
    if (gate.countdownRemaining > 0) InfoText("Take a moment… ${gate.countdownRemaining}s")
    if (!gate.requireTickAll) {
        OutlinedButton(onClick = { vm.acknowledge() }, enabled = !gate.acknowledged, modifier = Modifier.fillMaxWidth()) {
            Text(if (gate.acknowledged) "Read ✓" else "I've read them")
        }
    } else if (!gate.allTicked) {
        InfoText("Tick each rule to unlock Begin.")
    }
    Button(onClick = { vm.begin() }, enabled = gate.canBegin, modifier = Modifier.fillMaxWidth()) { Text("Begin") }
    TextButton(onClick = { vm.begin(skipped = true) }) { Text("Skip the rules this time (it's logged)") }
}

@Composable
private fun RunningStep(vm: StartJobViewModel) {
    val entry by vm.entry.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val e = entry ?: return
    val elapsed = (now - e.startedAt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()).coerceAtLeast(0)
    Text(e.jobName, style = MaterialTheme.typography.headlineSmall)
    Text(Format.duration(elapsed), style = MaterialTheme.typography.displayLarge)
    InfoText("Started ${Format.dateTime(e.startedAt)}. You can leave this screen; end the job later from the job log.")
    Button(onClick = { vm.end() }, modifier = Modifier.fillMaxWidth()) { Text("End job") }
}

@Composable
private fun ReflectStep(vm: StartJobViewModel) {
    val text by vm.reflection.collectAsStateWithLifecycle()
    Text("How did it go?", style = MaterialTheme.typography.headlineSmall)
    OutlinedTextField(text, { vm.reflection.value = it }, label = { Text("Reflection (optional)") }, minLines = 4, modifier = Modifier.fillMaxWidth())
    Button(onClick = { vm.finish() }, modifier = Modifier.fillMaxWidth()) { Text("Save to job log") }
}

data class JobLogState(val report: RulesReport, val logs: List<JobLogEntry>, val ruleText: Map<String, String>)

@OptIn(ExperimentalCoroutinesApi::class)
class JobLogViewModel(graph: UnlockedGraph) : VaultViewModel(graph) {
    val state: StateFlow<JobLogState?> = session.jobLog.revision.mapLatest {
        val logs = session.jobLog.all()
        val text = session.rules.get().sets.flatMap { it.rules }.associate { it.id to it.text }
        JobLogState(RulesStats.report(logs, LocalDate.now(), LocalDateTime.now()), logs, text)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun endNow(e: JobLogEntry) = launchSafe { session.jobLog.save(e.copy(endedAt = LocalDateTime.now().withNano(0))) }
    fun delete(e: JobLogEntry) = launchSafe { session.jobLog.delete(e) }
}

@Composable
fun JobLogScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val vm: JobLogViewModel = viewModel { JobLogViewModel(graph) }
    CollectMessages(vm.messages)
    val state by vm.state.collectAsStateWithLifecycle()
    ScreenScaffold(title = "Job log", onBack = { nav.popBackStack() }) { padding ->
        val s = state
        if (s == null) {
            LoadingBox(Modifier.padding(padding))
            return@ScreenScaffold
        }
        val r = s.report
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Rules read", Format.percent(r.readPercent), Modifier.weight(1f), sub = "${r.jobsWithRulesRead}/${r.totalJobs} jobs")
                    StatTile("Streak", "${r.readStreakDays} d", Modifier.weight(1f))
                    StatTile("This week", Format.minutes(r.minutesThisWeek), Modifier.weight(1f))
                }
            }
            if (r.minutesByWeek.isNotEmpty()) {
                item {
                    SectionCard(title = "Job time per week") {
                        val weeks = r.minutesByWeek.entries.toList().takeLast(8)
                        BarChart(weeks.map { it.value.toDouble() }, weeks.map { Format.shortDate(it.key) }, description = "Minutes of jobs per week")
                    }
                }
            }
            if (r.mostUnticked.isNotEmpty()) {
                item {
                    SectionCard(title = "Rules most often left unticked") {
                        InfoText("Consider tightening or dropping rules that aren't working for you.")
                        for (m in r.mostUnticked.take(5)) {
                            Text("${s.ruleText[m.ruleId] ?: "(deleted rule)"} — unticked ${m.timesUnticked} of ${m.timesShown}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (s.logs.isEmpty()) item { EmptyState("No jobs yet. Start one from Ground rules or the Today screen.") }
            items(s.logs, key = { it.id }) { e ->
                SectionCard(title = e.jobName) {
                    InfoText(
                        "${Format.dateTime(e.startedAt)}" + (e.endedAt?.let { " – ${Format.time(it.toLocalTime())} (${Format.minutes(RulesStats.minutes(e, LocalDateTime.now()))})" } ?: " · running") +
                            (if (e.ruleSetName.isNotBlank()) " · ${e.ruleSetName}" else ""),
                    )
                    Text(if (e.rulesRead) "Rules read (${e.rulesTicked.size}/${e.rulesShown.size} ticked)" else "Rules not read", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    if (e.reflection.isNotBlank()) Text(e.reflection, style = MaterialTheme.typography.bodyMedium)
                    Row {
                        if (e.endedAt == null) TextButton(onClick = { vm.endNow(e) }) { Text("End now") }
                        TextButton(onClick = { vm.delete(e) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

