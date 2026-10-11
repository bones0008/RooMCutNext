package com.RooMCutNext.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Frame-based timecode formatting used by the transport clock: "MM:SS:FF"
 * where the frame field follows the project fps (24/25/30/50/60).
 */
class TimeCodeTest {

    /**
     * Smallest integer microsecond value that lies INSIDE frame [frame] of an
     * [fps] grid (frame windows start at frame*1e6/fps, which is fractional
     * for e.g. 30fps, so round up to land inside the window).
     */
    private fun usAt(frame: Long, fps: Int): Long = (frame * 1_000_000L + fps - 1) / fps

    // ---------------- formatClockFrames ----------------

    @Test
    fun `zero renders 00-00-00 on any fps`() {
        assertEquals("00:00:00", TimeCode.formatClockFrames(0L, 30))
        assertEquals("00:00:00", TimeCode.formatClockFrames(0L, 24))
        assertEquals("00:00:00", TimeCode.formatClockFrames(0L, 60))
    }

    @Test
    fun `30fps frame field wraps at 30`() {
        // 4 seconds + 12 frames at 30fps.
        assertEquals("00:04:12", TimeCode.formatClockFrames(usAt(132, 30), 30))
        // 29 frames is the last frame of a second.
        assertEquals("00:04:29", TimeCode.formatClockFrames(usAt(149, 30), 30))
        // One frame later rolls the seconds.
        assertEquals("00:05:00", TimeCode.formatClockFrames(usAt(150, 30), 30))
    }

    @Test
    fun `24fps frame field wraps at 24`() {
        assertEquals("00:02:23", TimeCode.formatClockFrames(usAt(71, 24), 24))
        assertEquals("00:03:00", TimeCode.formatClockFrames(usAt(72, 24), 24))
    }

    @Test
    fun `25fps frame field wraps at 25`() {
        // 1 second + 24 frames at 25fps is frame 49.
        assertEquals("00:01:24", TimeCode.formatClockFrames(usAt(49, 25), 25))
        assertEquals("00:02:00", TimeCode.formatClockFrames(usAt(50, 25), 25))
    }

    @Test
    fun `60fps frame field wraps at 60`() {
        assertEquals("00:01:59", TimeCode.formatClockFrames(usAt(119, 60), 60))
        assertEquals("00:02:00", TimeCode.formatClockFrames(usAt(120, 60), 60))
    }

    @Test
    fun `minutes roll`() {
        // 1 minute 7 seconds + 3 frames at 30fps.
        assertEquals("01:07:03", TimeCode.formatClockFrames(usAt(2013, 30), 30))
    }

    @Test
    fun `hour gets a leading single digit field`() {
        val s = TimeCode.formatClockFrames(3_723_456_789L, 30)
        val parts = s.split(":")
        assertEquals(4, parts.size)
        assertEquals("1", parts[0])
        assertEquals("02", parts[1])
        assertEquals("03", parts[2])
    }

    @Test
    fun `negative input is clamped to zero`() {
        assertEquals("00:00:00", TimeCode.formatClockFrames(-5_000_000L, 30))
    }

    @Test
    fun `no milliseconds anywhere`() {
        val us = 4_123_456L // 4.12s at 30fps -> frame 123 -> 4s + 3 frames
        assertFalse(TimeCode.formatClockFrames(us, 30).contains("."))
        assertEquals("00:04:03", TimeCode.formatClockFrames(us, 30))
    }

    @Test
    fun `no hour-scale drift from truncated frame durations`() {
        // 59 minutes exactly at 30fps must read back cleanly (an integer
        // 33333us frame duration would drift several seconds by now).
        assertEquals("59:00:00", TimeCode.formatClockFrames(3_540_000_000L, 30))
        assertEquals("10:00:00", TimeCode.formatClockFrames(600_000_000L, 30))
    }

    // ---------------- formatTimecodeFrames (SMPTE-ish) ----------------

    @Test
    fun `smpte full form has four fields`() {
        assertEquals("00:00:04:12", TimeCode.formatTimecodeFrames(usAt(132, 30), 30))
    }

    // ---------------- frame grid math ----------------

    @Test
    fun `frameIndex uses exact rational math`() {
        // 10 minutes at 30fps = frame 18000 exactly.
        assertEquals(18_000L, TimeCode.frameIndex(600_000_000L, 30))
        // One frame into a 30fps grid.
        assertEquals(1L, TimeCode.frameIndex(33_334L, 30))
        assertEquals(0L, TimeCode.frameIndex(33_333L, 30))
    }

    @Test
    fun `frameIndex and snapToFrame agree on the grid`() {
        val fps = 25
        val fd = TimeCode.frameDurationUs(fps)
        assertEquals(5L, TimeCode.frameIndex(5 * fd + fd / 2, fps))
        // A quarter frame past boundary 5 snaps back down to boundary 5.
        assertEquals(5 * fd, TimeCode.snapToFrame(5 * fd + fd / 4, fps))
        assertEquals(5 * fd, TimeCode.snapDownToFrame(5 * fd + fd - 1, fps))
    }

    @Test
    fun `frameCount is the ceiling in frames`() {
        assertEquals(0L, TimeCode.frameCount(0L, 30))
        assertEquals(1L, TimeCode.frameCount(1L, 30))
        assertEquals(30L, TimeCode.frameCount(1_000_000L, 30))
    }
}
