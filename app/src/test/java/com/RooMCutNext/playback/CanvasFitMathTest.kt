package com.RooMCutNext.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.3 — CanvasFitMath against media3-ui 1.11.1's
 * AspectRatioFrameLayout.onMeasure (RESIZE_MODE_FIT), source-verified.
 *
 * The regression this locks down: the bottom bright line under transition
 * heads — the overlay layer's content rect must match the library's video
 * surface rect PIXEL FOR PIXEL, including the 1% deformation-tolerance branch
 * where the library does not resize at all.
 */
class CanvasFitMathTest {

    private val sixteenNine = 16f / 9f

    @Test
    fun `deformation tolerance matches the library band`() {
        // 0.04% off: inside the 1% band.
        assertTrue(CanvasFitMath.withinDeformationTolerance(1.7778f, 1.7771f))
        // 1.6% off: outside.
        assertFalse(CanvasFitMath.withinDeformationTolerance(1.7778f, 1.75f))
        // Degenerate inputs never claim a match.
        assertFalse(CanvasFitMath.withinDeformationTolerance(0f, 1f))
        assertFalse(CanvasFitMath.withinDeformationTolerance(1f, 0f))
    }

    @Test
    fun `aspect within tolerance fills the whole stage`() {
        // THE v1.5.3 FIELD BUG: layer 2264x1274 (Compose aspectRatio(16/9)
        // stage), canvas 16:9. The library's tolerance branch keeps the video
        // surface at the FULL stage (deformation 0.04%); the old truncating
        // math computed (2264/1.7778).toInt() = 1273 — one row of the outgoing
        // clip leaked under every transition.
        val rect = CanvasFitMath.contentRect(2264, 1274, sixteenNine)
        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
        assertEquals(2264, rect.width)
        assertEquals(1274, rect.height)
    }

    @Test
    fun `video wider than view truncates height and centers`() {
        // Portrait stage 1080x1920, landscape canvas: height = trunc(1080/(16/9)) = 607,
        // top = (1920-607)/2 = 656 — the same numbers AspectRatioFrameLayout produces.
        val rect = CanvasFitMath.contentRect(1080, 1920, sixteenNine)
        assertEquals(607, rect.height)
        assertEquals(1080, rect.width)
        assertEquals(0, rect.left)
        assertEquals(656, rect.top)
    }

    @Test
    fun `video taller than view truncates width and centers`() {
        // Landscape-ish stage 1080x1120 with a portrait canvas 9:16:
        // width = trunc(1120*0.5625) = 630, left = (1080-630)/2 = 225.
        val rect = CanvasFitMath.contentRect(1080, 1120, 9f / 16f)
        assertEquals(630, rect.width)
        assertEquals(1120, rect.height)
        assertEquals(225, rect.left)
        assertEquals(0, rect.top)
    }

    @Test
    fun `degenerate layer falls back to the full rect`() {
        val rect = CanvasFitMath.contentRect(0, 0, sixteenNine)
        assertEquals(0, rect.width)
        assertEquals(0, rect.height)
    }

    @Test
    fun `canvas fit snaps full only inside the tolerance band`() {
        val canvas = sixteenNine
        // Head content aspect 0.04% off the canvas: snap (no 1px seam).
        assertTrue(CanvasFitMath.canvasFitSnapsFull(1.7771f, canvas))
        // A genuinely different aspect (4:3 source in a 16:9 canvas): fit.
        assertFalse(CanvasFitMath.canvasFitSnapsFull(4f / 3f, canvas))
        // Wider than the canvas: fit (width-constrained), not snap.
        assertFalse(CanvasFitMath.canvasFitSnapsFull(2.4f, canvas))
    }
}
