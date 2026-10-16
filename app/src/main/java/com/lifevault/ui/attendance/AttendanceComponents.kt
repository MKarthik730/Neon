package com.lifevault.ui.attendance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifevault.data.loadAttendance
import com.lifevault.domain.attendance.DayKind
import com.lifevault.domain.attendance.ScheduledSession
import com.lifevault.domain.model.AttendanceRecord
import com.lifevault.domain.model.AttendanceStatus
import com.lifevault.domain.model.Session
import com.lifevault.ui.common.ErrorText
import com.lifevault.ui.common.Format
import com.lifevault.ui.common.InfoText
import com.lifevault.ui.common.LocalGraph
import com.lifevault.ui.common.userMessage
import com.lifevault.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun statusColor(status: AttendanceStatus?): Color {
    val c = LocalStatusColors.current
    return when (status) {
        AttendanceStatus.PRESENT -> c.present
        AttendanceStatus.ABSENT -> c.absent
        AttendanceStatus.CANCELLED -> c.cancelled
        null -> c.pending
    }
}

/** One session with Present / Absent / Cancelled choices. Tapping the selected choice again clears it (Pending). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionMarkRow(
    scheduled: ScheduledSession,
    record: AttendanceRecord?,
    onMark: (AttendanceStatus?) -> Unit,
    enabled: Boolean = true,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(
                "${scheduled.session.label}  ${Format.time(scheduled.start)}–${Format.time(scheduled.end)}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                record?.status?.label ?: "Pending",
                color = statusColor(record?.status),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (scheduled.subjects.isNotEmpty()) InfoText(scheduled.subjects.joinToString(", "))
        val options = listOf(AttendanceStatus.PRESENT, AttendanceStatus.ABSENT, AttendanceStatus.CANCELLED)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, s ->
                SegmentedButton(
                    selected = record?.status == s,
                    onClick = { onMark(if (record?.status == s) null else s) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    enabled = enabled,
                ) { Text(s.label) }
            }
        }
    }
}

@Composable
fun SessionClassLog(
    scheduled: ScheduledSession,
    record: AttendanceRecord?,
    allSubjects: List<String>,
    onSave: (Map<String, Int>, Boolean) -> Unit,
) {
    var irregular by remember(scheduled.date, scheduled.session, record?.irregular) {
        mutableStateOf(record?.irregular ?: false)
    }
    var selected by remember(scheduled.date, scheduled.session, record?.classCounts) {
        mutableStateOf(record?.classCounts?.keys.orEmpty())
    }
    var counts by remember(scheduled.date, scheduled.session, record?.classCounts) {
        mutableStateOf(record?.classCounts?.mapValues { it.value.toString() }.orEmpty())
    }
    val subjects = (if (irregular) allSubjects else scheduled.subjects)
        .distinctBy { it.trim().lowercase() }
        .filter { it.isNotBlank() }
    val valid = selected.all { subject ->
        counts[subject]?.toIntOrNull()?.let { it >= 0 } == true
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Text(
                "${scheduled.session.label}  ${Format.time(scheduled.start)}–${Format.time(scheduled.end)}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (record?.classLogRecorded == true) "Saved" else "Not entered",
                color = if (record?.classLogRecorded == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Text("Select each class taught and enter the number of periods.", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(checked = irregular, onCheckedChange = { irregular = it })
            Text("Irregular / substitution — show all classes, including labs")
        }
        InfoText("Leave a class unchecked or at 0 if another teacher took it.")
        if (subjects.isEmpty()) {
            InfoText("No classes are listed for this session. Add subjects or labs in the timetable.")
        } else {
            subjects.forEach { subject ->
                val isSelected = subject in selected
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { checked ->
                            selected = if (checked) selected + subject else selected - subject
                        },
                    )
                    Text(subject, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = counts[subject] ?: "0",
                        onValueChange = { value ->
                            if (value.all(Char::isDigit)) counts = counts + (subject to value)
                        },
                        modifier = Modifier.width(88.dp),
                        enabled = isSelected,
                        singleLine = true,
                        label = { Text("Periods") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }
        }
        Button(
            onClick = {
                onSave(
                    selected.associateWith { counts[it]?.toIntOrNull() ?: 0 },
                    irregular,
                )
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save ${scheduled.session.label} classes")
        }
    }
}


@Composable
fun DayKindText(kind: DayKind) {
    val c = LocalStatusColors.current
    when (kind) {
        is DayKind.HolidayDay -> Text("Holiday: ${kind.holiday.label}", color = c.holiday, style = MaterialTheme.typography.bodyLarge)
        DayKind.OffDay -> InfoText("Weekly off day — no sessions.")
        DayKind.NoTimetable -> InfoText("No timetable covers this date. Add one under Attendance → Timetable.")
        DayKind.NoSessions -> InfoText("No sessions scheduled.")
        is DayKind.Working -> {}
    }
}

/**
 * Mark one session from a notification (after unlock). [suggested] preselects the button the user tapped
 * on the notification; nothing is saved until they confirm.
 */
@Composable
fun QuickMarkDialog(date: LocalDate, session: Session, suggested: AttendanceStatus?, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    var scheduled by remember { mutableStateOf<ScheduledSession?>(null) }
    var record by remember { mutableStateOf<AttendanceRecord?>(null) }
    var choice by remember { mutableStateOf(suggested) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(date, session) {
        runCatching {
            val s = graph.session
            val snap = loadAttendance(s, s.settings.get(), s.timetable.get(), s.holidays.get(), date)
            scheduled = snap.schedule.session(date, session)
            record = snap.record(date, session)
            if (choice == null) choice = record?.status
        }.onFailure { error = it.userMessage() }
        loaded = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${session.label} · ${Format.date(date)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                error?.let { ErrorText(it) }
                val sch = scheduled
                if (loaded && sch == null) InfoText("This session is not in the timetable (holiday, off day or no class). You can still record it.")
                sch?.subjects?.takeIf { it.isNotEmpty() }?.let { InfoText(it.joinToString(", ")) }
                SessionMarkRow(
                    scheduled = sch ?: ScheduledSession(date, session, java.time.LocalTime.MIDNIGHT, java.time.LocalTime.MIDNIGHT, emptyList()),
                    record = choice?.let { AttendanceRecord(date, session, it) },
                    onMark = { choice = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    runCatching {
                        graph.session.attendance.mark(date, session, choice, scheduled?.subjects ?: emptyList())
                    }.onSuccess { onDismiss() }.onFailure { error = it.userMessage() }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
