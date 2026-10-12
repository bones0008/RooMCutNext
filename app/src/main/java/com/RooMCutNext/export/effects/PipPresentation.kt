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
 * v1.2.9 EXPORT POSITION FIX — the translate was computed in a [-0.5, 0.5]
 * normalized space, but Media3 1.11.1's MatrixTransformation operates in
 * NORMALIZED DEVICE COORDINATES (-1 to 1 — see the interface javadoc and
 * MatrixUtils.getGlMatrixArray, which converts the android.graphics.Matrix
 * to a GL 4x4 with NO rescaling; the media3 composition demo confirms the
 * translate magnitude convention). A translate of tx shifts the quad by
 * tx / 2 of the output width, so `centerX - 0.5` placed the PiP at HALF the
 * intended offset from the canvas center: the exported position was
 * compressed 2x toward the center — invisible at dead center, up to ±25% of
 * the canvas off near the edges ("the PiP sits somewhere else in the export
 * than in the editor"). Correct mapping: NDC x = 2*centerX - 1, and for the
 * y-flip (GL NDC +y is up, UI y is down): NDC y = 1 - 2*centerY.
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
        // (ratio of canvas width). Keep the input aspect for the height —
        // the scaleY formula and the NDC center mapping live in PipNdcMath
        // (pure, JVM unit-tested).
        val scaleX = displayWidthRatio.coerceIn(0.02f, 1f)
        val scaleY = PipNdcMath.scaleY(
            scaleX, canvasWidth, canvasHeight, inputWidth, inputHeight,
        )
        matrix.postScale(scaleX, scaleY)
        // v1.2.9: translate in NDC units (-1..1) — see PipNdcMath for the
        // convention evidence and the v1.2.8→v1.2.9 fix rationale.
        matrix.postTranslate(
            PipNdcMath.centerXNdc(centerX),
            PipNdcMath.centerYNdc(centerY),
        )
        return matrix
    }
}
