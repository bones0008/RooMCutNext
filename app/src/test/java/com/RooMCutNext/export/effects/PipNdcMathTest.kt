package com.RooMCutNext.export.effects

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.2.9 — pins the NDC geometry [PipPresentation] applies, extracted into
 * the pure [PipNdcMath] so it is JVM-testable (no android.graphics / media3
 * classes involved).
 *
 * These tests are the regression guard for the "exported PiP sits somewhere
 * else than in the editor" bug: the v1.2.8 matrix translated by
 * `centerX - 0.5` in a space whose full width is 2.0 NDC units, halving
 * every offset from the center (2x compression toward the center; up to
 * ±25% of the canvas off near the edges).
 */
class PipNdcMathTest {

    // ---- center mapping ----

    @Test
    fun `center ratios map to the full NDC range`() {
        // The quad CENTER must be able to reach the frame edges: a PiP dragged
        // to the left/top edge in the editor must export with its center at
        // the left/top edge (half of it hanging off-canvas), exactly like the
        // view-layer preview's place() positions it.
        assertEquals(-1f, PipNdcMath.centerXNdc(0f), 1e-6f)
        assertEquals(1f, PipNdcMath.centerXNdc(1f), 1e-6f)
        assertEquals(1f, PipNdcMath.centerYNdc(0f), 1e-6f) // UI top = NDC +y top
        assertEquals(-1f, PipNdcMath.centerYNdc(1f), 1e-6f) // UI bottom = NDC -y bottom
    }

    @Test
    fun `the canvas center maps to the NDC origin`() {
        assertEquals(0f, PipNdcMath.centerXNdc(0.5f), 1e-6f)
        assertEquals(0f, PipNdcMath.centerYNdc(0.5f), 1e-6f)
    }

    @Test
    fun `quarter positions map to half NDC offsets - v1 2 8 regression`() {
        // The old code produced centerXNdc(0.25) = -0.25 — HALF this offset.
        // 0.25 of the canvas width from the left must be NDC -0.5, or the
        // exported PiP drifts 12.5% of the canvas toward the center.
        assertEquals(-0.5f, PipNdcMath.centerXNdc(0.25f), 1e-6f)
        assertEquals(0.5f, PipNdcMath.centerXNdc(0.75f), 1e-6f)
        assertEquals(0.5f, PipNdcMath.centerYNdc(0.25f), 1e-6f)
        assertEquals(-0.5f, PipNdcMath.centerYNdc(0.75f), 1e-6f)
    }

    @Test
    fun `out of range ratios are clamped before mapping`() {
        assertEquals(-1f, PipNdcMath.centerXNdc(-3f), 1e-6f)
        assertEquals(1f, PipNdcMath.centerXNdc(3f), 1e-6f)
        assertEquals(1f, PipNdcMath.centerYNdc(-0.5f), 1e-6f)
        assertEquals(-1f, PipNdcMath.centerYNdc(2.5f), 1e-6f)
    }

    // ---- aspect preservation ----

    @Test
    fun `scaleY preserves the input aspect in output pixels`() {
        // Portrait canvas 1080x1920, portrait input 1080x1920, PiP width 40%
        // of the canvas: the quad is 432px wide and must be 768px tall.
        val sx = 0.4f
        val sy = PipNdcMath.scaleY(
            sx, canvasWidth = 1080, canvasHeight = 1920,
            inputWidth = 1080, inputHeight = 1920,
        )
        assertEquals(432f, sx * 1080f, 0.5f)
        assertEquals(768f, sy * 1920f, 0.5f)
    }

    @Test
    fun `landscape input on a portrait canvas stays landscape`() {
        // 16:9 input on a 9:16 canvas at half-canvas display width:
        // the pixel quad must be 16:9 regardless of the canvas ratio.
        val sx = 0.5f
        val sy = PipNdcMath.scaleY(
            sx, canvasWidth = 1080, canvasHeight = 1920,
            inputWidth = 1920, inputHeight = 1080,
        )
        val quadW = sx * 1080f
        val quadH = sy * 1920f
        assertEquals(16f / 9f, quadW / quadH, 1e-4f)
        assertEquals(540f, quadW, 0.5f)
        assertEquals(303.75f, quadH, 0.5f)
    }

    @Test
    fun `same aspect input and canvas yield equal pixel scales`() {
        // Input aspect == canvas aspect → scaleY == scaleX (square pixels).
        val sy = PipNdcMath.scaleY(
            0.7f, canvasWidth = 1920, canvasHeight = 1080,
            inputWidth = 1280, inputHeight = 720,
        )
        assertEquals(0.7f, sy, 1e-6f)
    }

    @Test
    fun `export quad reconstructs the preview place rect - WYSIWYG pin`() {
        // THE regression test for "the PiP sits somewhere else in the export
        // than in the editor": reconstruct both geometries as pixel
        // rectangles over the SAME canvas and require them identical.
        //
        // Preview (OverlayPreviewController.place, canvas-normalized):
        //   displayW = scale * canvasW; displayH = displayW / inputAspect
        //   left = cx - displayW/2; top = cy - displayH/2      (y down)
        // Export (NDC quad): identity spans [-1,1], so after scale+translate
        // the quad spans [tx-sx, tx+sx] x [ty-sy, ty+sy]; a fraction of the
        // frame is 0.5 + ndc/2 on x, and 0.5 - ndc/2 on y (NDC +y is UP).
        val cases = listOf(
            // cx, cy, scale, canvasW, canvasH, inputW, inputH
            listOf(0.25f, 0.30f, 0.50f, 1080f, 1920f, 1920f, 1080f),
            listOf(0.80f, 0.75f, 0.35f, 1080f, 1920f, 1080f, 1920f),
            listOf(0.50f, 0.50f, 1.00f, 1920f, 1080f, 1920f, 1080f),
            listOf(0.10f, 0.90f, 0.22f, 1920f, 1080f, 720f, 1280f),
        )
        for (c in cases) {
            val cx = c[0]; val cy = c[1]; val scale = c[2]
            val cw = c[3]; val ch = c[4]; val iw = c[5]; val ih = c[6]
            val inputAspect = iw / ih

            // Preview rect (fractions of the canvas, origin top-left).
            val dispW = scale * cw
            val dispH = dispW / inputAspect
            val pvLeft = cx - dispW / 2f / cw
            val pvTop = cy - dispH / 2f / ch

            // Export quad (NDC -> canvas fractions).
            val sx = scale
            val sy = PipNdcMath.scaleY(sx, cw.toInt(), ch.toInt(), iw.toInt(), ih.toInt())
            val tx = PipNdcMath.centerXNdc(cx)
            val ty = PipNdcMath.centerYNdc(cy)
            val exLeft = 0.5f + (tx - sx) / 2f
            val exTop = 0.5f - (ty + sy) / 2f // top edge = max NDC y

            assertEquals("left mismatch for $c", pvLeft, exLeft, 1e-4f)
            assertEquals("top mismatch for $c", pvTop, exTop, 1e-4f)
        }
    }
}
