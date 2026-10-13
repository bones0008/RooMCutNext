package com.RooMCutNext.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.RooMCutNext.R
import com.RooMCutNext.core.model.PipLayer
import com.RooMCutNext.core.model.TextOverlayItem
import com.RooMCutNext.core.util.PipGestureMath
import com.RooMCutNext.core.util.TextGestureMath
import com.RooMCutNext.export.effects.TextOverlayGeometry
import com.RooMCutNext.ui.theme.AccentColor
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Direct manipulation of TEXT overlays and PiP LAYERS on the preview.
 *
 * Text (v1.3.0):
 *   tap a text      → select it (and open the TEXT panel when no panel is
 *                     open), draw the rotated selection frame
 *   drag (1 finger) → move — position follows the finger 1:1 at frame rate
 *   pinch (2 fingers) → scale the font size
 *   twist (2 fingers)  → rotate
 *   tap empty space → deselect
 *
 * PiP / sticker (v1.3.1):
 *   tap a layer     → select it (opens the PiP panel when nothing else is)
 *   drag (1 finger) → move the layer
 *   pinch (2 fingers) → scale the layer
 *   tap empty space → deselect
 *
 * Z-order parity with the renderers: PiP layers draw ABOVE the baked text
 * overlays (preview: overlay TextureViews sit on top of the PlayerView;
 * export: PiP sequences sit above the text-bearing body) — so PiPs are
 * hit-tested FIRST and, when overlapping a text, win the gesture.
 *
 * HOW everything tracks the finger at frame rate with ZERO player rebuilds:
 * the preview's baked TimedTextOverlays read the LIVE project state every
 * output frame (EditorViewModel sets liveTextLookup on both preview
 * engines), and the PiP views are re-positioned by OverlayPreviewController
 * on every state emission (place() mirrors PipPresentation). This layer
 * simply writes the dragged item into the ViewModel with
 * updateText/updatePip(commit = false) — the same live-preview path the
 * panels' sliders use. On finger-up ONE undoable commit is recorded; the
 * follow-up composition refresh is a structural no-op (TextSignatures and
 * the PiP windows signature both ignore visual fields), so gestures never
 * re-prepare the player.
 *
 * Hit-testing and the selection frames are computed with the SAME geometry
 * the renderers use (TextOverlayGeometry / PipGestureMath.displayRect), so
 * what you see framed is exactly what you grab.
 */
