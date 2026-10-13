package com.RooMCutNext.core.util

import com.RooMCutNext.core.model.TextOverlayItem
import com.RooMCutNext.core.model.TextStyleSpec
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.0 direct-manipulation math — the rules the preview gesture layer
 * follows. Slider-parity clamps are contract here: gestures and the TEXT
 * panel sliders must never disagree about the legal value range.
 */
class TextGestureMathTest {

    private fun item(
        x: Float = 0.5f,
        y: Float = 0.5f,
        size: Float = 0.08f,
        rot: Float = 0f,
    ) = TextOverlayItem(
        id = "t1", text = "字幕", xRatio = x, yRatio = y, rotationDeg = rot,
        style = TextStyleSpec(fontSizeRatio = size),
    )

    // ---------------- contentRect (RESIZE_MODE_FIT letterbox) ----------------

    @Test
    fun `contentRect same aspect fills the view`() {
        val r = TextGestureMath.contentRect(1920f, 1080f, 1920f, 1080f)
        assertEquals(0f, r[0], 1e-4f)
        assertEquals(0f, r[1], 1e-4f)
        assertEquals(1920f, r[2], 1e-4f)
        assertEquals(1080f, r[3], 1e-4f)
    }

    @Test
    fun `contentRect portrait canvas in landscape view letterboxes horizontally`() {
        // 1080x1920 canvas in a 1920x1080 view: height fits, width = 607.5.
        val r = TextGestureMath.contentRect(1920f, 1080f, 1080f, 1920f)
        assertEquals(1080f, r[3], 1e-3f)
        assertEquals(1080f * (1080f / 1920f), r[2], 1e-3f)
        assertEquals((1920f - r[2]) / 2f, r[0], 1e-3f)
        assertEquals(0f, r[1], 1e-3f)
    }

    @Test
    fun `contentRect degenerate is all-zero`() {
        val r = TextGestureMath.contentRect(0f, 100f, 100f, 100f)
        assertTrue(r.all { it == 0f })
    }

    // ---------------- viewToCanvas ----------------

    @Test
    fun `viewToCanvas maps content center to canvas center`() {
        // 1920x1080 view showing a 960x540 canvas: content = whole view.
        val p = TextGestureMath.viewToCanvas(960f, 540f, 0f, 0f, 1920f, 960f)
        assertEquals(480f, p[0], 1e-3f)
        assertEquals(270f, p[1], 1e-3f)
    }

    @Test
    fun `viewToCanvas removes letterbox offset`() {
        // Portrait 1080x1920 canvas in a 1920x1080 view (content left ≈ 656.25).
        val r = TextGestureMath.contentRect(1920f, 1080f, 1080f, 1920f)
        val p = TextGestureMath.viewToCanvas(r[0], r[1], r[0], r[1], r[2], 1080f)
        assertEquals(0f, p[0], 1e-3f)
        assertEquals(0f, p[1], 1e-3f)
    }

    @Test
    fun `viewToCanvas degenerate yields NaN`() {
        val p = TextGestureMath.viewToCanvas(10f, 10f, 0f, 0f, 0f, 1080f)
        assertTrue(p[0].isNaN() && p[1].isNaN())
    }

    // ---------------- pan ----------------

    @Test
    fun `pan moves by view-pixel delta scaled through the content rect`() {
        val base = item(x = 0.5f, y = 0.5f)
        // content 400x200 px: moving +100 view px = +0.25 of width.
        val moved = TextGestureMath.pan(base, 100f, 50f, 400f, 200f)
        assertEquals(0.75f, moved.xRatio, 1e-4f)
        assertEquals(0.75f, moved.yRatio, 1e-4f)
    }

    @Test
    fun `pan clamps to the slider domain`() {
        val base = item(x = 0.9f, y = 0.1f)
        val moved = TextGestureMath.pan(base, +1000f, -1000f, 400f, 200f)
        assertEquals(TextGestureMath.X_MAX, moved.xRatio, 1e-6f)
        assertEquals(TextGestureMath.Y_MIN, moved.yRatio, 1e-6f)
    }

    @Test
    fun `pan with degenerate content is a no-op`() {
        val base = item()
        assertEquals(base, TextGestureMath.pan(base, 100f, 100f, 0f, 0f))
    }

    // ---------------- zoom ----------------

    @Test
    fun `zoom scales the font size from the anchor`() {
        val base = item(size = 0.1f)
        assertEquals(0.2f, TextGestureMath.zoom(base, 2f).style.fontSizeRatio, 1e-6f)
        assertEquals(0.05f, TextGestureMath.zoom(base, 0.5f).style.fontSizeRatio, 1e-6f)
    }

    @Test
    fun `zoom clamps to the slider domain`() {
        val base = item(size = 0.1f)
        assertEquals(TextGestureMath.SIZE_MAX, TextGestureMath.zoom(base, 100f).style.fontSizeRatio, 1e-6f)
        assertEquals(TextGestureMath.SIZE_MIN, TextGestureMath.zoom(base, 0.001f).style.fontSizeRatio, 1e-6f)
    }

    @Test
    fun `zoom with invalid scale is a no-op`() {
        val base = item()
        assertEquals(base, TextGestureMath.zoom(base, Float.NaN))
        assertEquals(base, TextGestureMath.zoom(base, 0f))
        assertEquals(base, TextGestureMath.zoom(base, -1f))
    }

