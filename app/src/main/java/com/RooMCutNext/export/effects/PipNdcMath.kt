package com.RooMCutNext.export.effects

/**
 * Pure NDC mapping math for [PipPresentation] (v1.2.9) — JVM unit-tested,
 * following the HeadWindowMath pattern: the GL geometry that has no Android
 * dependencies lives here so it can be pinned by tests.
 *
 * CONVENTION EVIDENCE (Media3 1.11.1, checked against the shipped source):
 *  - MatrixTransformation's interface javadoc: "The matrix is applied to
 *    points given in NORMALIZED DEVICE COORDINATES (-1 to 1 on x and y
 *    axes)". The full output frame therefore spans 2 NDC units per axis —
 *    a translate of 1.0 shifts the quad by HALF the frame.
 *  - MatrixUtils.getGlMatrixArray() converts the android.graphics.Matrix to
 *    a GL 4x4 with NO rescaling (3x3 → 4x4 embed + transpose only), so the
 *    android-space translate values reach the shader verbatim.
 *
 * THE v1.2.8 BUG these functions fix: the translate was computed as
 * `centerX - 0.5` / `(1 - centerY) - 0.5`, i.e. in a [-0.5, 0.5] space of
 * width 1. In NDC (width 2) that lands the PiP at HALF the intended offset
 * from the canvas center: the exported position was compressed 2x toward
 * the center. Dead center was unaffected (offset 0) and the display WIDTH
 * (scale) was unaffected (relative scale is span-invariant), which is why
 * the bug read as "the PiP sits somewhere else in the export than in the
 * editor" and survived center-ish test placements.
 *
 * UI convention (what the editor's drag handle and PipLayer store):
 * x/y ratios in [0, 1], origin at the TOP-LEFT of the canvas, y pointing
 * DOWN. GL NDC: [-1, 1], +y pointing UP. Mapping:
 *   x_ndc = 2 * cx - 1          (0 → left edge, 1 → right edge)
 *   y_ndc = 1 - 2 * cy          (0 → top edge, 1 → bottom edge)
 */
object PipNdcMath {

    /** NDC x of the PiP center from the UI x ratio ([0,1], left→right). */
    fun centerXNdc(centerXRatio: Float): Float =
        2f * centerXRatio.coerceIn(0f, 1f) - 1f

    /**
     * NDC y of the PiP center from the UI y ratio ([0,1], top→bottom).
     * Flips the axis as well as rescales it: GL NDC +y is UP.
     */
    fun centerYNdc(centerYRatio: Float): Float =
        1f - 2f * centerYRatio.coerceIn(0f, 1f)

    /**
     * Vertex-space y scale that preserves the input aspect for a given x
     * scale: the quad is [scaleX] of the output WIDTH wide and [scaleY] of
     * the output HEIGHT tall, so in pixels
     *   quadW = scaleX * canvasW, quadH = scaleY * canvasH
     * and quadW / quadH == inputW / inputH gives the formula below.
     */
    fun scaleY(
        scaleX: Float,
        canvasWidth: Int,
        canvasHeight: Int,
        inputWidth: Int,
        inputHeight: Int,
    ): Float =
        scaleX * (canvasWidth.toFloat() / canvasHeight) *
            (inputHeight.toFloat() / inputWidth)
}
