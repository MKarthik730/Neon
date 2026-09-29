package com.lifevault.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.lifevault.notifications.Notifier
import com.lifevault.ui.common.rememberExternalLauncher
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.lifevault.data.AttendanceSnapshot
import com.lifevault.data.attendanceSnapshots
import com.lifevault.di.UnlockedGraph
import com.lifevault.domain.attendance.DayKind
import com.lifevault.domain.dashboard.DashboardCalculator
import com.lifevault.domain.dashboard.DashboardSummary
import com.lifevault.domain.model.AttendanceStatus
import com.lifevault.domain.model.EntryType
import com.lifevault.domain.model.JobLogEntry
import com.lifevault.domain.model.MoodCheckIn
import com.lifevault.domain.model.MoodScale
import com.lifevault.domain.model.Session
import com.lifevault.domain.mood.MoodStats
import com.lifevault.ui.attendance.DayKindText
import com.lifevault.ui.attendance.SessionMarkRow
import com.lifevault.ui.common.BarChart
import com.lifevault.ui.common.CollectMessages
import com.lifevault.ui.common.Format
import com.lifevault.ui.common.InfoText
import com.lifevault.ui.common.LineChart
import com.lifevault.ui.common.LoadingBox
import com.lifevault.ui.common.LocalAppContainer
import com.lifevault.ui.common.LocalGraph
import com.lifevault.ui.common.ScreenScaffold
import com.lifevault.ui.common.SectionCard
import com.lifevault.ui.common.StatTile
import com.lifevault.ui.common.VaultViewModel
import com.lifevault.ui.main.Routes
import com.lifevault.ui.theme.LocalStatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.LocalDateTime

