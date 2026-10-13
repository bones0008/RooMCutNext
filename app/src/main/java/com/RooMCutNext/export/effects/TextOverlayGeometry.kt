package com.RooMCutNext.export.effects

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.RooMCutNext.core.model.TextOverlayItem
import com.RooMCutNext.core.util.TextGestureMath
import com.RooMCutNext.media.FontRegistry

/**
 * Shared text-block geometry (v1.3.0).
 *
 * The DIRECT-MANIPULATION layer (tap-to-select hit-test + the rotated
 * selection frame in PreviewGestures) must agree with what
 * [TimedTextOverlay] actually paints, pixel for pixel — or the user would
 * grab a box that does not match the text. This object is the single
 * source of truth for both: every layout constant and every builder step
 * of the draw path lives here, and the draw path calls back into it.
 *
 * Layout parity checklist (do not change one side only):
 *   - paint size: [paintTextSize] (fontSizeRatio * canvasH, clamped)
 *   - typeface: FontRegistry.resolve(fontId, bold)
 *   - line width limit: 0.92 * canvasW (floor 16px)
 *   - StaticLayout: ALIGN_CENTER, lineSpacing mult 1.12, includePad true
 *   - block centered at (xRatio*W, yRatio*H), rotated rotationDeg
 */
object TextOverlayGeometry {

    /** Max text block width as a fraction of the canvas width. */
    const val WIDTH_LIMIT_RATIO = 0.92f

    /** StaticLayout line-spacing multiplier — matches the draw path. */
    const val LINE_SPACING_MULT = 1.12f

    fun paintTextSize(item: TextOverlayItem, canvasH: Int): Float =
        (item.style.fontSizeRatio * canvasH).coerceIn(8f, canvasH * 0.5f)

    fun textWidthLimit(canvasW: Int): Int = (canvasW * WIDTH_LIMIT_RATIO).toInt().coerceAtLeast(16)

    fun typeface(item: TextOverlayItem): Typeface =
        FontRegistry.resolve(item.style.fontId, item.style.bold)

    /**
     * Build the fill layout exactly as the draw path does. Width/height of
     * the result is the on-canvas text-block size used for hit-testing and
     * the selection frame.
     */
    fun fillLayout(item: TextOverlayItem, canvasW: Int, canvasH: Int): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = typeface(item)
            textSize = paintTextSize(item, canvasH)
            color = item.style.color.toInt()
        }
        return staticLayout(item.text, paint, textWidthLimit(canvasW))
    }

    fun staticLayout(text: String, paint: TextPaint, width: Int): StaticLayout =
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, LINE_SPACING_MULT)
                .setIncludePad(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, width, Layout.Alignment.ALIGN_CENTER, LINE_SPACING_MULT, 0f, true)
        }

    /**
     * v1.3.2: the width the text INK actually occupies — the widest line.
     *
     * [StaticLayout.getWidth] is the CONSTRAINT the layout was built with
     * (0.92 × canvas width), NOT the content extent: with ALIGN_CENTER every
     * line is centered inside that constraint, so the glyphs span exactly
     * [blockWidth] around the block's horizontal center. This is what the
     * selection frame must hug and what hit-testing must use — v1.3.1 drew
     * the frame at the full constraint width ("the box never shrinks
     * sideways no matter how small the text"), and its hit region silently
     * covered the whole 92% strip.
     *
     * Blank/empty text yields 0 — callers that need a visible minimum
     * (e.g. the frame) apply their own floor; hit-testing rejects blank
     * text before ever reaching here.
     */
    fun blockWidth(layout: StaticLayout): Float {
        var w = 0f
        for (i in 0 until layout.lineCount) {
            val lw = layout.getLineWidth(i)
            if (lw > w) w = lw
        }
        return w
    }

    /**
     * Hit-test a CANVAS-pixel point against the item's rendered block.
     * Returns true when the point lands inside the rotated text rect.
     */
    fun hitTest(item: TextOverlayItem, canvasW: Int, canvasH: Int, pxCanvas: Float, pyCanvas: Float): Boolean {
        if (item.text.isBlank() || canvasW <= 0 || canvasH <= 0) return false
        if (!pxCanvas.isFinite() || !pyCanvas.isFinite()) return false
        val layout = fillLayout(item, canvasW, canvasH)
        return TextGestureMath.hitRotatedRect(
            px = pxCanvas,
            py = pyCanvas,
            cx = item.xRatio * canvasW,
            cy = item.yRatio * canvasH,
            halfW = blockWidth(layout) / 2f,
            halfH = layout.height / 2f,
            rotationDeg = item.rotationDeg,
        )
    }
}
