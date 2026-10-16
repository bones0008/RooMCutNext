package com.RooMCutNext.playback

/**
 * Pure alpha/window math for the crossfade head overlay (v1.2.8).
 *
 * WHY the fade-out TAIL exists — the flash bug this fixed:
 * at the exact moment the playhead leaves a transition window, the head
 * overlay's alpha used to drop 1 → 0 in a single sync tick, handing the
 * picture to the body player (CompositionPlayer) at the very instant it
 * crosses its "previous clip tail → second clip body" item boundary. That
 * crossing is NOT instantaneous: the next period's decoder may be
 * re-initialized, the GL effects chain re-configured, and the position the
 * player REPORTS leads the frame it has actually DISPLAYED by 1-2 vsyncs.
 * For those frames the body still shows the OUTGOING clip's last frame —
 * full screen, head gone — which read as "the first clip's picture
 * suddenly flashes once".
 *
 * The tail keeps the head fading 1 → 0 across [durationUs, durationUs+tailUs)
 * AFTER a FORWARD window exit (only while actually playing). The held head
 * frame is the second clip's content at the window end — visually the same
 * picture the body is about to show — so it covers the boundary crossing
 * without being seen as a freeze. Paused/scrubbed playheads skip the tail
 * (the body shows the true frame for that position), and backward exits
 * (scrub back before the window) hide instantly as before.
 *
 * Full alpha schedule for [localUs] = playhead − windowStart:
 *  - localUs < 0                     → 0            (preroll / before window)
 *  - 0 ≤ localUs < durationUs        → ramp 0→1     (the crossfade itself)
 *  - durationUs ≤ localUs < dur+tail → 1→0 fade     (boundary mask, playing only)
 *  - otherwise                       → 0            (past tail, or not playing)
 *
 * v1.5.9 — WHY the classic tail alone was not enough: [alphaAt]'s fade is a
 * pure POSITION schedule. At the boundary the body CompositionPlayer's
 * surface keeps showing the OUTGOING clip's last frame while the next
 * period's decoder initializes (commonly 100-400ms, longer on some devices),
 * yet the reported position — and therefore the tail alpha — can keep
 * advancing seamlessly. The head then faded LIVE incoming-clip content over
 * a STALE outgoing frame: the deeper the alpha dropped before the body's
 * first frame of the new item landed, the more the stale frame bled through,
 * and the new frame's arrival mid-fade popped (the residual end-of-transition
 * "跳帧" every pass, including the first). [gatedTailAlphaAt] keeps the mask
 * FULLY OPAQUE until the body actually renders that first frame — the reveal
 * ramps only after "body live", so what fades is always live-B over live-B.
 */
object HeadWindowMath {

    /**
     * v1.5.9: length of the reveal ramp AFTER the body goes live (or after the
     * wait timeout). The two layers' content differs by only the body's render
     * lag (~1-2 vsyncs) once the body is live, so the ramp is aesthetic — but
     * it must fit inside the head item's tail EXTENSION: the preview clip runs
     * [waitUs] + this ramp past the window (HeadEffectsPolicy.
     * previewTailExtensionSourceUs already reserves exactly tail + TAIL_MARGIN_US
     * — keeping this equal to TAIL_MARGIN_US keeps the extension exact).
     */
    const val TAIL_REVEAL_US = 250_000L

    fun alphaAt(localUs: Long, durationUs: Long, tailUs: Long, isPlaying: Boolean): Float {
        // Legacy semantics: a non-positive window is an EMPTY range — the
        // head never shows (driveHead's `localUs in 0 until durationUs`).
        if (durationUs <= 0L) return 0f
        return when {
            localUs < 0L -> 0f

            localUs < durationUs ->
                // In-window fade-in (bounded by the branch condition).
                (localUs.toFloat() / durationUs).coerceIn(0f, 1f)

            isPlaying && localUs < durationUs + tailUs ->
                // Forward fade-out tail: linear 1 → 0 across the tail.
                1f - ((localUs - durationUs).toFloat() / tailUs.coerceAtLeast(1L)).coerceIn(0f, 1f)

            else -> 0f
        }
    }

    /** True while [localUs] sits in the post-window fade-out tail (playing). */
    fun inTail(localUs: Long, durationUs: Long, tailUs: Long, isPlaying: Boolean): Boolean =
        isPlaying && durationUs > 0L && localUs >= durationUs && localUs < durationUs + tailUs

    /**
     * v1.5.9: the tail span a mounted head stays coasting for — the wait cap
     * PLUS the reveal ramp. Callers feed this to [inTail] so the head is not
     * hidden mid-reveal when the body goes live late.
     */
    fun tailSpanUs(waitUs: Long): Long = waitUs + TAIL_REVEAL_US

    /**
     * v1.5.9: BODY-LIVE-GATED tail alpha — the schedule that replaces
     * [alphaAt] on the tail path in driveHead.
     *
     * Phase WAIT — [durationUs, anchor): the mask holds FULLY OPAQUE. The body
     * is still crossing its item boundary (decoder re-init; its surface shows
     * the outgoing clip's last frame); revealing anything now bleeds stale
     * content. [bodyLiveLocalUs] == null means "not live yet".
     *
     * Phase REVEAL — [anchor, anchor + revealUs): linear 1 → 0. The anchor is
     * the sync tick where the body's first frame of the incoming item was
     * observed ([bodyLiveLocalUs]); both layers now show live incoming-clip
     * content a render-lag apart, so any ramp length is visually safe.
     *
     * TIMEOUT — if the body never reports live, the anchor falls back to
     * [durationUs] + [waitUs]: the mask still reveals (gracefully, over
     * revealUs) instead of covering a broken body forever. [alphaAt] is the
     * un-gated v1.2.8 schedule this degrades toward; it stays as the reference
     * semantics for the classic path and its tests.
     *
     * Degenerate windows (durationUs ≤ 0) never show, same legacy rule.
     */
    fun gatedTailAlphaAt(
        localUs: Long,
        durationUs: Long,
        waitUs: Long,
        bodyLiveLocalUs: Long?,
        revealUs: Long = TAIL_REVEAL_US,
    ): Float {
        if (durationUs <= 0L) return 0f
        val anchorUs = bodyLiveLocalUs ?: (durationUs + waitUs)
        return (1f - (localUs - anchorUs).toFloat() / revealUs.coerceAtLeast(1L))
            .coerceIn(0f, 1f)
    }
}
