package com.RooMCutNext.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameMathTest {

    // ---------------- aspectFitRect ----------------

    @Test
    fun `same aspect returns full canvas`() {
        val r = aspectFitRect(1920, 1080, 1920, 1080)
        assertEquals(0, r.left)
        assertEquals(0, r.top)
        assertEquals(1920, r.width)
        assertEquals(1080, r.height)
    }

    @Test
    fun `wide video in tall canvas is letterboxed centered`() {
        // 16:9 content inside a 9:16 canvas (portrait phone preview)
        val r = aspectFitRect(1080, 1920, 1920, 1080)
        assertEquals(1080, r.width)
        val expectedH = 1080 / (1920f / 1080f) // 607.5 -> 607
        assertEquals(expectedH.toInt(), r.height)
        // centered: top/bottom insets differ by at most 1 (odd leftover px)
        assertEquals((1920 - r.height) / 2, r.top)
        org.junit.Assert.assertTrue(
            "insets differ by at most 1",
            kotlin.math.abs((1920 - r.bottom) - r.top) <= 1,
        )
        assertEquals(r.height, r.bottom - r.top)
    }

    @Test
    fun `tall video in wide canvas is pillarboxed centered`() {
        // 9:16 content inside a 16:9 canvas
        val r = aspectFitRect(1920, 1080, 1080, 1920)
        assertEquals(1080, r.height)
        val expectedW = 1080 * (1080f / 1920f) // 607.5 -> 607
        assertEquals(expectedW.toInt(), r.width)
        assertEquals((1920 - r.width) / 2, r.left)
        org.junit.Assert.assertTrue(
            "insets differ by at most 1",
            kotlin.math.abs((1920 - r.right) - r.left) <= 1,
        )
        assertEquals(r.width, r.right - r.left)
    }

    @Test
    fun `square content in 4-3 canvas pillarboxes`() {
        val r = aspectFitRect(800, 600, 600, 600)
        assertEquals(600, r.height)
        assertEquals(600, r.width)
        assertEquals(100, r.left)
        assertEquals(700, r.right)
    }

    @Test
    fun `degenerate content falls back to full canvas`() {
        val r = aspectFitRect(1000, 500, 0, 0)
        assertEquals(0, r.left)
        assertEquals(1000, r.width)
        assertEquals(500, r.height)
    }

    @Test
    fun `degenerate canvas returns empty rect`() {
        val r = aspectFitRect(0, 0, 100, 100)
        assertEquals(0, r.width)
        assertEquals(0, r.height)
    }

    @Test
    fun `tiny crop never rounds to zero`() {
        // extreme aspect that would compute h = 0 without the coerceIn guard
        val r = aspectFitRect(1000, 1000, 100000, 1)
        assertEquals(1000, r.width)
        org.junit.Assert.assertTrue("height coerced to >= 1", r.height >= 1)
        org.junit.Assert.assertTrue("height stays within canvas", r.height <= 1000)
    }
}