    // ---------------- rotate / wrapDeg ----------------

    @Test
    fun `rotate accumulates from the anchor`() {
        val base = item(rot = 10f)
        assertEquals(100f, TextGestureMath.rotate(base, 90f).rotationDeg, 1e-4f)
    }

    @Test
    fun `wrapDeg maps into the slider domain`() {
        assertEquals(0f, TextGestureMath.wrapDeg(360f), 1e-4f)
        assertEquals(0f, TextGestureMath.wrapDeg(0f), 1e-4f)
        assertEquals(-90f, TextGestureMath.wrapDeg(270f), 1e-4f)
        assertEquals(90f, TextGestureMath.wrapDeg(-270f), 1e-4f)
        assertEquals(180f, TextGestureMath.wrapDeg(180f), 1e-4f)
        assertEquals(-180f, TextGestureMath.wrapDeg(-180f), 1e-4f)
        assertEquals(179.5f, TextGestureMath.wrapDeg(539.5f), 1e-4f)
    }

    // ---------------- hitRotatedRect ----------------

    @Test
    fun `hitRotatedRect axis aligned`() {
        assertTrue(TextGestureMath.hitRotatedRect(105f, 100f, 100f, 100f, 50f, 20f, 0f))
        assertFalse(TextGestureMath.hitRotatedRect(151f, 100f, 100f, 100f, 50f, 20f, 0f))
        assertFalse(TextGestureMath.hitRotatedRect(100f, 121f, 100f, 100f, 50f, 20f, 0f))
        assertTrue(TextGestureMath.hitRotatedRect(100f, 100f, 100f, 100f, 50f, 20f, 0f)) // center
    }

    @Test
    fun `hitRotatedRect quarter turn swaps the local axes`() {
        // A 100x40 rect rotated 90° around (100,100) spans x∈[80,120], y∈[50,150].
        assertTrue(TextGestureMath.hitRotatedRect(100f, 130f, 100f, 100f, 50f, 20f, 90f))
        assertTrue(TextGestureMath.hitRotatedRect(115f, 100f, 100f, 100f, 50f, 20f, 90f))
        assertFalse(TextGestureMath.hitRotatedRect(125f, 100f, 100f, 100f, 50f, 20f, 90f))
        assertFalse(TextGestureMath.hitRotatedRect(100f, 155f, 100f, 100f, 50f, 20f, 90f))
        // 45° diagonal: the local x-axis lies ON the world diagonal, so a
        // world-diagonal point at distance d maps to local (d, 0) — inside
        // iff d ≤ halfW = 50. d = 55 is outside the RECT (and even beyond
        // the corner distance hypot(50, 20) ≈ 53.85): hit-testing is the
        // rotated rect, not a bounding circle.
        val inD = 45f / kotlin.math.sqrt(2f)
        val outD = 55f / kotlin.math.sqrt(2f)
        assertTrue(TextGestureMath.hitRotatedRect(100f + inD, 100f + inD, 100f, 100f, 50f, 20f, 45f))
        assertFalse(TextGestureMath.hitRotatedRect(100f + outD, 100f + outD, 100f, 100f, 50f, 20f, 45f))
    }

    @Test
    fun `hitRotatedRect degenerate never hits`() {
        assertFalse(TextGestureMath.hitRotatedRect(100f, 100f, 100f, 100f, 0f, 20f, 0f))
        assertFalse(TextGestureMath.hitRotatedRect(Float.NaN, 100f, 100f, 100f, 50f, 20f, 0f))
    }

    // ---------------- twoFingerAnchors ----------------

    @Test
    fun `twoFingerAnchors 3-4-5 triangle`() {
        val a = TextGestureMath.twoFingerAnchors(0f, 0f, 3f, 4f)
        assertTrue(a != null)
        assertEquals(5f, a!![0], 1e-4f)
        assertTrue(abs(a[1] - 53.130102f) < 1e-3f)
    }

    @Test
    fun `twoFingerAnchors coincident points are null`() {
        assertNull(TextGestureMath.twoFingerAnchors(10f, 10f, 10f, 10f))
        assertNull(TextGestureMath.twoFingerAnchors(Float.NaN, 0f, 1f, 1f))
    }

    // ---------------- composite gesture ----------------

    @Test
    fun `pan then zoom then rotate compose without interference`() {
        var it = item(x = 0.5f, y = 0.5f, size = 0.1f, rot = 0f)
        it = TextGestureMath.pan(it, 100f, 0f, 400f, 200f)   // x: 0.5 → 0.75
        it = TextGestureMath.zoom(it, 1.5f)                  // size: 0.1 → 0.15
        it = TextGestureMath.rotate(it, -90f)                 // rot: 0 → -90
        assertEquals(0.75f, it.xRatio, 1e-4f)
        assertEquals(0.5f, it.yRatio, 1e-4f)
        assertEquals(0.15f, it.style.fontSizeRatio, 1e-6f)
        assertEquals(-90f, it.rotationDeg, 1e-4f)
        assertEquals(3_000_000L, it.endUs) // untouched fields untouched
        assertEquals("字幕", it.text)
    }
}
