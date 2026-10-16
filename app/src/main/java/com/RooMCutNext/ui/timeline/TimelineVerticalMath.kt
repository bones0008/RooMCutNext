package com.RooMCutNext.ui.timeline

import kotlin.math.max

/**
 * v1.5.4: vertical layout math for the timeline, extracted as pure
 * dp-domain functions so the numeric invariants can be pinned by unit tests
 * (same discipline as CanvasFitMath / HeadWindowMath).
 *
 * WHY THIS EXISTS — the pre-v1.5.4 layout anchored the lane stack to the TOP
 * of the timeline viewport: ruler, then N text lanes, then PiP lanes, then
 * the video track, then audio lanes. Every added text layer pushed the video
 * track (and the audio lanes below it) further DOWN until they fell past the
 * viewport's bottom edge and were clipped — on a portrait project that
 * happened after just 2 text lanes + 1 PiP + video + audio (214dp of stack).
 * The user saw it as "the timeline gets squeezed down and blocked by the
 * toolbar".
 *
 * THE NEW MODEL (CapCut-style bottom anchoring):
 *  - The ruler stays PINNED to the top of the viewport (it never scrolls).
 *  - The rest of the stack is anchored to the BOTTOM of the viewport: the
 *    audio lanes sit at the bottom, the video track above them, PiP above
 *    that, text lanes on top. Adding a text layer now grows the stack
 *    UPWARD; the video track never moves.
 *  - When the stack is taller than the space below the ruler, the content
 *    becomes vertically scrollable. `scrollY` is the content-space offset
 *    that is subtracted from content Y when drawing (translate(0, -scrollY))
 *    and added back when hit-testing.
 *
 * SCROLL SEMANTICS (all dp):
 *  - contentH <= viewportH  → scrollY = contentH - viewportH (NEGATIVE —
 *    the translate shifts the stack down so its bottom sits at the viewport
 *    bottom; no scrolling is possible).
 *  - contentH >  viewportH  → scrollY ∈ [0, contentH - viewportH].
 *    scrollY = contentH - viewportH (the maximum) shows the BOTTOM of the
 *    stack (video/audio visible) and is the default resting position.
 *    scrollY = 0 shows the TOP of the stack (topmost text lanes).
 *  - Crossing the overflow threshold keeps the BOTTOM visible (see
 *    [anchorScrollY]'s negative-current branch) so the video track never
 *    jumps out of view the moment one layer too many is added.
 */
object TimelineVerticalMath {

    // ------------------------------------------------------------------
    // Layout constants (dp) — single source of truth, shared with the
    // TimelineView draw pass. Values are UNCHANGED from v1.5.3 so existing
    // projects render with identical lane sizes.
    // ------------------------------------------------------------------

    const val RULER_HEIGHT_DP = 26f
    const val TRACK_HEIGHT_DP = 56f
    const val TEXT_LANE_HEIGHT_DP = 24f
    const val AUDIO_LANE_HEIGHT_DP = 30f
    const val PIP_LANE_HEIGHT_DP = 30f
    const val TRACK_GAP_DP = 6f

    /** Breathing room below the bottom-most region, included in the content
     * height so a bottom-anchored audio lane is not glued to the viewport's
     * bottom edge (which visually touches the action toolbar). */
    const val BOTTOM_PADDING_DP = 6f

    /** Margin kept around a lane when auto-revealing the selection. */
    const val REVEAL_PADDING_DP = 4f

    // ------------------------------------------------------------------
    // Region geometry (content-space dp, top-down; regions are disjoint and
    // zero-height when unused — identical to the pre-v1.5.4 stack order)
    // ------------------------------------------------------------------

    /**
     * Top of the text region. The first gap only exists when there are text
     * lanes; with none, the region is zero-height flush under the ruler.
     */
    fun textRegionTopDp(textLanes: Int): Float =
        RULER_HEIGHT_DP + if (textLanes > 0) TRACK_GAP_DP else 0f

    fun textRegionBottomDp(textLanes: Int): Float =
        textRegionTopDp(textLanes) + textLanes * TEXT_LANE_HEIGHT_DP

    /** Top of the PiP region (zero-height when there are no PiP lanes). */
    fun pipRegionTopDp(textLanes: Int, pipLanes: Int): Float =
        textRegionBottomDp(textLanes) + if (pipLanes > 0) TRACK_GAP_DP else 0f

    fun pipRegionBottomDp(textLanes: Int, pipLanes: Int): Float =
        pipRegionTopDp(textLanes, pipLanes) + pipLanes * PIP_LANE_HEIGHT_DP

    /** Top of the (always present) video track. */
    fun trackTopDp(textLanes: Int, pipLanes: Int): Float =
        pipRegionBottomDp(textLanes, pipLanes) + TRACK_GAP_DP