@Composable
fun BoxScope.PreviewGestureLayer(vm: EditorViewModel, state: EditorUiState, modifier: Modifier = Modifier) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            // Keyed on the view size only: the handler reads LIVE state via
            // vm.state.value so a recomposition mid-gesture must NOT restart
            // it (that would drop the drag session).
            .pointerInput(viewSize) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Something ON TOP (e.g. the grab-frame button) already
                    // claimed this touch — ignore it entirely.
                    if (down.isConsumed) return@awaitEachGesture

                    val s0 = vm.state.value
                    val proj = s0.project
                    val cw = proj.width.toFloat()
                    val ch = proj.height.toFloat()
                    if (cw <= 0f || ch <= 0f || viewSize == IntSize.Zero) return@awaitEachGesture
                    val rect = TextGestureMath.contentRect(
                        viewSize.width.toFloat(), viewSize.height.toFloat(), cw, ch,
                    )
                    val contentW = rect[2]
                    val contentH = rect[3]
                    if (contentW <= 0f || contentH <= 0f) return@awaitEachGesture

                    // ---- hit testing: PiPs FIRST (they render above texts) ----
                    // Later list entry = higher z (view add order AND export
                    // sequence order) → iterate reversed, first hit wins.
                    fun hitTestPip(pos: Offset): PipLayer? {
                        val s = vm.state.value
                        return s.project.pips
                            .filter { it.startUs <= s.playheadUs && s.playheadUs < it.endUs }
                            .reversed()
                            .firstOrNull { pip ->
                                val asset = s.project.assetById(pip.assetId)
                                asset != null && PipGestureMath.hitTest(
                                    pip, pos.x, pos.y,
                                    viewSize.width.toFloat(), viewSize.height.toFloat(),
                                    cw, ch, asset.width, asset.height,
                                )
                            }
                    }

                    fun hitTestText(pos: Offset): TextOverlayItem? {
                        val s = vm.state.value
                        val p = TextGestureMath.viewToCanvas(pos.x, pos.y, rect[0], rect[1], contentW, cw)
                        // Z-order parity with the renderer: texts are drawn
                        // sortedBy(startUs), later = on top → last hit wins.
                        return s.project.texts
                            .filter { it.overlaps(s.playheadUs) }
                            .sortedBy { it.startUs }
                            .lastOrNull {
                                TextOverlayGeometry.hitTest(it, proj.width, proj.height, p[0], p[1])
                            }
                    }

                    fun centroid(pressed: List<PointerInputChange>): Offset {
                        var x = 0f
                        var y = 0f
                        pressed.forEach { x += it.position.x; y += it.position.y }
                        return Offset(x / pressed.size, y / pressed.size)
                    }

                    fun twoFinger(pressed: List<PointerInputChange>): FloatArray? {
                        if (pressed.size < 2) return null
                        return TextGestureMath.twoFingerAnchors(
                            pressed[0].position.x, pressed[0].position.y,
                            pressed[1].position.x, pressed[1].position.y,
                        )
                    }

                    /** Fresh session for a target at the CURRENT touch state:
                     * centroid position + (when 2+ pointers) finger distance &
                     * angle. Mirrors the v1.3.0 text layer's anchoring exactly. */
                    fun newSession(target: GestureTarget, pressed: List<PointerInputChange>): GestureSession {
                        val c = centroid(pressed)
                        val a = twoFinger(pressed)
                        return GestureSession(target, c.x, c.y, a?.get(0) ?: 0f, a?.get(1) ?: 0f, pressed.size)
                    }

                    val hitPip = hitTestPip(down.position)
                    val hitText = if (hitPip == null) hitTestText(down.position) else null

                    if (hitPip != null) {
                        if (s0.selectedPipId != hitPip.id) {
                            vm.selectPip(hitPip.id)
                            // Open the PiP panel only when nothing else is
                            // open — never hijack an in-progress panel.
                            if (s0.activePanel == null) vm.openPanel(PanelKind.PIP)
                        }
                    } else if (hitText != null && s0.selectedTextId != hitText.id) {
                        vm.selectText(hitText.id)
                        // Open the TEXT panel only when nothing else is open.
                        if (s0.activePanel == null) vm.openPanel(PanelKind.TEXT)
                    }

                    var session = when {
                        hitPip != null -> newSession(GestureTarget.Pip(hitPip), listOf(down))
                        hitText != null -> newSession(GestureTarget.Text(hitText), listOf(down))
                        else -> null
                    }
                    var dirty = false
                    var travelled = 0f
                    val slop = viewConfiguration.touchSlop

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        // Finger added / removed → (re)anchor so the transform
                        // never jumps: the base re-captures the CURRENT item
                        // values and the touch anchors reset to now.
                        if (session == null) {
                            // A second finger landing anywhere starts a pinch on
                            // the SELECTED item (text OR PiP — standard editor
                            // behavior; selections are mutually exclusive).
                            if (pressed.size >= 2) {
                                val s = vm.state.value
                                val selPip = s.selectedPip
                                val selText = s.selectedText
                                session = when {
                                    selPip != null -> newSession(GestureTarget.Pip(selPip), pressed)
                                    selText != null -> newSession(GestureTarget.Text(selText), pressed)
                                    else -> null
                                }
                            }
                        } else if (pressed.size != session!!.pointers) {
                            val s = vm.state.value
                            val live = when (val t = session!!.target) {
                                is GestureTarget.Text ->
                                    s.project.textById(t.item.id)?.let { GestureTarget.Text(it) }
                                is GestureTarget.Pip ->
                                    s.project.pipById(t.layer.id)?.let { GestureTarget.Pip(it) }
                            }
                            session = live?.let { newSession(it, pressed) }
                        }

                        val sess = session
                        if (sess != null) {
                            val c = centroid(pressed)
                            travelled = maxOf(
                                travelled,
                                hypot(c.x - down.position.x, c.y - down.position.y),
                            )
                            // Anchor-relative transforms (no incremental drift).
                            when (val t = sess.target) {
                                is GestureTarget.Text -> {
                                    var item = TextGestureMath.pan(
                                        t.item, c.x - sess.anchorX, c.y - sess.anchorY, contentW, contentH,
                                    )
                                    if (pressed.size >= 2) {
                                        val a = twoFinger(pressed)
                                        if (a != null && sess.anchorDist > 0f) {
                                            item = TextGestureMath.zoom(item, a[0] / sess.anchorDist)
                                            item = TextGestureMath.rotate(item, a[1] - sess.anchorAngle)
                                        }
                                    }
                                    val current = vm.state.value.project.textById(t.item.id)
                                    if (current != null && item != current) {
                                        vm.updateText(item, commit = false)
                                        dirty = true
                                    }
                                }
                                is GestureTarget.Pip -> {
                                    var layer = PipGestureMath.pan(
                                        t.layer, c.x - sess.anchorX, c.y - sess.anchorY, contentW, contentH,
                                    )
                                    if (pressed.size >= 2) {
                                        val a = twoFinger(pressed)
                                        if (a != null && sess.anchorDist > 0f) {
                                            layer = PipGestureMath.zoom(layer, a[0] / sess.anchorDist)
                                        }
                                    }
                                    val current = vm.state.value.project.pipById(t.layer.id)
                                    if (current != null && layer != current) {
                                        vm.updatePip(layer, commit = false)
                                        dirty = true
                                    }
                                }
                            }
                            // Claim the motion while dragging so nothing under
                            // this layer reacts to it.
                            event.changes.forEach { it.consume() }
                        }
                    }

                    val sess = session
                    if (sess != null) {
                        if (dirty) {
                            when (val t = sess.target) {
                                is GestureTarget.Text -> {
                                    val live = vm.state.value.project.textById(t.item.id)
                                    if (live != null) {
                                        // Wrap into the slider's [-180, 180] domain
                                        // at commit; the wrap is visually a no-op.
                                        val wrapped =
                                            if (abs(live.rotationDeg) > 180f) {
                                                live.copy(rotationDeg = TextGestureMath.wrapDeg(live.rotationDeg))
                                            } else live
                                        vm.updateText(wrapped, commit = true)
                                    }
                                }
                                is GestureTarget.Pip -> {
                                    val live = vm.state.value.project.pipById(t.layer.id)
                                    if (live != null) vm.updatePip(live, commit = true)
                                }
                            }
                        }
                    } else if (hitPip == null && hitText == null && travelled < slop &&
                        vm.state.value.hasSelection
                    ) {
                        // Plain tap on empty preview → drop the selection.
                        vm.selectText(null)
                    }
                }
            },
    ) {
        TextSelectionFrame(state = state, viewSize = viewSize)
        PipSelectionFrame(state = state, viewSize = viewSize)
        GestureHint(state = state)
    }
}

