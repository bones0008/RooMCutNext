package com.RooMCutNext.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.8: the crossfade head's alpha schedule, including the fade-out TAIL
 * that masks the body player's item-boundary crossing (the "first clip
 * flashes once at the end of the transition" bug).
 *
 * v1.5.9: [HeadWindowMath.gatedTailAlphaAt] — the BODY-LIVE-GATED schedule
 * that replaces the classic tail in driveHead. The classic [alphaAt] tests
 * below stay as the documented reference semantics.
 */
class HeadWindowMathTest {

    private val windowUs = 2_000_000L // 2s transition
    private val tailUs = 200_000L // 200ms fade-out tail
    private val waitUs = 600_000L // v1.5.9 wait cap (production value)
    private val revealUs = 250_000L // v1.5.9 reveal ramp (production value)

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

    // ------------------------------------------------------------------
    // v1.5.9 — the BODY-LIVE-GATED tail (gatedTailAlphaAt)
    // ------------------------------------------------------------------

    @Test
    fun `wait phase holds fully opaque until the body reports live`() {
        // bodyLiveLocalUs = null → the mask must NOT open while the body's
        // surface may still show the outgoing clip's last frame.
        assertEquals(1f, HeadWindowMath.gatedTailAlphaAt(windowUs, windowUs, waitUs, null), 1e-6f)
        assertEquals(1f, HeadWindowMath.gatedTailAlphaAt(windowUs + waitUs / 2, windowUs, waitUs, null), 1e-6f)
        assertEquals(1f, HeadWindowMath.gatedTailAlphaAt(windowUs + waitUs - 1, windowUs, waitUs, null), 1e-6f)
    }

    @Test
    fun `reveal ramps one to zero from the body-live anchor`() {
        val anchor = windowUs + 120_000L // body went live 120ms into the tail
        assertEquals(1f, HeadWindowMath.gatedTailAlphaAt(anchor, windowUs, waitUs, anchor), 1e-6f)
        assertEquals(
            0.5f,
            HeadWindowMath.gatedTailAlphaAt(anchor + revealUs / 2, windowUs, waitUs, anchor),
            1e-6f,
        )
        assertEquals(0f, HeadWindowMath.gatedTailAlphaAt(anchor + revealUs, windowUs, waitUs, anchor), 0f)
        // Past the ramp: fully revealed (head hidden by driveHead).
        assertEquals(0f, HeadWindowMath.gatedTailAlphaAt(anchor + revealUs + 50_000L, windowUs, waitUs, anchor), 0f)
    }

    @Test
    fun `a late body-live anchor holds opaque past the wait cap`() {
        // The crossing exceeded the wait cap but the event STILL arrived —
        // the moment it lands, the reveal ramps from THERE, not from the cap.
        val anchor = windowUs + waitUs + 90_000L
        assertEquals(1f, HeadWindowMath.gatedTailAlphaAt(anchor - 1, windowUs, waitUs, anchor), 1e-6f)
        assertEquals(0.5f, HeadWindowMath.gatedTailAlphaAt(anchor + revealUs / 2, windowUs, waitUs, anchor), 1e-6f)
    }

    @Test
    fun `timeout reveals gracefully from the wait cap when the body stays silent`() {
        // No body-live event ever: the anchor falls back to durationUs+waitUs
        // so the mask still opens (over revealUs) instead of covering forever.
        assertEquals(1f, HeadWindowMath.gatedTailAlphaAt(windowUs + waitUs, windowUs, waitUs, null), 1e-6f)
        assertEquals(
            0.5f,
            HeadWindowMath.gatedTailAlphaAt(windowUs + waitUs + revealUs / 2, windowUs, waitUs, null),
            1e-6f,
        )
        assertEquals(0f, HeadWindowMath.gatedTailAlphaAt(windowUs + waitUs + revealUs, windowUs, waitUs, null), 0f)
    }

    @Test
    fun `an early body-live anchor may precede the wait cap`() {
        // Instant crossing (codec reused): the reveal starts right at the
        // anchor even though the wait budget was not spent.
        val anchor = windowUs + 20_000L
        assertEquals(1f, HeadWindowMath.gatedTailAlphaAt(anchor, windowUs, waitUs, anchor), 1e-6f)
        assertEquals(0f, HeadWindowMath.gatedTailAlphaAt(anchor + revealUs, windowUs, waitUs, anchor), 0f)
    }

    @Test
    fun `gated alpha never leaves the zero-to-one range`() {
        // Sweep both schedules (never-live and a mid-tail anchor).
        val anchor = windowUs + 300_000L
        var localUs = windowUs
        while (localUs < windowUs + waitUs + revealUs + 1) {
            val a1 = HeadWindowMath.gatedTailAlphaAt(localUs, windowUs, waitUs, null)
            val a2 = HeadWindowMath.gatedTailAlphaAt(localUs, windowUs, waitUs, anchor)
            assertTrue("null-anchor alpha out of range at ${localUs}us: $a1", a1 in 0f..1f)
            assertTrue("anchored alpha out of range at ${localUs}us: $a2", a2 in 0f..1f)
            localUs += revealUs / 10
        }
    }

    @Test
    fun `gated schedule degenerates the same way as the classic one`() {
        // durationUs <= 0 → the head never shows (legacy empty-window rule).
        assertEquals(0f, HeadWindowMath.gatedTailAlphaAt(0L, 0L, waitUs, null), 0f)
        assertEquals(0f, HeadWindowMath.gatedTailAlphaAt(5L, 0L, waitUs, windowUs), 0f)
        assertEquals(0f, HeadWindowMath.gatedTailAlphaAt(0L, -1L, waitUs, null), 0f)
    }

    @Test
    fun `tail span extends the wait cap by exactly the reveal ramp`() {
        assertEquals(waitUs + HeadWindowMath.TAIL_REVEAL_US, HeadWindowMath.tailSpanUs(waitUs))
        // The extended span must keep covering a LATE reveal's end tick.
        val span = HeadWindowMath.tailSpanUs(waitUs)
        val lateAnchor = windowUs + waitUs
        assertTrue(
            HeadWindowMath.inTail(lateAnchor + revealUs - 1, windowUs, span, isPlaying = true),
        )
        assertFalse(HeadWindowMath.inTail(lateAnchor + revealUs, windowUs, span, isPlaying = true))
        // Paused playheads still skip the tail entirely.
        assertFalse(HeadWindowMath.inTail(windowUs, windowUs, span, isPlaying = false))
    }

    @Test
    fun `reveal ramp fits the head item's tail extension by construction`() {
        // INVARIANT: the preview head item runs waitUs + TAIL_MARGIN_US past
        // the window (HeadEffectsPolicy.previewTailExtensionSourceUs feeds
        // headMediaItemFor). The reveal must fit inside that extension or a
        // late body-live would fade a FROZEN frame — pin the two constants
        // together so no future edit breaks the arithmetic silently.
        assertEquals(
            HeadEffectsPolicy.TAIL_MARGIN_US,
            HeadWindowMath.TAIL_REVEAL_US,
        )
    }
}
