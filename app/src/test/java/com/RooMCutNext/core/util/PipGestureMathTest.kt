package com.RooMCutNext.core.util

import com.RooMCutNext.core.model.PipLayer
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.1 direct-manipulation math for PiP layers — the rules the unified
 * preview gesture layer follows. Slider-parity clamps are contract here:
 * gestures and the PiP panel sliders must never disagree about the legal
 * value range (x/y 0.05..0.95, scale 0.1..1.0).
 *
 * The display-rect geometry is contract too: it must equal what
 * OverlayPreviewController.place() gives the view and what PipPresentation
 * draws on export (WYSIWYG).
 */
class PipGestureMathTest {

    private fun layer(
        x: Float = 0.5f,
        y: Float = 0.5f,
        scale: Float = 0.4f,
    ) = PipLayer(id = "p1", assetId = "a1", xRatio = x, yRatio = y, scale = scale)

    // ---------------- pan ----------------

    @Test
    fun `pan moves the center by the view delta in content units`() {
        // Content rect = full view (same aspect): a 100px drag moves the
        // ratio by 100/1000 = 0.1.
        val out = PipGestureMath.pan(layer(), 100f, 50f, 1000f, 1000f)
        assertEquals(0.6f, out.xRatio, 1e-4f)
        assertEquals(0.55f, out.yRatio, 1e-4f)
    }

    @Test
    fun `pan is letterbox aware for the y axis`() {
        // Canvas 1000x500 in a view 1000x1000 → content rect 1000x500, so a
        // 100px vertical drag is 100/500 = 0.2 of the canvas height.
        val out = PipGestureMath.pan(layer(), 0f, 100f, 1000f, 500f)
        assertEquals(0.5f, out.xRatio, 1e-4f)
        assertEquals(0.7f, out.yRatio, 1e-4f)
    }

    @Test
    fun `pan clamps to the slider range`() {
        val out = PipGestureMath.pan(layer(x = 0.9f, y = 0.9f), 500f, 500f, 1000f, 1000f)
        assertEquals(PipGestureMath.X_MAX, out.xRatio, 1e-6f)
        assertEquals(PipGestureMath.Y_MAX, out.yRatio, 1e-6f)
        val back = PipGestureMath.pan(layer(x = 0.1f, y = 0.1f), -500f, -500f, 1000f, 1000f)
        assertEquals(PipGestureMath.X_MIN, back.xRatio, 1e-6f)
        assertEquals(PipGestureMath.Y_MIN, back.yRatio, 1e-6f)
    }

    @Test
    fun `pan degenerate content is a no-op`() {
        val start = layer(x = 0.7f, y = 0.3f)
        assertEquals(start, PipGestureMath.pan(start, 100f, 100f, 0f, 0f))
        assertEquals(start, PipGestureMath.pan(start, Float.NaN, 10f, 1000f, 1000f))
    }

    // ---------------- zoom ----------------

    @Test
    fun `zoom multiplies the scale`() {
        val out = PipGestureMath.zoom(layer(scale = 0.4f), 1.5f)
        assertEquals(0.6f, out.scale, 1e-4f)
    }

    @Test
    fun `zoom clamps to the slider range`() {
        assertEquals(PipGestureMath.SCALE_MAX, PipGestureMath.zoom(layer(scale = 0.8f), 4f).scale, 1e-6f)
        assertEquals(PipGestureMath.SCALE_MIN, PipGestureMath.zoom(layer(scale = 0.2f), 0.01f).scale, 1e-6f)
    }

    @Test
    fun `zoom rejects non-finite and non-positive factors`() {
        val start = layer(scale = 0.4f)
        assertEquals(start, PipGestureMath.zoom(start, Float.NaN))
        assertEquals(start, PipGestureMath.zoom(start, 0f))
        assertEquals(start, PipGestureMath.zoom(start, -1f))
    }

    // ---------------- displayRect / hitTest (WYSIWYG geometry) ----------------

    @Test
    fun `displayRect same-aspect fills content rect proportionally`() {
        // View = canvas (1000x600), asset 500x250 (aspect 2.0):
        // displayW = scale*1000 = 400, displayH = 400/2 = 200, center (500,300).
        val r = PipGestureMath.displayRect(layer(x = 0.5f, y = 0.5f, scale = 0.4f), 1000f, 600f, 1000f, 600f, 500, 250)
        assertEquals(500f, r!![0], 1e-3f)
        assertEquals(300f, r[1], 1e-3f)
        assertEquals(400f, r[2], 1e-3f)
        assertEquals(200f, r[3], 1e-3f)
    }

    @Test
    fun `displayRect letterboxes with the canvas aspect`() {
        // Canvas 1000x1000 in a 1000x600 view → content rect 600x600 centered
        // at left=200: scale 0.5 → displayW = 300 (of content 600), center
        // x = 200 + 0.5*600 = 500, y = 0.5*600 = 300.
        val r = PipGestureMath.displayRect(layer(scale = 0.5f), 1000f, 600f, 1000f, 1000f, 400, 400)
        assertEquals(500f, r!![0], 1e-3f)
        assertEquals(300f, r[1], 1e-3f)
        assertEquals(300f, r[2], 1e-3f)
        assertEquals(300f, r[3], 1e-3f)
    }

    @Test
    fun `displayRect degenerate inputs return null`() {
        assertNull(PipGestureMath.displayRect(layer(), 0f, 100f, 100f, 100f, 10, 10))
        assertNull(PipGestureMath.displayRect(layer(), 100f, 100f, 100f, 100f, 0, 10))
    }

    @Test
    fun `hitTest inside and outside the rect`() {
        val l = layer(x = 0.5f, y = 0.5f, scale = 0.4f)
        // Rect: center (500,300), 400x200 (see displayRect same-aspect test).
        fun hit(px: Float, py: Float) =
            PipGestureMath.hitTest(l, px, py, 1000f, 600f, 1000f, 600f, 500, 250)
        assertTrue(hit(500f, 300f))
        assertTrue(hit(699f, 399f))
        assertFalse(hit(701f, 300f))
        assertFalse(hit(500f, 401f))
        assertFalse(hit(Float.NaN, 300f))
    }
}
