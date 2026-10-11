@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.RooMCutNext.ui.timeline

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.RooMCutNext.RooMCutNextApp
import com.RooMCutNext.R
import com.RooMCutNext.core.model.Clip
import com.RooMCutNext.core.model.MediaAsset
import com.RooMCutNext.core.model.Project
import com.RooMCutNext.core.model.TextOverlayItem
import com.RooMCutNext.core.ops.TimelineOps
import com.RooMCutNext.core.util.TimeCode
import com.RooMCutNext.ui.editor.EditorViewModel
import com.RooMCutNext.ui.theme.AccentColor
import com.RooMCutNext.ui.theme.ClipBgColor
import com.RooMCutNext.ui.theme.ClipBorderColor
import com.RooMCutNext.ui.theme.DangerColor
import com.RooMCutNext.ui.theme.RulerTextColor
import com.RooMCutNext.ui.theme.SurfaceVariantColor
import com.RooMCutNext.ui.theme.WarnColor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Layout constants + drag model
// ---------------------------------------------------------------------------

private const val RULER_HEIGHT_DP = 26f
private const val TRACK_HEIGHT_DP = 56f
private const val TEXT_TRACK_HEIGHT_DP = 24f
private const val TRACK_GAP_DP = 6f
private const val TRIM_HIT_PX = 26f
private const val BADGE_TEXT_DP = 8.5f

/** One clip's screen layout. */
private data class ClipLayout(
    val clip: Clip,
    val index: Int,
    val left: Float,
    val width: Float,
)

private data class TextLayout(
    val item: TextOverlayItem,
    val left: Float,
    val width: Float,
)

private class GeometryHolder {
    var viewportW: Float = 0f
    var viewportH: Float = 0f
    var pps: Float = 64f
    var scrollX: Float = 0f
    var rulerBottom: Float = 0f
    var trackTop: Float = 0f
    var trackBottom: Float = 0f
    var textTop: Float = 0f
    var textBottom: Float = 0f
    var clips: List<ClipLayout> = emptyList()
    var texts: List<TextLayout> = emptyList()
    var hasClips: Boolean = false

    fun timeToX(us: Long): Float = us / 1_000_000f * pps - scrollX
    fun xToTimeUs(x: Float): Long = ((x + scrollX) / pps * 1_000_000f).toLong().coerceAtLeast(0)
}

private sealed class DragMode {
    data object Scroll : DragMode()
    data object Playhead : DragMode()
    data class Trim(val clipId: String, val isLeft: Boolean) : DragMode()
    data class ClipMove(val clipId: String, val fromIndex: Int) : DragMode()
    data class TextMove(val textId: String) : DragMode()
    data class TextTrim(val textId: String, val isLeft: Boolean) : DragMode()
    data object Pinch : DragMode()
    data object TapGallery : DragMode()
    data object TapFiles : DragMode()
}

// ---------------------------------------------------------------------------
// Main composable
// ---------------------------------------------------------------------------

