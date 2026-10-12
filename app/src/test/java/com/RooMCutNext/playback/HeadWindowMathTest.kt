package com.RooMCutNext.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.8: the crossfade head's alpha schedule, including the fade-out TAIL
 * that masks the body player's item-boundary crossing (the "first clip
 * flashes once at the end of the transition" bug).
 */
class HeadWindowMathTest {

    private val windowUs = 2_000_000L // 2s transition
    private val tailUs = 200_000L // 200ms fade-out tail

    @Test
    fun `before the window the head is hidden`() {
        assertEquals(0f, HeadWindowMath.alphaAt(-1L, windowUs, tailUs, isPlaying = true), 0f)
        assertEquals(0f, HeadWindowMath.alphaAt(-400_000L, windowUs, tailUs, isPlaying = true), 0f)
    }

    @Test
    fun `in-window alpha ramps linearly zero to one`() {
        assertEquals(0f, HeadWindowMath.alphaAt(0L, windowUs, tailUs, isPlaying = true), 1e-6f)
        assertEquals(0.25f, HeadWindowMath.alphaAt(500_000L, windowUs, tailUs, isPlaying = true), 1e-6f)
        assertEquals(0.5f, HeadWindowMath.alphaAt(1_000_000L, windowUs, tailUs, isPlaying = true), 1e-6f)
        // Last in-window tick: essentially fully faded in.
        assertEquals(1f, HeadWindowMath.alphaAt(windowUs - 1, windowUs, tailUs, isPlaying = true), 1e-6f)
    }

    @Test
    fun `alpha never leaves the zero-to-one range`() {
        // Sweep the whole schedule: preroll → window → tail → past.
        var localUs = -tailUs
        while (localUs < windowUs + tailUs + 1) {
            val a = HeadWindowMath.alphaAt(localUs, windowUs, tailUs, isPlaying = true)
            assertTrue("alpha out of range at ${localUs}us: $a", a in 0f..1f)
            localUs += tailUs / 10
        }
    }

    @Test
    fun `forward exit fades one to zero across the tail while playing`() {
        val start = HeadWindowMath.alphaAt(windowUs, windowUs, tailUs, isPlaying = true)
        assertEquals(1f, start, 1e-6f)
        val mid = HeadWindowMath.alphaAt(windowUs + tailUs / 2, windowUs, tailUs, isPlaying = true)
        assertEquals(0.5f, mid, 1e-6f)
        val end = HeadWindowMath.alphaAt(windowUs + tailUs - 1, windowUs, tailUs, isPlaying = true)
        assertTrue(end in 0f..0.001f)
        assertEquals(0f, HeadWindowMath.alphaAt(windowUs + tailUs, windowUs, tailUs, isPlaying = true), 0f)
    }

    @Test
    fun `tail is skipped when paused or scrubbing`() {
        // A paused playhead past the window must show the BODY's true frame —
        // the head hides immediately (no stale overlay on frame-step/pause).
        assertEquals(0f, HeadWindowMath.alphaAt(windowUs, windowUs, tailUs, isPlaying = false), 0f)
        assertEquals(0f, HeadWindowMath.alphaAt(windowUs + tailUs / 2, windowUs, tailUs, isPlaying = false), 0f)
    }

    @Test
    fun `backward exit hides instantly even while playing`() {
        // Scrub back before the window while "playing" (suppression lag):
        // the head must not linger.
        assertEquals(0f, HeadWindowMath.alphaAt(-1L, windowUs, tailUs, isPlaying = true), 0f)
    }

    @Test
    fun `zero tail reproduces the legacy hard cut`() {
        // tailUs = 0: at the window end the tail range is empty → hidden
        // immediately (the v1.2.7 hard-cut behavior).
        assertEquals(0f, HeadWindowMath.alphaAt(windowUs, windowUs, 0L, isPlaying = true), 0f)
        assertEquals(0f, HeadWindowMath.alphaAt(windowUs + 1, windowUs, 0L, isPlaying = true), 0f)
    }

    @Test
    fun `degenerate zero-duration window never shows`() {
        // durationUs <= 0 → the in-window range is empty (legacy semantics)
        // and the tail math is disabled: nothing is ever visible.
        assertEquals(0f, HeadWindowMath.alphaAt(0L, 0L, tailUs, isPlaying = true), 0f)
        assertEquals(0f, HeadWindowMath.alphaAt(5L, 0L, tailUs, isPlaying = true), 0f)
        assertEquals(0f, HeadWindowMath.alphaAt(0L, -1L, tailUs, isPlaying = true), 0f)
    }

    @Test
    fun `inTail marks only the playing forward tail`() {
        assertTrue(HeadWindowMath.inTail(windowUs, windowUs, tailUs, isPlaying = true))
        assertTrue(HeadWindowMath.inTail(windowUs + tailUs - 1, windowUs, tailUs, isPlaying = true))
        assertFalse(HeadWindowMath.inTail(windowUs + tailUs, windowUs, tailUs, isPlaying = true))
        assertFalse(HeadWindowMath.inTail(windowUs, windowUs, tailUs, isPlaying = false))
        assertFalse(HeadWindowMath.inTail(windowUs - 1, windowUs, tailUs, isPlaying = true))
        assertFalse(HeadWindowMath.inTail(-1L, windowUs, tailUs, isPlaying = true))
    }
}
