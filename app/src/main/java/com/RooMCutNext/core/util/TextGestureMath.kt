package com.RooMCutNext.core.util

import com.RooMCutNext.core.model.TextOverlayItem
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pure math for direct-manipulation text gestures on the preview (v1.3.0).
 *
 * NO Android imports — every rule here is unit-tested (TextGestureMathTest)
 * and mirrored by the Compose gesture layer (PreviewGestures.kt), which
 * owns the pointer bookkeeping and calls into these functions.
 *
 * Units & conventions (shared with the renderer — TimedTextOverlay):
 *  - Canvas coordinates: pixels of the PROJECT canvas (project.width x
 *    project.height). Text center = (xRatio * W, yRatio * H); the text block
 *    is rotated by rotationDeg around that center.
 *  - View coordinates: pixels of the preview composable. The video is
 *    letterboxed inside it exactly like AspectRatioFrameLayout
 *    RESIZE_MODE_FIT (see [contentRect]).
 *  - Slider-parity clamps: x/y 0.05..0.95, fontSizeRatio 0.03..0.25,
 *    rotation wrapped to [-180, 180] — the same bounds the TEXT panel
 *    sliders enforce, so gestures and sliders can never disagree.
 */
object TextGestureMath {

    const val X_MIN = 0.05f
    const val X_MAX = 0.95f
    const val Y_MIN = 0.05f
    const val Y_MAX = 0.95f
    const val SIZE_MIN = 0.03f
    const val SIZE_MAX = 0.25f

    // ------------------------------------------------------------------
    // View <-> canvas mapping (letterboxed RESIZE_MODE_FIT geometry)
    // ------------------------------------------------------------------

    /**
     * The content rect of an aspect-fit video inside a view, centered:
     * identical to AspectRatioFrameLayout RESIZE_MODE_FIT. Returns
     * (left, top, width, height) in view pixels.
     */
    fun contentRect(
        viewW: Float,
        viewH: Float,
        canvasW: Float,
        canvasH: Float,
    ): FloatArray {
        if (viewW <= 0f || viewH <= 0f || canvasW <= 0f || canvasH <= 0f) {
            return FloatArray(4)
        }
        val scale = kotlin.math.min(viewW / canvasW, viewH / canvasH)
        val w = canvasW * scale
        val h = canvasH * scale
        val left = (viewW - w) / 2f
        val top = (viewH - h) / 2f
        return floatArrayOf(left, top, w, h)
    }

    /** View-pixel point -> canvas-pixel point (letterbox offsets removed). NaN when degenerate. */
    fun viewToCanvas(
        viewX: Float,
        viewY: Float,
        contentLeft: Float,
        contentTop: Float,
        contentW: Float,
        canvasW: Float,
    ): FloatArray {
        if (contentW <= 0f || canvasW <= 0f) return floatArrayOf(Float.NaN, Float.NaN)
        // Uniform scale (aspect fit): canvas px per view px.
        val scale = canvasW / contentW
        return floatArrayOf((viewX - contentLeft) * scale, (viewY - contentTop) * scale)
    }

    // ------------------------------------------------------------------
    // Gesture transforms (anchor-relative, drift-free)
    // ------------------------------------------------------------------

    /**
     * Apply a drag delta (view px) to a text item captured at gesture start.
     * Deltas are expressed against the CONTENT rect so the finger and the
     * text move at the same speed regardless of letterboxing.
     */
    fun pan(start: TextOverlayItem, dxView: Float, dyView: Float, contentW: Float, contentH: Float): TextOverlayItem {
        if (contentW <= 0f || contentH <= 0f) return start
        val x = (start.xRatio + dxView / contentW).coerceIn(X_MIN, X_MAX)
        val y = (start.yRatio + dyView / contentH).coerceIn(Y_MIN, Y_MAX)
        if (x == start.xRatio && y == start.yRatio) return start
        return start.copy(xRatio = x, yRatio = y)
    }

    /** Apply a pinch scale to the font size captured at gesture start. */
    fun zoom(start: TextOverlayItem, scale: Float): TextOverlayItem {
        if (!scale.isFinite() || scale <= 0f) return start
        val size = (start.style.fontSizeRatio * scale).coerceIn(SIZE_MIN, SIZE_MAX)
        if (size == start.style.fontSizeRatio) return start
        return start.copy(style = start.style.copy(fontSizeRatio = size))
    }

    /** Apply a rotation delta (degrees) to the rotation captured at gesture start. */
    fun rotate(start: TextOverlayItem, deltaDeg: Float): TextOverlayItem {
        if (!deltaDeg.isFinite() || deltaDeg == 0f) return start
        return start.copy(rotationDeg = wrapDeg(start.rotationDeg + deltaDeg))
    }

    /** Wrap to [-180, 180]: the slider's domain, and visually identical. */
    fun wrapDeg(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    // ------------------------------------------------------------------
    // Hit testing (point in rotated rect)
    // ------------------------------------------------------------------

    /**
     * Is canvas point (px, py) inside the rotated text block centered at
     * (cx, cy) with half-extents (halfW, halfH) and rotation degrees?
     * The selection box and the gesture hit-test share this function, so
     * "what you see framed" is always "what you grab".
     */
    fun hitRotatedRect(
        px: Float,
        py: Float,
        cx: Float,
        cy: Float,
        halfW: Float,
        halfH: Float,
        rotationDeg: Float,
    ): Boolean {
        if (halfW <= 0f || halfH <= 0f || !px.isFinite() || !py.isFinite()) return false
        val dx = px - cx
        val dy = py - cy
        if (rotationDeg == 0f) return abs(dx) <= halfW && abs(dy) <= halfH
        // Inverse-rotate the delta into the rect's local frame (= forward
        // rotation by the NEGATED angle).
        val l = rotateOffset(dx, dy, -rotationDeg)
        return abs(l[0]) <= halfW && abs(l[1]) <= halfH
    }

    /**
     * FORWARD-rotate the offset (dx, dy) by rotationDeg around the origin —
     * the same matrix [Canvas.rotate] applies (screen/CW convention, y down).
     * The INVERSE rotation used by [hitRotatedRect] falls out of this by
     * negating the angle, so the two can never drift apart.
     *
     * v1.3.2 use: placing the confirm checkmark on the rotated bottom-right
     * corner of the text selection frame — the corner offset (halfW, halfH)
     * is rotated around the frame center exactly like the frame itself.
     */
    fun rotateOffset(dx: Float, dy: Float, rotationDeg: Float): FloatArray {
        if (!dx.isFinite() || !dy.isFinite()) return floatArrayOf(Float.NaN, Float.NaN)
        if (rotationDeg == 0f) return floatArrayOf(dx, dy)
        val rad = Math.toRadians(rotationDeg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return floatArrayOf(dx * c - dy * s, dx * s + dy * c)
    }

    // ------------------------------------------------------------------
    // Two-pointer anchors
    // ------------------------------------------------------------------

    /**
     * Angle (degrees, atan2 convention) and distance between two pointers.
     * Returns null unless both points are finite and distinct.
     */
    fun twoFingerAnchors(x0: Float, y0: Float, x1: Float, y1: Float): FloatArray? {
        if (!x0.isFinite() || !y0.isFinite() || !x1.isFinite() || !y1.isFinite()) return null
        val dist = hypot(x1 - x0, y1 - y0)
        if (dist < 1e-3f) return null
        val angle = Math.toDegrees(kotlin.math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
        return floatArrayOf(dist, angle)
    }
}
