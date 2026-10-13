package com.RooMCutNext.core.util

import com.RooMCutNext.core.model.PipLayer

/**
 * Pure math for direct-manipulation PiP gestures on the preview (v1.3.1).
 *
 * Mirrors the TextGestureMath pattern: NO Android imports, every rule is
 * unit-tested (PipGestureMathTest) and the Compose gesture layer
 * (PreviewGestures.kt) owns only the pointer bookkeeping.
 *
 * Geometry contract (identical to OverlayPreviewController.place() and the
 * export's PipPresentation — the WYSIWYG invariant):
 *  - content rect = aspect-fit letterbox of the project canvas inside the
 *    preview view (same as TextGestureMath.contentRect);
 *  - the PiP DISPLAY WIDTH is scale × content-rect width;
 *  - display height = display width / input aspect, where the input aspect
 *    is the ASSET's width/height (PiP layers carry no rotation/crop);
 *  - the layer CENTER sits at (xRatio × contentW, yRatio × contentH) from
 *    the content rect's top-left, y pointing DOWN.
 *
 * Slider-parity clamps: x/y 0.05..0.95, scale 0.1..1.0 — the same bounds
 * the PiP panel sliders enforce, so gestures and sliders can never disagree.
 */
object PipGestureMath {

    const val X_MIN = 0.05f
    const val X_MAX = 0.95f
    const val Y_MIN = 0.05f
    const val Y_MAX = 0.95f
    const val SCALE_MIN = 0.1f
    const val SCALE_MAX = 1f

    // ------------------------------------------------------------------
    // Gesture transforms (anchor-relative, drift-free)
    // ------------------------------------------------------------------

    /**
     * Apply a drag delta (view px) to a layer captured at gesture start.
     * Deltas are divided by the CONTENT rect so the finger and the layer
     * move at the same speed regardless of letterboxing.
     */
    fun pan(start: PipLayer, dxView: Float, dyView: Float, contentW: Float, contentH: Float): PipLayer {
        if (contentW <= 0f || contentH <= 0f) return start
        if (!dxView.isFinite() || !dyView.isFinite()) return start
        val x = (start.xRatio + dxView / contentW).coerceIn(X_MIN, X_MAX)
        val y = (start.yRatio + dyView / contentH).coerceIn(Y_MIN, Y_MAX)
        if (x == start.xRatio && y == start.yRatio) return start
        return start.copy(xRatio = x, yRatio = y)
    }

    /** Apply a pinch factor to the layer scale captured at gesture start. */
    fun zoom(start: PipLayer, factor: Float): PipLayer {
        if (!factor.isFinite() || factor <= 0f) return start
        val s = (start.scale * factor).coerceIn(SCALE_MIN, SCALE_MAX)
        if (s == start.scale) return start
        return start.copy(scale = s)
    }

    /**
     * v1.3.3 CORNER-HANDLE drag: scale-only variant for PiP layers (they
     * carry no rotation). (v0x, v0y) is the grabbed corner's vector from the
     * display-rect center at gesture start; (v1x, v1y) is the finger's
     * vector now. The display rect is CENTERED on (xRatio, yRatio), so the
     * layer grows/shrinks in place — same affordance as the text frame's
     * corner handles, minus the twist.
     */
    fun cornerZoom(
        start: PipLayer,
        v0x: Float,
        v0y: Float,
        v1x: Float,
        v1y: Float,
    ): PipLayer {
        if (!v0x.isFinite() || !v0y.isFinite() || !v1x.isFinite() || !v1y.isFinite()) return start
        val d0 = kotlin.math.hypot(v0x, v0y)
        val d1 = kotlin.math.hypot(v1x, v1y)
        if (d0 < 1e-3f) return start
        return zoom(start, d1 / d0)
    }

    // ------------------------------------------------------------------
    // Hit testing (axis-aligned display rect in VIEW coordinates)
    // ------------------------------------------------------------------

    /**
     * The layer's display rect in view coordinates: (cx, cy, w, h) — center
     * and full size. Null when the geometry is degenerate. The rect is the
     * exact bounds the preview TextureView/ImageView occupies (place()) and
     * the exact quad the export's PipPresentation draws, so hit-testing and
     * the selection frame always match what is on screen.
     */
    fun displayRect(
        pip: PipLayer,
        viewW: Float,
        viewH: Float,
        canvasW: Float,
        canvasH: Float,
        assetW: Int,
        assetH: Int,
    ): FloatArray? {
        if (viewW <= 0f || viewH <= 0f || canvasW <= 0f || canvasH <= 0f) return null
        if (assetW <= 0 || assetH <= 0) return null
        val rect = TextGestureMath.contentRect(viewW, viewH, canvasW, canvasH)
        val contentW = rect[2]
        val contentH = rect[3]
        if (contentW <= 0f || contentH <= 0f) return null
        val aspect = assetW.toFloat() / assetH
        val w = pip.scale.coerceIn(0.02f, 1f) * contentW
        val h = w / aspect.coerceAtLeast(0.01f)
        val cx = rect[0] + pip.xRatio.coerceIn(0f, 1f) * contentW
        val cy = rect[1] + pip.yRatio.coerceIn(0f, 1f) * contentH
        return floatArrayOf(cx, cy, w, h)
    }

    /** Is the view-space point inside the layer's display rect? */
    fun hitTest(
        pip: PipLayer,
        px: Float,
        py: Float,
        viewW: Float,
        viewH: Float,
        canvasW: Float,
        canvasH: Float,
        assetW: Int,
        assetH: Int,
    ): Boolean {
        val r = displayRect(pip, viewW, viewH, canvasW, canvasH, assetW, assetH) ?: return false
        if (!px.isFinite() || !py.isFinite()) return false
        return px >= r[0] - r[2] / 2f && px <= r[0] + r[2] / 2f &&
            py >= r[1] - r[3] / 2f && py <= r[1] + r[3] / 2f
    }
}
