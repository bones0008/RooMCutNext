package com.RooMCutNext.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.2.10: mapping global timeline text windows into the crossfade head
 * player's LOCAL position space — the bridge that lets the preview bake the
 * same TimedTextOverlay effects the export bakes into head items (plain
 * ExoPlayer effect timestamps are PLAYER positions, verified against media3
 * 1.11.1 source; the composition path the body uses gets GLOBAL timestamps).
 */
class HeadTextWindowsTest {

    private val windowUs = 2_000_000L // 2s transition starting at t=5s
    private val windowStartUs = 5_000_000L
    private val spanUs = 2_000_000L // source span == window × speed 1

    private fun map(textStart: Long, textEnd: Long, speed: Float = 1f, span: Long = spanUs) =
        HeadTextWindows.localWindowUs(
            textStartUs = textStart,
            textEndUs = textEnd,
            windowStartUs = windowStartUs,
            windowDurationUs = windowUs,
            speed = speed,
            sourceSpanUs = span,
        )

    @Test
    fun `text covering the whole window maps to the full span plus one`() {
        // +1 on the end: the ENDED player's held last frame can carry a
        // position equal to the span, and the overlay check is t >= end.
        val w = map(windowStartUs - 1L, windowStartUs + windowUs + 1L)
        assertNotNull(w)
        assertEquals(0L, w!!.startUs)
        assertEquals(spanUs + 1L, w.endUs)
    }

    @Test
    fun `text starting mid-window maps to the offset within the window`() {
        // Text [6.0s, 6.5s) inside window [5s, 7s) → local [1.0s, 1.5s).
        val w = map(6_000_000L, 6_500_000L)
        assertNotNull(w)
        assertEquals(1_000_000L, w!!.startUs)
        assertEquals(1_500_000L, w.endUs)
    }

    @Test
    fun `text starting before the window is clamped to local zero`() {
        // Text [3s, 6s) → the head shows it from the window start.
        val w = map(3_000_000L, 6_000_000L)
        assertNotNull(w)
        assertEquals(0L, w!!.startUs)
        assertEquals(1_000_000L, w.endUs)
    }

    @Test
    fun `text extending past the window end is clamped to span plus one`() {
        // Text [6s, 9s) → on the head it plays to the window end and stays
        // drawn on the held last frame through the fade-out tail (matching
        // the export, where the body item carries the text from there on).
        val w = map(6_000_000L, 9_000_000L)
        assertNotNull(w)
        assertEquals(1_000_000L, w!!.startUs)
        assertEquals(spanUs + 1L, w.endUs)
    }

    @Test
    fun `texts entirely before or after the window are not attached`() {
        assertNull(map(1_000_000L, 4_999_999L)) // ends 1us before the window
        assertNull(map(1_000_000L, windowStartUs)) // touches, but exclusive end
        assertNull(map(windowStartUs + windowUs, 9_000_000L)) // starts at end
        assertNull(map(8_000_000L, 9_000_000L)) // fully after
    }

    @Test
    fun `speed scales the local window`() {
        // 2x clip: a 2s timeline window consumes 4s of source (span 4s);
        // text [6s, 7s) → local [2s, 4s) in source time.
        val w = map(6_000_000L, 7_000_000L, speed = 2f, span = 4_000_000L)
        assertNotNull(w)
        assertEquals(2_000_000L, w!!.startUs)
        assertEquals(4_000_000L, w.endUs)
    }

    @Test
    fun `speed scales a clamped start into the span`() {
        // 2x, text starts 0.5s BEFORE the window → local start -1s → 0.
        val w = map(4_500_000L, 5_500_000L, speed = 2f, span = 4_000_000L)
        assertNotNull(w)
        assertEquals(0L, w!!.startUs)
        assertEquals(1_000_000L, w.endUs)
    }

    @Test
    fun `end never precedes start and never exceeds span plus one`() {
        // Fuzz the four corners of the input space.
        val textStarts = listOf(-1L, 0L, windowStartUs, windowStartUs + windowUs, Long.MAX_VALUE / 4)
        val textEnds = listOf(-1L, 0L, windowStartUs, windowStartUs + windowUs, Long.MAX_VALUE / 4)
        for (s in textStarts) for (e in textEnds) for (sp in listOf(1L, spanUs, 4_000_000L)) {
            val w = HeadTextWindows.localWindowUs(s, e, windowStartUs, windowUs, 1f, sp)
            if (w != null) {
                assertTrue(w.startUs in 0..sp, "start ${w.startUs} not in [0,$sp] for text [$s,$e)")
                assertTrue(w.endUs <= sp + 1, "end ${w.endUs} > span+1 for text [$s,$e)")
                assertTrue(w.endUs > w.startUs, "end ${w.endUs} <= start ${w.startUs} for text [$s,$e)")
            }
        }
    }

    @Test
    fun `degenerate inputs never produce a window`() {
        assertNull(map(5_500_000L, 5_400_000L)) // end <= start
        // Zero-duration window / zero span → no head content to attach to.
        assertNull(
            HeadTextWindows.localWindowUs(5_500_000L, 5_600_000L, windowStartUs, 0L, 1f, spanUs)
        )
        assertNull(
            HeadTextWindows.localWindowUs(5_500_000L, 5_600_000L, windowStartUs, windowUs, 1f, 0L)
        )
    }

    private fun assertTrue(cond: Boolean, msg: String) = org.junit.Assert.assertTrue(msg, cond)
}
