package com.RooMCutNext.export.effects

import android.graphics.Matrix
import androidx.media3.common.util.Size
import androidx.media3.effect.MatrixTransformation

/**
 * Places and scales a picture-in-picture layer on the output canvas.
 *
 * v1.2.2 rewrite — the layer must render into a FULL CANVAS frame whose
 * non-content area is transparent, because Media3 1.11.1's
 * DefaultVideoCompositor composites whole SEQUENCES (each sequence is one
 * input texture):
 *   - configure() now returns the CANVAS size, so the pre-processor's output
 *     texture is the canvas and the vertex matrix below draws the layer's
 *     content into a sub-rectangle of it. Everything outside that rectangle
 *     keeps the FBO clear color (transparent black), so the layers below
 *     show through — src-over compositing then does the right thing.
 *   - The old version returned the INPUT size as output, which only makes
 *     sense in a single-input pipeline; in the multi-input compositor the
 *     texture would be non-uniformly re-stretched onto the primary's size,
 *     corrupting both the position and the scale.
 *
 * Matrix semantics (Media3 normalized VERTEX space — the texture quad is
 * transformed within the normalized output frame; identity stretches the
 * texture over the whole output frame):
 *   1. uniform scale so the layer's DISPLAY WIDTH becomes
 *      [PipLayer.scale] × canvasWidth (input aspect ratio preserved), then
 *   2. translate the quad's center to (centerX, centerY).
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
        // Output is the FULL canvas: the matrix below maps the layer into a
        // sub-rectangle; the rest of the frame stays transparent (FBO clear),
        // which is what makes sequence-on-sequence compositing work.
        return Size(canvasWidth, canvasHeight)
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
