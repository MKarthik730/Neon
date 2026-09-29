package com.lifevault.ui.media

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.lifevault.domain.model.MediaItem
import com.lifevault.domain.model.MediaKind
import com.lifevault.ui.common.Format
import com.lifevault.ui.common.InfoText
import com.lifevault.ui.common.LocalGraph
import com.lifevault.ui.common.LocalSnackbar
import com.lifevault.ui.common.rememberExternalLauncher
import com.lifevault.ui.common.userMessage
import com.lifevault.ui.main.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Decrypted thumbnail (from the in-memory cache when possible) or a type icon. */
@Composable
fun MediaThumb(item: MediaItem?, modifier: Modifier = Modifier, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val graph = LocalGraph.current
    val bitmap by produceState<Bitmap?>(null, item?.id) { value = item?.let { graph.media.thumbnail(it) } }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        when {
            item == null -> Text("Missing", style = MaterialTheme.typography.labelSmall)
            b != null -> Image(b.asImageBitmap(), contentDescription = item.originalName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            item.kind == MediaKind.AUDIO -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.AudioFile, contentDescription = item.originalName)
                item.durationMs?.let { Text(Format.duration(it), style = MaterialTheme.typography.labelSmall) }
            }
            else -> Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = item.originalName)
        }
        if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
    }
}

/**
 * Attachments for events, study pages and tracker entries: shows the attached vault media and lets the user
 * attach existing items, import new files, or capture a photo / recording that is attached on return.
 */
@Composable
fun AttachmentsEditor(nav: NavHostController, mediaIds: List<String>, onChange: (List<String>) -> Unit) {
    val graph = LocalGraph.current
    val index by graph.session.media.flow.collectAsState(initial = null)
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }

    // Result from the camera/recorder screens.
    val entry = remember(nav) { nav.currentBackStackEntry }
    val handle = entry?.savedStateHandle
    val captured by (handle?.getStateFlow<String?>(Routes.RESULT_MEDIA_ID, null) ?: remember { MutableStateFlow<String?>(null) }).collectAsState()
    LaunchedEffect(captured) {
        val id = captured ?: return@LaunchedEffect
        if (id !in mediaIds) onChange(mediaIds + id)
        handle?.set<String?>(Routes.RESULT_MEDIA_ID, null)
    }

    val importer = rememberExternalLauncher(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) scope.launch {
            val added = ArrayList<String>()
            for (u in uris) {
                runCatching { graph.media.import(u) }
                    .onSuccess { added += it.id }
                    .onFailure { snackbar.showSnackbar(it.userMessage()) }
            }
            if (added.isNotEmpty()) {
                onChange(mediaIds + added)
                snackbar.showSnackbar("Imported ${added.size} file(s) into the vault. The originals were not changed.")
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Attachments", style = MaterialTheme.typography.titleSmall)
        if (mediaIds.isEmpty()) InfoText("None")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(mediaIds, key = { it }) { id ->
                val item = index?.items?.firstOrNull { it.id == id }
                Box {
                    MediaThumb(item, Modifier.size(72.dp), onClick = { nav.navigate(Routes.mediaViewer(id)) })
                    IconButton(onClick = { onChange(mediaIds - id) }, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove attachment")
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { picking = true }, label = { Text("From vault") }, leadingIcon = { Icon(Icons.Filled.AttachFile, null) })
            AssistChip(onClick = { importer.launch(arrayOf("image/*", "audio/*")) }, label = { Text("Import") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { nav.navigate(Routes.CAMERA) }, label = { Text("Photo") }, leadingIcon = { Icon(Icons.Filled.PhotoCamera, null) })
            AssistChip(onClick = { nav.navigate(Routes.RECORDER) }, label = { Text("Record") }, leadingIcon = { Icon(Icons.Filled.Mic, null) })
        }
    }

    if (picking) {
        var selection by remember { mutableStateOf(mediaIds.toSet()) }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Attach from vault") },
            text = {
                val items = index?.items.orEmpty()
                if (items.isEmpty()) {
                    InfoText("The media vault is empty.")
                } else {
                    LazyVerticalGrid(GridCells.Adaptive(88.dp), Modifier.heightIn(max = 420.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(items, key = { it.id }) { item ->
                            Column {
                                MediaThumb(item, Modifier.fillMaxWidth().size(88.dp), selected = item.id in selection, onClick = {
                                    selection = if (item.id in selection) selection - item.id else selection + item.id
                                })
                                Text(item.originalName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Keep the existing order, append new picks.
                    onChange(mediaIds.filter { it in selection } + selection.filter { it !in mediaIds })
                    picking = false
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        )
    }
}