@Composable
fun TimelineView(
    vm: EditorViewModel,
    onAddMedia: () -> Unit,
    onImportFromFileManager: () -> Unit,
) {
    val density = LocalDensity.current
    val viewConfig = LocalViewConfiguration.current
    val dp = density.density

    val snapshot by vm.timelineFlow.collectAsState()
    val playhead by vm.playheadFlow.collectAsState()
    val pps by vm.zoomFlow.collectAsState()
    val isPlayingNow = collectIsPlaying(vm.state)

    val emptyHint = stringResource(R.string.timeline_empty_hint)
    val addLabel = stringResource(R.string.timeline_add_media)
    val filesLabel = stringResource(R.string.timeline_import_files)

    val scrollXState = remember { mutableFloatStateOf(0f) }
    val dragState = remember { mutableStateOf<DragMode?>(null) }
    val geom = remember { GeometryHolder() }
    val thumbRevision = remember { mutableIntStateOf(0) }
    val fingerX = remember { mutableFloatStateOf(Float.NaN) }

    val currentOnAddMedia by rememberUpdatedState(onAddMedia)
    val currentOnFiles by rememberUpdatedState(onImportFromFileManager)
    val currentVm by rememberUpdatedState(vm)

    val textPaint = remember {
        Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.rgb(0x77, 0x82, 0x8E)
            textSize = 9f * dp
        }
    }
    val badgePaint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = BADGE_TEXT_DP * dp
        }
    }

    fun maxScroll(): Float {
        val contentW = (snapshot?.project?.totalDurationUs ?: 0L) / 1_000_000f * pps
        return max(0f, contentW - geom.viewportW + 40f * dp)
    }

    fun clampedScroll(): Float = scrollXState.floatValue.coerceIn(0f, maxScroll())

    fun xToTimeUs(x: Float): Long = ((x + clampedScroll()) / pps * 1_000_000f).toLong().coerceAtLeast(0)

    // ---------------- hit testing ----------------
    fun hitTest(x: Float, y: Float): DragMode {
        val g = geom
        if (!g.hasClips) {
            val cx = g.viewportW / 2f
            val cy = g.viewportH / 2f
            val galleryRect = Rect(cx - 70f * dp, cy - 74f * dp, cx + 70f * dp, cy + 26f * dp)
            if (galleryRect.contains(Offset(x, y))) return DragMode.TapGallery
            val filesRect = Rect(cx - 120f * dp, cy + 44f * dp, cx + 120f * dp, cy + 84f * dp)
            if (filesRect.contains(Offset(x, y))) return DragMode.TapFiles
            return DragMode.TapGallery
        }
        if (y <= g.rulerBottom) return DragMode.Playhead
        if (y in g.trackTop..g.trackBottom) {
            val selected = snapshot?.selectedClipId
            if (selected != null) {
                val sel = g.clips.firstOrNull { it.clip.id == selected }
                if (sel != null && sel.width > 30f * dp) {
                    if (abs(x - sel.left) <= TRIM_HIT_PX) return DragMode.Trim(selected, true)
                    if (abs(x - (sel.left + sel.width)) <= TRIM_HIT_PX) return DragMode.Trim(selected, false)
                }
            }
            val hit = g.clips.firstOrNull { x >= it.left && x <= it.left + it.width }
            if (hit != null) return DragMode.ClipMove(hit.clip.id, hit.index)
            return DragMode.Scroll
        }
        if (y in g.textTop..g.textBottom) {
            val selectedText = snapshot?.selectedTextId
            if (selectedText != null) {
                val sel = g.texts.firstOrNull { it.item.id == selectedText }
                if (sel != null && sel.width > 24f * dp) {
                    if (abs(x - sel.left) <= TRIM_HIT_PX) return DragMode.TextTrim(selectedText, true)
                    if (abs(x - (sel.left + sel.width)) <= TRIM_HIT_PX) return DragMode.TextTrim(selectedText, false)
                }
            }
            val hit = g.texts.firstOrNull { x >= it.left && x <= it.left + it.width }
            if (hit != null) return DragMode.TextMove(hit.item.id)
            return DragMode.Scroll
        }
        return DragMode.Scroll
    }

    // ---------------- gesture handlers ----------------
    fun onDragStart(mode: DragMode, x: Float) {
        val v = currentVm
        when (mode) {
            is DragMode.Playhead -> v.beginScrub()
            is DragMode.Trim -> {
                v.selectClip(mode.clipId)
                v.beginScrub()
            }
            is DragMode.ClipMove -> RooMCutNextApp.vibrate(v.getApplication(), 14)
            is DragMode.TextTrim -> v.selectText(mode.textId)
            else -> Unit
        }
        if (mode is DragMode.ClipMove) fingerX.floatValue = x
    }

    fun onDragMove(mode: DragMode, dx: Float, x: Float) {
        val v = currentVm
        when (mode) {
            is DragMode.Scroll -> {
                scrollXState.floatValue = (scrollXState.floatValue - dx).coerceIn(0f, maxScroll())
            }
            is DragMode.Playhead -> v.seekDuringDrag(xToTimeUs(x))
            is DragMode.Trim -> {
                val snap = snapshot ?: return
                val idx = snap.project.clips.indexOfFirst { it.id == mode.clipId }
                if (idx < 0) return
                val clip = snap.project.clips[idx]
                val clipStart = TimelineOps.starts(snap.project.clips)[idx]
                val timeUs = xToTimeUs(x)
                if (mode.isLeft) {
                    val localF = (timeUs - clipStart).toFloat()
                    val lowerBound = -clip.sourceInUs.toFloat() / clip.speed
                    val local = localF.coerceIn(lowerBound, 60_000_000f)
                    v.previewTrim(clip.id, clip.sourceInUs + (local * clip.speed).toLong(), clip.sourceOutUs)
                    v.seekDuringDrag(clipStart + local.toLong().coerceAtLeast(0L))
                } else {
                    val local = (timeUs - clipStart).coerceAtLeast(0L)
                    v.previewTrim(clip.id, clip.sourceInUs, clip.sourceInUs + (local.toFloat() * clip.speed).toLong())
                    v.seekDuringDrag(clipStart + local)
                }
            }
            is DragMode.ClipMove -> fingerX.floatValue = x
            is DragMode.TextMove -> {
                val item = snapshot?.project?.texts?.firstOrNull { it.id == mode.textId } ?: return
                v.moveText(mode.textId, xToTimeUs(x - 20f * dp), commit = false)
            }
            is DragMode.TextTrim -> {
                val item = snapshot?.project?.texts?.firstOrNull { it.id == mode.textId } ?: return
                val t = xToTimeUs(x)
                if (mode.isLeft) v.trimText(mode.textId, t, item.endUs, commit = false)
                else v.trimText(mode.textId, item.startUs, t, commit = false)
            }
            else -> Unit
        }
    }

    fun onDragEnd(mode: DragMode, x: Float) {
        val v = currentVm
        when (mode) {
            is DragMode.Playhead -> {
                v.seekTo(xToTimeUs(x))
                v.endScrub()
            }
            is DragMode.Trim -> {
                v.commitTrim()
                v.endScrub()
            }
            is DragMode.ClipMove -> {
                val snap = snapshot ?: return
                val others = snap.project.clips.filter { it.id != mode.clipId }
                val fingerTimeUs = xToTimeUs(x)
                val startsWithout = TimelineOps.starts(others)
                var insertAt = others.size
                for (i in others.indices) {
                    val midTimeUs = startsWithout[i] + others[i].durationUs / 2
                    if (fingerTimeUs < midTimeUs) {
                        insertAt = i
                        break
                    }
                }
                // The insertion index among the OTHER clips maps 1:1 to the
                // remove-then-insert index expected by TimelineOps.move.
                if (insertAt != mode.fromIndex) {
                    v.moveClip(mode.fromIndex, insertAt)
                }
                fingerX.floatValue = Float.NaN
            }
            is DragMode.TextMove -> {
                val item = snapshot?.project?.texts?.firstOrNull { it.id == mode.textId } ?: return
                v.moveText(mode.textId, item.startUs, commit = true)
            }
            is DragMode.TextTrim -> {
                val item = snapshot?.project?.texts?.firstOrNull { it.id == mode.textId } ?: return
                v.trimText(mode.textId, item.startUs, item.endUs, commit = true)
            }
            else -> Unit
        }
    }

    fun onTap(mode: DragMode, x: Float, y: Float) {
        val v = currentVm
        when (mode) {
            is DragMode.TapGallery -> currentOnAddMedia()
            is DragMode.TapFiles -> currentOnFiles()
            is DragMode.Playhead -> v.seekTo(xToTimeUs(x))
            is DragMode.Trim -> v.selectClip(mode.clipId)
            is DragMode.ClipMove -> v.selectClip(mode.clipId)
            is DragMode.TextMove -> v.selectText(mode.textId)
            is DragMode.TextTrim -> v.selectText(mode.textId)
            is DragMode.Scroll -> v.selectClip(null)
            else -> Unit
        }
    }

    // ---------------- layout computation ----------------
    fun displayClips(snap: EditorViewModel.TimelineSnapshot): List<ClipLayout> {
        val preview = snap.trimPreview
        var acc = 0f
        val out = mutableListOf<ClipLayout>()
        snap.project.clips.forEachIndexed { index, clip ->
            val inUs = preview?.takeIf { it.clipId == clip.id }?.sourceInUs ?: clip.sourceInUs
            val outUs = preview?.takeIf { it.clipId == clip.id }?.sourceOutUs ?: clip.sourceOutUs
            val durUs = ((outUs - inUs) / clip.speed).toLong()
            val width = durUs / 1_000_000f * pps
            out += ClipLayout(clip.copy(sourceInUs = inUs, sourceOutUs = outUs), index, acc, width)
            acc += width
        }
        val scroll = clampedScroll()
        return out.map { it.copy(left = it.left - scroll) }
    }

    // ------------------------------------------------------------------
    // Composable tree
    // ------------------------------------------------------------------

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                geom.viewportW = size.width.toFloat()
                geom.viewportH = size.height.toFloat()
                currentVm.viewportWidthPx = size.width
            }
            .pointerInput(Unit) {
                val touchSlop = viewConfig.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var mode = hitTest(down.position.x, down.position.y)
                    var dragStarted = false
                    var lastX = down.position.x
                    var accumDx = 0f
                    var pinchSpan = 0f

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2) {
                            if (mode != DragMode.Pinch) {
                                if (dragStarted && mode is DragMode.Trim) currentVm.cancelTrim()
                                dragState.value = null
                                fingerX.floatValue = Float.NaN
                                mode = DragMode.Pinch
                                pinchSpan = spanOf(pressed)
                            }
                            val newSpan = spanOf(pressed)
                            if (pinchSpan > 10f) {
                                val factor = newSpan / pinchSpan
                                val oldPps = pps
                                val newPps = (oldPps * factor).coerceIn(
                                    EditorViewModel.MIN_PPS, EditorViewModel.MAX_PPS,
                                )
                                if (newPps != oldPps) currentVm.setZoom(newPps)
                                pinchSpan = newSpan
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        if (mode == DragMode.Pinch && pressed.size == 1) {
                            mode = DragMode.Scroll
                            dragStarted = false
                            lastX = pressed[0].position.x
                            accumDx = 0f
                        }

                        val change = pressed[0]
                        val x = change.position.x
                        val dx = x - lastX
                        lastX = x
                        accumDx += dx

                        if (!dragStarted && abs(accumDx) > touchSlop) {
                            dragStarted = true
                            dragState.value = mode
                            onDragStart(mode, x)
                        }
                        if (dragStarted) {
                            onDragMove(mode, dx, x)
                        } else if (mode is DragMode.Scroll) {
                            scrollXState.floatValue = (scrollXState.floatValue - dx).coerceIn(0f, maxScroll())
                        }
                        change.consume()
                    }

                    if (dragStarted) {
                        onDragEnd(mode, lastX)
                    } else {
                        onTap(mode, lastX, down.position.y)
                    }
                    dragState.value = null
                }
            }
    ) {
        // ---------------- content canvas ----------------
        Canvas(Modifier.fillMaxSize()) {
            val snap = snapshot ?: return@Canvas
            val g = geom
            g.pps = pps
            g.scrollX = clampedScroll()
            g.rulerBottom = RULER_HEIGHT_DP * dp
            g.trackTop = g.rulerBottom + TRACK_GAP_DP * dp
            g.trackBottom = g.trackTop + TRACK_HEIGHT_DP * dp
            g.textTop = g.trackBottom + TRACK_GAP_DP * dp
            g.textBottom = g.textTop + TEXT_TRACK_HEIGHT_DP * dp
            g.hasClips = snap.project.clips.isNotEmpty()

            thumbRevision.intValue // invalidate when thumbnails finish loading

            if (!g.hasClips) {
                drawEmptyState(g, emptyHint, addLabel, filesLabel, dp)
                return@Canvas
            }

            val clips = displayClips(snap)
            g.clips = clips

            drawRuler(snap.project, g, textPaint, dp)
            drawTrackBackground(g, snap.project.texts.isNotEmpty())

            val moved = dragState.value as? DragMode.ClipMove
            for (layout in clips) {
                if (moved != null && layout.clip.id == moved.clipId) continue
                drawClip(
                    layout = layout,
                    asset = snap.project.assetById(layout.clip.assetId),
                    selected = snap.selectedClipId == layout.clip.id,
                    missing = layout.clip.assetId in snap.missingAssetIds,
                    thumbs = currentVm.thumbnails.peek(layout.clip.assetId),
                    badgePaint = badgePaint,
                    trackTop = g.trackTop,
                    ppsValue = pps,
                    dp = dp,
                )
            }

            drawTextTrack(snap, g, badgePaint, dp)

            if (moved != null && !fingerX.floatValue.isNaN()) {
                // Insertion indicator among the remaining clips.
                val others = clips.filter { it.clip.id != moved.clipId }
                var insertBoundary = fingerX.floatValue
                val fx = fingerX.floatValue
                for (i in others.indices) {
                    val boundary = others[i].left + others[i].width
                    if (fx < others[i].left + others[i].width / 2f) {
                        insertBoundary = others[i].left
                        break
                    } else if (i == others.size - 1) {
                        insertBoundary = boundary
                    }
                }
                drawLine(
                    color = AccentColor,
                    start = Offset(insertBoundary, g.trackTop),
                    end = Offset(insertBoundary, g.trackBottom),
                    strokeWidth = 3.dp.toPx(),
                )
                // The dragged clip follows the finger, slightly raised.
                val dragged = clips.firstOrNull { it.clip.id == moved.clipId }
                if (dragged != null) {
                    drawClip(
                        layout = dragged.copy(left = fx - dragged.width / 2f),
                        asset = snap.project.assetById(dragged.clip.assetId),
                        selected = true,
                        missing = dragged.clip.assetId in snap.missingAssetIds,
                        thumbs = currentVm.thumbnails.peek(dragged.clip.assetId),
                        badgePaint = badgePaint,
                        trackTop = g.trackTop - 6f * dp,
                        ppsValue = pps,
                        dp = dp,
                        elevated = true,
                    )
                }
            }
        }

        // ---------------- playhead overlay ----------------
        Canvas(Modifier.fillMaxSize()) {
            val snap = snapshot ?: return@Canvas
            if (snap.project.clips.isEmpty()) return@Canvas
            val g = geom
            val x = playhead / 1_000_000f * pps - clampedScroll()
            val bottom = if (snap.project.texts.isNotEmpty()) g.textBottom else g.trackBottom
            drawLine(
                color = Color(0xFFFF5C6E),
                start = Offset(x, 0f),
                end = Offset(x, bottom),
                strokeWidth = 2.dp.toPx(),
            )
            val w = 10.dp.toPx()
            val path = Path().apply {
                moveTo(x - w, 0f)
                lineTo(x + w, 0f)
                lineTo(x + w, w * 0.7f)
                lineTo(x, w * 1.3f)
                lineTo(x - w, w * 0.7f)
                close()
            }
            drawPath(path, Color(0xFFFF5C6E))
        }
    }

    // ---------------- auto-follow playhead while playing ----------------
    LaunchedEffect(playhead, isPlayingNow) {
        if (isPlayingNow) {
            val g = geom
            val x = playhead / 1_000_000f * pps - clampedScroll()
            if (x > g.viewportW * 0.8f || x < 0f) {
                scrollXState.floatValue = (playhead / 1_000_000f * pps - g.viewportW * 0.2f)
                    .coerceIn(0f, maxScroll())
            }
        }
    }

    // ---------------- clamp scroll on zoom/duration change ----------------
    LaunchedEffect(pps, snapshot?.project?.totalDurationUs) {
        scrollXState.floatValue = scrollXState.floatValue.coerceIn(0f, maxScroll())
    }

    // ---------------- ensure thumbnails are loaded ----------------
    LaunchedEffect(snapshot?.project?.assets?.size, snapshot?.project?.id) {
        val snap = snapshot ?: return@LaunchedEffect
        val app = currentVm.getApplication<android.app.Application>()
        val dir = java.io.File(app.filesDir, "projects/${snap.project.id}/media")
        var loadedAny = false
        snap.project.assets.forEach { asset ->
            if (currentVm.thumbnails.peek(asset.id) == null) {
                val loaded = currentVm.thumbnails.load(dir, asset)
                if (loaded.isNotEmpty()) loadedAny = true
            }
        }
        if (loadedAny) thumbRevision.intValue++
    }
}

