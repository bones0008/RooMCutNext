package com.RooMCutNext.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Crossfade alpha ramp math (v1.2.1) — both timestamp conventions. */
class FadeMathTest {

    @Test
    fun `item-local timestamps ramp from zero`() {
        // Head frames arrive as t in [0, fade): global start is irrelevant.
        assertEquals(0f, FadeMath.fadeInAlpha(0L, 1_000_000L, 8_000_000L), 1e-4f)
        assertEquals(0.5f, FadeMath.fadeInAlpha(500_000L, 1_000_000L, 8_000_000L), 1e-4f)
        assertEquals(0.999f, FadeMath.fadeInAlpha(999_000L, 1_000_000L, 8_000_000L), 1e-3f)
    }

    @Test
    fun `global timestamps ramp over the shifted window`() {
        // Head frames arrive with the global composition clock.
        assertEquals(0f, FadeMath.fadeInAlpha(8_000_000L, 1_000_000L, 8_000_000L), 1e-4f)
        assertEquals(0.5f, FadeMath.fadeInAlpha(8_500_000L, 1_000_000L, 8_000_000L), 1e-4f)
        assertEquals(1f, FadeMath.fadeInAlpha(9_000_000L, 1_000_000L, 8_000_000L), 1e-4f)
    }

    @Test
    fun `after the fade completes alpha is one`() {
        assertEquals(1f, FadeMath.fadeInAlpha(20_000_000L, 1_000_000L, 8_000_000L), 0f)
        // Long past the item-local window AND the global window.
        assertEquals(1f, FadeMath.fadeInAlpha(5_000_000L, 1_000_000L, 8_000_000L), 0f)
    }

    @Test
    fun `zero fade duration is a no-op`() {
        assertEquals(1f, FadeMath.fadeInAlpha(123L, 0L, 456L), 0f)
    }

    @Test
    fun `alpha never leaves the unit domain`() {
        for (t in longArrayOf(-100L, 0L, 250_000L, 999_999L, 1_000_000L, 3_000_000L, 8_250_000L)) {
            val a = FadeMath.fadeInAlpha(t, 1_000_000L, 8_000_000L)
            assert(a in 0f..1f) { "alpha out of range at t=$t: $a" }
        }
    }

    @Test
    fun `global window starting at zero collapses onto the local window`() {
        // First transition right at timeline start: globalStart == 0.
        assertEquals(0f, FadeMath.fadeInAlpha(0L, 1_000_000L, 0L), 1e-4f)
        assertEquals(0.25f, FadeMath.fadeInAlpha(250_000L, 1_000_000L, 0L), 1e-4f)
        assertEquals(1f, FadeMath.fadeInAlpha(1_000_000L, 1_000_000L, 0L), 1e-4f)
    }
}
