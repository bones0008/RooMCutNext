package com.RooMCutNext.playback

/**
 * v1.5.3 — in-window CLOCK STEERING for the transition head player (pure
 * policy; the controller applies it, same discipline as PipSyncPolicy).
 *
 * THE BUG THIS PINS (field report: "圆形展开/收缩、时钟擦：效果做到一半会
 * 突然消失直接显示卡顿的第二段视频的画面"): the head player runs on its OWN
 * clock while the playhead follows the body CompositionPlayer. When the body
 * stalls (item-boundary decoder switch, buffering), the playhead freezes
 * while the head keeps advancing — the head runs AHEAD, its shader progress
 * hits 1 early, and the window's remaining ticks show a parked full-B frame:
 * the effect "completes" mid-window and the second clip pops in under it. If
 * the divergence crossed the hard-realign threshold the cooldown-gated seek
 * plus the cooldown-FREE ENDED rewind then re-seeked every sync tick
 * (~33ms), each seek flushing the decoder — the freeze/pop stutter cycle.
 *
 * The discipline (the v1.2.9 "constant lag has no visual reference" doctrine
 * kept for BEHIND; AHEAD is now corrected GENTLY):
 *  - AHEAD, small: TRIM the head's playback speed — no decoder flush, no
 *    keyframe re-decode (the v1.2.8 seek-storm lesson), the gap bleeds off
 *    at a few percent of rate.
 *  - AHEAD, growing: PARK the head entirely (playWhenReady=false) until the
 *    playhead catches back up — a parked head freezes a mid-effect frame
 *    over the (equally stalled) body instead of racing to its endpoint.
 *  - BEHIND: leave alone below the hard-realign threshold (1.5s, unchanged).
 */
object HeadSyncPolicy {

    /** Start trimming when the head runs this far AHEAD (pos > expected). */
    const val AHEAD_TRIM_TRIGGER_US = 80_000L

    /** Keep trimming until the ahead-drift has bled back inside this band. */
    const val AHEAD_TRIM_RELEASE_US = 40_000L

    /** Park the head when it is this far ahead despite trimming. */
    const val AHEAD_PARK_US = 500_000L

    /** Resume from park once the ahead-drift is back below this. */
    const val AHEAD_UNPARK_US = 250_000L

    /** The speed factor applied on top of the clip's own speed while trimming. */
    const val AHEAD_TRIM_FACTOR = 0.96f

    /**
     * The speed factor for the head player this tick.
     *
     * @param aheadUs player position minus expected position; positive = the
     *   head is AHEAD of the playhead (the dangerous direction — it races
     *   toward its endpoint and parks at full-B).
     * @param wasTrimming whether the factor was trimming last tick (hysteresis
     *   so the speed does not chatter on the trigger boundary).
     */
    fun speedFactor(aheadUs: Long, wasTrimming: Boolean): Float {
        val trigger = if (wasTrimming) AHEAD_TRIM_RELEASE_US else AHEAD_TRIM_TRIGGER_US
        return if (aheadUs >= trigger) AHEAD_TRIM_FACTOR else 1f
    }

    /**
     * Whether the head should PARK (pause) this tick.
     *
     * @param aheadUs player position minus expected position (positive ahead).
     * @param wasParked whether the head was parked last tick (hysteresis).
     */
    fun shouldPark(aheadUs: Long, wasParked: Boolean): Boolean {
        val parkAt = if (wasParked) AHEAD_UNPARK_US else AHEAD_PARK_US
        return aheadUs >= parkAt
    }
}
