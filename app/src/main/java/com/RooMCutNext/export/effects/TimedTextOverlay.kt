package com.RooMCutNext.export.effects

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.util.Size
import androidx.media3.effect.CanvasOverlay
import com.RooMCutNext.core.model.TextOverlayItem
import com.RooMCutNext.media.FontRegistry

/**
 * A [CanvasOverlay] rendering one timeline text overlay inside a time window
 * expressed in ITEM-LOCAL timeline coordinates.
 *
 * The canvas auto-sizes to the (post-rotation/crop) video frame, so text is
 * positioned in simple normalized frame coordinates and drawn 1:1 — including
 * stroke outline and shadow, which Spannable-based overlays cannot do.
 *
 * Outside the visibility window nothing is drawn (fully transparent overlay).
 */
class TimedTextOverlay(
    private val item: TextOverlayItem,
    private val windowStartUs: Long,
    private val windowEndUs: Long,
) : CanvasOverlay(/* useInputFrameSize = */ true) {

    private var lastCanvasWidth = -1
    private var fillLayout: StaticLayout? = null
    private var strokeLayout: StaticLayout? = null
    private var paintTextSize = 0f

    // v1.1: user-selectable font (bundled subset fonts + imports). Resolves
    // to the platform default for "system"/unknown ids, so old projects and
    // deleted user fonts degrade gracefully. Same resolution on the preview
    // and export paths — both construct this overlay.
    private val typeface: Typeface = FontRegistry.resolve(item.style.fontId, item.style.bold)

    private val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = this@TimedTextOverlay.typeface
    }
    private val strokePaint: TextPaint? = if (item.style.strokeWidthRatio > 0f) {
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
        if (item.text.isBlank()) return

        val frameW = canvas.width
        val frameH = canvas.height
        if (frameW <= 0 || frameH <= 0) return

        if (frameW != lastCanvasWidth || fillLayout == null) {
            rebuildLayouts(frameW, frameH)
            lastCanvasWidth = frameW
        }

        val cx = item.xRatio * frameW
        val cy = item.yRatio * frameH
        val layout = fillLayout ?: return

        canvas.save()
        canvas.rotate(item.rotationDeg, cx, cy)
        if (item.style.shadowRadiusRatio > 0f) {
            fillPaint.setShadowLayer(
                paintTextSize * item.style.shadowRadiusRatio,
                0f, 0f,
                (item.style.strokeColor and 0xFF000000L).toInt(),
            )
        }
        canvas.translate(cx - layout.width / 2f, cy - layout.height / 2f)
        strokeLayout?.draw(canvas)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun rebuildLayouts(frameW: Int, frameH: Int) {
        paintTextSize = (item.style.fontSizeRatio * frameH).coerceIn(8f, frameH * 0.5f)
        fillPaint.textSize = paintTextSize
        fillPaint.color = item.style.color.toInt()
        strokePaint?.let {
            it.textSize = paintTextSize
            it.color = item.style.strokeColor.toInt()
            it.strokeWidth = paintTextSize * item.style.strokeWidthRatio
        }
        val textWidth = (frameW * 0.92f).toInt().coerceAtLeast(16)
        fillLayout = staticLayout(item.text, fillPaint, textWidth)
        strokeLayout = strokePaint?.let { staticLayout(item.text, it, textWidth) }
    }

    private fun staticLayout(text: String, paint: TextPaint, width: Int): StaticLayout =
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.12f)
                .setIncludePad(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, width, Layout.Alignment.ALIGN_CENTER, 1.12f, 0f, true)
        }

    override fun release() {
        super.release()
        fillPaint.clearShadowLayer()
    }
}
