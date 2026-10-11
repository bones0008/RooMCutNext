@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.RooMCutNext.ui.editor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.RooMCutNext.R
import com.RooMCutNext.core.util.TimeCode
import com.RooMCutNext.export.ExportBus
import com.RooMCutNext.ui.theme.AccentColor
import com.RooMCutNext.ui.timeline.TimelineView

@Composable
fun EditorScreen(projectId: String, onExit: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    val vm: EditorViewModel = viewModel(
        key = "editor-$projectId",
        factory = EditorViewModel.factory(app, projectId),
    )
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // ---- media pickers ----
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(8)
    ) { uris -> vm.importUris(uris) }

    val openDocuments = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.importUris(uris) }

    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.importUris(uris) }

    val audioMimeTypes = arrayOf(
        "audio/*",
        "application/ogg", "application/x-flac", "application/flac",
    )

    val replaceMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val clipId = state.selectedClipId
        if (uri != null && clipId != null) vm.replaceClipAsset(clipId, uri)
    }

    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.startExport() else vm.openPanel(null)
    }

    fun launchMediaPicker() {
        pickMedia.launch(
            PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.ImageAndVideo
            )
        )
    }

    // ---- toast ----
    val toastMsg = state.toast
    LaunchedEffect(toastMsg) {
        if (toastMsg != null) {
            snackbar.showSnackbar(toastMsg)
            vm.clearToast()
        }
    }

    // ---- lifecycle: pause preview + flush save ----
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> vm.playback.pause()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> vm.saveNow()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.saveNow()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {

        // ---------------- top bar ----------------
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExit) {
                Icon(Icons.Filled.ArrowBack, stringResource(R.string.editor_undo),
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    state.project.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "${state.project.width}×${state.project.height} · ${state.project.fps}FPS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { vm.undo() }, enabled = vm.canUndo) {
                Icon(Icons.Filled.Undo, stringResource(R.string.editor_undo),
                    tint = if (vm.canUndo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            }
            IconButton(onClick = { vm.redo() }, enabled = vm.canRedo) {
                Icon(Icons.Filled.Redo, stringResource(R.string.editor_redo),
                    tint = if (vm.canRedo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            }
            Surface(
                color = AccentColor,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(end = 8.dp),
            ) {
                TextButton(onClick = {
                    vm.openPanel(PanelKind.EXPORT)
                }) {
                    Text(
                        stringResource(R.string.editor_export),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        // ---------------- preview ----------------
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { view -> view.player = vm.playback.player },
                modifier = Modifier.fillMaxSize(),
            )

            state.importing?.let { imp ->
                Surface(
                    color = Color(0x88000000),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(color = AccentColor, strokeWidth = 3.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.importing_progress, imp.done + 1, imp.total, imp.current),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            maxLines = 1,
                        )
                    }
                }
            }

            state.playbackError?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.error,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.error_playback_fmt, err),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            maxLines = 3,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        TextButton(onClick = { vm.clearPlaybackError() }) {
                            Text(stringResource(R.string.ok), color = Color.White)
                        }
                    }
                }
            }

            if (state.previewDegraded) {
                Surface(
                    color = Color(0xCC7A5900),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                ) {
                    Text(
                        stringResource(R.string.preview_degraded_chip),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }

        // ---------------- transport + timecodes ----------------
        PlaybackBar(vm = vm, state = state)

        // ---------------- timeline ----------------
        Box(Modifier.weight(1f).fillMaxWidth()) {
            TimelineView(
                vm = vm,
                onAddMedia = { launchMediaPicker() },
                onImportFromFileManager = {
                    openDocuments.launch(arrayOf("video/*", "image/*", "audio/*"))
                },
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }

        // ---------------- action toolbar ----------------
        ActionToolbar(
            state = state,
            onAddMedia = { launchMediaPicker() },
            onSplit = { vm.splitAtPlayhead() },
            onDelete = { vm.deleteSelected() },
            onDuplicate = { vm.duplicateSelected() },
            onSpeed = { vm.openPanel(PanelKind.SPEED) },
            onRotate = { vm.openPanel(PanelKind.ROTATE) },
            onCrop = { vm.openPanel(PanelKind.CROP) },
            onAdjust = { vm.openPanel(PanelKind.ADJUST) },
            onText = { vm.addTextAtPlayhead() },
            onAudio = {
                if (state.selectedAudio != null || state.selectedClip != null) {
                    vm.openPanel(PanelKind.AUDIO)
                } else {
                    pickAudio.launch(audioMimeTypes)
                }
            },
            onInfo = { vm.openPanel(PanelKind.INFO) },
            onGoto = { vm.openPanel(PanelKind.GOTO) },
        )
    }

    // ---------------- bottom sheet panels ----------------
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    state.activePanel?.let { panel ->
        ModalBottomSheet(
            onDismissRequest = { vm.openPanel(null) },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            when (panel) {
                PanelKind.SPEED -> SpeedSheet(vm, state)
                PanelKind.ROTATE -> RotateSheet(vm, state)
                PanelKind.CROP -> CropSheet(vm, state)
                PanelKind.ADJUST -> AdjustSheet(vm, state)
                PanelKind.TEXT -> TextSheet(vm, state)
                PanelKind.AUDIO -> AudioSheet(vm, state, onAddMusic = { pickAudio.launch(audioMimeTypes) })
                PanelKind.INFO -> InfoSheet(vm, state, onReplace = {
                    replaceMedia.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                })
                PanelKind.GOTO -> GotoSheet(vm, state)
                PanelKind.EXPORT -> ExportSheet(
                    vm = vm,
                    state = state,
                    onNeedPermission = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            vm.startExport()
                        }
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PlaybackBar(vm: EditorViewModel, state: EditorUiState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.goToStart() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.KeyboardDoubleArrowLeft, stringResource(R.string.go_start),
                    tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { vm.stepFrame(false) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.FastRewind, stringResource(R.string.prev_frame),
                    tint = MaterialTheme.colorScheme.onSurface)
            }
            Surface(
                onClick = { vm.togglePlay() },
                shape = androidx.compose.foundation.shape.CircleShape,
                color = AccentColor,
                modifier = Modifier.size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                        tint = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
            IconButton(onClick = { vm.stepFrame(true) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.FastForward, stringResource(R.string.next_frame),
                    tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { vm.goToEnd() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.KeyboardDoubleArrowRight, stringResource(R.string.go_end),
                    tint = MaterialTheme.colorScheme.onSurface)
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                TimeCode.formatClock(state.playheadUs),
                style = MaterialTheme.typography.titleLarge,
                color = AccentColor,
            )
            Text(
                " / ${TimeCode.formatClock(state.totalDurationUs)}",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.zoomOut() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.ZoomOut, stringResource(R.string.zoom_out),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { vm.fitZoom() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.FitScreen, stringResource(R.string.zoom_fit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { vm.zoomIn() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.ZoomIn, stringResource(R.string.zoom_in),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ActionToolbar(
    state: EditorUiState,
    onAddMedia: () -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onSpeed: () -> Unit,
    onRotate: () -> Unit,
    onCrop: () -> Unit,
    onAdjust: () -> Unit,
    onText: () -> Unit,
    onAudio: () -> Unit,
    onInfo: () -> Unit,
    onGoto: () -> Unit,
) {
    val hasClip = state.selectedClip != null
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
    ) {
        Spacer(Modifier.width(8.dp))
        ToolbarButton(Icons.Filled.Add, stringResource(R.string.timeline_add_media), enabled = true, onClick = onAddMedia)
        ToolbarButton(Icons.Filled.ContentCut, stringResource(R.string.action_split), enabled = hasClip, onClick = onSplit)
        ToolbarButton(Icons.Filled.Delete, stringResource(R.string.action_delete), enabled = state.hasSelection, onClick = onDelete)
        ToolbarButton(Icons.Filled.ContentCopy, stringResource(R.string.action_duplicate), enabled = hasClip, onClick = onDuplicate)
        ToolbarButton(Icons.Filled.Speed, stringResource(R.string.action_speed), enabled = hasClip, onClick = onSpeed)
        ToolbarButton(Icons.Filled.RotateRight, stringResource(R.string.action_rotate), enabled = hasClip, onClick = onRotate)
        ToolbarButton(Icons.Filled.Crop, stringResource(R.string.action_crop), enabled = hasClip, onClick = onCrop)
        ToolbarButton(Icons.Filled.Tune, stringResource(R.string.action_adjust), enabled = hasClip, onClick = onAdjust)
        ToolbarButton(Icons.Filled.TextFields, stringResource(R.string.action_text), enabled = true, onClick = onText)
        ToolbarButton(Icons.Filled.MusicNote, stringResource(R.string.action_audio), enabled = true, onClick = onAudio)
        ToolbarButton(Icons.Filled.Info, stringResource(R.string.action_info), enabled = hasClip, onClick = onInfo)
        ToolbarButton(Icons.Filled.GpsFixed, stringResource(R.string.action_goto), enabled = true, onClick = onGoto)
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun ToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.padding(2.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon, null,
                tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                maxLines = 1,
            )
        }
    }
}
