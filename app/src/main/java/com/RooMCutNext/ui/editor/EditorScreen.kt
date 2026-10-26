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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPictureAlt
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
import androidx.compose.material.icons.filled.Square
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.launch
import com.RooMCutNext.R
import com.RooMCutNext.core.model.BLACK_DEFAULT_DURATION_US
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

    // ---- v1.6.1: 黑场插入对话框（时长 + 前/后/末尾） ----
    var showBlackGapDialog by remember { mutableStateOf(false) }

    // ---- v1.6.3: AI 对话浮层开关（真机反馈：想看剪辑效果时关掉对话，
    // 得到完整视频画面）。rememberSaveable — 配置旋转后保持。 ----
    var aiOverlayVisible by rememberSaveable { mutableStateOf(true) }

    // ---- v1.6.11: 技能库弹窗开关提升到屏级（原为 AiBottomBar 私有）。
    // 空对话引导卡（浮层顶部）整卡可点直接打开技能库 —— 上传/导入
    // 自定义技能的入口在面板顶部，两轮真机反馈均找不到，需跨组件触发。 ----
    var aiSkillsOpen by rememberSaveable { mutableStateOf(false) }

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

    // v1.6.15: 「添加素材」入口已删（用户反馈：与素材库重复多此一举）——
    // 素材导入唯一路径 = 素材库面板顶部「导入素材」行，走同一个
    // pickMedia（多选，视频/图片/音频全收）。旧的 openDocuments
    // launcher 与「从文件管理器选择」旁路一并清理。

    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.importUris(uris) }

    // v1.2.2: import straight into a PiP layer at the playhead WITHOUT
    // touching the timeline — the sticker / overlay workflow. The main
    // 添加素材 keeps its "append to the timeline" behavior; this entry is
    // the answer to "the sticker I imported also landed on the timeline".
    val pickPipMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.importUris(uris, asPip = true) }

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

    // ---- v1.1.4: microphone permission for voiceover ----
    val requestMic = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.startVoiceover()
        else vm.flashToast(context.getString(R.string.record_permission_denied))
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
                // A backgrounded activity cannot keep the mic: finish the
                // take (keeping it when long enough) instead of losing it.
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    vm.playback.pause()
                    if (vm.isRecordingVoiceover) vm.stopVoiceover()
                }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> vm.saveNow()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // v1.2.6: the editor UI going away must never leave audio running
            // in the background. AppRoot clears the editor-scoped ViewModel
            // store right after this (which releases the player entirely);
            // pausing here first also covers any future host that embeds this
            // screen without that store teardown.
            vm.playback.pause()
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
            // v1.6.3: unconditional — AI-chat mode too. WeChat-style IME
            // avoidance: the keyboard compresses the whole column (the video
            // included), which keeps the input row directly above the IME.
            // v1.6.2 tried to keep the preview full-size and pad only the
            // chat area, but that area (timeline weight(1f) ≈ 1/3 screen)
            // cannot absorb an ~800dp IME — the negative available height
            // squeezed the input row clean off screen (real-device report).
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
        // v1.1.4: kept for frame export — PixelCopy needs the live PlayerView.
        var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
        val scope = rememberCoroutineScope()
        val grabLabel = stringResource(R.string.action_grab_frame)
        val frameSavedLabel = stringResource(R.string.frame_saved_fmt)
        val frameFailedLabel = stringResource(R.string.frame_save_failed)
        val frameEmptyLabel = stringResource(R.string.frame_empty_timeline)
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (portrait) Modifier.weight(1.4f) else Modifier.aspectRatio(16f / 9f))
                .background(Color.Black)
        ) {
            // v1.2.3: the preview stage is ONE FrameLayout holding
            //   [0] the PlayerView (body composition, bottom) and
            //   [1] an empty overlay layer the OverlayPreviewController fills
            //       with PiP TextureViews/ImageViews and the crossfade head
            //       (view-layer compositing — see OverlayPreviewController for
            //       why the composition itself stays single-video-sequence).
            // Keeping both in one Android view guarantees the overlay layer
            // always exactly covers the PlayerView bounds.
            AndroidView(
                factory = { ctx ->
                    android.widget.FrameLayout(ctx).apply {
                        val pv = PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                        }
                        addView(
                            pv,
                            android.widget.FrameLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            ),
                        )
                        val overlayLayer = android.widget.FrameLayout(ctx).apply {
                            // Purely a host for the overlay engine's children;
                            // never interactive.
                            isClickable = false
                        }
                        addView(
                            overlayLayer,
                            android.widget.FrameLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            ),
                        )
                        // v1.0.8 black-screen fix: create + bind the player at
                        // view-factory time so the output surface exists BEFORE
                        // the first setComposition()/prepare() (see
                        // PlaybackController.bindView). The update block below
                        // keeps the binding correct across recompositions.
                        vm.playback.bindView(pv)
                        vm.overlays.attach(overlayLayer)
                        playerViewRef = pv
                        // v1.6.2: IME-close video-restore fix. While PAUSED no
                        // frames flow, so after the stage resizes (keyboard
                        // opening/closing, panel switches…) the surface keeps
                        // showing the frame at the OLD size until playback
                        // resumes — the "must press play to see the video big
                        // again" report. Debounced same-frame redraw via the
                        // v1.3.3 replayable-cache nudge repaints the paused
                        // frame at the new size immediately.
                        val resizeNudge = Runnable { vm.onPreviewStageResized() }
                        addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                                v.removeCallbacks(resizeNudge)
                                v.postDelayed(resizeNudge, 120L)
                            }
                        }
                    }
                },
                update = { stage ->
                    val pv = (stage.getChildAt(0) as? PlayerView) ?: return@AndroidView
                    if (pv.player !== vm.playback.player) {
                        pv.player = vm.playback.player
                    }
                    vm.reportPreviewViewSize(stage.width, stage.height)
                },
                onRelease = { stage ->
                    vm.overlays.detach()
                    (stage.getChildAt(0) as? PlayerView)?.let { view ->
                        view.player = null
                    }
                    vm.playback.unbindView()
                    playerViewRef = null
                },
                modifier = Modifier.fillMaxSize(),
            )

            // v1.3.0/v1.3.1: direct manipulation of text overlays AND PiP
            // layers — tap to select, drag to move, pinch to scale (texts
            // also twist-rotate). Sits BETWEEN the stage and the grab-frame
            // button, so the button's corner always wins over the gesture
            // layer.
            PreviewGestureLayer(vm = vm, state = state)

            // v1.6.3: AI 对话浮层 — 半透明叠在视频显示区上（真机反馈
            // “对话内容做成透明浮层放在显示区域上面”）。reverseLayout
            // 底部对齐：最新气泡/提案卡贴播放栏上沿，视频上部完整露出；
            // 渐变 scrim 底深顶浅。浮层显示期间消费手势（滚动对话），
            // 想直接操作视频/看完整画面时用输入行左端的眼睛开关关掉。
            // 渲染顺序在手势层之上、拍照按钮之下 — 按钮永远可点。
            if (state.activePanel == PanelKind.AI_CHAT && aiOverlayVisible) {
                AiChatOverlay(
                    vm = vm,
                    onOpenSkills = { aiSkillsOpen = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // v1.1.4: save the current frame (WYSIWYG — includes grading,
            // crops and text overlays exactly as previewed) into the gallery.
            IconButton(
                onClick = {
                    val view = playerViewRef
                    if (view == null || !state.project.hasContent) {
                        vm.flashToast(frameEmptyLabel)
                        return@IconButton
                    }
                    scope.launch {
                        val bmp = com.RooMCutNext.media.FrameExporter.grabCurrentFrame(view)
                        if (bmp == null) {
                            vm.flashToast(frameFailedLabel)
                            return@launch
                        }
                        // Aspect of what is actually on screen: the player's
                        // current video size, falling back to the project
                        // canvas (image-only timelines report 0).
                        val vs = view.player?.videoSize
                        val contentW = if (vs != null && vs.width > 0) vs.width else state.project.width
                        val contentH = if (vs != null && vs.height > 0) vs.height else state.project.height
                        val cropped = com.RooMCutNext.media.FrameExporter.cropToContent(bmp, contentW, contentH)
                        val uri = com.RooMCutNext.media.FrameExporter.saveToGallery(
                            context, cropped, state.project.name,
                        )
                        if (uri != null) {
                            vm.flashToast(frameSavedLabel.format(
                                "${state.project.name}_frame"
                            ))
                        } else {
                            vm.flashToast(frameFailedLabel)
                        }
                        if (cropped !== bmp) cropped.recycle()
                        bmp.recycle()
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(36.dp)
                    .background(Color(0x66000000), androidx.compose.foundation.shape.CircleShape),
            ) {
                Icon(
                    Icons.Filled.PhotoCamera,
                    grabLabel,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }

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
        // v1.6.3: in AI-chat mode the chat content lives ON the video as a
        // translucent overlay (AiChatOverlay above) — the timeline box no
        // longer claims the weighted block; it shrinks to a wrap-content
        // input bar (AiBottomBar), giving the reclaimed height back to the
        // preview. Landscape keeps the weight (fixed 16:9 preview) so the
        // input bar still sits at the bottom edge.
        Box(
            Modifier
                .fillMaxWidth()
                .then(
                    if (state.activePanel == PanelKind.AI_CHAT && portrait) Modifier
                    else Modifier.weight(1f)
                )
        ) {
            val panel = state.activePanel
            if (panel == null) {
                TimelineView(
                    vm = vm,
                    // v1.6.15: 空时间线大 + 钮 → 打开素材库面板（导入
                    // 唯一路径），不再直接弹系统选择器。
                    onOpenLibrary = { vm.openPanel(PanelKind.LIBRARY) },
                )
            } else if (panel == PanelKind.AI_CHAT) {
                // v1.6.3: 微信式输入底栏（忙碌行 + 输入行 + 浮层开关）。
                // portrait：本 Box 已 wrap-content，底栏即全部高度；
                // landscape：Box 仍是 weight(1f)，底栏沉到 Box 底部。
                AiBottomBar(
                    vm = vm,
                    overlayVisible = aiOverlayVisible,
                    onToggleOverlay = { aiOverlayVisible = !aiOverlayVisible },
                    onClose = { vm.openPanel(null) },
                    skillsOpen = aiSkillsOpen,
                    onSkillsOpenChange = { aiSkillsOpen = it },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            } else {
                ToolPanelHost(
                    vm = vm,
                    state = state,
                    panel = panel,
                    onImportLut = { pickLut.launch(lutMimeTypes) },
                    onImportFont = { pickFont.launch(fontMimeTypes) },
                    onAddMusic = { pickAudio.launch(audioMimeTypes) },
                    // v1.6.13: 素材库面板内的「导入素材」→ 同一个统一选择器
                    // （视频/图片/音频全收，导入即入库）。
                    onImportMedia = { launchMediaPicker() },
                    onImportPipMedia = { pickPipMedia.launch(mediaMimeTypes) },
                    onRecord = {
                        // v1.1.4: mic permission gate before the take starts.
                        if (androidx.core.content.ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.RECORD_AUDIO
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            vm.startVoiceover()
                        } else {
                            requestMic.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    },
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
        // v1.6.2: hidden while the AI chat is open — those tools are useless
        // in a conversation (real-device feedback), and the chat gets the
        // reclaimed row of height. The panel's close button lives in its
        // own input row, so no exit affordance is lost.
        if (state.activePanel != PanelKind.AI_CHAT) {
            ActionToolbar(
            state = state,
            // v1.6.15: 素材库面板（导入即入库的浏览/上轨入口；「添加
            // 素材」按钮已删——导入唯一路径，见素材库面板顶部导入行）。
            onLibrary = { vm.openPanel(PanelKind.LIBRARY) },
            // v1.6.0: AI 剪辑 — 对话式 agent（不要求选中目标，agent 自己读项目）。
            onAi = { vm.openPanel(PanelKind.AI_CHAT) },
            // v1.6.1: 黑场 — 删除已改为收拢贴合，黑色停顿作为显式素材插入。
            onBlackGap = { showBlackGapDialog = true },
            onSpeed = { vm.openPanel(PanelKind.SPEED) },
            onRotate = { vm.openPanel(PanelKind.ROTATE) },
            onCrop = { vm.openPanel(PanelKind.CROP) },
            onAdjust = { vm.openPanel(PanelKind.ADJUST) },
            // v1.2.1: crossfade with the PREVIOUS clip — operates on the
            // selected clip; the sheet explains when a clip can't have one.
            onTransition = { vm.openPanel(PanelKind.TRANSITION) },
            // v1.2.2: per-clip mosaic regions (face / logo pixelation).
            onMosaic = { vm.openPanel(PanelKind.MOSAIC) },
            // v1.2: picture-in-picture / sticker tool — always available;
            // the sheet itself offers the asset picker when nothing is
            // selected yet.
            onPip = { vm.openPanel(PanelKind.PIP) },
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

        // v1.6.1: 黑场插入对话框。选项行点击即插入（默认 2s，选中即改为选中时长；
        // 拖动片段边缘可后续自由拉伸 0.1..60s）。
        if (showBlackGapDialog) {
            BlackGapDialog(
                hasSelection = state.selectedClipId != null,
                onDismiss = { showBlackGapDialog = false },
                onInsert = { before, durationUs ->
                    showBlackGapDialog = false
                    vm.addBlackGap(before, durationUs)
                },
            )
        }
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
                        // v1.2.10: play INTENT, not actual-isPlaying — the
                        // player's isPlaying blips false at every clip
                        // boundary (BUFFERING), which blinked the icon.
                        if (state.wantsPlay) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (state.wantsPlay) R.string.pause else R.string.play),
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
        // v1.5.5: PiP layers are splittable too (the razor cuts every layer
        // type under the playhead — see EditorViewModel.splitAtPlayhead).
        val canSplit = state.project.clips.isNotEmpty() || state.project.audios.isNotEmpty() ||
            state.project.pips.isNotEmpty()
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
    onLibrary: () -> Unit,
    onAi: () -> Unit,
    onBlackGap: () -> Unit,
    onSpeed: () -> Unit,
    onRotate: () -> Unit,
    onCrop: () -> Unit,
    onAdjust: () -> Unit,
    onTransition: () -> Unit,
    onMosaic: () -> Unit,
    onText: () -> Unit,
    onPip: () -> Unit,
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
    // right next to the 素材库 (they are the most-used actions and used to hide
    // off-screen in the scroll). Order: library, text, audio, then per-clip tools.
    // v1.6.15: 「添加素材」按钮已删（用户反馈与素材库重复）——素材库
    // 是唯一素材入口，位置不变继续排首位。
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
    ) {
        Spacer(Modifier.width(8.dp))
        // v1.6.13: 素材库 —— 导入即入库，点击素材上时间线；AI 同库选材。
        // v1.6.15: 升格为唯一素材入口（添加素材已删）。
        ToolbarButton(Icons.Filled.VideoLibrary, stringResource(R.string.action_library), enabled = true, active = active == PanelKind.LIBRARY, onClick = onLibrary)
        // v1.6.0: AI 剪辑放在最前面 — 对话即剪辑的入口。
        ToolbarButton(Icons.Filled.AutoAwesome, stringResource(R.string.action_ai), enabled = true, active = active == PanelKind.AI_CHAT, onClick = onAi)
        ToolbarButton(Icons.Filled.TextFields, stringResource(R.string.action_text), enabled = true, active = active == PanelKind.TEXT, onClick = onText)
        ToolbarButton(Icons.Filled.PictureInPictureAlt, stringResource(R.string.action_pip), enabled = true, active = active == PanelKind.PIP, onClick = onPip)
        ToolbarButton(Icons.Filled.MusicNote, stringResource(R.string.action_audio), enabled = true, active = active == PanelKind.AUDIO, onClick = onAudio)
        // v1.6.1: 黑场素材 —— 时间线级内容（同文字/音频一样不依赖选中），
        // 对话框里再选前/后/末尾。删除已改为收拢贴合，黑场成为显式停顿。
        ToolbarButton(Icons.Filled.Square, stringResource(R.string.action_black_gap), enabled = true, active = false, onClick = onBlackGap)
        ToolbarButton(Icons.Filled.Speed, stringResource(R.string.action_speed), enabled = hasClip, active = active == PanelKind.SPEED, onClick = onSpeed)
        ToolbarButton(Icons.Filled.RotateRight, stringResource(R.string.action_rotate), enabled = hasClip, active = active == PanelKind.ROTATE, onClick = onRotate)
        ToolbarButton(Icons.Filled.Crop, stringResource(R.string.action_crop), enabled = hasClip, active = active == PanelKind.CROP, onClick = onCrop)
        ToolbarButton(Icons.Filled.Tune, stringResource(R.string.action_adjust), enabled = hasClip, active = active == PanelKind.ADJUST, onClick = onAdjust)
        // v1.4.0: transitions are BACK. The redesign the v1.3.1 concealment
        // waited for arrived via the 转场开发技术参考 analysis: the preview
        // architecture the reference prescribes (dual player + view-layer
        // alpha blend + preroll warm-up + text baked into BOTH layers) is
        // exactly what the head engine already implements, and every defect
        // the reference documents (fade stutter, last-frame flash, text
        // fading with the clip) was fixed across v1.2.7-v1.2.10. The v1.4.0
        // multi-audio sync fix (PipSyncPolicy) hardens the same overlay
        // player family the head belongs to.
        ToolbarButton(Icons.Filled.CompareArrows, stringResource(R.string.action_transition), enabled = hasClip, active = active == PanelKind.TRANSITION, onClick = onTransition)
        ToolbarButton(Icons.Filled.BlurOn, stringResource(R.string.action_mosaic), enabled = hasClip, active = active == PanelKind.MOSAIC, onClick = onMosaic)
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
    PanelKind.TRANSITION, PanelKind.MOSAIC,
)

@Composable
private fun ToolPanelHost(
    vm: EditorViewModel,
    state: EditorUiState,
    panel: PanelKind,
    onImportLut: () -> Unit,
    onImportFont: () -> Unit,
    onAddMusic: () -> Unit,
    onImportMedia: () -> Unit,
    onImportPipMedia: () -> Unit,
    onRecord: () -> Unit,
    onReplace: () -> Unit,
    onNeedPermission: () -> Unit,
) {
    val titleRes = when (panel) {
        PanelKind.SPEED -> R.string.panel_speed_title
        PanelKind.ROTATE -> R.string.panel_rotate_title
        PanelKind.CROP -> R.string.panel_crop_title
        PanelKind.ADJUST -> R.string.panel_adjust_title
        PanelKind.TEXT -> R.string.panel_text_title
        PanelKind.PIP -> R.string.panel_pip_title
        PanelKind.AUDIO -> R.string.panel_audio_title
        PanelKind.INFO -> R.string.panel_info_title
        PanelKind.EXPORT -> R.string.panel_export_title
        PanelKind.GOTO -> R.string.panel_goto_title
        PanelKind.TRANSITION -> R.string.panel_transition_title
        PanelKind.MOSAIC -> R.string.panel_mosaic_title
        PanelKind.AI_CHAT -> R.string.panel_ai_title
        PanelKind.LIBRARY -> R.string.panel_library_title
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
        // v1.6.3: AI_CHAT no longer renders here — the conversation moved
        // ONTO the preview (AiChatOverlay) and the input row became a
        // wrap-content bottom bar in the timeline box. This host only serves
        // the regular tool panels now.
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
                    // AI_CHAT 不可达（已在上方独立分支处理），仅为枚举完备。
                    PanelKind.AI_CHAT -> Unit
                    PanelKind.SPEED -> SpeedSheet(vm, state)
                    PanelKind.ROTATE -> RotateSheet(vm, state)
                    PanelKind.CROP -> CropSheet(vm, state)
                    PanelKind.ADJUST -> AdjustSheet(vm, state, onImportLut = onImportLut)
                    PanelKind.TEXT -> TextSheet(vm, state, onImportFont = onImportFont)
                    PanelKind.PIP -> PipSheet(vm, state, onImportMedia = onImportPipMedia)
                    PanelKind.TRANSITION -> TransitionSheet(vm, state)
                    PanelKind.MOSAIC -> MosaicSheet(vm, state)
                    PanelKind.AUDIO -> AudioSheet(vm, state, onAddMusic = onAddMusic, onRecord = onRecord)
                    PanelKind.INFO -> InfoSheet(vm, state, onReplace = onReplace)
                    PanelKind.GOTO -> GotoSheet(vm, state)
                    PanelKind.LIBRARY -> LibrarySheet(vm, state, onImportMedia = onImportMedia)
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

// ===========================================================================
// v1.6.1 — 黑场插入对话框
//
// 删除自本版起收拢贴合（ripple），黑色停顿改为显式素材：对话框选时长
// （1/2/5/10/30s 预设，后续仍可拖边拉伸 0.1..60s）和插入位（选中素材的
// 前/后；未选中或空时间线则追加到末尾）。黑场是普通图片素材：可分割、
// 可加转场（叠化进黑场 = 专业淡入黑）、可删除。
// ===========================================================================

@Composable
private fun BlackGapDialog(
    hasSelection: Boolean,
    onDismiss: () -> Unit,
    onInsert: (before: Boolean?, durationUs: Long) -> Unit,
) {
    var durSec by remember { mutableStateOf((BLACK_DEFAULT_DURATION_US / 1_000_000L).toInt()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.black_gap_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.black_gap_duration),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 5, 10, 30).forEach { s ->
                        FilterChip(
                            selected = durSec == s,
                            onClick = { durSec = s },
                            label = { Text("${s}s") },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (hasSelection) {
                    BlackGapRow(stringResource(R.string.black_gap_before)) { onInsert(true, durSec * 1_000_000L) }
                    BlackGapRow(stringResource(R.string.black_gap_after)) { onInsert(false, durSec * 1_000_000L) }
                } else {
                    Text(
                        stringResource(R.string.black_gap_none_selected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                BlackGapRow(stringResource(R.string.black_gap_end)) { onInsert(null, durSec * 1_000_000L) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.black_gap_cancel)) }
        },
    )
}

@Composable
private fun BlackGapRow(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
