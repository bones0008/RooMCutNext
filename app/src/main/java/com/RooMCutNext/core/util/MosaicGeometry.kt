package com.RooMCutNext.core.util

import com.RooMCutNext.core.model.MosaicRegion
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure geometry for the mosaic shader (v1.2.2) — model coordinates in,
 * GL uniform values out, so every rule is unit-testable on the JVM.
 *
 * Two conversions happen here:
 *
 *  1. REGION RECT: [MosaicRegion] stores the intuitive convention (y DOWN,
 *     origin top-left). GL texture sampling runs y-UP with v=1 at the top of
 *     the picture (media3's documented normalized frame coordinates — the
 *     convention the device-verified Crop effect also uses). So the intuitive
 *     rect is flipped: glBottom = 1 − intuitiveBottom, glTop = 1 − intuitiveTop.
 *     This is the SAME flip [com.RooMCutNext.export.effects.PipPresentation]
 *     applies for its y position — if a real device ever proves the convention
 *     inverted, the fix flips both places together (see the worklog's PiP
 *     z-order/y-flip verification note).
 *
 *  2. BLOCK COUNT: blocks are square in PIXEL space. The region's width in
 *     pixels divided by the block size in pixels gives the horizontal block
 *     count; the vertical count accounts for the frame's aspect so 1 block
 *     covers a square patch of the picture.
 */
object MosaicGeometry {

    /**
     * GL-space region rect for the shader's `uRegion` uniform:
     * [xMin, yMin (GL bottom), xMax, yMax (GL top)], all 0..1.
     */
    fun glRegion(region: MosaicRegion): FloatArray {
        val halfW = (region.w.coerceIn(0.01f, 1f)) / 2f
        val halfH = (region.h.coerceIn(0.01f, 1f)) / 2f
        val x = region.x.coerceIn(0f, 1f)
        val y = region.y.coerceIn(0f, 1f)
        val xMin = (x - halfW).coerceIn(0f, 1f)
        val xMax = (x + halfW).coerceIn(0f, 1f)
        val yMin = (1f - (y + halfH)).coerceIn(0f, 1f)
        val yMax = (1f - (y - halfH)).coerceIn(0f, 1f)
        // Guard degenerate (zero-area after clamping) rects.
        return floatArrayOf(
            xMin,
            kotlin.math.min(yMin, yMax),
            max(xMin, xMax),
            max(yMin, yMax),
        )
    }

    /**
     * Block counts for the shader's `uBlocks` uniform (≥ 1 each):
     * [blocksX, blocksY] across the region, computed against the input frame
     * size so blocks stay square in pixels.
     */
    fun blocks(region: MosaicRegion, frameWidth: Int, frameHeight: Int): FloatArray {
        val w = frameWidth.coerceAtLeast(1)
        val h = frameHeight.coerceAtLeast(1)
        val rect = glRegion(region)
        val regionW = (rect[2] - rect[0]).coerceAtLeast(0f)
        val regionH = (rect[3] - rect[1]).coerceAtLeast(0f)
        val blockPx = (region.blockRatio.coerceIn(0.002f, 0.5f) * w).coerceAtLeast(1f)
        val blocksX = (regionW * w / blockPx).coerceAtLeast(1f)
        val blocksY = (regionH * h / blockPx).coerceAtLeast(1f)
        return floatArrayOf(blocksX, blocksY)
    }

    /** Block size in whole pixels (for diagnostics / tests). */
    fun blockPixels(region: MosaicRegion, frameWidth: Int): Int =
        (region.blockRatio.coerceIn(0.002f, 0.5f) * frameWidth.coerceAtLeast(1))
            .roundToInt().coerceAtLeast(1)
}
