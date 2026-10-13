package com.RooMCutNext.playback

/**
 * v1.4.0: the PiP overlay player's corrective-seek discipline, extracted
 * from OverlayPreviewController.drivePip into a pure policy so the decision
 * is unit-testable (the v1.3.3 stutter regression lived exactly here, and
 * drivePip had no test seam — see PipSyncPolicyTest).
 *
 * The discipline, in one breath:
 *  - PAUSED (scrub): track TIGHT — seeks are cheap while nothing renders,
 *    frame-accurate scrubbing is the contract, no cooldown.
 *  - PLAYING: a CONSTANT lag below [PLAYING_HARD_REALIGN_DRIFT_US] is left
 *    ALONE. ExoPlayer never time-compresses to close a position gap, so a
 *    PiP that opened late sits at a constant lag; seeking it re-opens the
 *    decoder + audio pipeline and re-incurs the very latency being
 *    corrected. Only a genuinely broken alignment (a mid-window user seek,
 *    seconds off) hard-realigns — once, cooldown-gated.
 *
 * v1.4.0 bug being fixed: the playing gate sat at 400ms since v1.2.10,
 * inside the multi-audio-source restart-latency range (BGM + clip audio +
 * PiP audio inflate the audio pipeline open well past the single-source
 * 100-250ms). The drift parked permanently above 400ms, every 500ms
 * cooldown expiry re-fired the seek, and each seek's decoder flush +
 * keyframe re-decode + audio reopen was the visible stutter ("PiP choppy,
 * its audio choppy"; the bursts also starved the body pipeline —
 * "everything freezes, sound keeps going"). The second play was smooth
 * because the warm pipeline re-opened under the threshold. The head player
 * got the identical fix in v1.2.10 (headHardRealignDriftUs 400ms -> 1.5s,
 * "any threshold a restart can exceed never settles"); the PiP gate is
 * raised to the same 1.5s here.
 */
object PipSyncPolicy {

    /** Minimum drift to seek a PAUSED overlay (scrubbing) — under a frame. */
    const val SCRUB_DRIFT_US = 20_000L

    /**
     * Drift big enough to hard-realign a PLAYING PiP player. 1.5s is beyond
     * any realistic (re)start latency, including the multi-audio-source
     * open; a constant sub-1.5s lag of PiP content has no visual reference
     * (the layer's own audio stays in sync with its own video — the drift
     * is against the timeline, not against its sound).
     */
    const val PLAYING_HARD_REALIGN_DRIFT_US = 1_500_000L

    /** Cooldown between hard-realign seeks of a PLAYING PiP player. */
    const val REALIGN_COOLDOWN_MS = 500L

    /**
     * An ENDED overlay player is only "held" (last frame kept) while the
     * playhead is this close to, or past, its source span end — further
     * back it rewinds and plays again (scrub-back / replay).
     */
    const val ENDED_HOLD_MARGIN_US = 100_000L

    /**
     * Whether a READY PiP player should be corrective-seeked THIS sync tick.
     *
     * @param driftAbsUs absolute drift |timeline-local position − player position|
     * @param isPlaying PLAY INTENT of the body player (playWhenReady, stable
     *   across buffering blips — v1.2.10), not the flickering actual state
     * @param nowMs uptime millis of this tick
     * @param lastSeekMs uptime millis of this overlay's last corrective seek
     */
    fun shouldCorrectiveSeek(
        driftAbsUs: Long,
        isPlaying: Boolean,
        nowMs: Long,
        lastSeekMs: Long,
    ): Boolean {
        if (!isPlaying) {
            // Paused scrub path: tight tracking, no cooldown (by design).
            return driftAbsUs > SCRUB_DRIFT_US
        }
        if (driftAbsUs <= PLAYING_HARD_REALIGN_DRIFT_US) return false
        return nowMs - lastSeekMs >= REALIGN_COOLDOWN_MS
    }

    /**
     * Whether an ENDED PiP player should rewind (seek back and replay)
     * because the playhead has gone back before its source span — vs. hold
     * its last frame near/past the span end ("window outlives source").
     */
    fun endedRewind(localUs: Long, sourceSpanUs: Long): Boolean =
        localUs + ENDED_HOLD_MARGIN_US < sourceSpanUs
}
