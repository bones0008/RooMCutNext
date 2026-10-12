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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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

    // ---- hidden DIAG overlay toggle: 5 taps on the project title ----
    val titleInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    var titleTapCount by remember { mutableStateOf(0) }
    var lastTitleTapMs by remember { mutableStateOf(0L) }

    // ---- media pickers ----
    // "添加素材" accepts ALL media types (video / image / audio): audio files
    // become independent audio-layer clips, so no separate audio import entry
    // is needed anymore. Photo Picker (PickVisualMedia) cannot handle audio,
    // so the unified entry uses the document picker with combined mimes.
    val mediaMimeTypes = arrayOf(
        "video/*", "image/*", "audio/*",
        "application/ogg", "application/x-flac", "application/flac",
    )

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
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

    // ---- v1.1: font & LUT import pickers (document picker; most providers
    // report .ttf/.cube as generic octet-stream, so include it) ----
    val fontMimeTypes = arrayOf(
        "font/ttf", "font/otf",
        "application/x-font-ttf", "application/x-font-otf",
        "application/octet-stream",
    )
    val lutMimeTypes = arrayOf(
        "application/octet-stream", "text/plain", "application/x-cube",
    )

    val pickFont = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> vm.importFont(uri) }

    val pickLut = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> vm.importLut(uri) }

    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.startExport() else vm.openPanel(null)
    }

    fun launchMediaPicker() {
        pickMedia.launch(mediaMimeTypes)
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
            // v1.1.1: lift the whole layout above the IME so text fields in
            // the inline tool panel (文字/精确定位) stay visible while typing.
            // Kept AFTER background() so the background still paints edge to
            // edge; when no keyboard is showing this is a no-op.
            .imePadding()
    ) {

        // ---------------- top bar ----------------
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                // v1.0.10: bottom padding keeps the (now compact) export chip
                // clear of the preview's top edge — it used to touch the video.
                .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExit) {
                Icon(Icons.Filled.ArrowBack, stringResource(R.string.editor_undo),
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Column(
                Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = titleInteraction,
                        indication = null,
                    ) {
                        // 5 taps on the title within 2s toggles the DIAG overlay
                        // (hidden diagnostics for field debugging).
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastTitleTapMs > 2000L) titleTapCount = 0
                        lastTitleTapMs = now
                        titleTapCount++
                        if (titleTapCount >= 5) {
                            titleTapCount = 0
                            vm.toggleDiagOverlay()
                        }
                    },
            ) {
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
            // v1.0.10: compact export chip. The old Surface+TextButton pair
            // carried TextButton's generous content padding, making the blue
            // block nearly as tall as the bar and visually crowding the video
            // below. Now a tight 30dp chip; the row's bottom padding above
            // supplies the gap to the preview. (Surface(onClick) is NOT used
            // here on purpose: it enforces a 48dp minimum interactive size
            // and would re-inflate the blue block.)
            Box(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .height(30.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(AccentColor)
                    .clickable { vm.openPanel(PanelKind.EXPORT) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.editor_export),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
        }

        // ---------------- preview ----------------
        // Portrait projects get a TALL preview (58% of the flexible space)
        // instead of a 16:9 letterbox sliver; landscape keeps 16:9.
        val portrait = state.project.height > state.project.width
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (portrait) Modifier.weight(1.4f) else Modifier.aspectRatio(16f / 9f))
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }.also { view ->
                        // v1.0.8 black-screen fix: create + bind the player at
                        // view-factory time so the output surface exists BEFORE
                        // the first setComposition()/prepare() (see
                        // PlaybackController.bindView). The update block below
                        // keeps the binding correct across recompositions.
                        vm.playback.bindView(view)
                    }
                },
                update = { view ->
                    if (view.player !== vm.playback.player) {
                        view.player = vm.playback.player
                    }
                    vm.reportPreviewViewSize(view.width, view.height)
                },
                onRelease = { view ->
                    view.player = null
                    vm.playback.unbindView()
                },
                modifier = Modifier.fillMaxSize(),
            )

            // ---- DIAG overlay (v1.0.6-diag): player lifecycle events on screen.
            // Hidden by default in the clean build; tap the project title 5x
            // to toggle it for field debugging. ----
            if (state.diagOverlayVisible && state.diagLog.isNotEmpty()) {
                Column(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .background(Color(0xB2000000), MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    state.diagLog.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF69F0AE),
                            maxLines = 1,
                        )
                    }
                }
            }

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

        // ---------------- timeline / inline tool panel ----------------
        // v1.1.1: while a tool panel is open it takes over the timeline area
        // — the preview above is NEVER covered, so sliders can be dragged
        // with the grading effect fully visible. Every panel scrolls inside
        // this bounded region; closing it brings the timeline back.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val panel = state.activePanel
            if (panel == null) {
                TimelineView(
                    vm = vm,
                    onAddMedia = { launchMediaPicker() },
                    onImportFromFileManager = {
                        openDocuments.launch(mediaMimeTypes)
                    },
                )
            } else {
                ToolPanelHost(
                    vm = vm,
                    state = state,
                    panel = panel,
                    onImportLut = { pickLut.launch(lutMimeTypes) },
                    onImportFont = { pickFont.launch(fontMimeTypes) },
                    onAddMusic = { pickAudio.launch(audioMimeTypes) },
                    onReplace = {
                        replaceMedia.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    onNeedPermission = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            vm.startExport()
                        }
                    },
                )
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }

        // ---------------- action toolbar ----------------
        ActionToolbar(
            state = state,
            onAddMedia = { launchMediaPicker() },
            onSpeed = { vm.openPanel(PanelKind.SPEED) },
            onRotate = { vm.openPanel(PanelKind.ROTATE) },
            onCrop = { vm.openPanel(PanelKind.CROP) },
            onAdjust = { vm.openPanel(PanelKind.ADJUST) },
            // Toggle: tapping 文字 while the text panel is already open
            // closes it instead of stacking another empty overlay.
            onText = {
                if (state.activePanel == PanelKind.TEXT) vm.openPanel(null)
                else vm.addTextAtPlayhead()
            },
            // "音频" is now purely an audio SETTINGS tool: it opens the audio
            // panel for the selected clip (video: volume/mute/fades; audio:
            // its own controls). Importing audio lives in 添加素材 instead.
            onAudio = { vm.openPanel(PanelKind.AUDIO) },
            onInfo = { vm.openPanel(PanelKind.INFO) },
            onGoto = { vm.openPanel(PanelKind.GOTO) },
        )
    }
}