data class HomeState(
    val snapshot: AttendanceSnapshot,
    val summary: DashboardSummary,
    val todayMood: List<MoodCheckIn>,
    val gentleMessage: Boolean,
    val runningJob: JobLogEntry?,
    val currency: String,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(graph: UnlockedGraph) : VaultViewModel(graph) {
    private val triggers = combine(session.entries.revision, session.mood.revision, session.jobLog.revision) { a, b, c -> Triple(a, b, c) }

    val state: StateFlow<HomeState?> = combine(session.attendanceSnapshots(), triggers, session.events.flow, session.media.flow) { snap, _, events, media ->
        Triple(snap, events, media)
    }.mapLatest { (snap, events, media) ->
        val today = LocalDate.now()
        val entries = session.entries.between(today.minusDays(400), today)
        val mood = session.mood.between(today.minusDays(60), today)
        val jobs = session.jobLog.all()
        val summary = DashboardCalculator.compute(
            today = today, now = LocalDateTime.now(), attendance = snap.report,
            thresholdPercent = snap.settings.attendance.thresholdPercent, entries = entries, events = events.events,
            mediaCount = media.items.size, mood = mood, jobs = jobs,
        )
        HomeState(
            snapshot = snap,
            summary = summary,
            todayMood = mood.filter { it.date == today },
            gentleMessage = MoodStats.shouldShowGentleMessage(mood, snap.settings.mood),
            runningJob = jobs.firstOrNull { it.endedAt == null },
            currency = snap.settings.trackers.currency,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun mark(date: LocalDate, s: Session, status: AttendanceStatus?) = launchSafe {
        val subjects = state.value?.snapshot?.schedule?.session(date, s)?.subjects ?: emptyList()
        session.attendance.mark(date, s, status, subjects)
    }
}

@Composable
fun HomeScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val vault = LocalAppContainer.current.vault
    val vm: HomeViewModel = viewModel { HomeViewModel(graph) }
    CollectMessages(vm.messages)
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var canNotify by remember { mutableStateOf(Notifier.canPost(context)) }
    var notifDismissed by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canNotify = Notifier.canPost(context) }
    val notifPermission = rememberExternalLauncher(ActivityResultContracts.RequestPermission()) { granted ->
        canNotify = Notifier.canPost(context)
        if (!granted) nav.navigate(Routes.RELIABILITY)
    }
    ScreenScaffold(
        title = "Today",
        actions = {
            IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
            IconButton(onClick = { vault.lock() }) { Icon(Icons.Filled.Lock, contentDescription = "Lock now") }
        },
    ) { padding ->
        val s = state
        if (s == null) {
            LoadingBox(Modifier.padding(padding))
            return@ScreenScaffold
        }
        val colors = LocalStatusColors.current
        val today = s.snapshot.today
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!canNotify && !notifDismissed) {
                item {
                    SectionCard(title = "Reminders are off") {
                        InfoText("Allow notifications for attendance prompts and reminders. They only ever show generic text, never your data.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    nav.navigate(Routes.RELIABILITY)
                                }
                            }) { Text("Allow notifications") }
                            TextButton(onClick = { notifDismissed = true }) { Text("Not now") }
                        }
                    }
                }
            }
            s.runningJob?.let { job ->
                item {
                    SectionCard(title = "Job in progress", onClick = { nav.navigate(Routes.JOB_LOG) }) {
                        Text(job.jobName, style = MaterialTheme.typography.bodyLarge)
                        InfoText("Started ${Format.dateTime(job.startedAt)}. Open the job log to end it.")
                    }
                }
            }
            item {
                SectionCard(title = Format.date(today), trailing = { TextButton(onClick = { nav.navigate(Routes.ATTENDANCE) }) { Text("Attendance") } }) {
                    if (s.snapshot.timetable.versions.isEmpty()) {
                        InfoText("Set up your timetable to track attendance.")
                        OutlinedButton(onClick = { nav.navigate(Routes.TIMETABLE) }) { Text("Set up timetable") }
                    } else {
                        when (val kind = s.snapshot.schedule.dayKind(today)) {
                            is DayKind.Working -> for (sch in kind.sessions) {
                                SessionMarkRow(sch, s.snapshot.record(today, sch.session), onMark = { vm.mark(today, sch.session, it) })
                            }
                            else -> DayKindText(kind)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val below = s.summary.attendanceBelowThreshold
                    StatTile(
                        "Attendance", Format.percent(s.summary.attendancePercent), Modifier.weight(1f),
                        sub = if (s.summary.pendingSessions > 0) "${s.summary.pendingSessions} unmarked" else "required ${s.snapshot.settings.attendance.thresholdPercent}%",
                        valueColor = if (s.summary.attendancePercent == null) androidx.compose.ui.graphics.Color.Unspecified else if (below) colors.warning else colors.good,
                        onClick = { nav.navigate(Routes.ATTENDANCE) },
                    )
                    StatTile("Spent this week", Format.money(s.summary.spendThisWeek, s.currency), Modifier.weight(1f), onClick = { nav.navigate(Routes.TRACK) })
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Food log streak", "${s.summary.foodStreak} d", Modifier.weight(1f), onClick = { nav.navigate(Routes.TRACK) })
                    StatTile("Rules read streak", "${s.summary.rulesReadStreak} d", Modifier.weight(1f), onClick = { nav.navigate(Routes.JOB_LOG) })
                    StatTile("Media", s.summary.mediaCount.toString(), Modifier.weight(1f), onClick = { nav.navigate(Routes.MEDIA) })
                }
            }
            item {
                SectionCard(title = "Mood this week", trailing = { TextButton(onClick = { nav.navigate(Routes.MOOD_REPORT) }) { Text("Report") } }) {
                    val points = (6 downTo 0).map { back -> s.summary.moodWeek.firstOrNull { it.date == today.minusDays(back.toLong()) }?.average }
                    if (points.all { it == null }) InfoText("No check-ins this week yet.")
                    else LineChart(points, 1.0, 5.0, labels = (6 downTo 0).map { Format.day(today.minusDays(it.toLong())).take(3) }, description = "Mood for the last 7 days")
                    s.summary.moodWeekAverage?.let { InfoText("Average ${"%.1f".format(it)} of 5") }
                    if (s.todayMood.isEmpty()) {
                        Button(onClick = { nav.navigate(Routes.moodCheckIn()) }) { Text("How is your mood?") }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (m in s.todayMood) AssistChip(onClick = { nav.navigate(Routes.moodCheckIn(id = m.id, date = m.date.toString())) }, label = { Text("${MoodScale.emoji[m.level]} ${m.slot.label}") })
                            if (s.snapshot.settings.mood.twoPerDay && s.todayMood.size < 2) {
                                TextButton(onClick = { nav.navigate(Routes.moodCheckIn()) }) { Text("Add check-in") }
                            }
                        }
                    }
                }
            }
            if (s.gentleMessage) {
                item { SectionCard(title = "A gentle note") { Text(MoodStats.GENTLE_MESSAGE, style = MaterialTheme.typography.bodyMedium) } }
            }
            item {
                SectionCard(title = "Upcoming", trailing = { TextButton(onClick = { nav.navigate(Routes.EVENTS) }) { Text("All events") } }) {
                    if (s.summary.upcomingEvents.isEmpty()) InfoText("Nothing scheduled.")
                    for (e in s.summary.upcomingEvents) {
                        TextButton(onClick = { nav.navigate(Routes.event(e.id)) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${e.kind.label}: ${e.title} — ${Format.shortDate(e.date)}${e.time?.let { " " + Format.time(it) } ?: ""}", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            item {
                SectionCard(title = "Spending, last 7 days") {
                    BarChart(
                        s.summary.spendLast7Days.map { it.second },
                        s.summary.spendLast7Days.map { Format.day(it.first).take(3) },
                        description = "Money spent per day for the last 7 days",
                    )
                }
            }
            item {
                SectionCard(title = "Quick actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { nav.navigate(Routes.startJob()) }, label = { Text("Start job") }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) })
                        AssistChip(onClick = { nav.navigate(Routes.entry(EntryType.MONEY)) }, label = { Text("Expense") }, leadingIcon = { Icon(Icons.Filled.Payments, null) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { nav.navigate(Routes.entry(EntryType.FOOD)) }, label = { Text("Food") }, leadingIcon = { Icon(Icons.Filled.Restaurant, null) })
                        AssistChip(onClick = { nav.navigate(Routes.entry(EntryType.TRAVEL)) }, label = { Text("Travel") }, leadingIcon = { Icon(Icons.Filled.DirectionsBus, null) })
                    }
                }
            }
            item { Text("Everything on this screen is calculated from your vault on the fly.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Light) }
        }
    }
}
