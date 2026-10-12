package com.RooMCutNext.export.effects

import android.graphics.Matrix
import androidx.media3.common.util.Size
import androidx.media3.effect.MatrixTransformation

/**
 * Places and scales a picture-in-picture layer on the output canvas (v1.2).
 *
 * Media3 1.11.1 has no ScaleAndTranslatePresentation (added in 1.12), but
 * [MatrixTransformation] gives full control over the normalized texture
 * coordinates, which is strictly more flexible: position, scale, and (later)
 * timestamp-driven animation all live in one matrix.
 *
 * Coordinate system: the layer's input texture is normalized to [0,1]² over
 * the OUTPUT canvas, so one unit of x equals [canvasWidth] pixels and one
 * unit of y equals [canvasHeight] pixels. The matrix
 *   1. uniformly scales so the layer's DISPLAY WIDTH becomes
 *      [PipLayer.scale] × canvasWidth (input aspect ratio preserved), then
 *   2. translates the center to (centerX, centerY).
 *
 * The GL y axis points UP in Media3's normalized space (a plain translate
 * of +y moves content towards the top), so [centerY] is flipped internally;
 * callers keep the intuitive "0 = top edge, 1 = bottom edge" convention.
 *
 * Degenerate inputs (0-sized configure) fall back to the identity matrix.
 */
class PipPresentation(
    private val canvasWidth: Int,
    private val canvasHeight: Int,
    private val centerX: Float,
    private val centerY: Float,
    private val displayWidthRatio: Float,
) : MatrixTransformation {

    private var inputWidth = 0
    private var inputHeight = 0

    override fun configure(width: Int, height: Int): Size {
        inputWidth = width
        inputHeight = height
        // Output keeps the input size: the VideoFrameProcessor composites
        // sequences in normalized space on the primary canvas, so the
        // layer's own output resolution is irrelevant; only the matrix maps
        // it onto the canvas.
        return Size(width, height)
    }

    override fun getMatrix(timestampUs: Long): Matrix {
        val matrix = Matrix()
        if (inputWidth <= 0 || inputHeight <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
            return matrix
        }
        // Display width in normalized x units is exactly displayWidthRatio
        // (ratio of canvas width). Keep the input aspect for the height:
        // display height in pixels = displayWidthRatio * canvasWidth * inH/inW
        // -> normalized y units = that / canvasHeight.
        val scaleX = displayWidthRatio.coerceIn(0.02f, 1f)
        val scaleY = scaleX * (canvasWidth.toFloat() / canvasHeight) *
            (inputHeight.toFloat() / inputWidth)
        matrix.postScale(scaleX, scaleY)
        // GL y-up: flip the intuitive y (0=top) into GL space (0=bottom),
        // then move the CENTER to the target point.
        val tx = (centerX.coerceIn(0f, 1f)) - 0.5f
        val ty = ((1f - centerY.coerceIn(0f, 1f))) - 0.5f
        matrix.postTranslate(tx, ty)
        return matrix
    }
}
