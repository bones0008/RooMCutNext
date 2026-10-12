package com.RooMCutNext.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CubeLut (.cube 3D LUT) parser tests.
 *
 * The r-fastest index order and the exact file quirks (headers, comments,
 * blank lines, CRLF, out-of-range clamping) mirror real Premiere/DaVinci
 * exports.
 */
class CubeLutTest {

    private fun cubeText(size: Int, lines: List<String>, title: String? = null): String =
        buildString {
            if (title != null) appendLine("TITLE \"$title\"")
            appendLine("LUT_3D_SIZE $size")
            appendLine("DOMAIN_MIN 0.0 0.0 0.0")
            appendLine("DOMAIN_MAX 1.0 1.0 1.0")
            lines.forEach { appendLine(it) }
        }

    /** Identity cube of any size: every sample equals its own grid point. */
    private fun identityText(size: Int): String {
        val lines = mutableListOf<String>()
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    lines.add("${"%.6f".format(r.toFloat() / (size - 1))} " +
                        "${"%.6f".format(g.toFloat() / (size - 1))} " +
                        "${"%.6f".format(b.toFloat() / (size - 1))}")
                }
            }
        }
        return cubeText(size, lines)
    }

    @Test
    fun `parses identity 2x2x2 cube with r fastest`() {
        // r fastest: (0,0,0) (1,0,0) (0,1,0) (1,1,0) (0,0,1) (1,0,1) (0,1,1) (1,1,1)
        val text = cubeText(2, listOf(
            "0.0 0.0 0.0",
            "1.0 0.0 0.0",
            "0.0 1.0 0.0",
            "1.0 1.0 0.0",
            "0.0 0.0 1.0",
            "1.0 0.0 1.0",
            "0.0 1.0 1.0",
            "1.0 1.0 1.0",
        ))
        val lut = CubeLut.parse(text)
        assertEquals(2, lut.size)
        assertEquals(8 * 3, lut.rgb.size)
        // grid(r, g, b) returns the mapped color; identity -> the grid point.
        assertEquals(0f, lut.grid(0, 0, 0).first, 1e-6f)
        assertEquals(1f, lut.grid(1, 0, 0).first, 1e-6f)
        assertEquals(0f, lut.grid(1, 0, 0).second, 1e-6f)
        assertEquals(1f, lut.grid(0, 1, 0).second, 1e-6f)
        assertEquals(1f, lut.grid(0, 0, 1).third, 1e-6f)
        assertEquals(0f, lut.grid(0, 0, 1).first, 1e-6f)
    }

    @Test
    fun `identity 17 cube round trips`() {
        val lut = CubeLut.parse(identityText(17))
        assertEquals(17, lut.size)
        assertEquals(17 * 17 * 17 * 3, lut.rgb.size)
        for (b in 0 until 17) {
            for (g in 0 until 17) {
                for (r in 0 until 17) {
                    val (rr, gg, bb) = lut.grid(r, g, b)
                    assertEquals(r / 16f, rr, 1e-5f)
                    assertEquals(g / 16f, gg, 1e-5f)
                    assertEquals(b / 16f, bb, 1e-5f)
                }
            }
        }
    }

    @Test
    fun `tolerates comments blanks crlf and title`() {
        val text = "# a comment\r\n\r\nTITLE \"My Look\"\r\nLUT_3D_SIZE 2\r\n" +
            "0 0 0\r\n0.5 0 0\r\n0 0.5 0\r\n0.5 0.5 0\r\n" +
            "0 0 0.5\r\n0.5 0 0.5\r\n0 0.5 0.5\r\n0.5 0.5 0.5\r\n"
        val lut = CubeLut.parse(text)
        assertEquals(2, lut.size)
        assertEquals("My Look", lut.title)
        assertEquals(0.5f, lut.grid(1, 0, 0).first, 1e-6f)
    }

    @Test
    fun `clamps out-of-range samples`() {
        val text = cubeText(2, listOf(
            "-0.5 0.0 2.0",
            "1.0 0.0 0.0",
            "0.0 1.0 0.0",
            "1.0 1.0 0.0",
            "0.0 0.0 1.0",
            "1.0 0.0 1.0",
            "0.0 1.0 1.0",
            "1.0 1.0 1.0",
        ))
        val lut = CubeLut.parse(text)
        // Negative clamps to 0, >1 clamps to 1 — no exception.
        assertEquals(0f, lut.grid(0, 0, 0).first, 1e-6f)
        assertEquals(1f, lut.grid(0, 0, 0).third, 1e-6f)
    }

    @Test
    fun `rejects missing size header`() {
        assertThrows(IllegalArgumentException::class.java) {
            CubeLut.parse("0.0 0.0 0.0\n0.1 0.1 0.1\n")
        }
    }

    @Test
    fun `rejects 1D LUTs`() {
        assertThrows(IllegalArgumentException::class.java) {
            CubeLut.parse("LUT_1D_SIZE 2\n0.0\n1.0\n")
        }
    }

    @Test
    fun `rejects wrong sample count`() {
        val text = cubeText(3, (0 until 26).map { "0.1 0.2 0.3" })
        assertThrows(IllegalArgumentException::class.java) {
            CubeLut.parse(text)
        }
    }

    @Test
    fun `rejects non-numeric samples`() {
        val text = cubeText(2, listOf(
            "zero 0.0 0.0",
            "1.0 0.0 0.0",
            "0.0 1.0 0.0",
            "1.0 1.0 0.0",
            "0.0 0.0 1.0",
            "1.0 0.0 1.0",
            "0.0 1.0 1.0",
            "1.0 1.0 1.0",
        ))
        assertThrows(IllegalArgumentException::class.java) {
            CubeLut.parse(text)
        }
    }

    @Test
    fun `rejects absurd size`() {
        assertThrows(IllegalArgumentException::class.java) {
            CubeLut.parse("LUT_3D_SIZE 1000\n")
        }
    }

    @Test
    fun `trilinear sampling hits grid points and midpoints`() {
        val lut = CubeLut.parse(identityText(3))
        // Exact grid point.
        val a = lut.sample(0.5f, 0.5f, 0.5f)
        assertEquals(0.5f, a.first, 1e-5f)
        // Midpoint between grid points on r axis (n=3 -> step 0.5).
        val b = lut.sample(0.25f, 0.5f, 0.5f)
        assertEquals(0.25f, b.first, 1e-4f)
        // Corners.
        assertEquals(0f, lut.sample(0f, 0f, 0f).first, 1e-6f)
        assertEquals(1f, lut.sample(1f, 1f, 1f).first, 1e-6f)
    }

    @Test
    fun `preview swatches are 8 opaque argb ints`() {
        val lut = CubeLut.parse(identityText(4))
        val sw = lut.previewSwatches()
        assertEquals(8, sw.size)
        sw.forEach {
            assertEquals(0xFF, (it ushr 24) and 0xFF)
            val r = (it shr 16) and 0xFF
            val g = (it shr 8) and 0xFF
            val b = it and 0xFF
            assertTrue(r in 0..255 && g in 0..255 && b in 0..255)
        }
    }

    @Test
    fun `parses a realistic 33-line headerless body`() {
        // Some free LUTs on the internet have no DOMAIN lines at all.
        val sb = StringBuilder("LUT_3D_SIZE 2\n")
        repeat(8) { sb.appendLine("0.25 0.5 0.75") }
        val lut = CubeLut.parse(sb.toString())
        assertNotNull(lut)
        assertEquals(0.25f, lut.grid(0, 0, 0).first, 1e-6f)
        assertEquals(0.75f, lut.grid(0, 0, 0).third, 1e-6f)
    }
}
