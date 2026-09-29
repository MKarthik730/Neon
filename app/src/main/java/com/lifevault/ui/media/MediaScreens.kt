package com.lifevault.ui.media

import com.lifevault.Brand
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.lifevault.di.UnlockedGraph
import com.lifevault.domain.model.MediaIndex
import com.lifevault.domain.model.MediaItem
import com.lifevault.domain.model.MediaKind
import com.lifevault.media.AudioRecorder
import com.lifevault.ui.common.CollectMessages
import com.lifevault.ui.common.ConfirmDialog
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
import com.lifevault.ui.common.userMessage
import com.lifevault.ui.main.Routes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MediaGalleryViewModel(graph: UnlockedGraph) : VaultViewModel(graph) {
    val index: StateFlow<MediaIndex?> = session.media.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val kind = MutableStateFlow<MediaKind?>(null)
    val query = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    /** Originals that were imported and can be deleted by the app (SAF documents that allow it). */
    val deletableOriginals = MutableStateFlow<List<Uri>>(emptyList())

    fun import(uris: List<Uri>) = launchSafe(onError = { busy.value = false }) {
        busy.value = true
        var n = 0
        val deletable = ArrayList<Uri>()
        for (u in uris) {
            val info = runCatching { graph.media.describe(u) }.getOrNull()
            graph.media.import(u)
            n++
            if (info?.canDelete == true) deletable += u
        }
        busy.value = false
        say("Imported $n file(s), encrypted in the vault.")
        deletableOriginals.value = deletable
    }

    fun deleteOriginals() = launchSafe {
        val list = deletableOriginals.value
        deletableOriginals.value = emptyList()
        val ok = list.count { graph.media.deleteOriginal(it) }
        say(if (ok == list.size) "Deleted $ok original(s)." else "Deleted $ok of ${list.size} originals. Delete the rest in your file manager.")
    }
}

