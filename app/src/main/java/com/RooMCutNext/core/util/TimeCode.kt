package com.RooMCutNext.core.util

import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/** Short unique ids that stay readable inside project JSON. */
object IdGen {
    fun new(prefix: String): String = "$prefix-" + UUID.randomUUID().toString().take(8)
}

/**
 * Time formatting and frame-grid math.
 *
 * All core times are integer microseconds. Frame snapping follows the
 * "snap-once" discipline: round the moving edge to the frame grid exactly once
 * and derive everything else by subtraction, so invariants are preserved.
 */
object TimeCode {

    /** Duration of one frame on the given grid, microseconds. */
    fun frameDurationUs(fps: Int): Long = 1_000_000L / fps

    /** Snap to the nearest frame boundary of the grid. */
    fun snapToFrame(us: Long, fps: Int): Long {
        val fd = frameDurationUs(fps)
        if (fd <= 0) return us
        return ((us + fd / 2) / fd) * fd
    }

    /** Snap down to the frame boundary at or before [us]. */
    fun snapDownToFrame(us: Long, fps: Int): Long {
        val fd = frameDurationUs(fps)
        if (fd <= 0) return us
        return (us / fd) * fd
    }

    /** Frame index of [us] on the grid (the frame whose window contains [us]). */
    fun frameIndex(us: Long, fps: Int): Long = (us.coerceAtLeast(0L) * fps) / 1_000_000L

    /** Total number of frames covered by [us] on the grid (ceiling). */
    fun frameCount(us: Long, fps: Int): Long = (us.coerceAtLeast(0L) * fps + 999_999L) / 1_000_000L

    /** "M:SS" or "H:MM:SS" compact label for clip badges. */
    fun formatShort(us: Long): String {
        val totalSec = us / 1_000_000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /**
     * "MM:SS" (or "H:MM:SS" past the hour) — the transport clock. Deliberately
     * the shortest useful form: users asked for "00:00", not milliseconds or
     * SMPTE frame counts.
     */
    fun formatClock(us: Long): String {
        val totalSec = us / 1_000_000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** Compact ruler label: sub-second steps get a "s" suffix. */
    fun formatRulerLabel(us: Long): String {
        val totalSec = us / 1_000_000
        val ms = us % 1_000_000
        if (totalSec == 0L && ms > 0) {
            return "%.1fs".format(us / 1_000_000f)
        }
        return formatShort(us)
    }

    /** "MM:SS.mmm" (or H:MM:SS.mmm) used for the transport time display. */
    fun formatPrecise(us: Long): String {
        val totalSec = us / 1_000_000
        val ms = (us % 1_000_000) / 1_000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d.%03d".format(h, m, s, ms)
        else "%02d:%02d.%03d".format(m, s, ms)
    }

    /**
     * "MM:SS:FF" (or "H:MM:SS:FF" past the hour) — the transport clock with
     * the frame number, e.g. `00:04:12` on a 30fps project reads as 4 seconds
     * and 12 frames. The frame field follows the project fps automatically
     * (00-23 on a 24fps project, 00-29 on 30fps, 00-59 on 60fps, ...).
     */
    fun formatClockFrames(us: Long, fps: Int): String {
        val safeFps = fps.coerceAtLeast(1)
        // Multiply before dividing once: truncating 1e6/fps to an integer
        // frame duration would drift a visible amount at hour-scale us.
        val frames = us.coerceAtLeast(0L) * safeFps / 1_000_000L
        val frameInSec = frames % safeFps
        val totalSec = frames / safeFps
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d:%02d".format(h, m, s, frameInSec)
        else "%02d:%02d:%02d".format(m, s, frameInSec)
    }

    /** "HH:MM:SS:FF" SMPTE-like timecode including the frame number. */
    fun formatTimecodeFrames(us: Long, fps: Int): String {
        val safeFps = fps.coerceAtLeast(1)
        val frames = us.coerceAtLeast(0L) * safeFps / 1_000_000L
        val frameInSec = frames % safeFps
        val totalSec = frames / safeFps
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%02d:%02d:%02d:%02d".format(h, m, s, frameInSec)
    }

    /** Simple millisecond parsing for the "go to time" field, e.g. "1:23.5" or "83.5". */
    fun parseMs(text: String): Long? {
        val cleaned = text.trim().replace(',', '.')
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split(':')
        if (parts.size > 3) return null
        var seconds = 0.0
        try {
            for (part in parts) {
                if (part.isEmpty()) return null
                seconds = seconds * 60 + part.toDouble()
            }
        } catch (e: NumberFormatException) {
            return null
        }
        if (seconds < 0) return null
        return (seconds * 1000).roundToLong() * 1000
    }

    /** Linear interpolation helper used by fade curves. */
    fun fractionInRange(value: Long, start: Long, end: Long): Float {
        if (end <= start) return if (value >= end) 1f else 0f
        val f = (value - start).toDouble() / (end - start)
        return f.coerceIn(0.0, 1.0).toFloat()
    }
}

/** Formats file sizes for the asset info sheet. */
object FileSize {
    fun format(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.0fKB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1fMB".format(mb)
        return "%.2fGB".format(mb / 1024.0)
    }
}