// Small helper: collect isPlaying without pulling the whole state at 30Hz.
@Composable
private fun collectIsPlaying(flow: kotlinx.coroutines.flow.StateFlow<com.RooMCutNext.ui.editor.EditorUiState>): Boolean {
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(flow) {
        flow.map { it.isPlaying }.distinctUntilChanged().collect { playing = it }
    }
    return playing
}

private fun spanOf(pressed: List<PointerInputChange>): Float {
    if (pressed.size < 2) return 0f
    val dx = pressed[0].position.x - pressed[1].position.x
    val dy = pressed[0].position.y - pressed[1].position.y
    return sqrt(dx * dx + dy * dy)
}

// ---------------------------------------------------------------------------
// Drawing helpers
// ---------------------------------------------------------------------------

private fun DrawScope.drawEmptyState(
    g: GeometryHolder,
    emptyHint: String,
    addLabel: String,
    filesLabel: String,
    dp: Float,
) {
    val cx = g.viewportW / 2f
    val cy = g.viewportH / 2f - 24f * dp
    // big round + button
    val r = 46f * dp
    drawCircle(AccentColor.copy(alpha = 0.16f), radius = r, center = Offset(cx, cy))
    drawCircle(AccentColor, radius = r, center = Offset(cx, cy), style = Stroke(2.dp.toPx()))
    val plus = 34f * dp
    drawLine(AccentColor, Offset(cx - plus / 2, cy), Offset(cx + plus / 2, cy), 5.dp.toPx())
    drawLine(AccentColor, Offset(cx, cy - plus / 2), Offset(cx, cy + plus / 2), 5.dp.toPx())
    // labels
    val canvas = drawContext.canvas.nativeCanvas
    val paint = Paint().apply {
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = 11f * dp
        color = android.graphics.Color.argb(0xE0, 0x97, 0xA3, 0xB0)
    }
    canvas.drawText(addLabel, cx, cy + r + 24f * dp, paint)
    paint.textSize = 10f * dp
    canvas.drawText(emptyHint, cx, cy + r + 44f * dp, paint)
    // file manager option pill
    val pillW = 240f * dp
    val pillH = 40f * dp
    val pillTop = cy + r + 58f * dp
    drawRoundRect(
        color = SurfaceVariantColor,
        topLeft = Offset(cx - pillW / 2, pillTop),
        size = Size(pillW, pillH),
        cornerRadius = CornerRadius(pillH / 2),
    )
    drawRoundRect(
        color = ClipBorderColor,
        topLeft = Offset(cx - pillW / 2, pillTop),
        size = Size(pillW, pillH),
        cornerRadius = CornerRadius(pillH / 2),
        style = Stroke(1.dp.toPx()),
    )
    paint.color = android.graphics.Color.argb(0xF0, 0xEA, 0xF0, 0xF4)
    canvas.drawText(filesLabel, cx, pillTop + pillH / 2 + 3.5f * dp, paint)
}

