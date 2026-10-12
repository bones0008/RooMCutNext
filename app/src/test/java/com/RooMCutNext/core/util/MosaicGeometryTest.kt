package com.RooMCutNext.core.util

import com.RooMCutNext.core.model.MosaicRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mosaic GL geometry (v1.2.2): intuitive→GL rect conversion + block counts. */
class MosaicGeometryTest {

    @Test
    fun `centered region maps to a centered GL rect`() {
        val r = MosaicRegion(id = "m", x = 0.5f, y = 0.5f, w = 0.4f, h = 0.4f)
        val rect = MosaicGeometry.glRegion(r)
        assertEquals(0.3f, rect[0], 1e-4f)  // xMin
        assertEquals(0.3f, rect[1], 1e-4f)  // glYMin (bottom)
        assertEquals(0.7f, rect[2], 1e-4f)  // xMax
        assertEquals(0.7f, rect[3], 1e-4f)  // glYMax (top)
    }

    @Test
    fun `gl rect y axis is flipped from the intuitive one`() {
        // Top-half region (y=0.25, h=0.5): intuitive [0.0, 0.5] from the top
        // maps to GL [0.5, 1.0] (v=1 is the picture's top).
        val r = MosaicRegion(id = "m", x = 0.5f, y = 0.25f, w = 0.5f, h = 0.5f)
        val rect = MosaicGeometry.glRegion(r)
        assertEquals(0.5f, rect[1], 1e-4f)
        assertEquals(1.0f, rect[3], 1e-4f)
        assertTrue(rect[1] < rect[3])
    }

    @Test
    fun `region hanging off the edge is clamped into the frame`() {
        val r = MosaicRegion(id = "m", x = 0.95f, y = 0.95f, w = 0.5f, h = 0.5f)
        val rect = MosaicGeometry.glRegion(r)
        assertTrue(rect[0] in 0f..1f && rect[2] in 0f..1f)
        assertTrue(rect[1] in 0f..1f && rect[3] in 0f..1f)
        assertTrue(rect[0] <= rect[2] && rect[1] <= rect[3])
    }

    @Test
    fun `square blocks in pixel space on a 16-9 frame`() {
        // Region w=0.5 h=0.5 on 1920x1080, blockRatio=0.05 (96px):
        // blocksX = (0.5*1920)/96 = 10; blocksY = (0.5*1080)/96 = 5.625.
        val r = MosaicRegion(id = "m", x = 0.5f, y = 0.5f, w = 0.5f, h = 0.5f, blockRatio = 0.05f)
        val blocks = MosaicGeometry.blocks(r, 1920, 1080)
        assertEquals(10f, blocks[0], 1e-3f)
        assertEquals(5.625f, blocks[1], 1e-3f)
    }

    @Test
    fun `block counts never drop below one`() {
        val r = MosaicRegion(id = "m", x = 0.5f, y = 0.5f, w = 0.05f, h = 0.05f, blockRatio = 0.25f)
        val blocks = MosaicGeometry.blocks(r, 1920, 1080)
        assertTrue(blocks[0] >= 1f && blocks[1] >= 1f)
    }

    @Test
    fun `blockPixels is whole pixels of the frame width`() {
        val r = MosaicRegion(id = "m", blockRatio = 0.03f)
        assertEquals(58, MosaicGeometry.blockPixels(r, 1920)) // 57.6 rounds to 58
        assertEquals(32, MosaicGeometry.blockPixels(r, 1080))
    }
}
