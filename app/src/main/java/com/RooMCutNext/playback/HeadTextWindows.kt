package com.RooMCutNext.playback

/**
 * Pure mapping of a GLOBAL timeline text window onto the LOCAL position
 * space of a crossfade head player (v1.2.10).
 *
 * WHY this exists — the preview crossfade fades the SECOND clip in through a
 * separate plain ExoPlayer (the "head", see OverlayPreviewController). Text
 * overlays render as GL effects baked into the BODY composition underneath,
 * so during the window the fading head COVERED the text: the text appeared to
 * crossfade away with the outgoing clip and pop back after — while the EXPORT
 * bakes the SAME text into the head ITEM as well (CompositionBuilder attaches
 * overlapping texts to head and body items), where two identical copies
 * crossfading at the same spot compose to CONSTANT visibility:
 *
 *     visible_text = head_text * alpha + body_text * (1 - alpha) = text
 *
 * The preview now bakes the same texts into the head PLAYER's video effects,
 * which reproduces that math exactly. But a plain ExoPlayer's GL effects
 * receive timestamps in PLAYER-POSITION coordinates, not global composition
 * coordinates (media3 1.11.1, verified against source:
 * MediaCodecVideoRenderer.getBufferTimestampAdjustmentUs == -startPositionUs,
 * applied in PlaybackVideoGraphWrapper.InputVideoSink.handleInputFrame —
 * "shift it back to the player position"), so each text's global window must
 * be mapped into the head's local space first:
 *
 *     local = (global - windowStart) * speed        (clamped to the span)
 *
 * where the span is the clipped source length [sourceIn, sourceOut) and the
 * player position runs [0, span) across the window. Pure data, no Media3
 * types — unit-testable like HeadWindowMath.
 */
object HeadTextWindows {

    /** A text's visibility window in the head player's local position space. */
    data class LocalWindow(val startUs: Long, val endUs: Long)

    /**
     * Maps a text window [textStartUs, textEndUs) (GLOBAL timeline) into the
     * head player's local space.
     *
     * Returns null when the text does not intersect the head's window at all
     * (entirely before or after it) — such texts are simply not attached.
     *
     * The end is clamped to [span + 1] rather than [span]: the player's last
     * rendered frame can carry a position equal to the span (an ENDED player
     * holds that frame through the post-window fade-out tail), and the
     * overlay's window check is `t >= end → skip` — +1 keeps the text drawn on
     * that held frame so it fades out WITH the head exactly like the export.
     */
    fun localWindowUs(
        textStartUs: Long,
        textEndUs: Long,
        windowStartUs: Long,
        windowDurationUs: Long,
        speed: Float,
        sourceSpanUs: Long,
    ): LocalWindow? {
        if (windowDurationUs <= 0L || sourceSpanUs <= 0L) return null
        if (textEndUs <= textStartUs) return null
        // No intersection with the head's timeline window at all.
        if (textEndUs <= windowStartUs) return null
        if (textStartUs >= windowStartUs + windowDurationUs) return null

        val s = if (speed > 0f) speed else 1f
        val start = ((textStartUs - windowStartUs) * s).toLong()
        val end = ((textEndUs - windowStartUs) * s).toLong()

        val clampedStart = start.coerceIn(0L, sourceSpanUs)
        val clampedEnd = end.coerceIn(clampedStart + 1L, sourceSpanUs + 1L)
        return LocalWindow(clampedStart, clampedEnd)
    }
}