private fun DrawScope.drawRuler(project: Project, g: GeometryHolder, paint: Paint, dp: Float) {
    val scroll = g.scrollX
    val pps = g.pps
    val h = g.rulerBottom
    drawRect(SurfaceVariantColor, topLeft = Offset(0f, 0f), size = Size(g.viewportW, h))
    drawRect(Color(0xFF10141A), topLeft = Offset(0f, h - 1f), size = Size(g.viewportW, 1f))

    val stepSec = rulerStepSec(pps)
    val stepPx = stepSec * pps
    val firstTick = floor(scroll / stepPx) * stepPx
    var tick = firstTick
    val contentEndPx = project.totalDurationUs / 1_000_000f * pps
    val canvas = drawContext.canvas.nativeCanvas
    while (tick <= scroll + g.viewportW + stepPx) {
        val x = tick - scroll
        if (tick >= 0f && x >= 0 && x <= g.viewportW) {
            drawLine(
                color = RulerTextColor.copy(alpha = 0.65f),
                start = Offset(x, h - 7.dp.toPx()),
                end = Offset(x, h),
                strokeWidth = 1.dp.toPx(),
            )
            val label = TimeCode.formatRulerLabel((tick / pps * 1_000_000f).toLong())
            paint.textSize = 8.5f * dp
            canvas.drawText(label, x + 3f * dp, h - 10.dp.toPx() + 3f * dp, paint)
        }
        val minor = stepPx / 5f
        var m = tick + minor
        while (m < tick + stepPx) {
            val mx = m - scroll
            if (mx in 0f..g.viewportW) {
                drawLine(
                    color = RulerTextColor.copy(alpha = 0.3f),
                    start = Offset(mx, h - 4.dp.toPx()),
                    end = Offset(mx, h),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            m += minor
        }
        tick += stepPx
    }
    if (contentEndPx - scroll in 0f..g.viewportW && project.clips.isNotEmpty()) {
        drawLine(
            color = DangerColor.copy(alpha = 0.5f),
            start = Offset(contentEndPx - scroll, 0f),
            end = Offset(contentEndPx - scroll, h),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

private fun rulerStepSec(pps: Float): Float {
    val candidates = floatArrayOf(0.1f, 0.2f, 0.5f, 1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f, 300f, 600f, 1800f)
    for (c in candidates) {
        if (c * pps >= 64f) return c
    }
    return candidates.last()
}

private fun DrawScope.drawTrackBackground(g: GeometryHolder, hasTexts: Boolean) {
    drawRect(
        Color(0xFF0E1218),
        topLeft = Offset(0f, g.trackTop),
        size = Size(g.viewportW, g.trackBottom - g.trackTop),
    )
    if (hasTexts) {
        drawRect(
            Color(0x14FFFFFF),
            topLeft = Offset(0f, g.textTop),
            size = Size(g.viewportW, g.textBottom - g.textTop),
        )
    }
}

private fun DrawScope.drawClip(
    layout: ClipLayout,
    asset: MediaAsset?,
    selected: Boolean,
    missing: Boolean,
    thumbs: List<ImageBitmap>?,
    badgePaint: Paint,
    trackTop: Float,
    ppsValue: Float,
    dp: Float,
    elevated: Boolean = false,
) {
    val clip = layout.clip
    if (layout.width <= 0.5f) return
    val top = trackTop
    val height = TRACK_HEIGHT_DP * dp - 4f * dp
    val corner = CornerRadius(6f * dp)

    if (elevated) {
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.5f),
            topLeft = Offset(layout.left + 2f * dp, top + 3f * dp),
            size = Size(layout.width, height),
            cornerRadius = corner,
        )
    }

    val borderColor = when {
        missing -> DangerColor
        selected -> AccentColor
        else -> ClipBorderColor
    }
    val strokeWidth = if (selected || missing) 2.dp.toPx() else 1.dp.toPx()

    drawRoundRect(
        color = ClipBgColor,
        topLeft = Offset(layout.left, top),
        size = Size(layout.width, height),
        cornerRadius = corner,
    )

    // ---- filmstrip thumbnails ----
    if (thumbs != null && thumbs.isNotEmpty() && asset != null && !missing) {
        val pad = 2.5f * dp
        val innerTop = top + pad
        val innerHeight = height - pad * 2 - 7f * dp
        if (innerHeight > 4f * dp && layout.width > 8f * dp) {
            if (asset.isImage) {
                val bmp = thumbs[0]
                val scale = max(layout.width / bmp.width, innerHeight / bmp.height)
                val w = bmp.width * scale
                val h = bmp.height * scale
                withClippedRoundRect(layout.left, innerTop, layout.width, innerHeight, corner) {
                    drawImage(
                        image = bmp,
                        dstOffset = IntOffset(
                            (layout.left + (layout.width - w) / 2f).toInt(),
                            (innerTop + (innerHeight - h) / 2f).toInt(),
                        ),
                        dstSize = IntSize(w.toInt(), h.toInt()),
                    )
                }
            } else {
                val n = thumbs.size
                val assetDur = if (asset.durationUs > 0) asset.durationUs else 1L
                val thumbH = innerHeight
                for (j in 0 until n) {
                    val bmp = thumbs[j]
                    val srcCenterUs = assetDur * (j + 1) / (n + 1)
                    val rel = (srcCenterUs - clip.sourceInUs) / clip.speed / 1_000_000f
                    val xCenter = (layout.left + rel * ppsValue).coerceIn(layout.left, layout.left + layout.width)
                    val thumbW = thumbH * bmp.width / bmp.height
                    withClippedRoundRect(layout.left, innerTop, layout.width, innerHeight, corner) {
                        drawImage(
                            image = bmp,
                            dstOffset = IntOffset(
                                (xCenter - thumbW / 2f).toInt(),
                                innerTop.toInt(),
                            ),
                            dstSize = IntSize(thumbW.toInt(), thumbH.toInt()),
                        )
                    }
                }
            }
        }
    } else if (missing) {
        val canvas = drawContext.canvas.nativeCanvas
        badgePaint.color = android.graphics.Color.rgb(0xFF, 0x5C, 0x5C)
        badgePaint.textAlign = android.graphics.Paint.Align.CENTER
        badgePaint.textSize = 9f * dp
        canvas.drawText("!", layout.left + layout.width / 2f, top + height / 2f + 3f * dp, badgePaint)
    }

    // ---- audio strip ----
    val stripTop = top + height - 6f * dp
    val stripHeight = 5f * dp
    val muted = clip.muted || (asset != null && !asset.hasAudio && !asset.isImage)
    drawRoundRect(
        color = Color(0x33000000),
        topLeft = Offset(layout.left + 3f * dp, stripTop),
        size = Size(max(0f, layout.width - 6f * dp), stripHeight),
        cornerRadius = CornerRadius(2.5f * dp),
    )
    if (!muted && asset != null) {
        val fill = (layout.width - 6f * dp) * clip.volume.coerceIn(0f, 1f)
        drawRoundRect(
            color = AccentColor.copy(alpha = 0.55f),
            topLeft = Offset(layout.left + 3f * dp, stripTop),
            size = Size(fill, stripHeight),
            cornerRadius = CornerRadius(2.5f * dp),
        )
        if (clip.fadeInMs > 0) {
            val w = (clip.fadeInMs * 1000f / clip.speed / 1_000_000f * ppsValue).coerceAtMost(layout.width)
            val path = Path().apply {
                moveTo(layout.left + 3f * dp, stripTop)
                lineTo(layout.left + 3f * dp + w, stripTop + stripHeight)
                lineTo(layout.left + 3f * dp, stripTop + stripHeight)
                close()
            }
            drawPath(path, Color.White.copy(alpha = 0.45f))
        }
        if (clip.fadeOutMs > 0) {
            val w = (clip.fadeOutMs * 1000f / clip.speed / 1_000_000f * ppsValue).coerceAtMost(layout.width)
            val x1 = layout.left + layout.width - 3f * dp
            val path = Path().apply {
                moveTo(x1, stripTop)
                lineTo(x1 - w, stripTop + stripHeight)
                lineTo(x1, stripTop + stripHeight)
                close()
            }
            drawPath(path, Color.White.copy(alpha = 0.45f))
        }
    }

    // ---- border ----
    drawRoundRect(
        color = borderColor,
        topLeft = Offset(layout.left, top),
        size = Size(layout.width, height),
        cornerRadius = corner,
        style = Stroke(width = strokeWidth),
    )

    // ---- badges ----
    val canvas = drawContext.canvas.nativeCanvas
    badgePaint.textAlign = android.graphics.Paint.Align.CENTER
    badgePaint.textSize = BADGE_TEXT_DP * dp
    var badgeX = layout.left + 4f * dp
    if (clip.speed != 1f) {
        badgeX = drawBadge(canvas, badgePaint, badgeX, top + 3f * dp, clip.speedLabel(), AccentColor, dp)
    }
    if (clip.muted) {
        badgeX = drawBadge(canvas, badgePaint, badgeX, top + 3f * dp, "MUTE", DangerColor, dp)
    }
    if (clip.rotationDeg != 0 || clip.flipH || clip.flipV) {
        badgeX = drawBadge(canvas, badgePaint, badgeX, top + 3f * dp, "${clip.rotationDeg}°", WarnColor, dp)
    }
    if (clip.crop != null) {
        badgeX = drawBadge(canvas, badgePaint, badgeX, top + 3f * dp, "CROP", WarnColor, dp)
    }

    // ---- trim handles ----
    if (selected && !elevated && layout.width > 30f * dp) {
        val handleW = 12f * dp
        val handleH = height - 10f * dp
        val handleTop = top + 5f * dp
        drawRoundRect(
            color = AccentColor,
            topLeft = Offset(layout.left + 1f * dp, handleTop),
            size = Size(handleW, handleH),
            cornerRadius = CornerRadius(3f * dp),
        )
        drawRoundRect(
            color = AccentColor,
            topLeft = Offset(layout.left + layout.width - handleW - 1f * dp, handleTop),
            size = Size(handleW, handleH),
            cornerRadius = CornerRadius(3f * dp),
        )
        val gripColor = Color(0xFF06231E)
        listOf(5.5f, 8.5f).forEach { off ->
            drawLine(
                gripColor,
                Offset(layout.left + off * dp, handleTop + 5f * dp),
                Offset(layout.left + off * dp, handleTop + handleH - 5f * dp),
                1.5f.dp.toPx(),
            )
            drawLine(
                gripColor,
                Offset(layout.left + layout.width - off * dp, handleTop + 5f * dp),
                Offset(layout.left + layout.width - off * dp, handleTop + handleH - 5f * dp),
                1.5f.dp.toPx(),
            )
        }
    }

    // ---- duration label ----
    if (layout.width > 70f * dp) {
        badgePaint.color = android.graphics.Color.argb(0xB0, 0xFF, 0xFF, 0xFF)
        badgePaint.textAlign = android.graphics.Paint.Align.RIGHT
        canvas.drawText(TimeCode.formatShort(clip.durationUs), layout.left + layout.width - 6f * dp, top + height - 9f * dp, badgePaint)
    }
}

private fun drawBadge(
    canvas: android.graphics.Canvas,
    paint: Paint,
    x: Float,
    y: Float,
    text: String,
    color: Color,
    dp: Float,
): Float {
    val padH = 4f * dp
    val textW = paint.measureText(text)
    val w = textW + padH * 2
    val h = 12f * dp
    val r = 4f * dp
    val bg = android.graphics.Color.argb(
        (color.alpha * 255).toInt(),
        (color.red * 255).toInt(),
        (color.green * 255).toInt(),
        (color.blue * 255).toInt(),
    )
    val rect = android.graphics.RectF(x, y, x + w, y + h)
    val rp = Paint().apply { isAntiAlias = true; this.color = bg }
    canvas.drawRoundRect(rect, r, r, rp)
    paint.color = android.graphics.Color.argb(0xF0, 0x06, 0x23, 0x1E)
    canvas.drawText(text, x + w / 2f, y + h - 3.5f * dp, paint)
    return x + w + 3f * dp
}

private fun DrawScope.drawTextTrack(
    snap: EditorViewModel.TimelineSnapshot,
    g: GeometryHolder,
    paint: Paint,
    dp: Float,
) {
    if (snap.project.texts.isEmpty()) return
    val top = g.textTop + 2f * dp
    val height = TEXT_TRACK_HEIGHT_DP * dp - 4f * dp
    val scroll = g.scrollX
    val pps = g.pps
    val canvas = drawContext.canvas.nativeCanvas
    snap.project.texts.forEach { item ->
        val left = item.startUs / 1_000_000f * pps - scroll
        val width = (item.durationUs / 1_000_000f * pps).coerceAtLeast(6f * dp)
        if (left + width < -20f || left > g.viewportW + 20f) return@forEach
        val selected = snap.selectedTextId == item.id
        drawRoundRect(
            color = WarnColor.copy(alpha = 0.28f),
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = CornerRadius(4f * dp),
        )
        drawRoundRect(
            color = if (selected) AccentColor else WarnColor.copy(alpha = 0.7f),
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = CornerRadius(4f * dp),
            style = Stroke(width = if (selected) 2.dp.toPx() else 1.dp.toPx()),
        )
        paint.textAlign = android.graphics.Paint.Align.LEFT
        paint.textSize = 9f * dp
        paint.color = android.graphics.Color.argb(0xE0, 0xFF, 0xC2, 0x4B)
        val label = if (item.text.isBlank()) "T" else "T ${item.text}"
        val charW = max(1f, paint.measureText("T ") * 0.55f)
        val maxChars = max(1, ((width - 8f * dp) / charW).toInt())
        canvas.drawText(label.take(maxChars), left + 4f * dp, top + height - 4.5f * dp, paint)
        if (selected) {
            val handleH = height - 6f * dp
            drawRoundRect(
                color = AccentColor,
                topLeft = Offset(left + 1f * dp, top + 3f * dp),
                size = Size(4f * dp, handleH),
                cornerRadius = CornerRadius(2f * dp),
            )
            drawRoundRect(
                color = AccentColor,
                topLeft = Offset(left + width - 5f * dp, top + 3f * dp),
                size = Size(4f * dp, handleH),
                cornerRadius = CornerRadius(2f * dp),
            )
        }
    }
}

private inline fun DrawScope.withClippedRoundRect(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    corner: CornerRadius,
    block: DrawScope.() -> Unit,
) {
    val path = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                rect = Rect(left, top, left + width, top + height),
                cornerRadius = corner,
            )
        )
    }
    clipPath(path) { block() }
}