/** What a gesture is dragging. */
private sealed class GestureTarget {
    data class Text(val item: TextOverlayItem) : GestureTarget()
    data class Pip(val layer: PipLayer) : GestureTarget()
}

/** Per-gesture anchor state: item values + touch anchors captured together. */
private class GestureSession(
    val target: GestureTarget,
    val anchorX: Float,
    val anchorY: Float,
    val anchorDist: Float,
    val anchorAngle: Float,
    val pointers: Int,
)

/** The rotated selection frame + corner handles for TEXT, in view coordinates. */
@Composable
private fun TextSelectionFrame(state: EditorUiState, viewSize: IntSize) {
    val item = state.selectedText
    if (item == null || !item.overlaps(state.playheadUs)) return
    if (viewSize == IntSize.Zero) return
    val proj = state.project
    if (proj.width <= 0 || proj.height <= 0) return

    Canvas(Modifier.fillMaxSize()) {
        val cw = proj.width.toFloat()
        val ch = proj.height.toFloat()
        val rect = TextGestureMath.contentRect(size.width, size.height, cw, ch)
        if (rect[2] <= 0f || rect[3] <= 0f) return@Canvas

        // Same layout the renderer paints — the frame hugs the real glyphs.
        val layout = TextOverlayGeometry.fillLayout(item, proj.width, proj.height)
        val viewPerCanvas = rect[2] / cw // view px per canvas px (uniform)
        val cx = rect[0] + item.xRatio * rect[2]
        val cy = rect[1] + item.yRatio * rect[3]
        val w = layout.width * viewPerCanvas
        val h = layout.height * viewPerCanvas

        val pad = 5.dp.toPx()
        val strokeW = 2.dp.toPx()
        val cornerR = 4.5.dp.toPx()
        val halfW = w / 2f + pad
        val halfH = h / 2f + pad

        rotate(degrees = item.rotationDeg, pivot = Offset(cx, cy)) {
            drawRect(
                color = Color.White,
                topLeft = Offset(cx - halfW, cy - halfH),
                size = Size(halfW * 2f, halfH * 2f),
                style = Stroke(width = strokeW),
            )
            listOf(
                Offset(cx - halfW, cy - halfH),
                Offset(cx + halfW, cy - halfH),
                Offset(cx - halfW, cy + halfH),
                Offset(cx + halfW, cy + halfH),
            ).forEach { corner ->
                drawCircle(color = AccentColor, radius = cornerR, center = corner)
            }
        }
    }
}

