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
 */
object HeadWindowMath {

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
}