@Composable
fun MediaGalleryScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val vm: MediaGalleryViewModel = viewModel { MediaGalleryViewModel(graph) }
    CollectMessages(vm.messages)
    val index by vm.index.collectAsStateWithLifecycle()
    val kind by vm.kind.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val deletable by vm.deletableOriginals.collectAsStateWithLifecycle()
    val importer = rememberExternalLauncher(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> -> if (uris.isNotEmpty()) vm.import(uris) }
    ScreenScaffold(
        title = "Media vault",
        actions = {
            IconButton(onClick = { nav.navigate(Routes.CAMERA) }) { Icon(Icons.Filled.PhotoCamera, contentDescription = "Take photo") }
            IconButton(onClick = { nav.navigate(Routes.RECORDER) }) { Icon(Icons.Filled.Mic, contentDescription = "Record audio") }
            IconButton(onClick = { importer.launch(arrayOf("image/*", "audio/*")) }) { Icon(Icons.Filled.Add, contentDescription = "Import files") }
        },
    ) { padding ->
        val idx = index
        if (idx == null) {
            LoadingBox(Modifier.padding(padding))
            return@ScreenScaffold
        }
        val shown = idx.items.filter { (kind == null || it.kind == kind) && it.matches(query) }
        LazyVerticalGrid(
            GridCells.Adaptive(104.dp),
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        query, { vm.query.value = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Filled.Search, null) }, label = { Text("Search names and tags") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(kind == null, { vm.kind.value = null }, label = { Text("All ${idx.items.size}") })
                        FilterChip(kind == MediaKind.IMAGE, { vm.kind.value = MediaKind.IMAGE }, label = { Text("Images") })
                        FilterChip(kind == MediaKind.AUDIO, { vm.kind.value = MediaKind.AUDIO }, label = { Text("Audio") })
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (shown.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(if (idx.items.isEmpty()) "Import photos and recordings, or capture them here. They are encrypted inside the vault folder." else "Nothing matches.")
                }
            }
            items(shown, key = { it.id }) { item ->
                Column {
                    MediaThumb(item, Modifier.fillMaxWidth().aspectRatio(1f), onClick = { nav.navigate(Routes.mediaViewer(item.id)) })
                    Text(item.originalName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (deletable.isNotEmpty()) {
        ConfirmDialog(
            title = "Delete the originals?",
            text = "${deletable.size} imported file(s) are now encrypted in the vault. Delete the unencrypted originals from their old location?",
            confirmLabel = "Delete originals", dismissLabel = "Keep them", destructive = true,
            onConfirm = { vm.deleteOriginals() }, onDismiss = { vm.deletableOriginals.value = emptyList() },
        )
    }
}

class MediaViewerViewModel(graph: UnlockedGraph, private val id: String) : VaultViewModel(graph) {
    val item: StateFlow<MediaItem?> = kotlinx.coroutines.flow.combine(session.media.flow, MutableStateFlow(id)) { idx, i -> idx.items.firstOrNull { it.id == i } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val bitmap = MutableStateFlow<Bitmap?>(null)
    val loadError = MutableStateFlow<String?>(null)
    val deleted = MutableStateFlow(false)
    val player get() = graph.player

    fun loadImage(item: MediaItem) = launchSafe(onError = { loadError.value = it.userMessage() }) {
        if (bitmap.value == null) bitmap.value = graph.media.loadImage(item) ?: run { loadError.value = "This image cannot be shown."; null }
    }

    fun export(item: MediaItem, uri: Uri) = launchSafe { graph.media.exportDecrypted(item, uri); say("Exported an unencrypted copy.") }
    fun delete(item: MediaItem) = launchSafe {
        if (player.state.value.itemId == item.id) player.stop()
        graph.media.delete(item)
        deleted.value = true
    }
    fun save(item: MediaItem) = launchSafe { graph.media.update(item) }

    override fun onCleared() {
        if (!session.isClosed && player.state.value.itemId == id) player.pause()
    }
}

@Composable
fun MediaViewerScreen(nav: NavHostController, id: String) {
    val graph = LocalGraph.current
    val vm: MediaViewerViewModel = viewModel { MediaViewerViewModel(graph, id) }
    CollectMessages(vm.messages)
    val item by vm.item.collectAsStateWithLifecycle()
    val bitmap by vm.bitmap.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }
    LaunchedEffect(deleted) { if (deleted) nav.popBackStack() }
    val it0 = item
    val exporter = rememberExternalLauncher(ActivityResultContracts.CreateDocument(it0?.mimeType ?: "application/octet-stream")) { uri ->
        val i = item
        if (uri != null && i != null) vm.export(i, uri)
    }
    ScreenScaffold(
        title = it0?.originalName ?: "Media",
        onBack = { nav.popBackStack() },
        actions = {
            if (it0 != null) {
                IconButton(onClick = { confirmExport = true }) { Icon(Icons.Filled.FileDownload, contentDescription = "Export decrypted copy") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            }
        },
    ) { padding ->
        val i = item
        if (i == null) {
            EmptyState("This item is no longer in the vault.", Modifier.padding(padding))
            return@ScreenScaffold
        }
        LaunchedEffect(i.id) { if (i.kind == MediaKind.IMAGE) vm.loadImage(i) }
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (i.kind) {
                MediaKind.IMAGE -> {
                    val b = bitmap
                    when {
                        loadError != null -> ErrorText(loadError!!)
                        b == null -> LoadingBox(Modifier.heightIn(min = 240.dp))
                        else -> ZoomableImage(b, i.originalName)
                    }
                }
                MediaKind.AUDIO -> AudioPlayerCard(vm, i)
                MediaKind.OTHER -> InfoText("This file type cannot be previewed. Export a decrypted copy to open it in another app.")
            }
            SectionCard(title = "Details") {
                InfoText("${i.mimeType} · ${Format.bytes(i.sizeBytes)} · added ${Format.dateTime(java.time.Instant.ofEpochMilli(i.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime())}")
                if (i.width != null && i.height != null) InfoText("${i.width} × ${i.height}")
                i.durationMs?.let { InfoText("Length ${Format.duration(it)}") }
            }
            SectionCard(title = "Tags") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in i.tags) InputChip(selected = false, onClick = { vm.save(i.copy(tags = i.tags - t)) }, label = { Text("$t  ✕") })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newTag, { newTag = it }, label = { Text("Add tag") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val t = newTag.trim()
                        if (t.isNotEmpty() && t !in i.tags) vm.save(i.copy(tags = i.tags + t))
                        newTag = ""
                    }) { Text("Add") }
                }
            }
            NoteField(i.note) { vm.save(i.copy(note = it)) }
        }
    }
    val i = item
    if (confirmDelete && i != null) {
        ConfirmDialog(
            title = "Delete ${i.originalName}?",
            text = "The encrypted file is overwritten and removed from the vault. Attachments pointing to it will show as missing.",
            confirmLabel = "Delete", destructive = true, onConfirm = { vm.delete(i) }, onDismiss = { confirmDelete = false },
        )
    }
    if (confirmExport && i != null) {
        ConfirmDialog(
            title = "Export an unencrypted copy?",
            text = "This writes a normal, readable copy of ${i.originalName} to a location you choose, outside the vault. Anyone with access to that location can open it.",
            confirmLabel = "Choose location",
            onConfirm = { exporter.launch(i.originalName) }, onDismiss = { confirmExport = false },
        )
    }
}

