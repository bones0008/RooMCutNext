package com.RooMCutNext.ui.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * v1.5.4: numeric invariants for the timeline's vertical layout —
 * bottom-anchored lane stack + overflow scrolling (the fix for "too many
 * text layers push the timeline down under the toolbar").
 *
 * Everything is in the dp domain; the view multiplies by density once.
 */
class TimelineVerticalMathTest {

    private val R = TimelineVerticalMath.RULER_HEIGHT_DP   // 26
    private val T = TimelineVerticalMath.TRACK_HEIGHT_DP   // 56
    private val TX = TimelineVerticalMath.TEXT_LANE_HEIGHT_DP // 24
    private val P = TimelineVerticalMath.PIP_LANE_HEIGHT_DP   // 30
    private val A = TimelineVerticalMath.AUDIO_LANE_HEIGHT_DP // 30
    private val G = TimelineVerticalMath.TRACK_GAP_DP      // 6
    private val PAD = TimelineVerticalMath.BOTTOM_PADDING_DP // 6

    // ------------------------------------------------------------------
    // Region geometry: stack order + gap discipline
    // ------------------------------------------------------------------

    @Test
    fun `bare project is ruler gap track plus padding`() {
        // (0 text, 0 pip, 0 audio): 26 + 6 + 56 + 6 = 94dp of content.
        assertEquals(94f, TimelineVerticalMath.contentHeightDp(0, 0, 0), 0f)
        assertEquals(R + G, TimelineVerticalMath.trackTopDp(0, 0), 0f)
        assertEquals(R + G + T, TimelineVerticalMath.trackBottomDp(0, 0), 0f)
    }

    @Test
    fun `portrait saturation case matches the field report`() {
        // The user's portrait-project saturation case: 2 text lanes + 1 PiP +
        // 1 video + 1 audio = exactly the stack that used to fit. The total
        // must stay 26+6+48 +6+30 +6+56 +6+30 +6 = 220dp — unchanged sizes.
        assertEquals(220f, TimelineVerticalMath.contentHeightDp(2, 1, 1), 0f)
        // Region order top-down: text → pip → track → audio, disjoint, with
        // the 6dp gap between neighbors and NO gap for absent regions.
        assertEquals(R + G, TimelineVerticalMath.textRegionTopDp(2), 0f)
        assertEquals(R + G + 2 * TX, TimelineVerticalMath.textRegionBottomDp(2), 0f)
        assertEquals(R + G + 2 * TX + G, TimelineVerticalMath.pipRegionTopDp(2, 1), 0f)
        assertEquals(R + G + 2 * TX + G + P, TimelineVerticalMath.pipRegionBottomDp(2, 1), 0f)
        assertEquals(R + G + 2 * TX + G + P + G, TimelineVerticalMath.trackTopDp(2, 1), 0f)
        assertEquals(R + G + 2 * TX + G + P + G + T, TimelineVerticalMath.trackBottomDp(2, 1), 0f)
        assertEquals(R + G + 2 * TX + G + P + G + T + G, TimelineVerticalMath.audioRegionTopDp(2, 1, 1), 0f)
        assertEquals(R + G + 2 * TX + G + P + G + T + G + A, TimelineVerticalMath.audioRegionBottomDp(2, 1, 1), 0f)
    }

    @Test
    fun `absent regions are zero-height without gaps`() {
        // No text lanes → the text region is zero-height flush under the
        // ruler, and a PiP region then takes the first gap: pip top =
        // ruler + gap (matching the pre-v1.5.4 yCursor walk exactly).
        assertEquals(R, TimelineVerticalMath.textRegionBottomDp(0), 0f)
        assertEquals(R + G, TimelineVerticalMath.pipRegionTopDp(0, 1), 0f)
        // No pip lanes → NO extra gap: the video track's own gap follows the
        // text region bottom directly.
        assertEquals(R + G + TX + G, TimelineVerticalMath.trackTopDp(1, 0), 0f)
        // No audio lanes → audio region collapses onto the track bottom.
        val trackBottom = TimelineVerticalMath.trackBottomDp(1, 0)
        assertEquals(trackBottom, TimelineVerticalMath.audioRegionTopDp(1, 0, 0), 0f)
        assertEquals(trackBottom, TimelineVerticalMath.audioRegionBottomDp(1, 0, 0), 0f)
        // Content height then is ruler+gap+text+gap+track+padding.
        assertEquals(R + G + TX + G + T + PAD, TimelineVerticalMath.contentHeightDp(1, 0, 0), 0f)
    }

    @Test
    fun `every added text lane grows the stack upward only`() {
        // The video/audio TRACK positions must NOT depend on the text lane
        // count once regions exist — that is the whole point of the fix: the
        // text region grows and the content height grows, but the video
        // track's offset from the BOTTOM of the content is invariant.
        val trackTop3 = TimelineVerticalMath.trackTopDp(3, 1)
        val trackTop5 = TimelineVerticalMath.trackTopDp(5, 1)
        val h3 = TimelineVerticalMath.contentHeightDp(3, 1, 1)
        val h5 = TimelineVerticalMath.contentHeightDp(5, 1, 1)
        // Distance from the track top to the content bottom (audio + padding):
        assertEquals(h3 - trackTop3, h5 - trackTop5, 0f)
        // And the content height grows exactly by 2 text-lane strides.
        assertEquals(h3 + 2 * TX, h5, 0f)
    }

    @Test
    fun `lane tops advance by their stride`() {
        assertEquals(
            TimelineVerticalMath.textRegionTopDp(2) + 3 * TX,
            TimelineVerticalMath.textLaneTopDp(3, 2), 0f,
        )
        assertEquals(
            TimelineVerticalMath.pipRegionTopDp(2, 2) + P,
            TimelineVerticalMath.pipLaneTopDp(1, 2, 2), 0f,
        )
        assertEquals(
            TimelineVerticalMath.audioRegionTopDp(1, 1, 2) + A,
            TimelineVerticalMath.audioLaneTopDp(1, 1, 1, 2), 0f,
        )
    }

    // ------------------------------------------------------------------
    // Anchoring / clamping
    // ------------------------------------------------------------------

    @Test
    fun `no overflow anchors to the bottom`() {
        // Content 220dp in a 300dp viewport → scrollY = -80: the translate
        // shifts the stack DOWN so its bottom sits at the viewport bottom.
        assertEquals(-80f, TimelineVerticalMath.anchorScrollY(0f, 220f, 300f), 0f)
        // Idempotent: an already-anchored value maps to itself.
        assertEquals(-80f, TimelineVerticalMath.anchorScrollY(-80f, 220f, 300f), 0f)
        // Any stray value collapses to the same anchor — scrolling is
        // impossible when everything fits.
        assertEquals(-80f, TimelineVerticalMath.anchorScrollY(55f, 220f, 300f), 0f)
        assertEquals(0f, TimelineVerticalMath.maxScrollYDp(220f, 300f), 0f)
    }

    @Test
    fun `overflow clamps a legal user scroll`() {
        // Content 320dp in a 200dp viewport → scroll range [0, 120].
        assertEquals(120f, TimelineVerticalMath.maxScrollYDp(320f, 200f), 0f)
        assertEquals(40f, TimelineVerticalMath.anchorScrollY(40f, 320f, 200f), 0f)
        assertEquals(120f, TimelineVerticalMath.anchorScrollY(999f, 320f, 200f), 0f)
        // A small legal scroll (5dp) is preserved — clamping only protects
        // the boundaries, it never recenters.
        assertEquals(5f, TimelineVerticalMath.anchorScrollY(5f, 320f, 200f), 0f)
    }

    @Test
    fun `crossing the overflow threshold keeps the bottom visible`() {
        // One frame the stack fits (scrollY = -20, bottom-anchored); the user
        // adds a text lane and content goes 20dp past the viewport. The
        // anchor must land at the MAXIMUM scroll — the video/audio tracks the
        // user was looking at stay on screen rather than snapping to the top.
        assertEquals(20f, TimelineVerticalMath.anchorScrollY(-20f, 220f, 200f), 0f)
        assertEquals(20f, TimelineVerticalMath.anchorScrollY(-0.01f, 220f, 200f), 0f)
    }

    // ------------------------------------------------------------------
    // Selection reveal
    // ------------------------------------------------------------------

    @Test
    fun `reveal is a no-op when everything fits`() {
        // Returns the anchor (negative) — the draw pass owns that state; a
        // positive scroll must never be produced when nothing overflows.
        assertEquals(-80f, TimelineVerticalMath.revealScrollY(-80f, 30f, 54f, 220f, 300f), 0f)
    }

    @Test
    fun `reveal keeps an already-visible target untouched`() {
        // Viewport 200dp, content 320dp, scroll 60 → visible content window
        // [86, 260] in content space. Target lane [110, 134] is inside.
        assertEquals(60f, TimelineVerticalMath.revealScrollY(60f, 110f, 134f, 320f, 200f), 0f)
    }

    @Test
    fun `reveal scrolls up minimally for a target above the window`() {
        // Same window, target lane [30, 54] (a top text lane). The minimal
        // scroll puts its top at ruler+pad = 26+4 = 30 → s = 0 exactly here,
        // then clamped (>= 0).
        val s = TimelineVerticalMath.revealScrollY(60f, 30f, 54f, 320f, 200f)
        assertTrue(s in 0f..60f)
        assertEquals(0f, s, 0.001f)
        // A target slightly below the top keeps a small scroll (minimal move,
        // not a jump to 0): target [40, 64] → s = 40 - 30 = 10.
        assertEquals(10f, TimelineVerticalMath.revealScrollY(60f, 40f, 64f, 320f, 200f), 0.001f)
    }

    @Test
    fun `reveal scrolls down minimally for a target below the window`() {
        // Visible window [86, 260]; target audio lane [280, 310]. The minimal
        // scroll puts its bottom at viewport - pad = 196 → s = 310 - 196 = 114
        // (within [0, 120]).
        assertEquals(114f, TimelineVerticalMath.revealScrollY(60f, 280f, 310f, 320f, 200f), 0.001f)
        // A target whose bottom is exactly at the viewport edge minus pad
        // stays put: target [240, 256] → 256 - 60 = 196 = viewport - pad.
        assertEquals(60f, TimelineVerticalMath.revealScrollY(60f, 240f, 256f, 320f, 200f), 0.001f)
    }

    @Test
    fun `reveal clamps into the legal range`() {
        // Absurd target far below: scroll clamps to the max (120), never past.
        assertEquals(120f, TimelineVerticalMath.revealScrollY(60f, 400f, 500f, 320f, 200f), 0f)
        // Absurd target far above: clamps to 0.
        assertEquals(0f, TimelineVerticalMath.revealScrollY(60f, -50f, -10f, 320f, 200f), 0f)
        // A negative (just-crossed-threshold) current scroll is treated as
        // bottom-anchored first, then revealed.
        val s = TimelineVerticalMath.revealScrollY(-20f, 30f, 54f, 320f, 200f)
        assertTrue(s >= 0f)
        assertTrue(abs(s - 0f) < 0.001f)
    }

    // ------------------------------------------------------------------
    // Playhead end clamp (mirrors the overlay's min(playheadBottom - scrollY, viewportH))
    // ------------------------------------------------------------------

    @Test
    fun `playhead line end follows the visible viewport`() {
        val pad = TimelineVerticalMath.BOTTOM_PADDING_DP
        // In the view, playheadBottom = audioRegionBottom = contentH - PAD.
        // Overflow scrolled to the max: the line ends PAD above the viewport
        // bottom — the true visible end of the audio lanes.
        val contentH = 320f
        val viewportH = 200f
        val scrollMax = contentH - viewportH
        assertEquals(viewportH - pad, minOf(contentH - pad - scrollMax, viewportH), 0f)
        // Overflow scrolled to the top: the content end lies beyond the
        // viewport, so the line clamps to the viewport bottom edge.
        assertEquals(viewportH, minOf(contentH - pad - 0f, viewportH), 0f)
        // Not overflowing (content 220 in a 300 viewport, anchored at -80):
        // the line ends PAD above the viewport bottom, at the audio lanes'
        // true end — 220 - 6 + 80 = 294 = 300 - 6.
        val anchoredScroll = 220f - 300f // -80
        assertEquals(300f - pad, minOf(220f - pad - anchoredScroll, 300f), 0f)
    }
}
