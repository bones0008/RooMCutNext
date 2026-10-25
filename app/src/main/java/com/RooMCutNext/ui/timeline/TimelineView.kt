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
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
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
import com.RooMCutNext.core.model.AudioClip
import com.RooMCutNext.core.model.Clip
import com.RooMCutNext.core.model.MediaAsset
import com.RooMCutNext.core.model.Project
import com.RooMCutNext.core.model.TextOverlayItem
import com.RooMCutNext.core.model.TransitionStyle
import com.RooMCutNext.core.ops.TimelineOps
import com.RooMCutNext.core.util.TimeCode
import com.RooMCutNext.ui.editor.EditorViewModel
import com.RooMCutNext.ui.editor.PanelKind
import com.RooMCutNext.ui.editor.TransitionUi
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

// v1.5.4: lane/track/ruler dimensions moved to TimelineVerticalMath — the
// single source of truth shared with the vertical-layout unit tests. They
// are referenced through the object below; values are UNCHANGED from
// v1.5.3 so existing projects render with identical lane sizes.
private const val TRIM_HIT_PX = 26f
private const val BADGE_TEXT_DP = 8.5f
/** Window in which two taps on the same text clip count as a double tap. */
private const val TEXT_DOUBLE_TAP_MS = 400L

/** v1.5.4: persists the vertical scroll across tool-panel swaps (the
 *  TimelineView leaves composition while a panel owns its area) and across
 *  process recreation. The draw pass re-clamps it against the current
 *  content height, so a restored value is always legal. */
private val ScrollYSaver = Saver<MutableFloatState, Float>(
    save = { it.floatValue },
    restore = { mutableFloatStateOf(it) },
)

/** One clip's screen layout. [gapLeft]/[gapWidth] describe the black hole in
 *  front of the clip (v1.0.8 gaps), zero-width when the clip is tight. */
private data class ClipLayout(
    val clip: Clip,
    val index: Int,
    val left: Float,
    val width: Float,
    val gapLeft: Float = 0f,
    val gapWidth: Float = 0f,
    /** Effective crossfade-in of this clip (v1.2.1); 0 = hard cut. */
    val transitionUs: Long = 0L,
    /** v1.5.0: which style renders over the overlap (a TransitionStyle id;
     *  the marker pill shows its short label). */
    val transitionType: String = "",
)

/** Projected drop of a clip drag (v1.0.9 free positioning): the slot the
 *  clip will occupy and the black hole in front of it. */
private data class Placement(
    val insertAt: Int,
    val gapBeforeUs: Long,
    /** Timeline start/end of the clip once dropped, microseconds. */
    val landStartUs: Long,
    val landEndUs: Long,
    /** End of the clip in front (where the projected hole begins). */
    val predEndUs: Long,
)

private data class TextLayout(
    val item: TextOverlayItem,
    val lane: Int,
    val left: Float,
    val width: Float,
)

private data class AudioLayout(
    val clip: AudioClip,
    val lane: Int,
    val left: Float,
    val width: Float,
)

private data class PipLayout(
    val pip: com.RooMCutNext.core.model.PipLayer,
    val lane: Int,
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
    var textRegionTop: Float = 0f
    var textRegionBottom: Float = 0f
    var pipRegionTop: Float = 0f
    var pipRegionBottom: Float = 0f
    var audioRegionTop: Float = 0f
    var audioRegionBottom: Float = 0f
    var playheadBottom: Float = 0f
    // v1.5.4: vertical content scroll (px). Viewport Y = content Y - scrollY;
    // hit-testing adds it back. NEGATIVE while the stack is bottom-anchored
    // with room to spare (no overflow). Written by the draw pass
    // (self-healing anchor — see TimelineVerticalMath.anchorScrollY), read
    // by hitTest and the gesture loop.
    var scrollY: Float = 0f
    /** Max legal vertical scroll (px); > 0 only when the stack overflows. */
    var maxScrollY: Float = 0f
    var textLaneStride: Float = 0f
    var pipLaneStride: Float = 0f
    var audioLaneStride: Float = 0f
    var clips: List<ClipLayout> = emptyList()
    var texts: List<TextLayout> = emptyList()
    var pips: List<PipLayout> = emptyList()
    var audios: List<AudioLayout> = emptyList()
    var hasClips: Boolean = false
    var hasAnyContent: Boolean = false

    // v1.3.1: track-mute toggle buttons in the pre-origin zone (left of the
    // 00:00 mark), vertically centered on their track. Null when the track
    // does not exist. Written by the draw pass, read by hitTest.
    var videoMuteRect: Rect? = null
    var pipMuteRect: Rect? = null

    fun timeToX(us: Long): Float = us / 1_000_000f * pps - scrollX
    fun xToTimeUs(x: Float): Long = ((x + scrollX) / pps * 1_000_000f).toLong().coerceAtLeast(0)

    /** Y span of the given text lane. */
    fun textLaneRect(lane: Int): Rect =
        Rect(0f, textRegionTop + lane * textLaneStride, viewportW, textRegionTop + (lane + 1) * textLaneStride)

    /** Y span of the given PiP lane. */
    fun pipLaneRect(lane: Int): Rect =
        Rect(0f, pipRegionTop + lane * pipLaneStride, viewportW, pipRegionTop + (lane + 1) * pipLaneStride)

    /** Y span of the given audio lane. */
    fun audioLaneRect(lane: Int): Rect =
        Rect(0f, audioRegionTop + lane * audioLaneStride, viewportW, audioRegionTop + (lane + 1) * audioLaneStride)
}

private sealed class DragMode {
    /** Drag the timeline content under the fixed playhead (scrub). */
    data object Scrub : DragMode()
    data object Pinch : DragMode()
    /** v1.5.4: vertical pan — scrolls the lane stack when it overflows the
     *  viewport (finger up reveals lower content, standard list semantics). */
    data object VScroll : DragMode()
    data class Trim(val clipId: String, val isLeft: Boolean) : DragMode()
    data class ClipMove(val clipId: String, val fromIndex: Int) : DragMode()
    data class TextMove(val textId: String) : DragMode()
    data class TextTrim(val textId: String, val isLeft: Boolean) : DragMode()
    data class AudioMove(val audioId: String) : DragMode()
    data class AudioTrim(val audioId: String, val isLeft: Boolean) : DragMode()
    data class PipMove(val pipId: String) : DragMode()
    data class PipTrim(val pipId: String, val isLeft: Boolean) : DragMode()
    data object TapGallery : DragMode()
    data object TapFiles : DragMode()
    /** v1.3.1: track-level mute toggles (tap-only chips left of 00:00). */
    data object TapVideoMute : DragMode()
    data object TapPipMute : DragMode()
}

// ---------------------------------------------------------------------------
// Main composable
// ---------------------------------------------------------------------------

