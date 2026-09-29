package com.lifevault.ui.attendance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.lifevault.data.AttendanceSnapshot
import com.lifevault.data.attendanceSnapshots
import com.lifevault.di.UnlockedGraph
import com.lifevault.domain.attendance.DayKind
import com.lifevault.domain.attendance.Planner
import com.lifevault.domain.attendance.Tally
import com.lifevault.domain.model.AttendanceStatus
import com.lifevault.domain.model.Session
import com.lifevault.ui.common.CollectMessages
import com.lifevault.ui.common.EmptyState
import com.lifevault.ui.common.Format
import com.lifevault.ui.common.InfoText
import com.lifevault.ui.common.LoadingBox
import com.lifevault.ui.common.LocalGraph
import com.lifevault.ui.common.PercentBar
import com.lifevault.ui.common.ScreenScaffold
import com.lifevault.ui.common.SectionCard
import com.lifevault.ui.common.VaultViewModel
import com.lifevault.ui.main.Routes
import com.lifevault.ui.theme.LocalStatusColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

class AttendanceViewModel(graph: UnlockedGraph) : VaultViewModel(graph) {
    val snapshot: StateFlow<AttendanceSnapshot?> =
        session.attendanceSnapshots().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val month = MutableStateFlow(YearMonth.now())

    fun mark(date: LocalDate, s: Session, status: AttendanceStatus?) = launchSafe {
        val subjects = snapshot.value?.schedule?.session(date, s)?.subjects ?: emptyList()
        session.attendance.mark(date, s, status, subjects)
    }
}

@Composable
fun AttendanceScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val vm: AttendanceViewModel = viewModel { AttendanceViewModel(graph) }
    CollectMessages(vm.messages)
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }
    ScreenScaffold(
        title = "Attendance",
        actions = {
            IconButton(onClick = { nav.navigate(Routes.TIMETABLE) }) { Icon(Icons.Filled.EditCalendar, contentDescription = "Timetable") }
            IconButton(onClick = { nav.navigate(Routes.HOLIDAYS) }) { Icon(Icons.Filled.BeachAccess, contentDescription = "Holidays") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Today", "Calendar", "Stats").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            val s = snap
            if (s == null) {
                LoadingBox()
            } else if (s.timetable.versions.isEmpty()) {
                EmptyState(
                    "No timetable yet. Add your weekly Morning/Evening sessions, or import them from a CSV file.",
                    actionLabel = "Set up timetable",
                    onAction = { nav.navigate(Routes.TIMETABLE) },
                )
            } else {
                when (tab) {
                    0 -> TodayTab(s, vm)
                    1 -> CalendarTab(s, vm)
                    else -> StatsTab(s)
                }
            }
        }
    }
}