@Composable
private fun PlaybackBar(vm: EditorViewModel, state: EditorUiState) {
    // v1.0.8: breathing room between the video area and the transport row —
    // the blue play button used to touch the bottom edge of the preview.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp)
            .padding(horizontal = 8.dp)
    ) {
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

        // v1.0.12: jitter-free timecode row. While playing, the playhead
        // digits change width every frame in a proportional font (1 is
        // narrower than 0), and the old two-flex-spacer layout translated
        // that width change into horizontal wobble of the total-duration
        // text and the split/delete/copy cluster. Two fixes:
        //  1) Structure: timecodes live in a weight(1f) box aligned start,
        //     the zoom group in an equal weight(1f) box aligned end, and the
        //     fixed-width icon cluster sits BETWEEN the two boxes — its
        //     center is the row center, independent of any text width.
        //  2) Typography: monospace timecodes — every digit has the same
        //     advance width, so the total-duration text (which follows the
        //     playhead text) holds perfectly still frame to frame.
        val canSplit = state.project.clips.isNotEmpty() || state.project.audios.isNotEmpty()
        val hasClip = state.selectedClip != null
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        TimeCode.formatClockFrames(state.playheadUs, state.project.fps),
                        style = MaterialTheme.typography.titleSmall,
                        color = AccentColor,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                    Text(
                        " / ${TimeCode.formatClockFrames(state.totalDurationUs, state.project.fps)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
            }
            IconButton(
                onClick = { vm.splitAtPlayhead() },
                enabled = canSplit,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Filled.ContentCut, stringResource(R.string.action_split),
                    tint = if (canSplit) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(
                onClick = { vm.deleteSelected() },
                enabled = state.hasSelection,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Filled.Delete, stringResource(R.string.action_delete),
                    tint = if (state.hasSelection) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(
                onClick = { vm.duplicateSelected() },
                enabled = hasClip,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Filled.ContentCopy, stringResource(R.string.action_duplicate),
                    tint = if (hasClip) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                )
            }
            Box(
                Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row {
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
    }
}

@Composable
private fun ActionToolbar(
    state: EditorUiState,
    onAddMedia: () -> Unit,
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
    // v1.1.1: the button of the currently-open inline panel is highlighted
    // (dim-accent background) so the user always knows which tool is active
    // while the timeline area is showing the panel instead.
    val active = state.activePanel
    // v1.0.10: 分割/删除/复制 moved up into the timecode row; 文字/音频 moved
    // right next to 添加素材 (they are the most-used actions and used to hide
    // off-screen in the scroll). Order: add, text, audio, then per-clip tools.
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
    ) {
        Spacer(Modifier.width(8.dp))
        ToolbarButton(Icons.Filled.Add, stringResource(R.string.timeline_add_media), enabled = true, active = false, onClick = onAddMedia)
        ToolbarButton(Icons.Filled.TextFields, stringResource(R.string.action_text), enabled = true, active = active == PanelKind.TEXT, onClick = onText)
        ToolbarButton(Icons.Filled.MusicNote, stringResource(R.string.action_audio), enabled = true, active = active == PanelKind.AUDIO, onClick = onAudio)
        ToolbarButton(Icons.Filled.Speed, stringResource(R.string.action_speed), enabled = hasClip, active = active == PanelKind.SPEED, onClick = onSpeed)
        ToolbarButton(Icons.Filled.RotateRight, stringResource(R.string.action_rotate), enabled = hasClip, active = active == PanelKind.ROTATE, onClick = onRotate)
        ToolbarButton(Icons.Filled.Crop, stringResource(R.string.action_crop), enabled = hasClip, active = active == PanelKind.CROP, onClick = onCrop)
        ToolbarButton(Icons.Filled.Tune, stringResource(R.string.action_adjust), enabled = hasClip, active = active == PanelKind.ADJUST, onClick = onAdjust)
        ToolbarButton(Icons.Filled.Info, stringResource(R.string.action_info), enabled = hasClip, active = active == PanelKind.INFO, onClick = onInfo)
        ToolbarButton(Icons.Filled.GpsFixed, stringResource(R.string.action_goto), enabled = true, active = active == PanelKind.GOTO, onClick = onGoto)
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun ToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.padding(2.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val tint = when {
                active -> AccentColor
                enabled -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.outline
            }
            Icon(
                icon, null,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

// ===========================================================================
// Inline tool panel host (v1.1.1)
//
// The old ModalBottomSheet expanded over the preview and blocked the very
// thing the user was adjusting. The inline host lives INSIDE the timeline
// area instead: the preview, transport bar and action toolbar all stay
// visible, the panel gets the timeline's bounded height, and its body is a
// single shared vertical scroller — every tool (调色/文字/音频/…) scrolls the
// same way while the grading result stays fully in view.
// ===========================================================================

/** Panels that operate on the selected video clip (guard + header badge). */
private val CLIP_PANELS = setOf(
    PanelKind.SPEED, PanelKind.ROTATE, PanelKind.CROP, PanelKind.ADJUST, PanelKind.INFO,
)

@Composable
private fun ToolPanelHost(
    vm: EditorViewModel,
    state: EditorUiState,
    panel: PanelKind,
    onImportLut: () -> Unit,
    onImportFont: () -> Unit,
    onAddMusic: () -> Unit,
    onReplace: () -> Unit,
    onNeedPermission: () -> Unit,
) {
    val titleRes = when (panel) {
        PanelKind.SPEED -> R.string.panel_speed_title
        PanelKind.ROTATE -> R.string.panel_rotate_title
        PanelKind.CROP -> R.string.panel_crop_title
        PanelKind.ADJUST -> R.string.panel_adjust_title
        PanelKind.TEXT -> R.string.panel_text_title
        PanelKind.AUDIO -> R.string.panel_audio_title
        PanelKind.INFO -> R.string.panel_info_title
        PanelKind.EXPORT -> R.string.panel_export_title
        PanelKind.GOTO -> R.string.panel_goto_title
    }
    // The timeline is hidden while the panel is open, so the header shows
    // WHICH clip the clip-targeted tools are editing.
    val clipIndex = state.project.clips.indexOfFirst { it.id == state.selectedClipId }
    val clipBadge = if (panel in CLIP_PANELS && clipIndex >= 0) {
        stringResource(R.string.panel_clip_of_fmt, clipIndex + 1, state.project.clips.size)
    } else {
        null
    }

    // v1.1.3: M3 Surface (non-clickable) instead of Column + background().
    // Surface provides LocalContentColor = contentColorFor(surface) =
    // onSurface to the whole subtree, so every Text without an explicit
    // color (slider labels, chip labels, info rows…) renders in the theme's
    // on-color again. Plain background() provides nothing — those texts
    // fell back to the raw composition-local default (BLACK) and were
    // unreadable on the dark surface. The old ModalBottomSheet performed
    // exactly this provide internally.
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
        // ---- pinned header: title + target clip + close ----
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (clipBadge != null) {
                Text(
                    clipBadge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { vm.openPanel(null) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    stringResource(R.string.panel_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // ---- scrollable body: the ONE vertical scroller shared by every
        // panel. Sheets emit plain ColumnScope content, so they stack
        // vertically and scroll together; sliders keep their horizontal
        // drag gesture, vertical drags scroll the panel. ----
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            val needsClip = panel in CLIP_PANELS
            when {
                needsClip && state.selectedClip == null ->
                    PanelEmptyHint(stringResource(R.string.panel_need_clip))
                panel == PanelKind.TEXT && state.selectedText == null ->
                    PanelEmptyHint(stringResource(R.string.panel_text_gone))
                else -> when (panel) {
                    PanelKind.SPEED -> SpeedSheet(vm, state)
                    PanelKind.ROTATE -> RotateSheet(vm, state)
                    PanelKind.CROP -> CropSheet(vm, state)
                    PanelKind.ADJUST -> AdjustSheet(vm, state, onImportLut = onImportLut)
                    PanelKind.TEXT -> TextSheet(vm, state, onImportFont = onImportFont)
                    PanelKind.AUDIO -> AudioSheet(vm, state, onAddMusic = onAddMusic)
                    PanelKind.INFO -> InfoSheet(vm, state, onReplace = onReplace)
                    PanelKind.GOTO -> GotoSheet(vm, state)
                    PanelKind.EXPORT -> ExportSheet(
                        vm = vm,
                        state = state,
                        onNeedPermission = onNeedPermission,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        }
    }
}

/** Friendly fallback when a panel's target was deleted mid-edit. */
@Composable
private fun PanelEmptyHint(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