    fun trackBottomDp(textLanes: Int, pipLanes: Int): Float =
        trackTopDp(textLanes, pipLanes) + TRACK_HEIGHT_DP

    /** Top of the audio region (zero-height when there are no audio lanes). */
    fun audioRegionTopDp(textLanes: Int, pipLanes: Int, audioLanes: Int): Float =
        trackBottomDp(textLanes, pipLanes) + if (audioLanes > 0) TRACK_GAP_DP else 0f

    fun audioRegionBottomDp(textLanes: Int, pipLanes: Int, audioLanes: Int): Float =
        audioRegionTopDp(textLanes, pipLanes, audioLanes) + audioLanes * AUDIO_LANE_HEIGHT_DP

    /**
     * Total height of the scrollable content (ruler included, bottom padding
     * included). With (0,0,0) lanes this is the bare ruler + gap + video
     * track + padding; with the user's portrait saturation case (2 text,
     * 1 PiP, 1 audio) it is 220dp.
     */
    fun contentHeightDp(textLanes: Int, pipLanes: Int, audioLanes: Int): Float =
        audioRegionBottomDp(textLanes, pipLanes, audioLanes) + BOTTOM_PADDING_DP

    /** Content-space top of the given text lane. */
    fun textLaneTopDp(lane: Int, textLanes: Int): Float =
        textRegionTopDp(textLanes) + lane * TEXT_LANE_HEIGHT_DP

    /** Content-space top of the given PiP lane. */
    fun pipLaneTopDp(lane: Int, textLanes: Int, pipLanes: Int): Float =
        pipRegionTopDp(textLanes, pipLanes) + lane * PIP_LANE_HEIGHT_DP

    /** Content-space top of the given audio lane. */
    fun audioLaneTopDp(lane: Int, textLanes: Int, pipLanes: Int, audioLanes: Int): Float =
        audioRegionTopDp(textLanes, pipLanes, audioLanes) + lane * AUDIO_LANE_HEIGHT_DP

    // ------------------------------------------------------------------
    // Scroll anchoring / clamping
    // ------------------------------------------------------------------

    /**
     * Re-anchors an arbitrary [currentScrollYDp] onto the legal scroll range
     * for the current content/viewport heights. Call this whenever either
     * height changes (lane added/removed, viewport resized) — it is
     * idempotent and self-healing:
     *
     *  - No overflow  → returns contentH - viewportH (< 0): bottom-anchored,
     *    the whole stack is visible, scrolling impossible.
     *  - Overflow, current < 0 (we JUST crossed the threshold — the previous
     *    frame was bottom-anchored) → returns the maximum scroll so the
     *    BOTTOM (video/audio) stays visible instead of snapping to the top.
     *  - Overflow, current ≥ 0 → clamped into [0, contentH - viewportH];
     *    a user scroll position is preserved when still legal.
     */
    fun anchorScrollY(currentScrollYDp: Float, contentHeightDp: Float, viewportHeightDp: Float): Float {
        val overflow = contentHeightDp - viewportHeightDp
        if (overflow <= 0f) return overflow
        if (currentScrollYDp < 0f) return overflow
        return currentScrollYDp.coerceIn(0f, overflow)
    }

    /**
     * Minimal scroll adjustment that brings [targetTopDp, targetBottomDp]
     * (content-space) fully into the visible window. The visible window in
     * content-space is [scrollY + ruler, scrollY + viewportH] minus the
     * reveal padding. Returns the current scroll unchanged when the target
     * is already visible; returns the bottom-anchored value when no
     * overflow exists (everything is visible by definition).
     */
    fun revealScrollY(
        currentScrollYDp: Float,
        targetTopDp: Float,
        targetBottomDp: Float,
        contentHeightDp: Float,
        viewportHeightDp: Float,
    ): Float {
        val overflow = contentHeightDp - viewportHeightDp
        if (overflow <= 0f) return overflow
        var s = if (currentScrollYDp < 0f) overflow else currentScrollYDp.coerceIn(0f, overflow)
        val visibleTopLimit = RULER_HEIGHT_DP + REVEAL_PADDING_DP
        val visibleBottomLimit = viewportHeightDp - REVEAL_PADDING_DP
        if (targetTopDp - s < visibleTopLimit) {
            s = targetTopDp - visibleTopLimit
        } else if (targetBottomDp - s > visibleBottomLimit) {
            s = targetBottomDp - visibleBottomLimit
        }
        return s.coerceIn(0f, overflow)
    }

    /** Maximum legal scroll (0 when everything fits — no scrolling). */
    fun maxScrollYDp(contentHeightDp: Float, viewportHeightDp: Float): Float =
        max(0f, contentHeightDp - viewportHeightDp)
}
