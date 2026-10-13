package com.RooMCutNext.core.util

import com.RooMCutNext.core.model.TransitionStyle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.0 — the pure math behind the transition GLSL (the JVM-side source of
 * truth the shader branches are a literal transcription of, same pattern as
 * MosaicGeometry vs MosaicEffect).
 *
 * SCREEN-UV convention shared with the shader (v=1 at the TOP of the picture,
 * the media3 texture convention — see FadeInEffect's vertex shader note):
 *  - x ∈ [0,1]: left → right
 *  - y ∈ [0,1]: bottom → TOP
 *  - aspect = frame width / height
 *
 * Expected values below are HAND-CALCULATED literals (geometry on paper),
 * never recomputed with the same formulas — that is what pins the shader
 * semantics down.
 */
class TransitionMathTest {

    // ------------------------------------------------------------------
    // progress — the two timestamp conventions (v1.2.1 FadeMath semantics,
    // generalized from "alpha" to "transition progress 0..1").
    // ------------------------------------------------------------------

    @Test
    fun `item-local timestamps ramp from zero`() {
        assertEquals(0f, TransitionMath.progress(0L, 1_000_000L, 8_000_000L), 1e-4f)
        assertEquals(0.5f, TransitionMath.progress(500_000L, 1_000_000L, 8_000_000L), 1e-4f)
        assertEquals(0.999f, TransitionMath.progress(999_000L, 1_000_000L, 8_000_000L), 1e-3f)
    }

    @Test
    fun `global timestamps ramp over the shifted window`() {
        assertEquals(0f, TransitionMath.progress(8_000_000L, 1_000_000L, 8_000_000L), 1e-4f)
        assertEquals(0.5f, TransitionMath.progress(8_500_000L, 1_000_000L, 8_000_000L), 1e-4f)
        assertEquals(1f, TransitionMath.progress(9_000_000L, 1_000_000L, 8_000_000L), 1e-4f)
    }

    @Test
    fun `after the transition completes progress is one`() {
        assertEquals(1f, TransitionMath.progress(20_000_000L, 1_000_000L, 8_000_000L), 0f)
        assertEquals(1f, TransitionMath.progress(5_000_000L, 1_000_000L, 8_000_000L), 0f)
    }

    @Test
    fun `zero duration is a no-op`() {
        assertEquals(1f, TransitionMath.progress(123L, 0L, 456L), 0f)
    }

    @Test
    fun `progress never leaves the unit domain`() {
        for (t in longArrayOf(-100L, 0L, 250_000L, 999_999L, 1_000_000L, 3_000_000L, 8_250_000L)) {
            val v = TransitionMath.progress(t, 1_000_000L, 8_000_000L)
            assert(v in 0f..1f) { "progress out of range at t=$t: $v" }
        }
    }

    @Test
    fun `source-clock timestamps span a speed-scaled window`() {
        // The PREVIEW head player feeds SOURCE-clock timestamps into the
        // effect chain, so the caller passes fadeUs = T*speed (the head's
        // source span) and globalStartUs = -1 (never matches; the local
        // window is the live one). 2x speed, 1s timeline fade -> 2s source.
        assertEquals(0.5f, TransitionMath.progress(1_000_000L, 2_000_000L, -1L), 1e-4f)
        assertEquals(1f, TransitionMath.progress(2_000_000L, 2_000_000L, -1L), 1e-4f)
    }

    // ------------------------------------------------------------------
    // slide — the incoming clip B travels INTO the frame (its leading edge
    // starts off-screen and reaches the far edge at p=1). UV offsets below
    // are "sample B at uv + offset" so the content visibly MOVES.
    // ------------------------------------------------------------------

    @Test
    fun `slide left enters from the right edge`() {
        val s = TransitionStyle.SLIDE_LEFT
        // p=0.5: B's leading (left) edge crossed to x=0.5.
        assertEquals(0f, TransitionMath.slideMask(s, 0.25f, 0.5f, 0.5f), 1e-4f)
        assertEquals(1f, TransitionMath.slideMask(s, 0.75f, 0.5f, 0.5f), 1e-4f)
        // Screen x=0.75 shows B's x=0.25 (offset = p-1 = -0.5).
        val uv = TransitionMath.slideUv(s, 0.75f, 0.5f, 0.5f)
        assertEquals(0.25f, uv[0], 1e-4f)
        assertEquals(0.5f, uv[1], 1e-4f)
    }

    @Test
    fun `slide right enters from the left edge`() {
        val s = TransitionStyle.SLIDE_RIGHT
        assertEquals(1f, TransitionMath.slideMask(s, 0.25f, 0.5f, 0.5f), 1e-4f)
        assertEquals(0f, TransitionMath.slideMask(s, 0.75f, 0.5f, 0.5f), 1e-4f)
        val uv = TransitionMath.slideUv(s, 0.25f, 0.5f, 0.5f)
        // Screen x=0.25 shows B's x=0.75 (offset = 1-p = +0.5).
        assertEquals(0.75f, uv[0], 1e-4f)
    }

    @Test
    fun `slide up enters from the bottom edge`() {
        val s = TransitionStyle.SLIDE_UP
        // y=1 is the TOP: at p=0.5 the bottom half of the screen shows B.
        assertEquals(1f, TransitionMath.slideMask(s, 0.5f, 0.25f, 0.5f), 1e-4f)
        assertEquals(0f, TransitionMath.slideMask(s, 0.5f, 0.75f, 0.5f), 1e-4f)
        val uv = TransitionMath.slideUv(s, 0.5f, 0.25f, 0.5f)
        // The bottom of the screen shows the BOTTOM part of B at first? No:
        // B slides UP, its TOP leads — screen y=0.25 shows B's y=0.75.
        assertEquals(0.75f, uv[1], 1e-4f)
        assertEquals(0.5f, uv[0], 1e-4f)
    }

    @Test
    fun `slide down enters from the top edge`() {
        val s = TransitionStyle.SLIDE_DOWN
        assertEquals(1f, TransitionMath.slideMask(s, 0.5f, 0.75f, 0.5f), 1e-4f)
        assertEquals(0f, TransitionMath.slideMask(s, 0.5f, 0.25f, 0.5f), 1e-4f)
        val uv = TransitionMath.slideUv(s, 0.5f, 0.75f, 0.5f)
        assertEquals(0.25f, uv[1], 1e-4f)
    }

    @Test
    fun `slides are invisible at p=0 and identity at p=1`() {
        TransitionStyle.entries.filter { it.group == TransitionStyle.Group.SLIDE }.forEach { s ->
            assertEquals("mask at p=0 ($s)", 0f, TransitionMath.slideMask(s, 0.5f, 0.5f, 0f), 1e-4f)
            assertEquals("mask at p=1 ($s)", 1f, TransitionMath.slideMask(s, 0.5f, 0.5f, 1f), 1e-4f)
            val uv = TransitionMath.slideUv(s, 0.3f, 0.7f, 1f)
            assertEquals("uv at p=1 ($s)", 0.3f, uv[0], 1e-4f)
            assertEquals("uv at p=1 ($s)", 0.7f, uv[1], 1e-4f)
        }
    }

    // ------------------------------------------------------------------
    // wipe — B sits in place; a soft reveal FRONT sweeps across it. No UV
    // offset at all (the mask does all the work).
    // ------------------------------------------------------------------

    @Test
    fun `wipe right reveals from the left edge`() {
        val s = TransitionStyle.WIPE_RIGHT
        assertEquals(1f, TransitionMath.wipeMask(s, 0.25f, 0.5f, 0.5f), 1e-4f)
        assertEquals(0f, TransitionMath.wipeMask(s, 0.75f, 0.5f, 0.5f), 1e-4f)
    }

    @Test
    fun `wipe left reveals from the right edge`() {
        val s = TransitionStyle.WIPE_LEFT
        assertEquals(0f, TransitionMath.wipeMask(s, 0.25f, 0.5f, 0.5f), 1e-4f)
        assertEquals(1f, TransitionMath.wipeMask(s, 0.75f, 0.5f, 0.5f), 1e-4f)
    }

    @Test
    fun `wipe up reveals from the bottom edge`() {
        val s = TransitionStyle.WIPE_UP
        assertEquals(1f, TransitionMath.wipeMask(s, 0.5f, 0.25f, 0.5f), 1e-4f)
        assertEquals(0f, TransitionMath.wipeMask(s, 0.5f, 0.75f, 0.5f), 1e-4f)
    }

    @Test
    fun `wipe down reveals from the top edge`() {
        val s = TransitionStyle.WIPE_DOWN
        assertEquals(0f, TransitionMath.wipeMask(s, 0.5f, 0.25f, 0.5f), 1e-4f)
        assertEquals(1f, TransitionMath.wipeMask(s, 0.5f, 0.75f, 0.5f), 1e-4f)
    }

    @Test
    fun `wipes are fully open at p=1`() {
        TransitionStyle.entries.filter { it.group == TransitionStyle.Group.WIPE && it.id.startsWith("wipe") }
            .forEach { s ->
                assertEquals(1f, TransitionMath.wipeMask(s, 0.5f, 0.5f, 1f), 1e-4f)
                assertEquals(0f, TransitionMath.wipeMask(s, 0.5f, 0.5f, 0f), 1e-4f)
            }
    }

    // ------------------------------------------------------------------
    // iris (circle) — open reveals B from the center out; close pinches the
    // outgoing picture through black first (the A half) then opens B.
    // ------------------------------------------------------------------

    @Test
    fun `iris open radius grows to the corner distance`() {
        val aspect = 1f // square: R = 0.5*sqrt(2) = 0.7071
        assertEquals(0.3536f, TransitionMath.irisOpenRadius(0.5f, aspect), 1e-3f)
        assertEquals(0.7071f, TransitionMath.irisOpenRadius(1f, aspect), 1e-3f)
        // 16:9 frame: R = 0.5*sqrt((16/9)^2 + 1) = 0.5*sqrt(4.1605) = 1.0199
        // (x is aspect-corrected into square space before measuring).
        assertEquals(1.0199f, TransitionMath.irisOpenRadius(1f, 16f / 9f), 1e-3f)
    }

    @Test
    fun `iris close pinches A then opens B through black`() {
        // r(p) = |2p-1| * R: zero at the midpoint, full at both ends.
        assertEquals(0.3536f, TransitionMath.irisCloseRadius(0.25f, 1f), 1e-3f) // |2*.25-1|*0.7071
        assertEquals(0.7071f, TransitionMath.irisCloseRadius(0f, 1f), 1e-3f)
        assertEquals(0f, TransitionMath.irisCloseRadius(0.5f, 1f), 1e-4f)
        // First half shows A inside the circle (head is black outside);
        // second half shows B inside, black outside.
        assertTrue(TransitionMath.irisShowsUnderneath(0.25f))
        assertTrue(!TransitionMath.irisShowsUnderneath(0.75f))
    }

    // ------------------------------------------------------------------
    // clock wipe — a clockwise sweep starting at 12 o'clock.
    // ------------------------------------------------------------------

    @Test
    fun `clock angle starts at twelve and runs clockwise`() {
        val a = 1f
        assertEquals(0f, TransitionMath.clockAngle(0.5f, 0.75f, a), 1e-3f)   // 12
        assertEquals(0.25f, TransitionMath.clockAngle(0.75f, 0.5f, a), 1e-3f) // 3
        assertEquals(0.5f, TransitionMath.clockAngle(0.5f, 0.25f, a), 1e-3f)  // 6
        assertEquals(0.75f, TransitionMath.clockAngle(0.25f, 0.5f, a), 1e-3f) // 9
    }

    @Test
    fun `clock angle covers the full unit domain`() {
        for (x in 0..10) for (y in 0..10) {
            val v = TransitionMath.clockAngle(x / 10f, y / 10f, 16f / 9f)
            assert(v in 0f..1f) { "angle out of range at ($x,$y): $v" }
        }
    }

    // ------------------------------------------------------------------
    // zoom / spin / blur / mosaic / flash / blinds / through-color
    // ------------------------------------------------------------------

    @Test
    fun `zoom in scales from a quarter to full`() {
        assertEquals(0.25f, TransitionMath.zoomScale(TransitionStyle.ZOOM_IN, 0f), 1e-4f)
        assertEquals(1f, TransitionMath.zoomScale(TransitionStyle.ZOOM_IN, 1f), 1e-4f)
    }

    @Test
    fun `zoom out scales from oversize back to full`() {
        assertEquals(1.8f, TransitionMath.zoomScale(TransitionStyle.ZOOM_OUT, 0f), 1e-4f)
        assertEquals(1f, TransitionMath.zoomScale(TransitionStyle.ZOOM_OUT, 1f), 1e-4f)
        // The zoom-out fade-in completes within the first 30%.
        assertEquals(0f, TransitionMath.zoomOutRevealAlpha(0f), 1e-4f)
        assertEquals(1f, TransitionMath.zoomOutRevealAlpha(0.4f), 1e-4f)
    }

    @Test
    fun `spin unwinds 240 degrees while growing to full size`() {
        assertEquals(240f, Math.toDegrees(TransitionMath.spinAngleRad(0f).toDouble()).toFloat(), 1e-2f)
        assertEquals(0f, TransitionMath.spinAngleRad(1f), 1e-4f)
        assertEquals(0.4f, TransitionMath.spinScale(0f), 1e-4f)
        assertEquals(1f, TransitionMath.spinScale(1f), 1e-4f)
    }

    @Test
    fun `blur dissolves from a soft blur to sharp`() {
        assertEquals(0.05f, TransitionMath.blurRadiusUv(0f), 1e-4f)
        assertEquals(0f, TransitionMath.blurRadiusUv(1f), 1e-4f)
        assertTrue(TransitionMath.blurRadiusUv(0.5f) in 0.02f..0.03f) // linear: 0.025
    }

    @Test
    fun `mosaic blocks refine as the transition proceeds`() {
        assertEquals(24f, TransitionMath.mosaicBlocks(0f), 1e-3f)
        assertTrue(TransitionMath.mosaicBlocks(1f) > 700f) // ~per-pixel by the end
        assertTrue(TransitionMath.mosaicBlocks(0.5f) > 24f && TransitionMath.mosaicBlocks(0.5f) < TransitionMath.mosaicBlocks(1f))
        // The last stretch cross-fades the residual quantization away.
        assertEquals(0f, TransitionMath.mosaicClearMix(0.5f), 1e-4f)
        assertEquals(1f, TransitionMath.mosaicClearMix(1f), 1e-4f)
    }

    @Test
    fun `flash peaks white at the midpoint only`() {
        assertEquals(0f, TransitionMath.flashBoost(0f), 1e-4f)
        assertEquals(0.8f, TransitionMath.flashBoost(0.5f), 1e-2f)
        assertEquals(0f, TransitionMath.flashBoost(1f), 1e-4f)
        assertEquals(0.2f, TransitionMath.flashBoost(0.25f), 0.05f) // (1-0.5)^2*0.8 = 0.2
    }

    @Test
    fun `blinds light up band by band top to bottom`() {
        val n = 8
        assertEquals(1f, TransitionMath.blindsBandAlpha(0, 0.5f, n), 1e-4f) // top band done
        assertEquals(0.5f, TransitionMath.blindsBandAlpha(4, 0.5f, n), 1e-4f) // sweeping band
        assertEquals(0f, TransitionMath.blindsBandAlpha(6, 0.5f, n), 1e-4f) // not reached
        assertEquals(1f, TransitionMath.blindsBandAlpha(n - 1, 1f, n), 1e-4f) // all done
    }

    @Test
    fun `through-color fades cover then reveal`() {
        // fade_black / fade_white: the head takes over the whole frame by
        // the midpoint (alpha reaches 1), then B fades in inside it.
        assertEquals(0.5f, TransitionMath.throughColorAlpha(0.25f), 1e-4f)
        assertEquals(1f, TransitionMath.throughColorAlpha(0.5f), 1e-4f)
        assertEquals(0f, TransitionMath.throughColorTexMix(0.25f), 1e-4f)
        assertEquals(0.5f, TransitionMath.throughColorTexMix(0.75f), 1e-4f)
        assertEquals(1f, TransitionMath.throughColorTexMix(1f), 1e-4f)
    }

    // ------------------------------------------------------------------
    // premultiply — the v1.5.1 consumer alpha-convention fix. The shader's
    // final write is premultiplied ONLY for the TextureView preview path;
    // the export compositor blends GL_SRC_ALPHA and needs straight alpha.
    // ------------------------------------------------------------------

    @Test
    fun `premultiplied output scales rgb by alpha and keeps the alpha channel`() {
        // (0.8, 0.4, 0.2) at a=0.5 -> (0.4, 0.2, 0.1, 0.5) — hand-calculated.
        val out = TransitionMath.premultiply(0.8f, 0.4f, 0.2f, 0.5f, premultiplied = true)
        assertEquals(0.4f, out[0], 1e-5f)
        assertEquals(0.2f, out[1], 1e-5f)
        assertEquals(0.1f, out[2], 1e-5f)
        assertEquals(0.5f, out[3], 1e-5f)
    }

    @Test
    fun `straight output leaves rgb unscaled for the export compositor`() {
        val out = TransitionMath.premultiply(0.8f, 0.4f, 0.2f, 0.5f, premultiplied = false)
        assertEquals(0.8f, out[0], 1e-5f)
        assertEquals(0.4f, out[1], 1e-5f)
        assertEquals(0.2f, out[2], 1e-5f)
        assertEquals(0.5f, out[3], 1e-5f)
    }

    @Test
    fun `premultiply endpoints are convention-independent`() {
        // a=0 -> fully transparent; a=1 -> opaque identity in BOTH modes:
        // premultiplying by 1 is the identity, so the window endpoints
        // (preroll pin / tail pin) render identically on either path.
        val zero = TransitionMath.premultiply(0.9f, 0.9f, 0.9f, 0f, premultiplied = true)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f), zero, 1e-6f)
        val one = TransitionMath.premultiply(0.9f, 0.9f, 0.9f, 1f, premultiplied = true)
        assertArrayEquals(floatArrayOf(0.9f, 0.9f, 0.9f, 1f), one, 1e-6f)
        val oneStraight = TransitionMath.premultiply(0.9f, 0.9f, 0.9f, 1f, premultiplied = false)
        assertArrayEquals(one, oneStraight, 1e-6f)
    }

    @Test
    fun `a straight-alpha buffer composited as premultiplied ghosts`() {
        // The regression, pinned as math: with the WRONG convention the
        // displayed color is rgb + (1-a)*body instead of rgb*a + (1-a)*body.
        // B=(0.8,0.8,0.8), A=(0.2,0.2,0.2), a=0.5:
        //  - correct premultiplied composite:  0.8*0.5 + 0.2*0.5 = 0.5
        //  - ghosted (v1.5.0 preview)       :  0.8     + 0.2*0.5 = 0.9
        // The 0.9 ghost is B at near-full strength — every position-stable
        // style collapses into this same look.
        val b = 0.8f
        val a = 0.5f
        val body = 0.2f
        val correct = TransitionMath.premultiply(b, b, b, a, premultiplied = true)[0] + (1f - a) * body
        val ghosted = b + (1f - a) * body
        assertEquals(0.5f, correct, 1e-5f)
        assertEquals(0.9f, ghosted, 1e-5f)
    }
}