/** The axis-aligned selection frame + corner handles for a PiP layer. */
@Composable
private fun PipSelectionFrame(state: EditorUiState, viewSize: IntSize) {
    val pip = state.selectedPip ?: return
    if (pip.startUs > state.playheadUs || state.playheadUs >= pip.endUs) return
    if (viewSize == IntSize.Zero) return
    val proj = state.project
    if (proj.width <= 0 || proj.height <= 0) return
    val asset = proj.assetById(pip.assetId) ?: return

    Canvas(Modifier.fillMaxSize()) {
        val r = PipGestureMath.displayRect(
            pip, size.width, size.height,
            proj.width.toFloat(), proj.height.toFloat(), asset.width, asset.height,
        ) ?: return@Canvas

        val pad = 5.dp.toPx()
        val strokeW = 2.dp.toPx()
        val cornerR = 4.5.dp.toPx()
        val halfW = r[2] / 2f + pad
        val halfH = r[3] / 2f + pad
        val cx = r[0]
        val cy = r[1]

        // Same rect place() gives the view / PipPresentation draws on export.
        drawRect(
            color = Color.White,
            topLeft = Offset(cx - halfW, cy - halfH),
            size = Size(halfW * 2f, halfH * 2f),
            style = Stroke(width = strokeW),
        )
        listOf(
            Offset(cx - halfW, cy - halfH),
            Offset(cx + halfW, cy - halfH),
            Offset(cx - halfW, cy + halfH),
            Offset(cx + halfW, cy + halfH),
        ).forEach { corner ->
            drawCircle(color = AccentColor, radius = cornerR, center = corner)
        }
    }
}

/** Small translucent hint while a text or PiP is selected — discoverability. */
@Composable
private fun BoxScope.GestureHint(state: EditorUiState) {
    val item = state.selectedText
    val pip = state.selectedPip
    val textVisible = item != null && item.overlaps(state.playheadUs)
    val pipVisible = pip != null && pip.startUs <= state.playheadUs && state.playheadUs < pip.endUs
    if (!textVisible && !pipVisible) return
    Text(
        text = stringResource(
            if (pipVisible) R.string.preview_pip_gesture_hint
            else R.string.preview_text_gesture_hint
        ),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        maxLines = 1,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 10.dp)
            .background(Color(0x99000000), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}