/**
 * LibreCut-style timeline: the PLAYHEAD IS FIXED at the horizontal center of
 * the viewport; the timeline content scrolls underneath it. Dragging any
 * empty area (or the ruler) scrubs — the frame under the fixed playhead
 * changes as the content follows the finger. Pinch zoom keeps the playhead
 * time anchored at the center.
 */
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
    // v1.5.4: vertical content scroll in px. Semantics live in
    // TimelineVerticalMath: the lane stack is BOTTOM-ANCHORED (video/audio
    // tracks pinned at the viewport bottom — adding text layers grows the
    // stack upward and never pushes the video track off screen); when the
    // stack is taller than the sub-ruler viewport it becomes scrollable.
    // rememberSaveable so the position survives tool-panel swaps (the
    // timeline leaves composition while a panel is open) — the draw pass
    // re-clamps it on return.
    val scrollYState = rememberSaveable(saver = ScrollYSaver) { mutableFloatStateOf(0f) }
    val viewportHState = remember { mutableFloatStateOf(0f) }
    val dragState = remember { mutableStateOf<DragMode?>(null) }
    /** True while a Scrub drag owns the scroll (auto-sync disabled). */
    val scrubbingActive = remember { mutableStateOf(false) }
    val geom = remember { GeometryHolder() }
    val thumbRevision = remember { mutableIntStateOf(0) }
    val fingerX = remember { mutableFloatStateOf(Float.NaN) }
    val viewportWState = remember { mutableFloatStateOf(0f) }
    /** Last tap on a text clip (id + timestamp) for double-tap-to-edit. */
    val lastTextTap = remember { mutableStateOf<Pair<String, Long>?>(null) }
    /** Grab offset (fingerX - clipLeft, px) while dragging a video clip — the
     *  elevated preview and the drop target keep it, so the clip lands exactly
     *  where it is drawn. NaN when no clip drag is active. */
    val clipGrabOffset = remember { mutableFloatStateOf(Float.NaN) }

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

    /** The playhead is always drawn at this fixed screen X. */
    fun playheadScreenX(): Float = viewportWState.floatValue * 0.5f

    fun contentEndPx(): Float = (snapshot?.project?.totalDurationUs ?: 0L) / 1_000_000f * pps

    fun clampedScroll(): Float = scrollXState.floatValue

    fun xToTimeUs(x: Float): Long = ((x + clampedScroll()) / pps * 1_000_000f).toLong().coerceAtLeast(0)

    /** Time currently under the fixed playhead for a given scroll value. */
    fun timeAtPlayhead(scroll: Float): Long =
        (((scroll + playheadScreenX()) / pps * 1_000_000f).toLong()).coerceAtLeast(0L)

    /**
     * Projected drop of a clip drag (v1.0.9 free positioning): release at
     * [fingerXpx] and the clip lands at [Placement.landStartUs] with a black
     * hole of [Placement.gapBeforeUs] in front of it. This is how the user
     * puts space between two clips — the drag is no longer a pure reorder.
     *
     * The clip's left edge tracks (fingerX - grabOffset), matching the
     * elevated preview, so the clip lands exactly where it is drawn.
     */
    fun computePlacement(
        snap: EditorViewModel.TimelineSnapshot,
        moved: Clip,
        fingerXpx: Float,
    ): Placement? {
        val others = snap.project.clips.filter { it.id != moved.id }
        val offset = if (clipGrabOffset.floatValue.isNaN()) moved.durationUs / 2
        else (clipGrabOffset.floatValue / pps * 1_000_000f).toLong()
        val targetStartUs = (xToTimeUs(fingerXpx) - offset).coerceAtLeast(0L)
        val starts = TimelineOps.starts(others)
        // Insertion slot: before the first clip whose midpoint is past the target.
        var insertAt = others.size
        for (i in others.indices) {
            if (targetStartUs < starts[i] + others[i].durationUs / 2) {
                insertAt = i
                break
            }
        }
        val predEndUs = if (insertAt == 0) 0L else starts[insertAt - 1] + others[insertAt - 1].durationUs
        // Magnetic: a near-touch drop snaps tight — no sliver black holes.
        val gap = (targetStartUs - predEndUs).coerceAtLeast(0L)
        val snappedGap = if (gap <= 300_000L) 0L else gap
        val landStartUs = predEndUs + snappedGap
        return Placement(insertAt, snappedGap, landStartUs, landStartUs + moved.durationUs, predEndUs)
    }

    // ---------------- auto-sync scroll to the playhead ----------------
    // Whenever the playhead moves for any reason other than an active scrub
    // drag (playback, tap-seek, frame step, zoom change), the content scrolls
    // so the playhead time stays at the fixed center X.
    LaunchedEffect(playhead, pps, viewportWState.floatValue, scrubbingActive.value, snapshot?.project?.totalDurationUs) {
        if (!scrubbingActive.value) {
            scrollXState.floatValue = playhead / 1_000_000f * pps - playheadScreenX()
        }
    }

    // ---------------- v1.5.4: auto-reveal the selection vertically ----------------
    // When a lane (or the video track) is selected while the stack overflows
    // the viewport, scroll minimally so it becomes fully visible. This is
    // what keeps "too many layers" usable: adding a text layer always brings
    // its lane into view, and the bottom-anchored video/audio tracks are a
    // flick away. No-op when everything fits (the draw pass owns anchoring).
    LaunchedEffect(
        snapshot?.selectedTextId,
        snapshot?.selectedPipId,
        snapshot?.selectedAudioId,
        snapshot?.selectedClipId,
        snapshot?.project?.texts?.size,
        snapshot?.project?.pips?.size,
        snapshot?.project?.audios?.size,
        viewportHState.floatValue,
        dp,
    ) {
        val snap = snapshot ?: return@LaunchedEffect
        if (!snap.project.hasContent) return@LaunchedEffect
        val viewportHdp = viewportHState.floatValue / dp
        if (viewportHdp <= 0f) return@LaunchedEffect
        val textLanes = TimelineOps.laneIndexes(snap.project.texts.map { it.startUs to it.endUs })
        val textLaneCount = (textLanes.maxOrNull() ?: -1) + 1
        val pipLanes = TimelineOps.laneIndexes(snap.project.pips.map { it.startUs to it.endUs })
        val pipLaneCount = (pipLanes.maxOrNull() ?: -1) + 1
        val audioLanes = TimelineOps.laneIndexes(snap.project.audios.map { it.startUs to it.endUs })
        val audioLaneCount = (audioLanes.maxOrNull() ?: -1) + 1
        val contentHdp = TimelineVerticalMath.contentHeightDp(textLaneCount, pipLaneCount, audioLaneCount)
        if (contentHdp <= viewportHdp) return@LaunchedEffect // everything visible

        var targetTop = -1f
        var targetBottom = -1f
        val selTextIdx = snap.project.texts.indexOfFirst { it.id == snap.selectedTextId }
        if (snap.selectedTextId != null && selTextIdx >= 0) {
            targetTop = TimelineVerticalMath.textLaneTopDp(textLanes[selTextIdx], textLaneCount)
            targetBottom = targetTop + TimelineVerticalMath.TEXT_LANE_HEIGHT_DP
        } else {
            val selPipIdx = snap.project.pips.indexOfFirst { it.id == snap.selectedPipId }
            if (snap.selectedPipId != null && selPipIdx >= 0) {
                targetTop = TimelineVerticalMath.pipLaneTopDp(pipLanes[selPipIdx], textLaneCount, pipLaneCount)
                targetBottom = targetTop + TimelineVerticalMath.PIP_LANE_HEIGHT_DP
            } else {
                val selAudioIdx = snap.project.audios.indexOfFirst { it.id == snap.selectedAudioId }
                if (snap.selectedAudioId != null && selAudioIdx >= 0) {
                    targetTop = TimelineVerticalMath.audioLaneTopDp(audioLanes[selAudioIdx], textLaneCount, pipLaneCount, audioLaneCount)
                    targetBottom = targetTop + TimelineVerticalMath.AUDIO_LANE_HEIGHT_DP
                } else if (snap.selectedClipId != null && snap.project.clips.isNotEmpty()) {
                    targetTop = TimelineVerticalMath.trackTopDp(textLaneCount, pipLaneCount)
                    targetBottom = TimelineVerticalMath.trackBottomDp(textLaneCount, pipLaneCount)
                }
            }
        }
        if (targetTop < 0f) return@LaunchedEffect
        val anchored = TimelineVerticalMath.anchorScrollY(scrollYState.floatValue / dp, contentHdp, viewportHdp)
        val revealed = TimelineVerticalMath.revealScrollY(anchored, targetTop, targetBottom, contentHdp, viewportHdp)
        if (revealed != anchored) scrollYState.floatValue = revealed * dp
    }

    // ---------------- hit testing ----------------
    fun hitTest(x: Float, y: Float): DragMode {
        val g = geom
        if (!g.hasAnyContent) {
            val cx = g.viewportW / 2f
            val cy = g.viewportH / 2f
            val galleryRect = Rect(cx - 70f * dp, cy - 74f * dp, cx + 70f * dp, cy + 26f * dp)
            if (galleryRect.contains(Offset(x, y))) return DragMode.TapGallery
            val filesRect = Rect(cx - 120f * dp, cy + 44f * dp, cx + 120f * dp, cy + 84f * dp)
            if (filesRect.contains(Offset(x, y))) return DragMode.TapFiles
            return DragMode.TapGallery
        }
        if (y <= g.rulerBottom) return DragMode.Scrub
        // v1.5.4: content regions live in content space — add the vertical
        // scroll back to obtain the content-space Y. (The ruler is pinned to
        // the viewport and never scrolls.) While the stack is bottom-anchored
        // with room to spare, scrollY is negative and the dead space above
        // the stack hits no region → Scrub, exactly like the old layout.
        val cy = y + g.scrollY
        // v1.3.1: track mute chips win over everything in their rect
        // (they sit over blank pre-origin space anyway).
        g.videoMuteRect?.let { if (it.contains(Offset(x, cy))) return DragMode.TapVideoMute }
        g.pipMuteRect?.let { if (it.contains(Offset(x, cy))) return DragMode.TapPipMute }
        if (g.pipRegionBottom > g.pipRegionTop && cy in g.pipRegionTop..g.pipRegionBottom) {
            val selectedPip = snapshot?.selectedPipId
            if (selectedPip != null) {
                val sel = g.pips.firstOrNull { it.pip.id == selectedPip }
                if (sel != null && sel.width > 24f * dp) {
                    if (abs(x - sel.left) <= TRIM_HIT_PX) return DragMode.PipTrim(selectedPip, true)
                    if (abs(x - (sel.left + sel.width)) <= TRIM_HIT_PX) return DragMode.PipTrim(selectedPip, false)
                }
            }
            val laneIdx = ((cy - g.pipRegionTop) / g.pipLaneStride).toInt()
            val hit = g.pips.firstOrNull {
                x >= it.left && x <= it.left + it.width && it.lane == laneIdx
            }
            if (hit != null) return DragMode.PipMove(hit.pip.id)
            return DragMode.Scrub
        }
        if (cy in g.trackTop..g.trackBottom) {
            // Empty video track (text/audio-only project) behaves like blank
            // space: tap deselects, press-and-drag scrubs.
            if (!g.hasClips) return DragMode.Scrub
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
            return DragMode.Scrub
        }
        if (g.textRegionBottom > g.textRegionTop && cy in g.textRegionTop..g.textRegionBottom) {
            val selectedText = snapshot?.selectedTextId
            if (selectedText != null) {
                val sel = g.texts.firstOrNull { it.item.id == selectedText }
                if (sel != null && sel.width > 24f * dp) {
                    if (abs(x - sel.left) <= TRIM_HIT_PX) return DragMode.TextTrim(selectedText, true)
                    if (abs(x - (sel.left + sel.width)) <= TRIM_HIT_PX) return DragMode.TextTrim(selectedText, false)
                }
            }
            val laneIdx = ((cy - g.textRegionTop) / g.textLaneStride).toInt()
            val hit = g.texts.firstOrNull {
                x >= it.left && x <= it.left + it.width && it.lane == laneIdx
            }
            if (hit != null) return DragMode.TextMove(hit.item.id)
            return DragMode.Scrub
        }
        if (g.audioRegionBottom > g.audioRegionTop && cy in g.audioRegionTop..g.audioRegionBottom) {
            val selectedAudio = snapshot?.selectedAudioId
            if (selectedAudio != null) {
                val sel = g.audios.firstOrNull { it.clip.id == selectedAudio }
                if (sel != null && sel.width > 24f * dp) {
                    if (abs(x - sel.left) <= TRIM_HIT_PX) return DragMode.AudioTrim(selectedAudio, true)
                    if (abs(x - (sel.left + sel.width)) <= TRIM_HIT_PX) return DragMode.AudioTrim(selectedAudio, false)
                }
            }
            val laneIdx = ((cy - g.audioRegionTop) / g.audioLaneStride).toInt()
            val hit = g.audios.firstOrNull {
                x >= it.left && x <= it.left + it.width && it.lane == laneIdx
            }
            if (hit != null) return DragMode.AudioMove(hit.clip.id)
            return DragMode.Scrub
        }
        return DragMode.Scrub
    }

    // ---------------- gesture handlers ----------------
    fun onDragStart(mode: DragMode, x: Float) {
        val v = currentVm
        when (mode) {
            is DragMode.Scrub -> {
                scrubbingActive.value = true
                v.beginScrub()
            }
            is DragMode.Trim -> v.selectClip(mode.clipId)
            is DragMode.ClipMove -> {
                RooMCutNextApp.vibrate(v.getApplication(), 14)
                // Where inside the clip the finger grabbed (px from its left
                // edge) — the elevated preview and the drop target both keep
                // it so the clip lands where it is drawn, not centered on the
                // finger.
                val layout = geom.clips.firstOrNull { it.clip.id == mode.clipId }
                clipGrabOffset.floatValue = layout?.let { x - it.left } ?: Float.NaN
            }
            is DragMode.TextTrim -> v.selectText(mode.textId)
            is DragMode.AudioTrim -> v.selectAudio(mode.audioId)
            is DragMode.PipTrim -> v.selectPip(mode.pipId)
            else -> Unit
        }
        if (mode is DragMode.ClipMove) fingerX.floatValue = x
    }

    fun onDragMove(mode: DragMode, dx: Float, dy: Float, x: Float) {
        val v = currentVm
        when (mode) {
            is DragMode.VScroll -> {
                // Finger up (dy < 0) increases the scroll and reveals LOWER
                // content; finger down scrolls back up toward the text lanes.
                // Clamped every event so a mid-gesture geometry change
                // (panel swap, resize) can never strand the scroll outside
                // the legal range.
                scrollYState.floatValue =
                    (scrollYState.floatValue - dy).coerceIn(0f, geom.maxScrollY)
            }
            is DragMode.Scrub -> {
                // Content follows the finger; the fixed playhead scrubs over it.
                val centerX = playheadScreenX()
                val endPx = contentEndPx()
                val s = (scrollXState.floatValue - dx)
                    .coerceIn(-centerX, max(-centerX, endPx - centerX))
                scrollXState.floatValue = s
                v.seekDuringDrag(timeAtPlayhead(s))
            }
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
                } else {
                    val local = (timeUs - clipStart).coerceAtLeast(0L)
                    v.previewTrim(clip.id, clip.sourceInUs, clip.sourceInUs + (local.toFloat() * clip.speed).toLong())
                }
            }
            is DragMode.ClipMove -> fingerX.floatValue = x
            is DragMode.TextMove -> {
                v.moveText(mode.textId, xToTimeUs(x - 20f * dp), commit = false)
            }
            is DragMode.TextTrim -> {
                val item = snapshot?.project?.texts?.firstOrNull { it.id == mode.textId } ?: return
                val t = xToTimeUs(x)
                if (mode.isLeft) v.trimText(mode.textId, t, item.endUs, commit = false)
                else v.trimText(mode.textId, item.startUs, t, commit = false)
            }
            is DragMode.AudioMove -> {
                v.moveAudio(mode.audioId, xToTimeUs(x - 20f * dp), commit = false)
            }
            is DragMode.AudioTrim -> {
                v.previewAudioTrimEdge(mode.audioId, mode.isLeft, xToTimeUs(x))
            }
            is DragMode.PipMove -> {
                v.movePip(mode.pipId, xToTimeUs(x - 20f * dp), commit = false)
            }
            is DragMode.PipTrim -> {
                v.trimPipEdge(mode.pipId, mode.isLeft, xToTimeUs(x), commit = false)
            }
            else -> Unit
        }
    }

    fun onDragEnd(mode: DragMode, x: Float) {
        val v = currentVm
        when (mode) {
            is DragMode.Scrub -> {
                val finalTime = timeAtPlayhead(scrollXState.floatValue)
                v.seekTo(finalTime)
                v.endScrub()
                scrubbingActive.value = false
                // Re-derive the scroll from the (clamped) final playhead so the
                // content never rests past the timeline bounds.
                val endPx = contentEndPx()
                scrollXState.floatValue = (finalTime / 1_000_000f * pps - playheadScreenX())
                    .coerceIn(-playheadScreenX(), max(-playheadScreenX(), endPx - playheadScreenX()))
            }
            is DragMode.Trim -> v.commitTrim()
            is DragMode.ClipMove -> {
                val snap = snapshot ?: return
                val moved = snap.project.clips.firstOrNull { it.id == mode.clipId } ?: return
                val placement = computePlacement(snap, moved, x)
                if (placement != null) {
                    // v1.0.9: the drop CREATES the gap the user dragged —
                    // clips can now sit apart with black between them.
                    v.placeClip(mode.fromIndex, placement.insertAt, placement.gapBeforeUs)
                }
                fingerX.floatValue = Float.NaN
                clipGrabOffset.floatValue = Float.NaN
            }
            is DragMode.TextMove -> {
                val item = snapshot?.project?.texts?.firstOrNull { it.id == mode.textId } ?: return
                v.moveText(mode.textId, item.startUs, commit = true)
            }
            is DragMode.TextTrim -> {
                val item = snapshot?.project?.texts?.firstOrNull { it.id == mode.textId } ?: return
                v.trimText(mode.textId, item.startUs, item.endUs, commit = true)
            }
            is DragMode.AudioMove -> {
                val audio = snapshot?.project?.audios?.firstOrNull { it.id == mode.audioId } ?: return
                v.moveAudio(mode.audioId, audio.startUs, commit = true)
            }
            is DragMode.AudioTrim -> v.commitAudioTrim()
            is DragMode.PipMove -> {
                val pip = snapshot?.project?.pips?.firstOrNull { it.id == mode.pipId } ?: return
                v.movePip(mode.pipId, pip.startUs, commit = true)
            }
            is DragMode.PipTrim -> {
                val pip = snapshot?.project?.pips?.firstOrNull { it.id == mode.pipId } ?: return
                v.trimPipEdge(mode.pipId, mode.isLeft, if (mode.isLeft) pip.startUs else pip.endUs, commit = true)
            }
            else -> Unit
        }
    }

    fun handleTextTap(textId: String) {
        val now = android.os.SystemClock.uptimeMillis()
        val prev = lastTextTap.value
        val isDoubleTap = prev != null && prev.first == textId && now - prev.second <= TEXT_DOUBLE_TAP_MS
        lastTextTap.value = textId to now
        currentVm.selectText(textId)
        if (isDoubleTap) {
            lastTextTap.value = null
            currentVm.openPanel(PanelKind.TEXT)
        }
    }

    fun onTap(mode: DragMode, x: Float, y: Float) {
        val v = currentVm
        when (mode) {
            is DragMode.TapGallery -> currentOnAddMedia()
            is DragMode.TapFiles -> currentOnFiles()
            // v1.3.1: track-level mute toggles — audio lanes (BGM) are
            // deliberately NOT covered by these switches.
            is DragMode.TapVideoMute -> v.toggleVideoTrackMute()
            is DragMode.TapPipMute -> v.togglePipTrackMute()
            // Blank space (ruler, empty track areas): drop the selection.
            // Scrolling/scrubbing only happens via press-and-drag.
            is DragMode.Scrub -> {
                lastTextTap.value = null
                v.clearSelection()
            }
            is DragMode.Trim -> {
                lastTextTap.value = null
                v.selectClip(mode.clipId)
            }
            is DragMode.ClipMove -> {
                lastTextTap.value = null
                v.selectClip(mode.clipId)
            }
            // Text clips: SINGLE tap selects; DOUBLE tap (same text within
            // 400ms) opens the editing sheet (content, color, size, ...).
            is DragMode.TextMove -> handleTextTap(mode.textId)
            is DragMode.TextTrim -> handleTextTap(mode.textId)
            is DragMode.AudioMove -> {
                lastTextTap.value = null
                v.selectAudio(mode.audioId)
            }
            is DragMode.AudioTrim -> {
                lastTextTap.value = null
                v.selectAudio(mode.audioId)
            }
            is DragMode.PipMove -> {
                lastTextTap.value = null
                v.selectPip(mode.pipId)
            }
            is DragMode.PipTrim -> {
                lastTextTap.value = null
                v.selectPip(mode.pipId)
            }
            is DragMode.Pinch -> Unit
            // A vertical scroll always registers as a drag before release,
            // so it can never arrive here — listed for exhaustiveness.
            is DragMode.VScroll -> Unit
        }
    }

    // ---------------- layout computation ----------------
    fun displayClips(snap: EditorViewModel.TimelineSnapshot): List<ClipLayout> {
        val preview = snap.trimPreview
        // Apply the live trim preview FIRST so widths and effective
        // transitions both see the projected durations (v1.2.1).
        val clips = snap.project.clips.map { c ->
            preview?.takeIf { it.clipId == c.id }?.let {
                c.copy(sourceInUs = it.sourceInUs, sourceOutUs = it.sourceOutUs)
            } ?: c
        }
        val transitions = com.RooMCutNext.core.model.effectiveTransitions(clips)
        var acc = 0f
        val out = mutableListOf<ClipLayout>()
        clips.forEachIndexed { index, clip ->
            // The black hole in front of the clip (v1.0.8 gaps).
            val gapWidth = clip.gapBeforeUs / 1_000_000f * pps
            // v1.2.1: a crossfade pulls the clip's start back onto the
            // previous clip's tail — the blocks overlap like CapCut's.
            val overlapWidth = transitions[index] / 1_000_000f * pps
            val width = clip.durationUs / 1_000_000f * pps
            val left = acc + gapWidth - overlapWidth
            out += ClipLayout(
                clip, index, left, width,
                gapLeft = acc, gapWidth = gapWidth,
                transitionUs = transitions[index],
                transitionType = clip.transitionType,
            )
            acc = left + width
        }
        val scroll = clampedScroll()
        return out.map { it.copy(left = it.left - scroll, gapLeft = it.gapLeft - scroll) }
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
                viewportWState.floatValue = size.width.toFloat()
                viewportHState.floatValue = size.height.toFloat()
                currentVm.viewportWidthPx = size.width
            }
            .pointerInput(Unit) {
                val touchSlop = viewConfig.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var mode = hitTest(down.position.x, down.position.y)
                    var dragStarted = false
                    var lastX = down.position.x
                    var lastY = down.position.y
                    var accumDx = 0f
                    var accumDy = 0f
                    var pinchSpan = 0f

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2) {
                            if (mode != DragMode.Pinch) {
                                if (dragStarted && mode is DragMode.Trim) currentVm.cancelTrim()
                                if (dragStarted && mode is DragMode.AudioTrim) currentVm.cancelAudioTrim()
                                if (dragStarted && mode is DragMode.Scrub) {
                                    currentVm.endScrub()
                                    scrubbingActive.value = false
                                }
                                dragState.value = null
                                fingerX.floatValue = Float.NaN
                                clipGrabOffset.floatValue = Float.NaN
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
                            mode = DragMode.Scrub
                            dragStarted = false
                            lastX = pressed[0].position.x
                            lastY = pressed[0].position.y
                            accumDx = 0f
                            accumDy = 0f
                        }

                        val change = pressed[0]
                        val x = change.position.x
                        val y = change.position.y
                        val dx = x - lastX
                        val dy = y - lastY
                        lastX = x
                        lastY = y
                        accumDx += dx
                        accumDy += dy

                        if (!dragStarted && (abs(accumDx) > touchSlop || abs(accumDy) > touchSlop)) {
                            // v1.5.4: when the lane stack overflows the
                            // viewport, a predominantly VERTICAL pan takes over
                            // and scrolls the stack (finger up reveals lower
                            // content — standard list semantics). Horizontal
                            // pans keep the pre-existing behavior (scrub /
                            // move / trim) for every mode, and when nothing
                            // overflows the gesture model is bit-for-bit the
                            // old one: the vertical branch simply cannot fire.
                            if (mode != DragMode.Pinch &&
                                geom.maxScrollY > 0f &&
                                abs(accumDy) > abs(accumDx)
                            ) {
                                mode = DragMode.VScroll
                            }
                            dragStarted = true
                            dragState.value = mode
                            onDragStart(mode, x)
                        }
                        if (dragStarted) {
                            onDragMove(mode, dx, dy, x)
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
        // v1.5.0: short labels for the transition marker pills, resolved in
        // the composable scope (canvas draws take plain strings).
        val transitionShortLabels = TransitionStyle.entries.associate { s ->
            s.id to stringResource(TransitionUi.shortRes(s.id))
        }
        Canvas(Modifier.fillMaxSize()) {
            val snap = snapshot ?: return@Canvas
            val g = geom
            g.pps = pps
            g.scrollX = clampedScroll()
            g.hasClips = snap.project.clips.isNotEmpty()
            g.hasAnyContent = snap.project.hasContent

            thumbRevision.intValue // invalidate when thumbnails finish loading

            if (!g.hasAnyContent) {
                drawEmptyState(g, emptyHint, addLabel, filesLabel, dp)
                return@Canvas
            }

            // ---- region geometry (depends on lane counts) ----
            val textLanes = TimelineOps.laneIndexes(snap.project.texts.map { it.startUs to it.endUs })
            val textLaneCount = (textLanes.maxOrNull() ?: -1) + 1
            val pipLanes = TimelineOps.laneIndexes(snap.project.pips.map { it.startUs to it.endUs })
            val pipLaneCount = (pipLanes.maxOrNull() ?: -1) + 1
            val audioLanes = TimelineOps.laneIndexes(snap.project.audios.map { it.startUs to it.endUs })
            val audioLaneCount = (audioLanes.maxOrNull() ?: -1) + 1

            // LibreCut-style stack: text lanes ABOVE the video track, PiP
            // lanes between text and video (closer to the picture they
            // overlay), audio lanes below it. Regions are disjoint;
            // zero-height when unused.
            //
            // v1.5.4: the geometry now comes from TimelineVerticalMath (the
            // same dp-domain formulas the unit tests pin), and the stack is
            // BOTTOM-ANCHORED — the whole content is translated vertically by
            // -scrollY so the audio/video tracks sit at the viewport bottom
            // no matter how many text lanes exist. Adding text layers grows
            // the stack UPWARD (the video track never moves); when the stack
            // is taller than the sub-ruler viewport it scrolls vertically.
            val m = TimelineVerticalMath
            g.rulerBottom = m.RULER_HEIGHT_DP * dp
            g.textLaneStride = m.TEXT_LANE_HEIGHT_DP * dp
            g.pipLaneStride = m.PIP_LANE_HEIGHT_DP * dp
            g.audioLaneStride = m.AUDIO_LANE_HEIGHT_DP * dp
            g.textRegionTop = m.textRegionTopDp(textLaneCount) * dp
            g.textRegionBottom = m.textRegionBottomDp(textLaneCount) * dp
            g.pipRegionTop = m.pipRegionTopDp(textLaneCount, pipLaneCount) * dp
            g.pipRegionBottom = m.pipRegionBottomDp(textLaneCount, pipLaneCount) * dp
            g.trackTop = m.trackTopDp(textLaneCount, pipLaneCount) * dp
            g.trackBottom = m.trackBottomDp(textLaneCount, pipLaneCount) * dp
            g.audioRegionTop = m.audioRegionTopDp(textLaneCount, pipLaneCount, audioLaneCount) * dp
            g.audioRegionBottom = m.audioRegionBottomDp(textLaneCount, pipLaneCount, audioLaneCount) * dp
            g.playheadBottom = g.audioRegionBottom

            // Self-healing vertical anchor: re-clamp the scroll whenever the
            // content or viewport height changed (lane added/removed, panel
            // swap, resize, first layout). Converges in one frame — the
            // target is a pure function of inputs that do not change on the
            // redraw it triggers.
            val contentHdp = m.contentHeightDp(textLaneCount, pipLaneCount, audioLaneCount)
            val viewportHdp = g.viewportH / dp
            val targetScrollDp = m.anchorScrollY(scrollYState.floatValue / dp, contentHdp, viewportHdp)
            if (scrollYState.floatValue != targetScrollDp * dp) {
                scrollYState.floatValue = targetScrollDp * dp
            }
            g.scrollY = targetScrollDp * dp
            g.maxScrollY = m.maxScrollYDp(contentHdp, viewportHdp) * dp

            // v1.3.1: track-mute chips live in the PRE-ORIGIN zone — the empty
            // space left of the 00:00 ruler origin (x = timeToX(0)), one chip
            // vertically centered on the video track, one on the PiP region.
            // They scroll with the timeline content (they are "at 00:00's
            // left", not pinned to the viewport) and come back whenever the
            // user returns to the start.
            val originX = -g.scrollX
            val muteSize = 28f * dp
            val muteGap = 8f * dp
            fun muteRectFor(top: Float, bottom: Float): Rect {
                val cy = (top + bottom) / 2f
                val right = originX - muteGap
                return Rect(right - muteSize, cy - muteSize / 2f, right, cy + muteSize / 2f)
            }
            g.videoMuteRect = if (g.hasClips) muteRectFor(g.trackTop, g.trackBottom) else null
            g.pipMuteRect = if (pipLaneCount > 0) muteRectFor(g.pipRegionTop, g.pipRegionBottom) else null

            val clips = displayClips(snap)
            g.clips = clips

            drawRuler(snap.project, g, textPaint, dp)

            // v1.5.4: all lane content below the pinned ruler is drawn in
            // CONTENT space — clipped to the sub-ruler viewport and shifted
            // by the vertical scroll. Every existing draw helper keeps using
            // content coordinates (g.trackTop, g.textRegionTop, …), so their
            // bodies are untouched; the block body below intentionally keeps
            // its original indentation — the lambda scope is mechanical.
            clipRect(left = 0f, top = g.rulerBottom, right = g.viewportW, bottom = g.viewportH) {
            translate(left = 0f, top = -g.scrollY) {
            drawTrackBackground(g, textLaneCount > 0, audioLaneCount > 0, dp)
            drawMuteButtons(snap.project, g, dp)

            val moved = dragState.value as? DragMode.ClipMove
            for (layout in clips) {
                if (moved != null && layout.clip.id == moved.clipId) continue
                drawGap(
                    gapLeft = layout.gapLeft,
                    gapWidth = layout.gapWidth,
                    gapUs = layout.clip.gapBeforeUs,
                    trackTop = g.trackTop,
                    trackBottom = g.trackBottom,
                    badgePaint = badgePaint,
                    dp = dp,
                )
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
                // v1.2.1: crossfade marker over the overlap region.
                // v1.3.1 concealed it pending the redesign; v1.4.0 restores it
                // (see the EditorScreen toolbar note — the reference-design
                // audit cleared the preview architecture, and the entry
                // points are the only thing that was ever missing).
                if (layout.transitionUs > 0L) {
                    drawTransitionMarker(
                        layout = layout,
                        ppsValue = pps,
                        trackTop = g.trackTop,
                        badgePaint = badgePaint,
                        dp = dp,
                        typeLabel = transitionShortLabels[layout.transitionType] ?: "",
                    )
                }
            }

            g.texts = snap.project.texts.mapIndexedNotNull { i, item ->
                val lane = textLanes[i]
                val left = item.startUs / 1_000_000f * pps - g.scrollX
                val width = (item.durationUs / 1_000_000f * pps).coerceAtLeast(6f * dp)
                if (left + width < -20f || left > g.viewportW + 20f) null
                else TextLayout(item, lane, left, width)
            }
            drawTextLanes(snap, g, badgePaint, dp)

            g.pips = snap.project.pips.mapIndexedNotNull { i, pip ->
                val left = pip.startUs / 1_000_000f * pps - g.scrollX
                val width = (pip.durationUs / 1_000_000f * pps).coerceAtLeast(6f * dp)
                if (left + width < -20f || left > g.viewportW + 20f) null
                else PipLayout(pip, pipLanes[i], left, width)
            }
            drawPipLanes(snap, g, badgePaint, dp)

            g.audios = snap.project.audios.mapIndexedNotNull { i, audio ->
                val preview = snap.audioTrimPreview?.takeIf { it.audioId == audio.id }
                val startUs = preview?.startUs ?: audio.startUs
                val inUs = preview?.sourceInUs ?: audio.sourceInUs
                val outUs = preview?.sourceOutUs ?: audio.sourceOutUs
                val left = startUs / 1_000_000f * pps - g.scrollX
                val width = ((outUs - inUs) / 1_000_000f * pps).coerceAtLeast(6f * dp)
                if (left + width < -20f || left > g.viewportW + 20f) null
                else AudioLayout(audio.copy(startUs = startUs, sourceInUs = inUs, sourceOutUs = outUs), audioLanes[i], left, width)
            }
            drawAudioLanes(snap, g, badgePaint, dp)

            if (moved != null && !fingerX.floatValue.isNaN()) {
                val dragged = clips.firstOrNull { it.clip.id == moved.clipId }
                val movedClip = snap.project.clips.firstOrNull { it.id == moved.clipId }
                val fx = fingerX.floatValue
                val placement = movedClip?.let { computePlacement(snap, it, fx) }
                if (placement != null) {
                    // ---- projected black hole (only when the drop leaves a gap) ----
                    if (placement.gapBeforeUs > 0L) {
                        val gapL = g.timeToX(placement.predEndUs)
                        val gapR = g.timeToX(placement.landStartUs)
                        if (gapR - gapL > 1f) {
                            drawRoundRect(
                                color = Color(0xAA000000),
                                topLeft = Offset(gapL, g.trackTop + 2f * dp),
                                size = Size(gapR - gapL, TimelineVerticalMath.TRACK_HEIGHT_DP * dp - 8f * dp),
                                cornerRadius = CornerRadius(6f * dp),
                            )
                            drawRoundRect(
                                color = Color(0x8037474F),
                                topLeft = Offset(gapL, g.trackTop + 2f * dp),
                                size = Size(gapR - gapL, TimelineVerticalMath.TRACK_HEIGHT_DP * dp - 8f * dp),
                                cornerRadius = CornerRadius(6f * dp),
                                style = Stroke(width = 1.dp.toPx()),
                            )
                        }
                    }
                    // ---- landing outline: where the clip will snap ----
                    val landL = g.timeToX(placement.landStartUs)
                    val landW = g.timeToX(placement.landEndUs) - landL
                    drawRoundRect(
                        color = AccentColor.copy(alpha = 0.9f),
                        topLeft = Offset(landL, g.trackTop + 2f * dp),
                        size = Size(landW, TimelineVerticalMath.TRACK_HEIGHT_DP * dp - 8f * dp),
                        cornerRadius = CornerRadius(6f * dp),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    drawLine(
                        color = AccentColor,
                        start = Offset(landL, g.trackTop),
                        end = Offset(landL, g.trackBottom),
                        strokeWidth = 3.dp.toPx(),
                    )
                }
                // The dragged clip follows the finger (grab point preserved),
                // slightly raised.
                if (dragged != null) {
                    val grabOff = if (clipGrabOffset.floatValue.isNaN()) dragged.width / 2f
                    else clipGrabOffset.floatValue
                    drawClip(
                        layout = dragged.copy(left = fx - grabOff),
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
            } // v1.5.4: end translate(0, -scrollY) — content space ends here
            } // v1.5.4: end clipRect(below the pinned ruler)
        }

        // ---------------- fixed playhead overlay ----------------
        Canvas(Modifier.fillMaxSize()) {
            val snap = snapshot ?: return@Canvas
            if (!snap.project.hasContent) return@Canvas
            val g = geom
            val x = playheadScreenX()
            // v1.5.4: clamp the playhead to the visible viewport — scrolled,
            // the content bottom sits beyond the viewport edge; with room to
            // spare (negative scrollY) it rests above the bottom edge at the
            // audio lanes' true end.
            val endY = min(g.playheadBottom - g.scrollY, g.viewportH)
            drawLine(
                color = Color(0xFFFF5C6E),
                start = Offset(x, 0f),
                end = Offset(x, endY),
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

    // ---------------- ensure thumbnails are loaded ----------------
    LaunchedEffect(snapshot?.project?.assets?.size, snapshot?.project?.id) {
        val snap = snapshot ?: return@LaunchedEffect
        val app = currentVm.getApplication<android.app.Application>()
        val dir = java.io.File(app.filesDir, "projects/${snap.project.id}/media")
        var loadedAny = false
        snap.project.assets.forEach { asset ->
            if (!asset.isAudio && currentVm.thumbnails.peek(asset.id) == null) {
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

    val stepSec = rulerStepSec(pps, g.viewportW)
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
    if (contentEndPx - scroll in 0f..g.viewportW && project.hasContent) {
        drawLine(
            color = DangerColor.copy(alpha = 0.5f),
            start = Offset(contentEndPx - scroll, 0f),
            end = Offset(contentEndPx - scroll, h),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

/**
 * Ruler label step, seconds (v1.0.9 — user request: numbers were too dense).
 *
 * Labels sit on the 5-SECOND grid at normal zoom (default 64px/s used to
 * label every single second). Zooming OUT stretches the step to 10/15/30s…
 * so labels never crowd below ~64px. Zooming IN past the point where one
 * 5s cell no longer fits the viewport subdivides back to 1s — the ruler
 * keeps numeric reference for precise trimming, and at that zoom 1s is
 * already hundreds of pixels apart, so it never reads as dense.
 */
private fun rulerStepSec(pps: Float, viewportW: Float): Float {
    if (5f * pps > viewportW * 0.9f) return 1f
    val candidates = floatArrayOf(5f, 10f, 15f, 30f, 60f, 120f, 300f, 600f, 1800f)
    for (c in candidates) {
        if (c * pps >= 64f) return c
    }
    return candidates.last()
}

private fun DrawScope.drawTrackBackground(g: GeometryHolder, hasTextLanes: Boolean, hasAudioLanes: Boolean, dp: Float) {
    drawRect(
        Color(0xFF0E1218),
        topLeft = Offset(0f, g.trackTop),
        size = Size(g.viewportW, g.trackBottom - g.trackTop),
    )
    if (hasTextLanes) {
        drawRect(
            Color(0x14FFFFFF),
            topLeft = Offset(0f, g.textRegionTop),
            size = Size(g.viewportW, g.textRegionBottom - g.textRegionTop),
        )
    }
    if (hasAudioLanes) {
        drawRect(
            Color(0x14204050),
            topLeft = Offset(0f, g.audioRegionTop),
            size = Size(g.viewportW, g.audioRegionBottom - g.audioRegionTop),
        )
    }
}

/**
 * v1.3.1: the track-level MUTE toggles — rounded chips with a speaker glyph,
 * sitting in the pre-origin zone to the LEFT of the 00:00 ruler mark, one
 * vertically centered on the video track and one on the PiP region. The
 * audio (BGM) lanes deliberately get NO chip: these switches must never
 * touch the music below. Muted state = red border + slash over the glyph.
 */
private fun DrawScope.drawMuteButtons(
    project: Project,
    g: GeometryHolder,
    dp: Float,
) {
    fun drawChip(rect: Rect, muted: Boolean) {
        val corner = CornerRadius(9f * dp)
        drawRoundRect(
            color = if (muted) DangerColor.copy(alpha = 0.30f) else Color(0xE60E1218),
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width, rect.height),
            cornerRadius = corner,
        )
        drawRoundRect(
            color = if (muted) DangerColor else Color(0xFF546E7A),
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width, rect.height),
            cornerRadius = corner,
            style = Stroke(width = 1.5.dp.toPx()),
        )
        val cx = rect.center.x
        val cy = rect.center.y
        // Speaker glyph: box + cone, drawn as one closed path.
        val speaker = Path().apply {
            moveTo(cx - 6f * dp, cy - 3.5f * dp)
            lineTo(cx - 2.5f * dp, cy - 3.5f * dp)
            lineTo(cx + 1.5f * dp, cy - 7f * dp)
            lineTo(cx + 1.5f * dp, cy + 7f * dp)
            lineTo(cx - 2.5f * dp, cy + 3.5f * dp)
            lineTo(cx - 6f * dp, cy + 3.5f * dp)
            close()
        }
        drawPath(speaker, color = Color.White)
        if (muted) {
            drawLine(
                color = DangerColor,
                start = Offset(cx - 7f * dp, cy - 7f * dp),
                end = Offset(cx + 7f * dp, cy + 7f * dp),
                strokeWidth = 2.5.dp.toPx(),
            )
        } else {
            // Sound waves (two arcs opening right).
            drawArc(
                color = Color.White,
                startAngle = -50f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = Offset(cx + 3f * dp - 3.5f * dp, cy - 3.5f * dp),
                size = Size(7f * dp, 7f * dp),
                style = Stroke(width = 1.6.dp.toPx()),
            )
            drawArc(
                color = Color.White.copy(alpha = 0.65f),
                startAngle = -50f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = Offset(cx + 3f * dp - 6f * dp, cy - 6f * dp),
                size = Size(12f * dp, 12f * dp),
                style = Stroke(width = 1.6.dp.toPx()),
            )
        }
    }
    g.videoMuteRect?.let { drawChip(it, project.videoTrackMuted) }
    g.pipMuteRect?.let { drawChip(it, project.pipTrackMuted) }
}

/**
 * The black hole in front of a clip (v1.0.8 gaps): renders as a solid black
 * slab with a dashed-ish border and a "黑场" feel — three slash marks, or a
 * readable duration badge (e.g. "2.0s") when the slab is wide enough.
 * Invisible when the gap is (almost) zero-width.
 */
private fun DrawScope.drawGap(
    gapLeft: Float,
    gapWidth: Float,
    gapUs: Long,
    trackTop: Float,
    trackBottom: Float,
    badgePaint: Paint,
    dp: Float,
) {
    if (gapWidth <= 1.5f * dp) return
    val top = trackTop
    val height = TimelineVerticalMath.TRACK_HEIGHT_DP * dp - 4f * dp
    val corner = CornerRadius(6f * dp)
    drawRoundRect(
        color = Color(0xFF000000),
        topLeft = Offset(gapLeft, top),
        size = Size(gapWidth, height),
        cornerRadius = corner,
    )
    drawRoundRect(
        color = Color(0xFF37474F),
        topLeft = Offset(gapLeft, top),
        size = Size(gapWidth, height),
        cornerRadius = corner,
        style = Stroke(width = 1.dp.toPx()),
    )
    val cx = gapLeft + gapWidth / 2f
    val cy = top + height / 2f
    if (gapWidth > 40f * dp) {
        // Wide hole: show its duration — the user lays text over a known
        // length of black ("2s 黑场 + 文字").
        val canvas = drawContext.canvas.nativeCanvas
        badgePaint.color = android.graphics.Color.argb(0xE0, 0x90, 0xA4, 0xAE)
        badgePaint.textAlign = android.graphics.Paint.Align.CENTER
        badgePaint.textSize = 9.5f * dp
        canvas.drawText(TimeCode.formatShort(gapUs), cx, cy + 3.5f * dp, badgePaint)
    } else {
        // Center slash marks: three short diagonal strokes signaling "black".
        val slash = 3.2f * dp
        drawLine(Color(0xFF546E7A), Offset(cx - slash, cy + slash / 2), Offset(cx - slash / 2, cy - slash / 2), 1.5f * dp)
        drawLine(Color(0xFF546E7A), Offset(cx, cy + slash / 2), Offset(cx + slash / 2, cy - slash / 2), 1.5f * dp)
        drawLine(Color(0xFF546E7A), Offset(cx + slash, cy + slash / 2), Offset(cx + slash * 1.5f, cy - slash / 2), 1.5f * dp)
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
    val height = TimelineVerticalMath.TRACK_HEIGHT_DP * dp - 4f * dp
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

    // ---- v1.6.1: 黑场专属渲染（纯黑 + 斜纹 + 居中标签）----
    // 黑场是普通图片素材，但纯黑缩略图在深色时间线上与空洞无法区分：
    // 用斜纹纹理 + “黑场”标签让它一眼可辨，选中/裁剪手柄/转场徽章等
    // 其余绘制路径与普通素材完全共用。
    if (asset != null && asset.isBlackFrame && !missing) {
        val pad = 2.5f * dp
        val innerTop = top + pad
        val innerHeight = height - pad * 2 - 7f * dp
        if (innerHeight > 4f * dp && layout.width > 8f * dp) {
            withClippedRoundRect(layout.left, innerTop, layout.width, innerHeight, corner) {
                drawRect(
                    Color(0xFF000000),
                    topLeft = Offset(layout.left, innerTop),
                    size = Size(layout.width, innerHeight),
                )
                val hatch = Color(0xFF2A313C)
                val step = 7f * dp
                var x = layout.left - innerHeight
                while (x < layout.left + layout.width) {
                    val x0 = x.coerceAtLeast(layout.left)
                    val x1 = (x + innerHeight).coerceAtMost(layout.left + layout.width)
                    if (x1 > x0) {
                        drawLine(
                            color = hatch,
                            start = Offset(x0, innerTop + innerHeight),
                            end = Offset(x1, innerTop),
                            strokeWidth = 1f * dp,
                        )
                    }
                    x += step
                }
            }
            if (layout.width > 26f * dp) {
                val native = drawContext.canvas.nativeCanvas
                badgePaint.color = android.graphics.Color.rgb(0xB8, 0xC2, 0xCC)
                badgePaint.textAlign = android.graphics.Paint.Align.CENTER
                badgePaint.textSize = 10f * dp
                native.drawText(
                    "黑场",
                    layout.left + layout.width / 2f,
                    top + height / 2f + 3.5f * dp,
                    badgePaint,
                )
            }
        }
    } else if (thumbs != null && thumbs.isNotEmpty() && asset != null && !missing) {
    // ---- filmstrip thumbnails ----
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

private fun DrawScope.drawTextLanes(
    snap: EditorViewModel.TimelineSnapshot,
    g: GeometryHolder,
    paint: Paint,
    dp: Float,
) {
    if (snap.project.texts.isEmpty()) return
    val canvas = drawContext.canvas.nativeCanvas
    snap.project.texts.forEachIndexed { index, item ->
        val layout = g.texts.firstOrNull { it.item.id == item.id } ?: return@forEachIndexed
        val laneRect = g.textLaneRect(layout.lane)
        val top = laneRect.top + 2f * dp
        val height = laneRect.height - 4f * dp
        if (height <= 2f) return@forEachIndexed
        val selected = snap.selectedTextId == item.id
        drawRoundRect(
            color = WarnColor.copy(alpha = 0.28f),
            topLeft = Offset(layout.left, top),
            size = Size(layout.width, height),
            cornerRadius = CornerRadius(4f * dp),
        )
        drawRoundRect(
            color = if (selected) AccentColor else WarnColor.copy(alpha = 0.7f),
            topLeft = Offset(layout.left, top),
            size = Size(layout.width, height),
            cornerRadius = CornerRadius(4f * dp),
            style = Stroke(width = if (selected) 2.dp.toPx() else 1.dp.toPx()),
        )
        paint.textAlign = android.graphics.Paint.Align.LEFT
        paint.textSize = 9f * dp
        paint.color = android.graphics.Color.argb(0xE0, 0xFF, 0xC2, 0x4B)
        val label = if (item.text.isBlank()) "T" else "T ${item.text}"
        val charW = max(1f, paint.measureText("T ") * 0.55f)
        val maxChars = max(1, ((layout.width - 8f * dp) / charW).toInt())
        canvas.drawText(label.take(maxChars), layout.left + 4f * dp, top + height - 4.5f * dp, paint)
        if (selected) {
            val handleH = height - 6f * dp
            drawRoundRect(
                color = AccentColor,
                topLeft = Offset(layout.left + 1f * dp, top + 3f * dp),
                size = Size(4f * dp, handleH),
                cornerRadius = CornerRadius(2f * dp),
            )
            drawRoundRect(
                color = AccentColor,
                topLeft = Offset(layout.left + layout.width - 5f * dp, top + 3f * dp),
                size = Size(4f * dp, handleH),
                cornerRadius = CornerRadius(2f * dp),
            )
        }
    }
}

private fun DrawScope.drawPipLanes(
    snap: EditorViewModel.TimelineSnapshot,
    g: GeometryHolder,
    paint: Paint,
    dp: Float,
) {
    if (snap.project.pips.isEmpty()) return
    val canvas = drawContext.canvas.nativeCanvas
    snap.project.pips.forEach { pip ->
        val layout = g.pips.firstOrNull { it.pip.id == pip.id } ?: return@forEach
        val laneRect = g.pipLaneRect(layout.lane)
        val top = laneRect.top + 2f * dp
        val height = laneRect.height - 4f * dp
        if (height <= 2f) return@forEach
        val selected = snap.selectedPipId == pip.id
        val missing = pip.assetId in snap.missingAssetIds
        val asset = snap.project.assetById(pip.assetId)

        val borderColor = when {
            missing -> DangerColor
            selected -> AccentColor
            else -> PipColor.copy(alpha = 0.7f)
        }

        drawRoundRect(
            color = PipColor.copy(alpha = 0.22f),
            topLeft = Offset(layout.left, top),
            size = Size(layout.width, height),
            cornerRadius = CornerRadius(4f * dp),
        )

        // The layer's little preview rectangle: a scaled-down frame hinting
        // the picture-in-picture geometry (position + size on the canvas).
        if (layout.width > 24f * dp) {
            val canvasAspect = (snap.project.width.toFloat() / snap.project.height).coerceIn(0.35f, 3f)
            val pipW = (layout.width - 8f * dp) * pip.scale.coerceIn(0.1f, 1f)
            val pipH = (pipW / canvasAspect).coerceAtMost(height - 8f * dp)
            if (pipW > 3f * dp && pipH > 2f * dp) {
                val cx = layout.left + 4f * dp + (layout.width - 8f * dp) * pip.xRatio
                val cy = top + height / 2f + (height / 2f - 2f * dp) * (pip.yRatio - 0.5f) * 1.4f
                drawRoundRect(
                    color = PipColor.copy(alpha = 0.5f),
                    topLeft = Offset(cx - pipW / 2f, cy - pipH / 2f),
                    size = Size(pipW, pipH),
                    cornerRadius = CornerRadius(2f * dp),
                )
                if (pip.opacity < 0.99f) {
                    drawRoundRect(
                        color = PipColor.copy(alpha = 0.2f + 0.3f * pip.opacity),
                        topLeft = Offset(cx - pipW / 2f, cy - pipH / 2f),
                        size = Size(pipW, pipH),
                        cornerRadius = CornerRadius(2f * dp),
                    )
                }
            }
        }

        drawRoundRect(
            color = borderColor,
            topLeft = Offset(layout.left, top),
            size = Size(layout.width, height),
            cornerRadius = CornerRadius(4f * dp),
            style = Stroke(width = if (selected) 2.dp.toPx() else 1.dp.toPx()),
        )

        paint.textAlign = android.graphics.Paint.Align.LEFT
        paint.textSize = 9f * dp
        paint.color = android.graphics.Color.argb(0xE0, 0x64, 0xB5, 0xF6)
        val prefix = when {
            asset == null -> "PiP ?"
            asset.isImage -> "贴纸"
            else -> "PiP"
        }
        val label = "$prefix ${asset?.displayName ?: ""}"
        val charW = max(1f, paint.measureText("PiP ") * 0.55f)
        val maxChars = max(1, ((layout.width - 8f * dp) / charW).toInt())
        canvas.drawText(label.take(maxChars), layout.left + 4f * dp, top + height - 4.5f * dp, paint)

        if (selected) {
            val handleH = height - 6f * dp
            drawRoundRect(
                color = AccentColor,
                topLeft = Offset(layout.left + 1f * dp, top + 3f * dp),
                size = Size(4f * dp, handleH),
                cornerRadius = CornerRadius(2f * dp),
            )
            drawRoundRect(
                color = AccentColor,
                topLeft = Offset(layout.left + layout.width - 5f * dp, top + 3f * dp),
                size = Size(4f * dp, handleH),
                cornerRadius = CornerRadius(2f * dp),
            )
        }
    }
}

private fun DrawScope.drawAudioLanes(
    snap: EditorViewModel.TimelineSnapshot,
    g: GeometryHolder,
    paint: Paint,
    dp: Float,
) {
    if (snap.project.audios.isEmpty()) return
    val canvas = drawContext.canvas.nativeCanvas
    snap.project.audios.forEach { audio ->
        val layout = g.audios.firstOrNull { it.clip.id == audio.id } ?: return@forEach
        val laneRect = g.audioLaneRect(layout.lane)
        val top = laneRect.top + 2f * dp
        val height = laneRect.height - 4f * dp
        if (height <= 2f) return@forEach
        val selected = snap.selectedAudioId == audio.id
        val missing = audio.assetId in snap.missingAssetIds
        val asset = snap.project.assetById(audio.assetId)

        val borderColor = when {
            missing -> DangerColor
            selected -> AccentColor
            else -> AudioColor.copy(alpha = 0.7f)
        }

        drawRoundRect(
            color = AudioColor.copy(alpha = 0.22f),
            topLeft = Offset(layout.left, top),
            size = Size(layout.width, height),
            cornerRadius = CornerRadius(4f * dp),
        )

        // pseudo waveform bars (decorative but instantly readable as audio)
        if (!missing && layout.width > 16f * dp) {
            val bars = (layout.width / (5f * dp)).toInt().coerceAtMost(80)
            if (bars > 2) {
                val barW = 2.2f * dp
                val gap = (layout.width - 6f * dp) / bars
                val waveTop = top + 3f * dp
                val waveH = height - 9f * dp
                for (b in 0 until bars) {
                    val phase = b * 0.9f
                    val amp = (0.35f + 0.3f * kotlin.math.sin(phase) + 0.2f * kotlin.math.sin(phase * 2.7f))
                        .coerceIn(0.15f, 1f)
                    val bh = waveH * amp
                    drawRoundRect(
                        color = AudioColor.copy(alpha = 0.55f),
                        topLeft = Offset(layout.left + 3f * dp + b * gap, waveTop + (waveH - bh) / 2f),
                        size = Size(barW, bh),
                        cornerRadius = CornerRadius(barW / 2f),
                    )
                }
            }
        }

        // volume strip + fades (same visual language as video clips)
        val stripTop = top + height - 6f * dp
        val stripHeight = 4f * dp
        drawRoundRect(
            color = Color(0x33000000),
            topLeft = Offset(layout.left + 3f * dp, stripTop),
            size = Size(max(0f, layout.width - 6f * dp), stripHeight),
            cornerRadius = CornerRadius(2f * dp),
        )
        if (!audio.muted) {
            val fill = (layout.width - 6f * dp) * audio.volume.coerceIn(0f, 1f)
            drawRoundRect(
                color = AudioColor.copy(alpha = 0.8f),
                topLeft = Offset(layout.left + 3f * dp, stripTop),
                size = Size(fill, stripHeight),
                cornerRadius = CornerRadius(2f * dp),
            )
        }

        drawRoundRect(
            color = borderColor,
            topLeft = Offset(layout.left, top),
            size = Size(layout.width, height),
            cornerRadius = CornerRadius(4f * dp),
            style = Stroke(width = if (selected) 2.dp.toPx() else 1.dp.toPx()),
        )

        // label
        paint.textAlign = android.graphics.Paint.Align.LEFT
        paint.textSize = 8.5f * dp
        paint.color = android.graphics.Color.argb(0xE0, 0x7F, 0xD9, 0xC9)
        val name = asset?.displayName?.substringBeforeLast('.')?.take(18) ?: "audio"
        val label = "A $name"
        val charW = max(1f, paint.measureText("A ") * 0.55f)
        val maxChars = max(1, ((layout.width - 8f * dp) / charW).toInt())
        canvas.drawText(label.take(maxChars), layout.left + 4f * dp, top + height - 8f * dp, paint)

        if (audio.muted) {
            drawBadge(canvas, paint, layout.left + 4f * dp, top + 2f * dp, "MUTE", DangerColor, dp)
        }

        // trim handles
        if (selected && layout.width > 24f * dp) {
            val handleH = height - 6f * dp
            drawRoundRect(
                color = AccentColor,
                topLeft = Offset(layout.left + 1f * dp, top + 3f * dp),
                size = Size(4f * dp, handleH),
                cornerRadius = CornerRadius(2f * dp),
            )
            drawRoundRect(
                color = AccentColor,
                topLeft = Offset(layout.left + layout.width - 5f * dp, top + 3f * dp),
                size = Size(4f * dp, handleH),
                cornerRadius = CornerRadius(2f * dp),
            )
        }

        // duration label
        if (layout.width > 70f * dp) {
            paint.textAlign = android.graphics.Paint.Align.RIGHT
            paint.color = android.graphics.Color.argb(0xB0, 0xFF, 0xFF, 0xFF)
            canvas.drawText(TimeCode.formatShort(layout.clip.durationUs), layout.left + layout.width - 6f * dp, top + height - 2.5f * dp, paint)
        }
    }
}

/** Teal accent used for the audio track. */
private val AudioColor = Color(0xFF2FB8A6)

/** PiP / sticker lane accent (soft blue, distinct from audio teal). */
private val PipColor = Color(0xFF64B5F6)

/** Crossfade transition accent (warm orange, v1.2.1). */
private val TransitionColor = Color(0xFFFFB74D)

/**
 * Transition marker on the overlap region (v1.2.1; v1.5.0 style label):
 * a dark pill straddling the boundary between the two clips with converging
 * triangles and — when there is room — the style's short label plus the
 * effective duration. Centered on the overlap's midpoint, which sits at
 * the visual seam between the two blocks.
 */
private fun DrawScope.drawTransitionMarker(
    layout: ClipLayout,
    ppsValue: Float,
    trackTop: Float,
    badgePaint: Paint,
    dp: Float,
    typeLabel: String,
) {
    val transitionPx = layout.transitionUs / 1_000_000f * ppsValue
    if (transitionPx <= 3f * dp) return
    val height = TimelineVerticalMath.TRACK_HEIGHT_DP * dp - 4f * dp
    val top = trackTop
    val cx = layout.left + transitionPx / 2f
    val cy = top + height / 2f

    // v1.5.0: wider cap — the label now carries the style short name plus
    // the duration ("叠化 0.5s").
    val pillW = minOf(transitionPx, 100f * dp).coerceAtLeast(22f * dp)
    val pillH = 15f * dp
    val pillTopLeft = Offset(cx - pillW / 2f, cy - pillH / 2f)
    drawRoundRect(
        color = Color(0xD9000000),
        topLeft = pillTopLeft,
        size = Size(pillW, pillH),
        cornerRadius = CornerRadius(pillH / 2f),
    )
    drawRoundRect(
        color = TransitionColor,
        topLeft = pillTopLeft,
        size = Size(pillW, pillH),
        cornerRadius = CornerRadius(pillH / 2f),
        style = Stroke(width = 1.dp.toPx()),
    )

    // Converging triangles glyph (◁ ▷ meeting = a transition) on the left
    // side of the pill; the style label + duration take the rest if roomy.
    val text = "$typeLabel ${TimeCode.formatShort(layout.transitionUs)}".trim()
    val hasText = pillW >= 60f * dp && text.isNotEmpty()
    val glyphCx = if (hasText) cx - pillW / 4f else cx
    val s = 4f * dp
    val gapGlyph = 2.5f * dp
    val path = Path().apply {
        moveTo(glyphCx - gapGlyph / 2f - s, cy)
        lineTo(glyphCx - gapGlyph / 2f, cy - s)
        lineTo(glyphCx - gapGlyph / 2f, cy + s)
        close()
        moveTo(glyphCx + gapGlyph / 2f + s, cy)
        lineTo(glyphCx + gapGlyph / 2f, cy - s)
        lineTo(glyphCx + gapGlyph / 2f, cy + s)
        close()
    }
    drawPath(path, TransitionColor)

    // Style short label + effective duration when the pill has room.
    if (hasText) {
        val canvas = drawContext.canvas.nativeCanvas
        badgePaint.color = android.graphics.Color.argb(0xF0, 0xFF, 0xFF, 0xFF)
        badgePaint.textAlign = android.graphics.Paint.Align.CENTER
        badgePaint.textSize = 9f * dp
        canvas.drawText(
            text,
            cx + pillW / 5f, cy + 3f * dp, badgePaint,
        )
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
