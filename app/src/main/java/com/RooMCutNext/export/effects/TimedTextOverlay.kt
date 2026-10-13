package com.RooMCutNext.export.effects

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import androidx.media3.effect.CanvasOverlay
import com.RooMCutNext.core.model.TextOverlayItem

/**
 * A [CanvasOverlay] rendering one timeline text overlay inside a time window
 * expressed in ITEM-LOCAL timeline coordinates.
 *
 * The canvas auto-sizes to the (post-rotation/crop) video frame, so text is
 * positioned in simple normalized frame coordinates and drawn 1:1 — including
 * stroke outline and shadow, which Spannable-based overlays cannot do.
 *
 * Outside the visibility window nothing is drawn (fully transparent overlay).
 *
 * v1.3.0 LIVE PREVIEW HOOK: [liveTransform] is an optional per-frame source
 * of the item's CURRENT state. onDraw runs for every output frame, so when
 * the preview passes a lookup that reads the editor's live project state,
 * position / size / rotation / color / font / content edits render
 * IMMEDIATELY — no composition rebuild, no seek, no player churn. This is
 * what makes direct-manipulation gestures (drag / pinch / rotate) track the
 * fingers at frame rate, and what lets the TEXT panel sliders go live too.
 *
 * The hook is deliberately PREVIEW-ONLY:
 *  - EXPORT builds with liveTransform == null and paints the frozen snapshot
 *    captured at build time — deterministic, replayable, undo-safe;
 *  - the visibility WINDOW always stays the frozen constructor value on both
 *    paths (windows are structural: they choose which item carries which
 *    overlay, and rebuilding them is PlaybackController's job — see
 *    TextSignatures).
 */
class TimedTextOverlay(
    private val item: TextOverlayItem,
    private val windowStartUs: Long,
    private val windowEndUs: Long,
    private val liveTransform: (() -> TextOverlayItem?)? = null,
) : CanvasOverlay(/* useInputFrameSize = */ true) {

    private var lastCanvasWidth = -1
    /** Item the cached layouts were built for (frozen snapshot or live read). */
    private var layoutItem: TextOverlayItem? = null
    private var fillLayout: android.text.StaticLayout? = null
    private var strokeLayout: android.text.StaticLayout? = null
    private var paintTextSize = 0f

    // v1.1: user-selectable font (bundled subset fonts + imports). Resolves
    // to the platform default for "system"/unknown ids, so old projects and
    // deleted user fonts degrade gracefully. Re-resolved on every layout
    // rebuild so live font switches (v1.3.0) take effect without a rebuild.
    private var typeface: Typeface = TextOverlayGeometry.typeface(item)

    private val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = this@TimedTextOverlay.typeface
    }

    // v1.3.0: var — a live edit can turn the outline on/off after
    // construction, so the paint is (re)created inside rebuildLayouts.
    private var strokePaint: TextPaint? = if (item.style.strokeWidthRatio > 0f) {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = this@TimedTextOverlay.typeface
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
    } else null

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        // The canvas is reused across frames — always clear first, or stale
        // text would linger outside the visibility window.
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        if (presentationTimeUs < windowStartUs || presentationTimeUs >= windowEndUs) return

        // v1.3.0: the live read. A null live result means "deleted from the
        // project" (or plain export mode) — the frozen item still renders in
        // export; in preview a deletion briefly draws nothing until the
        // structural rebuild swaps this overlay out, which is the correct
        // fade-free behavior.
        val eff = liveTransform?.invoke() ?: item
        if (eff.text.isBlank()) return

        val frameW = canvas.width
        val frameH = canvas.height
        if (frameW <= 0 || frameH <= 0) return

        if (frameW != lastCanvasWidth || fillLayout == null || eff != layoutItem) {
            rebuildLayouts(eff, frameW, frameH)
            lastCanvasWidth = frameW
            layoutItem = eff
        }

        val cx = eff.xRatio * frameW
        val cy = eff.yRatio * frameH
        val layout = fillLayout ?: return

        canvas.save()
        canvas.rotate(eff.rotationDeg, cx, cy)
        if (eff.style.shadowRadiusRatio > 0f) {
            fillPaint.setShadowLayer(
                paintTextSize * eff.style.shadowRadiusRatio,
                0f, 0f,
                (eff.style.strokeColor and 0xFF000000L).toInt(),
            )
        } else {
            fillPaint.clearShadowLayer()
        }
        canvas.translate(cx - layout.width / 2f, cy - layout.height / 2f)
        strokeLayout?.draw(canvas)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun rebuildLayouts(eff: TextOverlayItem, frameW: Int, frameH: Int) {
        paintTextSize = TextOverlayGeometry.paintTextSize(eff, frameH)
        typeface = TextOverlayGeometry.typeface(eff)

        fillPaint.textSize = paintTextSize
        fillPaint.color = eff.style.color.toInt()
        fillPaint.typeface = typeface

        val widthLimit = TextOverlayGeometry.textWidthLimit(frameW)
        fillLayout = TextOverlayGeometry.staticLayout(eff.text, fillPaint, widthLimit)

        // The stroke paint exists iff the CURRENT style wants one — a live
        // edit can turn the outline on/off, so (re)create it here.
        strokeLayout = if (eff.style.strokeWidthRatio > 0f) {
            val sp = strokePaint ?: TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
            }.also { strokePaint = it }
            sp.textSize = paintTextSize
            sp.color = eff.style.strokeColor.toInt()
            sp.strokeWidth = paintTextSize * eff.style.strokeWidthRatio
            sp.typeface = typeface
            TextOverlayGeometry.staticLayout(eff.text, sp, widthLimit)
        } else null
    }

    override fun release() {
        super.release()
        fillPaint.clearShadowLayer()
    }
}
