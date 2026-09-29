package com.lifevault.ui.study

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.lifevault.di.UnlockedGraph
import com.lifevault.domain.model.ChecklistItem
import com.lifevault.domain.model.RuleSet
import com.lifevault.domain.model.StudyIndex
import com.lifevault.domain.model.StudyPage
import com.lifevault.domain.model.StudySubject
import com.lifevault.domain.util.Ids
import com.lifevault.ui.common.CollectMessages
import com.lifevault.ui.common.ConfirmDialog
import com.lifevault.ui.common.Dropdown
import com.lifevault.ui.common.EmptyState
import com.lifevault.ui.common.InfoText
import com.lifevault.ui.common.LoadingBox
import com.lifevault.ui.common.LocalGraph
import com.lifevault.ui.common.ScreenScaffold
import com.lifevault.ui.common.SectionCard
import com.lifevault.ui.common.VaultViewModel
import com.lifevault.ui.main.Routes
import com.lifevault.ui.media.AttachmentsEditor
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StudyViewModel(graph: UnlockedGraph) : VaultViewModel(graph) {
    val index: StateFlow<StudyIndex?> = session.study.index.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val suggestions = MutableStateFlow<List<String>>(emptyList())

    init { launchSafe { suggestions.value = session.timetable.get().allSubjects() } }

    fun add(name: String, open: (String) -> Unit) = launchSafe {
        if (name.isBlank()) return@launchSafe
        open(session.study.addSubject(name).id)
    }
}

