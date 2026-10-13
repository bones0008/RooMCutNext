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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerId
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
 *
 * PiP / sticker (v1.3.1):
 *   tap a layer     → select it (opens the PiP panel when nothing else is)
 *   drag (1 finger) → move the layer
 *   pinch (2 fingers) → scale the layer
 *
 * v1.3.2 SELECTION LOCK + CONFIRM CHIP:
 *   While an element is selected AND visible at the playhead, it owns the
 *   whole canvas — the first finger anywhere starts a gesture on THAT
 *   element, extra fingers join it, and NOTHING can switch the selection
 *   mid-gesture (the v1.3.1 bug: during a pinch, a stray finger landing on
 *   another text/PiP instantly stole the selection). The only ways out:
 *     - tap the CONFIRM CHIP (checkmark at the frame's bottom-right corner):
 *       keep the placed position, drop the selection AND close the tool
 *       panel (v1.3.3 — the editor returns to the standard timeline view);
 *     - a still tap that began OUTSIDE the frame (empty space or another
 *       element): drops the selection without ever switching to whatever
 *       else was under the finger (also closes the panel, v1.3.3).
 *
 * v1.3.3 CORNER-HANDLE DRAG + LIVE REDRAW:
 *   The three corner handles of a selection frame are now DRAGGABLE: for a
 *   TEXT they scale AND rotate the block about its center (sticker gesture
 *   — the frame's size and angle follow the finger, and so does the baked
 *   text); for a PiP they scale the layer about its center. Only the finger
 *   that grabbed the handle drives it — extra fingers are claimed but
 *   ignored, so a stray thumb can neither re-anchor nor steal it. This rides
 *   the same live-preview path as pinch: updateText/updatePip(commit=false)
 *   plus the nudge, which now replays the last frame through the effects
 *   graph via CompositionPlayer.experimentalRedrawLastFrame (see
 *   PlaybackController) — the v1.3.2 same-position seek never actually
 *   rendered on devices, which is why text edits while paused used to stay
 *   invisible until the user pressed play.
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
 * panels' sliders use. While PAUSED the engines are nudged to re-render the
 * current frame (see PlaybackController.nudgeRedraw) — a paused player
 * produces no output frames, which is why v1.3.1 needed a play-pause cycle
 * to see a scaled text. On finger-up ONE undoable commit is recorded; the
 * follow-up composition refresh is a structural no-op (TextSignatures and
 * the PiP windows signature both ignore visual fields), so gestures never
 * re-prepare the player.
 *
 * Hit-testing and the selection frames are computed with the SAME geometry
 * the renderers use (TextOverlayGeometry / PipGestureMath.displayRect), so
 * what you see framed is exactly what you grab. v1.3.2: the text frame hugs
 * the INK (widest line via TextOverlayGeometry.blockWidth), not the
 * 0.92-width layout constraint the old frame (and its hit region) used.
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
                    val viewW = viewSize.width.toFloat()
                    val viewH = viewSize.height.toFloat()
                    val rect = TextGestureMath.contentRect(viewW, viewH, cw, ch)
                    val contentW = rect[2]
                    val contentH = rect[3]
                    if (contentW <= 0f || contentH <= 0f) return@awaitEachGesture

                    val framePad = 5.dp.toPx()
                    // Generous touch target around the drawn 13dp chip —
                    // matches Material's ~48dp interactive minimum closely
                    // enough without covering half a small frame.
                    val checkHitR = 21.dp.toPx()
                    // v1.3.3: grab radius for the DRAGGABLE corner handles
                    // (drawn 4.5dp circles). Checked AFTER the chip.
                    val cornerHitR = 24.dp.toPx()

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
                                    viewW, viewH,
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

                    // ---- v1.3.2 SELECTION LOCK ----
                    // A selected element that is VISIBLE at the playhead owns
                    // the canvas until deselected: no hit-test re-routing, so
                    // a stray finger landing on another text/PiP mid-pinch can
                    // never steal the gesture (selections are also mutually
                    // exclusive — a text lock wins only when no PiP is locked).
                    val lockedPip = s0.selectedPip
                        ?.takeIf { s0.playheadUs >= it.startUs && s0.playheadUs < it.endUs }
                    val lockedText = if (lockedPip == null) {
                        s0.selectedText?.takeIf { it.overlaps(s0.playheadUs) }
                    } else null
                    val lockedFrame = when {
                        lockedPip != null -> proj.assetById(lockedPip.assetId)?.let { a ->
                            pipSelectionFrame(
                                lockedPip, viewW, viewH, proj.width, proj.height,
                                a.width, a.height, framePad,
                            )
                        }
                        lockedText != null -> textSelectionFrame(
                            lockedText, viewW, viewH, proj.width, proj.height, framePad,
                        )
                        else -> null
                    }

                    var session: GestureSession? = null
                    var dirty = false
                    var travelled = 0f
                    // True when the first finger landed ON the element that
                    // owns the session (locked frame or freshly hit) — a still
                    // tap like that must KEEP the selection.
                    var startedOnTarget = false
                    // True when the first finger landed on the confirm chip.
                    var tappedCheck = false
                    val slop = viewConfiguration.touchSlop

                    if (lockedFrame != null) {
                        val chip = lockedFrame.checkCorner()
                        val onChip = hypot(down.position.x - chip.x, down.position.y - chip.y) <= checkHitR
                        if (onChip) {
                            tappedCheck = true
                        } else {
                            // v1.3.3: down on a DRAGGABLE corner handle →
                            // corner-drag session (text: scale+rotate about
                            // the center; PiP: scale). The bottom-right seat
                            // is the confirm chip and never starts a drag.
                            // A corner tap counts as ON-target even when the
                            // handle circle spills outside a tiny frame.
                            val cornerOff = lockedFrame.nearestCornerOffset(down.position, cornerHitR)
                            startedOnTarget = cornerOff != null || TextGestureMath.hitRotatedRect(
                                down.position.x, down.position.y,
                                lockedFrame.cx, lockedFrame.cy,
                                lockedFrame.halfW, lockedFrame.halfH,
                                lockedFrame.rotationDeg,
                            )
                            val target = when {
                                lockedPip != null -> GestureTarget.Pip(lockedPip)
                                else -> GestureTarget.Text(lockedText!!)
                            }
                            session = if (cornerOff != null) {
                                GestureSession(
                                    target = target,
                                    anchorX = down.position.x,
                                    anchorY = down.position.y,
                                    anchorDist = 0f,
                                    anchorAngle = 0f,
                                    pointers = 1,
                                    corner = CornerAnchor(
                                        centerX = lockedFrame.cx,
                                        centerY = lockedFrame.cy,
                                        baseX = cornerOff[0],
                                        baseY = cornerOff[1],
                                        pointerId = down.id,
                                    ),
                                )
                            } else {
                                newSession(target, listOf(down))
                            }
                        }
                    } else {
                        // ---- nothing selected (or the selection is not at
                        // the playhead): free hit-testing as before ----
                        val hitPip = hitTestPip(down.position)
                        val hitText = if (hitPip == null) hitTestText(down.position) else null

                        if (hitPip != null) {
                            startedOnTarget = true
                            if (s0.selectedPipId != hitPip.id) {
                                vm.selectPip(hitPip.id)
                                // Open the PiP panel only when nothing else is
                                // open — never hijack an in-progress panel.
                                if (s0.activePanel == null) vm.openPanel(PanelKind.PIP)
                            }
                            session = newSession(GestureTarget.Pip(hitPip), listOf(down))
                        } else if (hitText != null) {
                            startedOnTarget = true
                            if (s0.selectedTextId != hitText.id) {
                                vm.selectText(hitText.id)
                                // Open the TEXT panel only when nothing else is open.
                                if (s0.activePanel == null) vm.openPanel(PanelKind.TEXT)
                            }
                            session = newSession(GestureTarget.Text(hitText), listOf(down))
                        }
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        // Finger added / removed → (re)anchor so the transform
                        // never jumps: the base re-captures the CURRENT item
                        // values and the touch anchors reset to now.
                        // v1.3.3: corner drags NEVER re-anchor — only the
                        // finger that grabbed the handle drives them, and
                        // handing them to a centroid mid-twist would jump.
                        if (session == null) {
                            // A second finger landing anywhere starts a pinch on
                            // the SELECTED item — but only one VISIBLE at the
                            // playhead (v1.3.2: pinching an invisible selection
                            // through empty space was a silent foot-gun).
                            if (pressed.size >= 2) {
                                val s = vm.state.value
                                val selPip = s.selectedPip
                                    ?.takeIf { s.playheadUs >= it.startUs && s.playheadUs < it.endUs }
                                val selText = if (selPip == null) {
                                    s.selectedText?.takeIf { it.overlaps(s.playheadUs) }
                                } else null
                                session = when {
                                    selPip != null -> newSession(GestureTarget.Pip(selPip), pressed)
                                    selText != null -> newSession(GestureTarget.Text(selText), pressed)
                                    else -> null
                                }
                            }
                        } else if (session!!.corner == null && pressed.size != session!!.pointers) {
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
                            val corner = sess.corner
                            if (corner != null) {
                                // ---- v1.3.3 CORNER-HANDLE DRAG ----
                                // Only the finger that grabbed the handle
                                // drives it; extra fingers are claimed but
                                // ignored. When the grabbing finger lifts,
                                // the gesture ends here — the post-loop code
                                // commits and the still-tap rules see a
                                // dirty drag, exactly like a pan/pinch.
                                val tracked = pressed.firstOrNull { it.id == corner.pointerId }
                                if (tracked == null) {
                                    break
                                }
                                travelled = maxOf(
                                    travelled,
                                    hypot(tracked.position.x - down.position.x, tracked.position.y - down.position.y),
                                )
                                val v1x = tracked.position.x - corner.centerX
                                val v1y = tracked.position.y - corner.centerY
                                when (val t = sess.target) {
                                    is GestureTarget.Text -> {
                                        val item = TextGestureMath.cornerDrag(
                                            t.item, corner.baseX, corner.baseY, v1x, v1y,
                                        )
                                        val current = vm.state.value.project.textById(t.item.id)
                                        if (current != null && item != current) {
                                            vm.updateText(item, commit = false)
                                            dirty = true
                                        }
                                    }
                                    is GestureTarget.Pip -> {
                                        val layer = PipGestureMath.cornerZoom(
                                            t.layer, corner.baseX, corner.baseY, v1x, v1y,
                                        )
                                        val current = vm.state.value.project.pipById(t.layer.id)
                                        if (current != null && layer != current) {
                                            vm.updatePip(layer, commit = false)
                                            dirty = true
                                        }
                                    }
                                }
                                event.changes.forEach { it.consume() }
                            } else {
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
                    }

                    val sess = session
                    if (sess != null && dirty) {
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

                    if (tappedCheck) {
                        // Confirm chip: a STILL, non-editing tap keeps the placed
                        // position and drops the selection so another element
                        // may be picked next. v1.3.3: also CLOSES the inline
                        // tool panel — the editor returns to the standard
                        // timeline view (the v1.3.2 bare deselect left the
                        // TEXT/PIP tool open, and the text panel then claimed
                        // "text deleted"). (Chip touch followed by an actual
                        // pinch already committed above — keep the selection.)
                        if (!dirty && travelled < slop) vm.confirmPreviewPlacement()
                    } else if (sess != null) {
                        if (!dirty && travelled < slop && !startedOnTarget) {
                            // v1.3.2: a still tap that began OUTSIDE the locked
                            // element's frame (empty space or another element)
                            // drops the selection — it never switches to what
                            // else was under the finger. v1.3.3: panel closes
                            // too (same confirm semantics as the chip).
                            vm.confirmPreviewPlacement()
                        }
                    } else if (travelled < slop && vm.state.value.hasSelection) {
                        // No lock was active and nothing was hit: plain tap on
                        // empty preview → drop any (possibly playhead-invisible)
                        // selection. v1.3.3: panel closes with it.
                        vm.confirmPreviewPlacement()
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
    /**
     * v1.3.3: non-null when the gesture started on a DRAGGABLE corner
     * handle — corner-drag semantics replace the centroid pan/pinch math.
     */
    val corner: CornerAnchor? = null,
)

/**
 * v1.3.3 corner-drag anchor: the frame center (pinned for the whole drag —
 * corner drags never pan), the grabbed corner's vector from that center at
 * gesture start, and the pointer id of the finger that grabbed it.
 */
private class CornerAnchor(
    val centerX: Float,
    val centerY: Float,
    val baseX: Float,
    val baseY: Float,
    val pointerId: PointerId,
)

/**
 * View-space geometry of a selection frame — the SINGLE source shared by the
 * frame drawing, the confirm-chip seat and the gesture hit-testing, so the
 * three can never drift apart. halfW/halfH INCLUDE the frame padding.
 */
private class SelectionFrame(
    val cx: Float,
    val cy: Float,
    val halfW: Float,
    val halfH: Float,
    val rotationDeg: Float,
) {
    /** The bottom-right corner in view coordinates — the confirm chip's seat. */
    fun checkCorner(): Offset {
        val r = TextGestureMath.rotateOffset(halfW, halfH, rotationDeg)
        return Offset(cx + r[0], cy + r[1])
    }

    /**
     * v1.3.3: the three DRAGGABLE corners (top-left, top-right, bottom-left
     * — bottom-right is the confirm chip's seat), as absolute view-space
     * positions with the frame rotation applied.
     */
    fun cornerPoints(): List<Offset> {
        val tl = TextGestureMath.rotateOffset(-halfW, -halfH, rotationDeg)
        val tr = TextGestureMath.rotateOffset(halfW, -halfH, rotationDeg)
        val bl = TextGestureMath.rotateOffset(-halfW, halfH, rotationDeg)
        return listOf(
            Offset(cx + tl[0], cy + tl[1]),
            Offset(cx + tr[0], cy + tr[1]),
            Offset(cx + bl[0], cy + bl[1]),
        )
    }

    /**
     * v1.3.3: the closest draggable corner within [radius] of [p], returned
     * as the corner's OFFSET from the frame center — exactly the base vector
     * a corner-drag session anchors against. Null when nothing is in reach.
     */
    fun nearestCornerOffset(p: Offset, radius: Float): FloatArray? {
        if (!p.x.isFinite() || !p.y.isFinite() || radius <= 0f) return null
        var best: FloatArray? = null
        var bestD = radius
        for (pt in cornerPoints()) {
            val d = hypot(p.x - pt.x, p.y - pt.y)
            if (d <= bestD) {
                bestD = d
                best = floatArrayOf(pt.x - cx, pt.y - cy)
            }
        }
        return best
    }
}

/**
 * Text frame geometry: the block the RENDERER paints, mapped to view space.
 * v1.3.2: hugs the widest LINE (the ink), not the 0.92 layout constraint —
 * floored at 0.75 × block height so blank/near-empty text (editable via the
 * panel) still shows a grabbable frame with its confirm chip.
 */
private fun textSelectionFrame(
    item: TextOverlayItem,
    viewW: Float,
    viewH: Float,
    canvasW: Int,
    canvasH: Int,
    padPx: Float,
): SelectionFrame? {
    if (viewW <= 0f || viewH <= 0f || canvasW <= 0 || canvasH <= 0) return null
    val cw = canvasW.toFloat()
    val ch = canvasH.toFloat()
    val rect = TextGestureMath.contentRect(viewW, viewH, cw, ch)
    if (rect[2] <= 0f || rect[3] <= 0f) return null
    val layout = TextOverlayGeometry.fillLayout(item, canvasW, canvasH)
    val viewPerCanvas = rect[2] / cw // view px per canvas px (uniform)
    val cx = rect[0] + item.xRatio * rect[2]
    val cy = rect[1] + item.yRatio * rect[3]
    val h = layout.height * viewPerCanvas
    val w = maxOf(TextOverlayGeometry.blockWidth(layout) * viewPerCanvas, h * 0.75f)
    return SelectionFrame(cx, cy, w / 2f + padPx, h / 2f + padPx, item.rotationDeg)
}

/** PiP frame geometry: the exact display rect place()/PipPresentation draw. */
private fun pipSelectionFrame(
    pip: PipLayer,
    viewW: Float,
    viewH: Float,
    canvasW: Int,
    canvasH: Int,
    assetW: Int,
    assetH: Int,
    padPx: Float,
): SelectionFrame? {
    val r = PipGestureMath.displayRect(
        pip, viewW, viewH,
        canvasW.toFloat(), canvasH.toFloat(), assetW, assetH,
    ) ?: return null
    return SelectionFrame(r[0], r[1], r[2] / 2f + padPx, r[3] / 2f + padPx, 0f)
}

/**
 * v1.3.2: the confirm chip — a filled accent circle with a white check
 * glyph, seated on the frame's bottom-right corner. Tapping it (see the
 * gesture layer's generous [checkHitR]) commits the placed position and
 * drops the selection.
 */
private fun DrawScope.drawConfirmChip(center: Offset, radius: Float) {
    if (radius <= 0f || !center.x.isFinite() || !center.y.isFinite()) return
    // Dark scrim under the chip so it stays legible over bright video.
    drawCircle(color = Color(0x66000000), radius = radius + 2.dp.toPx(), center = center)
    drawCircle(color = AccentColor, radius = radius, center = center)
    drawCircle(color = Color.White, radius = radius, center = center, style = Stroke(width = 1.5.dp.toPx()))
    val check = Path().apply {
        moveTo(center.x - radius * 0.42f, center.y + radius * 0.02f)
        lineTo(center.x - radius * 0.10f, center.y + radius * 0.36f)
        lineTo(center.x + radius * 0.48f, center.y - radius * 0.34f)
    }
    drawPath(
        check,
        color = Color.White,
        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** The rotated selection frame + corner handles + confirm chip for TEXT. */
@Composable
private fun TextSelectionFrame(state: EditorUiState, viewSize: IntSize) {
    val item = state.selectedText
    if (item == null || !item.overlaps(state.playheadUs)) return
    if (viewSize == IntSize.Zero) return
    val proj = state.project
    if (proj.width <= 0 || proj.height <= 0) return

    Canvas(Modifier.fillMaxSize()) {
        val frame = textSelectionFrame(
            item, size.width, size.height, proj.width, proj.height, 5.dp.toPx(),
        ) ?: return@Canvas

        val strokeW = 2.dp.toPx()
        val cornerR = 4.5.dp.toPx()

        rotate(degrees = frame.rotationDeg, pivot = Offset(frame.cx, frame.cy)) {
            drawRect(
                color = Color.White,
                topLeft = Offset(frame.cx - frame.halfW, frame.cy - frame.halfH),
                size = Size(frame.halfW * 2f, frame.halfH * 2f),
                style = Stroke(width = strokeW),
            )
            listOf(
                Offset(frame.cx - frame.halfW, frame.cy - frame.halfH),
                Offset(frame.cx + frame.halfW, frame.cy - frame.halfH),
                Offset(frame.cx - frame.halfW, frame.cy + frame.halfH),
                // bottom-right seat is the confirm chip, not a plain handle
            ).forEach { corner ->
                drawCircle(color = AccentColor, radius = cornerR, center = corner)
            }
            drawConfirmChip(
                center = Offset(frame.cx + frame.halfW, frame.cy + frame.halfH),
                radius = 13.dp.toPx(),
            )
        }
    }
}

/** The axis-aligned selection frame + handles + confirm chip for a PiP layer. */
@Composable
private fun PipSelectionFrame(state: EditorUiState, viewSize: IntSize) {
    val pip = state.selectedPip ?: return
    if (pip.startUs > state.playheadUs || state.playheadUs >= pip.endUs) return
    if (viewSize == IntSize.Zero) return
    val proj = state.project
    if (proj.width <= 0 || proj.height <= 0) return
    val asset = proj.assetById(pip.assetId) ?: return

    Canvas(Modifier.fillMaxSize()) {
        val frame = pipSelectionFrame(
            pip, size.width, size.height, proj.width, proj.height,
            asset.width, asset.height, 5.dp.toPx(),
        ) ?: return@Canvas

        val strokeW = 2.dp.toPx()
        val cornerR = 4.5.dp.toPx()

        // Same rect place() gives the view / PipPresentation draws on export.
        drawRect(
            color = Color.White,
            topLeft = Offset(frame.cx - frame.halfW, frame.cy - frame.halfH),
            size = Size(frame.halfW * 2f, frame.halfH * 2f),
            style = Stroke(width = strokeW),
        )
        listOf(
            Offset(frame.cx - frame.halfW, frame.cy - frame.halfH),
            Offset(frame.cx + frame.halfW, frame.cy - frame.halfH),
            Offset(frame.cx - frame.halfW, frame.cy + frame.halfH),
            // bottom-right seat is the confirm chip, not a plain handle
        ).forEach { corner ->
            drawCircle(color = AccentColor, radius = cornerR, center = corner)
        }
        drawConfirmChip(
            center = Offset(frame.cx + frame.halfW, frame.cy + frame.halfH),
            radius = 13.dp.toPx(),
        )
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
