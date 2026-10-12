package com.RooMCutNext.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ColorAdjust v1.1: gain composition + LUT fields + JSON compatibility. */
class ColorAdjustTest {

    @Test
    fun `default is neutral`() {
        val a = ColorAdjust()
        assertTrue(a.isNeutral)
        assertTrue(a.isGainNeutral)
        assertNull(a.lutId)
        assertEquals(1f, a.lutStrength, 1e-6f)
    }

    @Test
    fun `neutral gains are identity`() {
        val g = ColorAdjust(temperature = 0f, tint = 0f).channelGains()
        assertEquals(1f, g[0], 1e-6f)
        assertEquals(1f, g[1], 1e-6f)
        assertEquals(1f, g[2], 1e-6f)
    }

    @Test
    fun `warm temperature boosts red and cuts blue`() {
        val g = ColorAdjust(temperature = 1f).channelGains()
        assertEquals(1.4f, g[0], 1e-4f)
        assertEquals(1f, g[1], 1e-4f)
        assertEquals(0.6f, g[2], 1e-4f)
        // Symmetric the other way.
        val c = ColorAdjust(temperature = -1f).channelGains()
        assertEquals(0.6f, c[0], 1e-4f)
        assertEquals(1.4f, c[2], 1e-4f)
    }

    @Test
    fun `magenta tint cuts green and lifts red blue`() {
        val g = ColorAdjust(tint = 1f).channelGains()
        assertEquals(0.65f, g[1], 1e-4f)
        assertEquals(1.175f, g[0], 1e-4f)
        assertEquals(1.175f, g[2], 1e-4f)
    }

    @Test
    fun `direct rgb gains map minus one to one`() {
        val g = ColorAdjust(rGain = 1f, gGain = -1f).channelGains()
        assertEquals(2f, g[0], 1e-4f)
        assertEquals(0f, g[1], 1e-4f)
        assertEquals(1f, g[2], 1e-4f)
    }

    @Test
    fun `gains compose multiplicatively`() {
        // Small values so the 0..2.5 clamp does not kick in.
        val solo = ColorAdjust(temperature = 0.5f).channelGains() // R=1.2
        val combo = ColorAdjust(temperature = 0.5f, rGain = 0.5f).channelGains()
        assertEquals(solo[0] * 1.5f, combo[0], 1e-4f)
    }

    @Test
    fun `extreme combinations stay clamped`() {
        val g = ColorAdjust(
            temperature = 1f, tint = 1f, rGain = 1f, gGain = 1f, bGain = 1f,
        ).channelGains()
        assertTrue(g.all { it in 0f..2.5f })
        // Raw product would be 3.29 for R — the clamp holds it at 2.5.
        assertEquals(2.5f, g[0], 1e-4f)
    }

    @Test
    fun `lut makes adjust non-neutral but not gain-neutral`() {
        val a = ColorAdjust(lutId = "builtin:warm")
        assertFalse(a.isNeutral)
        assertTrue(a.isGainNeutral)
    }

    @Test
    fun `old project json deserializes with defaults`() {
        // v1.0.x projects only had brightness/contrast/saturation.
        val json = """{"brightness":0.1,"contrast":-0.2,"saturation":0.3}"""
        val a = Json.decodeFromString<ColorAdjust>(json)
        assertEquals(0.1f, a.brightness, 1e-6f)
        assertEquals(-0.2f, a.contrast, 1e-6f)
        assertEquals(0.3f, a.saturation, 1e-6f)
        assertEquals(0f, a.temperature, 1e-6f)
        assertEquals(0f, a.tint, 1e-6f)
        assertEquals(0f, a.rGain, 1e-6f)
        assertNull(a.lutId)
        // Non-zero legacy sliders -> NOT neutral, but all NEW fields neutral.
        assertFalse(a.isNeutral)
        assertTrue(a.isGainNeutral)
    }

    @Test
    fun `round trips new fields through json`() {
        val a = ColorAdjust(
            brightness = 0.2f, temperature = -0.5f, tint = 0.25f,
            rGain = 0.1f, gGain = -0.1f, bGain = 0.05f,
            lutId = "user:teal.cube", lutStrength = 0.6f,
        )
        val b = Json.decodeFromString<ColorAdjust>(Json.encodeToString(a))
        assertEquals(a, b)
        assertEquals("user:teal.cube", b.lutId)
        assertEquals(0.6f, b.lutStrength, 1e-6f)
    }
}

/** TextStyleSpec v1.1: fontId default + JSON compatibility. */
class TextStyleSpecFontTest {

    @Test
    fun `default font is system`() {
        assertEquals("system", TextStyleSpec().fontId)
    }

    @Test
    fun `old project json deserializes with system font`() {
        val json = """{"fontSizeRatio":0.1,"color":4294967295,"bold":true}"""
        val s = Json.decodeFromString<TextStyleSpec>(json)
        assertEquals("system", s.fontId)
        assertEquals(0.1f, s.fontSizeRatio, 1e-6f)
        assertTrue(s.bold)
    }

    @Test
    fun `round trips font id`() {
        val s = TextStyleSpec(fontId = "kai")
        val b = Json.decodeFromString<TextStyleSpec>(Json.encodeToString(s))
        assertEquals("kai", b.fontId)
    }
}