@Composable
private fun NoteField(initial: String, onSave: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    OutlinedTextField(text, { text = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
    if (text != initial) TextButton(onClick = { onSave(text) }) { Text("Save note") }
}

@Composable
private fun ZoomableImage(bitmap: Bitmap, description: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Image(
        bitmap.asImageBitmap(),
        contentDescription = description,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
    )
}

@Composable
private fun AudioPlayerCard(vm: MediaViewerViewModel, item: MediaItem) {
    val state by vm.player.state.collectAsStateWithLifecycle()
    val mine = state.itemId == item.id
    val duration = if (mine && state.durationMs > 0) state.durationMs else item.durationMs ?: 0
    val position = if (mine) state.positionMs else 0
    SectionCard(title = "Recording") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledIconButton(onClick = { if (mine && state.isPlaying) vm.player.pause() else vm.player.play(item.id) }, modifier = Modifier.size(56.dp)) {
                Icon(if (mine && state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (mine && state.isPlaying) "Pause" else "Play")
            }
            Text("${Format.duration(position)} / ${Format.duration(duration)}")
        }
        Slider(
            value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { f -> if (mine) vm.player.seekTo((f * duration).toLong()) else { vm.player.play(item.id); vm.player.seekTo((f * duration).toLong()) } },
            enabled = duration > 0,
        )
        if (mine) state.error?.let { ErrorText(it) }
        InfoText("Decrypted on the fly while playing; no unencrypted copy is written.")
    }
}

@Composable
private fun rememberPermission(permission: String): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberExternalLauncher(ActivityResultContracts.RequestPermission()) { granted = it }
    return granted to { launcher.launch(permission) }
}

@Composable
fun CameraScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val (granted, request) = rememberPermission(Manifest.permission.CAMERA)
    var lensBack by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var savedCount by remember { mutableStateOf(0) }
    val previewView = remember { PreviewView(context) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(granted) { if (!granted) request() }
    LaunchedEffect(granted, lensBack) {
        if (!granted) return@LaunchedEffect
        runCatching {
            val p = ProcessCameraProvider.awaitInstance(context)
            provider = p
            p.unbindAll()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val selector = if (lensBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            p.bindToLifecycle(lifecycleOwner, selector, preview, capture)
        }.onFailure { error = "Camera unavailable: ${it.message}" }
    }
    DisposableEffect(Unit) { onDispose { provider?.unbindAll() } }

    ScreenScaffold(title = "Take photo", onBack = { nav.popBackStack() }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!granted) {
                EmptyState("${Brand.NAME} needs the camera permission to take photos straight into the vault.", actionLabel = "Allow camera", onAction = request)
                return@ScreenScaffold
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView({ previewView }, Modifier.fillMaxSize())
            }
            error?.let { ErrorText(it, Modifier.padding(8.dp)) }
            InfoText(
                if (savedCount == 0) "Photos are kept in memory, encrypted, and written only into the vault — never to the gallery."
                else "$savedCount photo(s) saved to the vault.",
                Modifier.padding(horizontal = 16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { lensBack = !lensBack }) { Icon(Icons.Filled.Cameraswitch, contentDescription = "Switch camera") }
                FilledIconButton(
                    onClick = {
                        busy = true
                        capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                                val rotation = image.imageInfo.rotationDegrees
                                image.close()
                                scope.launch {
                                    runCatching { graph.media.saveCapturedJpeg(bytes, rotation) }
                                        .onSuccess { item ->
                                            savedCount++
                                            nav.previousBackStackEntry?.savedStateHandle?.set(Routes.RESULT_MEDIA_ID, item.id)
                                            if (nav.previousBackStackEntry?.destination?.route != Routes.MEDIA) nav.popBackStack()
                                        }
                                        .onFailure { error = it.userMessage() }
                                    busy = false
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                error = "Capture failed: ${exception.message}"
                                busy = false
                            }
                        })
                    },
                    enabled = !busy,
                    modifier = Modifier.size(72.dp),
                ) { Icon(Icons.Filled.PhotoCamera, contentDescription = "Capture") }
                TextButton(onClick = { nav.popBackStack() }) { Text("Done") }
            }
        }
    }
}