@Composable
private fun TodayTab(s: AttendanceSnapshot, vm: AttendanceViewModel) {
    val pendingPast = s.report?.pending?.filter { it.date.isBefore(s.today) }?.takeLast(20)?.reversed() ?: emptyList()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard(title = Format.date(s.today)) {
                val kind = s.schedule.dayKind(s.today)
                if (kind is DayKind.Working) {
                    for (sch in kind.sessions) {
                        SessionMarkRow(sch, s.record(sch.date, sch.session), onMark = { vm.mark(sch.date, sch.session, it) })
                    }
                } else {
                    DayKindText(kind)
                }
            }
        }
        s.report?.let { r ->
            item { SummaryCard(r.overall, s.settings.attendance.thresholdPercent) }
        }
        if (pendingPast.isNotEmpty()) {
            item { Text("Unmarked sessions", style = MaterialTheme.typography.titleMedium) }
            items(pendingPast, key = { "${it.date}-${it.session}" }) { sch ->
                SectionCard(title = Format.date(sch.date)) {
                    SessionMarkRow(sch, s.record(sch.date, sch.session), onMark = { vm.mark(sch.date, sch.session, it) })
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(t: Tally, threshold: Int) {
    val colors = LocalStatusColors.current
    val below = t.isBelow(threshold)
    SectionCard(title = "Overall") {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Format.percent(t.percentage), style = MaterialTheme.typography.displaySmall, color = if (below) colors.warning else colors.good)
            Text("  required $threshold%", style = MaterialTheme.typography.bodyMedium)
        }
        PercentBar(((t.percentage ?: 0.0) / 100).toFloat(), if (below) colors.warning else colors.good, threshold = threshold / 100f)
        InfoText("${t.present} present · ${t.absent} absent · ${t.pending} pending · ${t.cancelled} cancelled")
        if (below) Text("Below the required $threshold%.", color = colors.warning, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CalendarTab(s: AttendanceSnapshot, vm: AttendanceViewModel) {
    val ym by vm.month.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    val colors = LocalStatusColors.current
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.month.value = ym.minusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month") }
            Text(Format.month(ym.atDay(1)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            IconButton(onClick = { vm.month.value = ym.plusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month") }
        }
        Row(Modifier.fillMaxWidth()) {
            for (d in DayOfWeek.entries) {
                Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
            }
        }
        val first = ym.atDay(1)
        val offset = first.dayOfWeek.value - 1
        val cells = offset + ym.lengthOfMonth()
        val rows = (cells + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in 0 until 7) {
                    val dayNum = r * 7 + c - offset + 1
                    Box(Modifier.weight(1f).aspectRatio(1f)) {
                        if (dayNum in 1..ym.lengthOfMonth()) {
                            val date = ym.atDay(dayNum)
                            DayCell(date, s, colors.holiday, onClick = { selected = date })
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Legend("Present", colors.present); Legend("Absent", colors.absent); Legend("Pending", colors.pending); Legend("Holiday", colors.holiday)
        }
    }
    selected?.let { date -> DayDialog(date, s, vm, onDismiss = { selected = null }) }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(" $label", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DayCell(date: LocalDate, s: AttendanceSnapshot, holidayColor: Color, onClick: () -> Unit) {
    val colors = LocalStatusColors.current
    val kind = s.schedule.dayKind(date)
    val statuses = (kind as? DayKind.Working)?.sessions?.map { s.record(date, it.session)?.status } ?: emptyList()
    val future = date.isAfter(s.today)
    val bg: Color = when {
        kind is DayKind.HolidayDay -> holidayColor.copy(alpha = 0.25f)
        kind !is DayKind.Working -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        future -> Color.Transparent
        statuses.any { it == AttendanceStatus.ABSENT } -> colors.absent.copy(alpha = 0.25f)
        statuses.any { it == null } -> colors.pending.copy(alpha = 0.25f)
        else -> colors.present.copy(alpha = 0.25f)
    }
    val label = buildString {
        append(Format.date(date))
        when (kind) {
            is DayKind.HolidayDay -> append(", holiday")
            is DayKind.Working -> append(", " + kind.sessions.joinToString { "${it.session.label} ${s.record(date, it.session)?.status?.label ?: "pending"}" })
            else -> append(", no sessions")
        }
    }
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(bg)
            .then(if (date == s.today) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!future) for (st in statuses) Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor(st)))
            }
        }
    }
}

@Composable
private fun DayDialog(date: LocalDate, s: AttendanceSnapshot, vm: AttendanceViewModel, onDismiss: () -> Unit) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val current = snap ?: s
    val kind = current.schedule.dayKind(date)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Format.date(date)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (kind is DayKind.Working) {
                    if (date.isAfter(current.today)) InfoText("This day is in the future; you can pre-mark a cancelled session.")
                    for (sch in kind.sessions) SessionMarkRow(sch, current.record(date, sch.session), onMark = { vm.mark(date, sch.session, it) })
                } else {
                    DayKindText(kind)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun StatsTab(s: AttendanceSnapshot) {
    val r = s.report ?: return
    val threshold = s.settings.attendance.thresholdPercent
    val colors = LocalStatusColors.current
    val plan = Planner.forTally(r.overall, threshold)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SummaryCard(r.overall, threshold) }
        item {
            SectionCard(title = "Planner") {
                InfoText("Counting: ${r.mode.label}. Based on ${r.overall.marked} marked sessions.")
                Text("You can skip ${plan.canSkip} more and stay at or above $threshold%.", style = MaterialTheme.typography.bodyLarge)
                val must = plan.mustAttend
                when {
                    must == null -> Text("A $threshold% requirement can't be reached again after an absence.", color = colors.warning)
                    must > 0 -> Text("Attend the next $must in a row to get back to $threshold%.", color = colors.warning, style = MaterialTheme.typography.bodyLarge)
                    else -> Text("You're at or above the requirement.", color = colors.good)
                }
                if (plan.unmarked > 0) InfoText("${plan.unmarked} session(s) are still unmarked and are not included — mark them for an exact answer.")
            }
        }
        if (r.bySubject.isNotEmpty()) {
            item { Text("By subject", style = MaterialTheme.typography.titleMedium) }
            items(r.bySubject.entries.toList(), key = { "subj-" + it.key }) { (subject, t) -> TallyRow(subject, t, threshold) }
        }
        if (r.byMonth.isNotEmpty()) {
            item { Text("By month", style = MaterialTheme.typography.titleMedium) }
            items(r.byMonth.entries.toList().reversed(), key = { "m-" + it.key }) { (ym, t) -> TallyRow(Format.month(ym.atDay(1)), t, threshold) }
        }
        item {
            SectionCard(title = "By session") {
                InfoText("Morning + Evening sessions: ${Format.percent(r.bySession.percentage)} (${r.bySession.present}/${r.bySession.marked})")
            }
        }
    }
}

@Composable
private fun TallyRow(label: String, t: Tally, threshold: Int) {
    val colors = LocalStatusColors.current
    val below = t.isBelow(threshold)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(Format.percent(t.percentage), color = if (below) colors.warning else Color.Unspecified, fontWeight = FontWeight.SemiBold)
        }
        PercentBar(((t.percentage ?: 0.0) / 100).toFloat(), if (below) colors.warning else colors.good, threshold = threshold / 100f)
        InfoText("${t.present}/${t.marked} marked · ${t.pending} pending")
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}
