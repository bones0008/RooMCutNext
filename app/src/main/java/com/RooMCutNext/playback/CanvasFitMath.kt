package com.RooMCutNext.playback

/**
 * v1.5.3 — the canvas-fit rect math the overlay layer MUST share with
 * media3's [androidx.media3.ui.AspectRatioFrameLayout] (RESIZE_MODE_FIT),
 * extracted pure so it is unit-testable against the library's published
 * semantics.
 *
 * THE BUG THIS PINS (field report: "转场的时间里面，视频画面的最下面有
 * 一条亮线" for every style except 下滑入/下擦除): AspectRatioFrameLayout's
 * onMeasure has a 1% DEFORMATION TOLERANCE — when the video aspect differs
 * from the view aspect by ≤1%, it does NOT resize at all and the video
 * surface fills the whole view (stretched ≤1%). The old layoutOverlays
 * truncated the fitted dimension unconditionally, so on a stage whose aspect
 * sits within that 1% band (the landscape editor's Modifier.aspectRatio(16/9)
 * stage is exactly this case whenever the exact fit lands on a fractional
 * pixel — e.g. layer 2264×1274, canvas 16:9 → AFRFL keeps 1274 rows, the old
 * math computed (2264/1.7778).toInt() = 1273), the head overlay rect came out
 * ONE ROW SHORTER than the body video underneath. During a transition that
 * 1px row shows the OUTGOING clip A under the incoming B — a bright seam at
 * the bottom of the picture for every style whose mask covers the bottom
 * rows. 下滑入/下擦除 keep the bottom masked (their reveal front travels
 * downward), so the sliver was intended content for them — exactly the
 * reported symptom table.
 *
 * Source-verified against media3-ui 1.11.1 AspectRatioFrameLayout.onMeasure:
 *  - deformation = videoAspect / viewAspect − 1
 *  - |deformation| ≤ MAX_ASPECT_RATIO_DEFORMATION_FRACTION → no resize
 *  - deformation > 0 (video wider)  → height = (int)(width / videoAspect)
 *  - deformation < 0 (video taller) → width  = (int)(height * videoAspect)
 *  - the surface is gravity-CENTER placed: offsets by integer division.
 */
object CanvasFitMath {

    /**
     * AspectRatioFrameLayout.MAX_ASPECT_RATIO_DEFORMATION_FRACTION
     * (media3-ui 1.11.1): within this band the video fills the view without
     * resizing. Locked as a constant so a media3 upgrade that changes it
     * shows up as a failing CanvasFitMathTest, not as a 1px seam.
     */
    const val MAX_DEFORMATION_FRACTION = 0.01f

    /** An axis-aligned rect in stage pixel coordinates. */
    data class Rect(val left: Int, val top: Int, val width: Int, val height: Int)

    /** |a/b − 1| within the AFRFL deformation tolerance. */
    fun withinDeformationTolerance(a: Float, b: Float): Boolean {
        if (a <= 0f || b <= 0f) return false
        return kotlin.math.abs(a / b - 1f) <= MAX_DEFORMATION_FRACTION
    }

    /**
     * The rect a PlayerView's video surface occupies inside a
     * [layerW]×[layerH] stage for a [videoAspect] frame — AspectRatioFrame-
     * Layout's onMeasure (RESIZE_MODE_FIT) + gravity-CENTER layout replicated
     * exactly (see the class doc for the branch map).
     */
    fun contentRect(layerW: Int, layerH: Int, videoAspect: Float): Rect {
        if (layerW <= 0 || layerH <= 0 || videoAspect <= 0f) return Rect(0, 0, layerW, layerH)
        val viewAspect = layerW.toFloat() / layerH
        val deformation = videoAspect / viewAspect - 1f
        if (kotlin.math.abs(deformation) <= MAX_DEFORMATION_FRACTION) {
            // The library's tolerance branch: no resize, full-bleed.
            return Rect(0, 0, layerW, layerH)
        }
        return if (deformation > 0f) {
            // Video WIDER than the view: width fixed, height truncated.
            val h = (layerW / videoAspect).toInt().coerceAtLeast(1)
            Rect(0, (layerH - h) / 2, layerW, h)
        } else {
            // Video TALLER than the view: height fixed, width truncated.
            val w = (layerH * videoAspect).toInt().coerceAtLeast(1)
            Rect((layerW - w) / 2, 0, w, layerH)
        }
    }

    /**
     * Whether a canvas-fit of [contentAspect] inside a [canvasAspect] canvas
     * should snap to the FULL rect (deformation tolerance) instead of the
     * truncating fit — the same 1% rule that keeps the head's own letterbox
     * from ending 1px short of the body's content rect.
     */
    fun canvasFitSnapsFull(contentAspect: Float, canvasAspect: Float): Boolean =
        withinDeformationTolerance(contentAspect, canvasAspect)
}
