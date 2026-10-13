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
import com.RooMCutNext.core.model.TextOverlayItem
import com.RooMCutNext.core.util.TextGestureMath
import com.RooMCutNext.export.effects.TextOverlayGeometry
import com.RooMCutNext.ui.theme.AccentColor
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Direct manipulation of text overlays on the PREVIEW (v1.3.0).
 *
 *   tap a text      → select it (and open the TEXT panel when no panel is
 *                     open), draw the rotated selection frame
 *   drag (1 finger) → move — position follows the finger 1:1 at frame rate
 *   pinch (2 fingers) → scale the font size
 *   twist (2 fingers)  → rotate
 *   tap empty space → deselect
 *
 * HOW the text tracks the finger at frame rate with ZERO player rebuilds:
 * the preview's baked TimedTextOverlays read the LIVE project state every
 * output frame (EditorViewModel sets liveTextLookup on both preview
 * engines). This layer simply writes the dragged item into the ViewModel
 * with updateText(commit = false) — the same live-preview path the TEXT
 * panel sliders use — and the GL pipeline renders it on the next frame.
 * On finger-up ONE undoable commit is recorded; the follow-up composition
 * refresh is a structural no-op (TextSignatures ignores visual fields), so
 * gestures never re-prepare the player.
 *
 * Hit-testing and the selection frame are computed with the SAME geometry
 * the renderer uses (TextOverlayGeometry — shared StaticLayout builders),
 * so what you see framed is exactly what you grab.
 */
@Composable
fun BoxScope.TextGestureLayer(vm: EditorViewModel, state: EditorUiState, modifier: Modifier = Modifier) {
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

                    fun hitTest(pos: Offset): TextOverlayItem? {
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

                    val hit = hitTest(down.position)
                    if (hit != null && s0.selectedTextId != hit.id) {
                        vm.selectText(hit.id)
                        // Open the TEXT panel only when nothing else is open —
                        // never hijack an in-progress panel (PIP / EXPORT / …).
                        if (s0.activePanel == null) vm.openPanel(PanelKind.TEXT)
                    }

                    var session = hit?.let {
                        TextDragSession(it.id, it, down.position.x, down.position.y, 0f, 0f, 1)
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
                            // the SELECTED text (standard editor behavior).
                            val sel = vm.state.value.selectedText
                            if (sel != null && pressed.size >= 2) {
                                val c = centroid(pressed)
                                val a = twoFinger(pressed)
                                session = TextDragSession(
                                    sel.id, sel, c.x, c.y, a?.get(0) ?: 0f, a?.get(1) ?: 0f, pressed.size,
                                )
                            }
                        } else if (pressed.size != session!!.pointers) {
                            val live = vm.state.value.project.textById(session!!.itemId)
                            if (live == null) {
                                session = null
                            } else {
                                val c = centroid(pressed)
                                val a = twoFinger(pressed)
                                session = TextDragSession(
                                    live.id, live, c.x, c.y, a?.get(0) ?: 0f, a?.get(1) ?: 0f, pressed.size,
                                )
                            }
                        }

                        val sess = session
                        if (sess != null) {
                            val c = centroid(pressed)
                            travelled = maxOf(
                                travelled,
                                hypot(c.x - down.position.x, c.y - down.position.y),
                            )
                            // Anchor-relative transforms (no incremental drift).
                            var item = TextGestureMath.pan(
                                sess.base, c.x - sess.anchorX, c.y - sess.anchorY, contentW, contentH,
                            )
                            if (pressed.size >= 2) {
                                val a = twoFinger(pressed)
                                if (a != null && sess.anchorDist > 0f) {
                                    item = TextGestureMath.zoom(item, a[0] / sess.anchorDist)
                                    item = TextGestureMath.rotate(item, a[1] - sess.anchorAngle)
                                }
                            }
                            val current = vm.state.value.project.textById(sess.itemId)
                            if (current != null && item != current) {
                                vm.updateText(item, commit = false)
                                dirty = true
                            }
                            // Claim the motion while dragging so nothing under
                            // this layer reacts to it.
                            event.changes.forEach { it.consume() }
                        }
                    }

                    val sess = session
                    if (sess != null) {
                        if (dirty) {
                            val live = vm.state.value.project.textById(sess.itemId)
                            if (live != null) {
                                // Wrap into the slider's [-180, 180] domain at
                                // commit; the wrap is visually a no-op.
                                val wrapped =
                                    if (abs(live.rotationDeg) > 180f) {
                                        live.copy(rotationDeg = TextGestureMath.wrapDeg(live.rotationDeg))
                                    } else live
                                vm.updateText(wrapped, commit = true)
                            }
                        }
                    } else if (hit == null && travelled < slop && vm.state.value.selectedTextId != null) {
                        // Plain tap on empty preview → drop the selection.
                        vm.selectText(null)
                    }
                }
            },
    ) {
        SelectionFrame(state = state, viewSize = viewSize)
        GestureHint(state = state)
    }
}

/** Per-gesture anchor state: item values + touch anchors captured together. */
private class TextDragSession(
    val itemId: String,
    val base: TextOverlayItem,
    val anchorX: Float,
    val anchorY: Float,
    val anchorDist: Float,
    val anchorAngle: Float,
    val pointers: Int,
)

/** The rotated selection frame + corner handles, in view coordinates. */
@Composable
private fun SelectionFrame(state: EditorUiState, viewSize: IntSize) {
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

/** Small translucent hint while a text is selected — gesture discoverability. */
@Composable
private fun BoxScope.GestureHint(state: EditorUiState) {
    val item = state.selectedText ?: return
    if (!item.overlaps(state.playheadUs)) return
    Text(
        text = stringResource(R.string.preview_text_gesture_hint),
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
