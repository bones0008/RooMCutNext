package com.RooMCutNext.core.util

/**
 * Pure integer rect used by [aspectFitRect] — deliberately NOT
 * android.graphics.Rect so it stays unit-testable on the JVM.
 */
data class IntRect4(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * The sub-rectangle of a (bitmapW × bitmapH) canvas that a RESIZE_MODE_FIT
 * video of aspect (contentW × contentH) actually occupies, centered.
 *
 * Used by frame export: PixelCopy grabs the whole PlayerView surface
 * including the black letterbox bars, so the visible content is the largest
 * centered rect matching the video's aspect ratio.
 *
 * Degenerate content sizes (0 or negative) fall back to the full canvas —
 * caller passes either the player's current video size or the project
 * resolution when nothing is playing.
 */
fun aspectFitRect(bitmapW: Int, bitmapH: Int, contentW: Int, contentH: Int): IntRect4 {
    if (bitmapW <= 0 || bitmapH <= 0) return IntRect4(0, 0, 0, 0)
    if (contentW <= 0 || contentH <= 0) return IntRect4(0, 0, bitmapW, bitmapH)
    val contentAspect = contentW.toFloat() / contentH
    val canvasAspect = bitmapW.toFloat() / bitmapH
    return if (contentAspect > canvasAspect) {
        // content wider than canvas -> full width, letterboxed height
        val h = (bitmapW / contentAspect).toInt().coerceIn(1, bitmapH)
        val t = (bitmapH - h) / 2
        IntRect4(0, t, bitmapW, t + h)
    } else {
        // content taller (or equal) -> full height, pillarboxed width
        val w = (bitmapH * contentAspect).toInt().coerceIn(1, bitmapW)
        val l = (bitmapW - w) / 2
        IntRect4(l, 0, l + w, bitmapH)
    }
}