@Composable
fun StudyScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val vm: StudyViewModel = viewModel { StudyViewModel(graph) }
    CollectMessages(vm.messages)
    val index by vm.index.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    ScreenScaffold(
        title = "Study pages",
        onBack = { nav.popBackStack() },
        floatingActionButton = { com.lifevault.ui.common.AddFab("Subject") { adding = true } },
    ) { padding ->
        val idx = index
        if (idx == null) {
            LoadingBox(Modifier.padding(padding))
            return@ScreenScaffold
        }
        val active = idx.subjects.filterNot { it.archived }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (active.isEmpty()) item { EmptyState("One page per subject: notes, a checklist and attachments.") }
            val missing = suggestions.filter { s -> active.none { it.name.equals(s, ignoreCase = true) } }
            if (missing.isNotEmpty()) {
                item {
                    SectionCard(title = "From your timetable") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (s in missing.take(4)) AssistChip(onClick = { vm.add(s) { id -> nav.navigate(Routes.studyPage(id)) } }, label = { Text("+ $s") })
                        }
                    }
                }
            }
            items(active, key = { it.id }) { s ->
                SectionCard(onClick = { nav.navigate(Routes.studyPage(s.id)) }) { Text(s.name, style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("New subject") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Subject name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.add(name) { id -> nav.navigate(Routes.studyPage(id)) }; adding = false }, enabled = name.isNotBlank()) { Text("Add") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(FlowPreview::class)
class StudyPageViewModel(graph: UnlockedGraph, private val subjectId: String) : VaultViewModel(graph) {
    private val repo = session.study.page(subjectId)
    val subject = MutableStateFlow<StudySubject?>(null)
    val page = MutableStateFlow<StudyPage?>(null)
    val ruleSets = MutableStateFlow<List<RuleSet>>(emptyList())
    val deleted = MutableStateFlow(false)
    val saving = MutableStateFlow(false)

    init {
        launchSafe {
            subject.value = session.study.index.get().subjects.firstOrNull { it.id == subjectId }
            ruleSets.value = session.rules.get().sets.filterNot { it.archived }
            page.value = repo.get()
            // Autosave shortly after the user stops typing.
            viewModelScope.launch {
                page.filterNotNull().drop(1).debounce(700).collect { p ->
                    runCatching { repo.set(p.copy(updatedAt = System.currentTimeMillis())) }.onFailure { say("Could not save: ${it.message}") }
                    saving.value = false
                }
            }
        }
    }

    fun edit(f: (StudyPage) -> StudyPage) {
        page.value = page.value?.let(f)
        saving.value = true
    }

    fun setRuleSet(id: String?) = launchSafe {
        val s = subject.value ?: return@launchSafe
        val n = s.copy(ruleSetId = id)
        session.study.saveSubject(n)
        subject.value = n
    }

    fun rename(name: String) = launchSafe {
        val s = subject.value ?: return@launchSafe
        val n = s.copy(name = name.trim())
        session.study.saveSubject(n)
        subject.value = n
    }

    fun delete() = launchSafe { session.study.deleteSubject(subjectId); deleted.value = true }

    /** Flush pending edits when leaving the screen. */
    override fun onCleared() {
        val p = page.value
        if (saving.value && p != null && !session.isClosed) session.scope.launch { runCatching { repo.set(p) } }
    }
}

@Composable
fun StudyPageScreen(nav: NavHostController, subjectId: String) {
    val graph = LocalGraph.current
    val vm: StudyPageViewModel = viewModel { StudyPageViewModel(graph, subjectId) }
    CollectMessages(vm.messages)
    val subject by vm.subject.collectAsStateWithLifecycle()
    val page by vm.page.collectAsStateWithLifecycle()
    val ruleSets by vm.ruleSets.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf(false) }
    var newItem by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(deleted) { if (deleted) nav.popBackStack() }
    ScreenScaffold(
        title = subject?.name ?: "Study",
        onBack = { nav.popBackStack() },
        actions = {
            if (saving) Text("Saving…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 8.dp))
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete subject page") }
        },
    ) { padding ->
        val p = page
        val s = subject
        if (p == null || s == null) {
            LoadingBox(Modifier.padding(padding))
            return@ScreenScaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(title = "Start studying") {
                if (ruleSets.isNotEmpty()) {
                    Dropdown("Rules", listOf<RuleSet?>(null) + ruleSets, ruleSets.firstOrNull { it.id == s.ruleSetId }, { it?.name ?: "None" }, { vm.setRuleSet(it?.id) })
                }
                AssistChip(onClick = { nav.navigate(Routes.startJob(s.ruleSetId, "Study: ${s.name}")) }, label = { Text("Start study job") }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!preview, { preview = false }, label = { Text("Edit") })
                FilterChip(preview, { preview = true }, label = { Text("Preview") })
            }
            if (preview) {
                SectionCard { MarkdownText(p.notes.ifBlank { "_No notes yet._" }) }
            } else {
                OutlinedTextField(
                    p.notes, { t -> vm.edit { it.copy(notes = t) } },
                    label = { Text("Notes (Markdown: # heading, - list, **bold**, _italic_)") },
                    minLines = 8, modifier = Modifier.fillMaxWidth(),
                )
            }
            SectionCard(title = "Checklist") {
                for (item in p.checklist) {
                    com.lifevault.ui.common.CheckboxRow(
                        checked = item.done,
                        onCheckedChange = { d -> vm.edit { pg -> pg.copy(checklist = pg.checklist.map { if (it.id == item.id) it.copy(done = d) else it }) } },
                        label = item.text,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                        trailing = {
                            IconButton(onClick = { vm.edit { pg -> pg.copy(checklist = pg.checklist.filterNot { it.id == item.id }) } }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove ${item.text}")
                            }
                        },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newItem, { newItem = it }, label = { Text("New item") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        if (newItem.isNotBlank()) vm.edit { it.copy(checklist = it.checklist + ChecklistItem(Ids.new(), newItem.trim())) }
                        newItem = ""
                    }) { Text("Add") }
                }
                val done = p.checklist.count { it.done }
                if (p.checklist.isNotEmpty()) InfoText("$done of ${p.checklist.size} done")
            }
            AttachmentsEditor(nav, p.mediaIds) { ids -> vm.edit { it.copy(mediaIds = ids) } }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete this page?", "Notes and the checklist for ${subject?.name} are removed. Attachments stay in the media vault.", confirmLabel = "Delete", destructive = true, onConfirm = { vm.delete() }, onDismiss = { confirmDelete = false })
    }
}

/** Very small Markdown renderer: headings, bullet lists, bold, italic. Enough for study notes. */
@Composable
fun MarkdownText(md: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (raw in md.lines()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("### ") -> Text(inline(line.drop(4)), style = MaterialTheme.typography.titleSmall)
                line.startsWith("## ") -> Text(inline(line.drop(3)), style = MaterialTheme.typography.titleMedium)
                line.startsWith("# ") -> Text(inline(line.drop(2)), style = MaterialTheme.typography.titleLarge)
                line.startsWith("- ") || line.startsWith("* ") -> Text(inline("•  " + line.drop(2)), style = MaterialTheme.typography.bodyMedium)
                line.isBlank() -> Text("")
                else -> Text(inline(line), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i) { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }; i = end + 2 } else { append(text[i]); i++ }
            }
            text[i] == '_' || text[i] == '*' -> {
                val c = text[i]
                val end = text.indexOf(c, i + 1)
                if (end > i + 1) { withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(text.substring(i + 1, end)) }; i = end + 1 } else { append(c); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}