class RecorderViewModel(graph: UnlockedGraph) : VaultViewModel(graph) {
    private var recorder: AudioRecorder? = null
    val recording = MutableStateFlow(false)
    val elapsed = MutableStateFlow(0L)
    val level = MutableStateFlow(0f)
    val savedId = MutableStateFlow<String?>(null)

    fun start() = launchSafe(onError = { recording.value = false }) {
        val r = graph.media.newRecorder()
        r.start()
        recorder = r
        recording.value = true
        viewModelScope.launch {
            while (isActive && recording.value) {
                elapsed.value = r.elapsedMs
                level.value = r.level
                delay(100)
            }
        }
    }

    fun stop() = launchSafe(onError = { recording.value = false }) {
        val r = recorder ?: return@launchSafe
        recording.value = false
        val duration = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { r.stop() }
        val item = graph.media.saveRecording(r, duration)
        recorder = null
        savedId.value = item.id
    }

    fun discard() {
        recording.value = false
        recorder?.cancel()
        recorder = null
    }

    override fun onCleared() = discard()
}

@Composable
fun RecorderScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val vm: RecorderViewModel = viewModel { RecorderViewModel(graph) }
    CollectMessages(vm.messages)
    val (granted, request) = rememberPermission(Manifest.permission.RECORD_AUDIO)
    val recording by vm.recording.collectAsStateWithLifecycle()
    val elapsed by vm.elapsed.collectAsStateWithLifecycle()
    val level by vm.level.collectAsStateWithLifecycle()
    val savedId by vm.savedId.collectAsStateWithLifecycle()
    LaunchedEffect(granted) { if (!granted) request() }
    val view = LocalView.current
    DisposableEffect(recording) {
        view.keepScreenOn = recording
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(savedId) {
        val id = savedId ?: return@LaunchedEffect
        nav.previousBackStackEntry?.savedStateHandle?.set(Routes.RESULT_MEDIA_ID, id)
        nav.popBackStack()
    }
    ScreenScaffold(title = "Record audio", onBack = { vm.discard(); nav.popBackStack() }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (!granted) {
                EmptyState("${Brand.NAME} needs the microphone permission to record straight into the vault.", actionLabel = "Allow microphone", onAction = request)
                return@ScreenScaffold
            }
            Text(Format.duration(elapsed), style = MaterialTheme.typography.displayMedium)
            LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())
            FilledIconButton(onClick = { if (recording) vm.stop() else vm.start() }, modifier = Modifier.size(88.dp)) {
                Icon(if (recording) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = if (recording) "Stop and save" else "Start recording", modifier = Modifier.size(40.dp))
            }
            if (recording) OutlinedButton(onClick = { vm.discard() }) { Text("Discard") }
            InfoText("Audio is encoded and encrypted as it is recorded; no unencrypted file is ever written. Keep ${Brand.NAME} open while recording — locking the vault discards an unfinished recording.")
        }
    }
}
