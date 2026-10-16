package com.RooMCutNext.playback

import com.RooMCutNext.export.CompositionBuilder

/**
 * Pure mount/staleness policy for the crossfade head overlay (v1.5.2).
 *
 * Two preview bugs this policy pins down as MATH so they cannot silently
 * regress:
 *
 *  BUG 1 (v1.5.0-v1.5.1, "every transition style previews as dissolve"):
 *  the effects-fallback grace was a single 450ms wall budget measured from
 *  MOUNT while the preroll was only 400ms, so the v1.2.8 comment's promise
 *  ("the latch lands in the preroll, invisible") was arithmetically false
 *  by 50ms; worse, driveHead's out-of-window early-out reset the
 *  first-frame flag on EVERY preroll sync tick, so a first frame rendered
 *  during preroll was erased before the in-window grace check could see
 *  it. Playing through a transition therefore latched the session-wide
 *  plain-player fallback almost deterministically, and every style then
 *  degraded to the view-layer dissolve ramp (the documented fallback
 *  approximation). Export was unaffected because Transformer never mounts
 *  OverlayPreviewController. The fix: never reset the first-frame flag
 *  mid-mount, and give the mount a grace budget that actually covers the
 *  work, split COLD (the session's first effects head pays EGL context +
 *  shader compilation + decoder spin-up) from WARM (later mounts reuse a
 *  proven pipeline).
 *
 *  BUG 2 (v1.5.1, "a deleted transition keeps playing on the timeline"):
 *  reconcileHead kept a mounted head hidden but alive when the playhead
 *  sat in no window, and driveHead (which runs AFTER reconcile in the same
 *  sync tick) then resurrected the view from the STALE spec every tick,
 *  in-window. The fix: a mounted spec that no longer exists in the current
 *  spec list (transition deleted, or any identity field edited) must be
 *  RELEASED, not hidden.
 *
 * JVM-testable because it is pure data in, decision out (same discipline
 * as PipSyncPolicy / HeadWindowMath); the controller applies the policy.
 */
object HeadEffectsPolicy {

    /**
     * Mount a head this far BEFORE its window opens so the effects player
     * has rendered before the fade starts.
     *
     * v1.5.2: 400ms -> 600ms. The reference band is 300-500ms for a PLAIN
     * player; an effects-bearing mount also pays PlaybackVideoGraphWrapper
     * initialization, and 400ms left the window entry colliding with the
     * tail of a normal warm render. 600ms keeps the mount invisible while
     * giving a warm pipeline the whole preroll to land its first frame.
     */
    const val PREROLL_US = 600_000L

    /**
     * v1.5.3: mount the NEXT window's head this far ahead WHILE PLAYING, so
     * the cold pipeline (codec init + EGL context + shader compile, commonly
     * 0.8–2s on mid-range devices) completes while the player is parked at
     * position 0 — instead of landing inside/near the window. The v1.5.2
     * field reports on this: per-style stutter through every window and a
     * flash-black that only smoothed out after 7–8 plays, i.e. only after the
     * process-wide codec/shader caches had warmed; premounting moves that
     * warm-up off the window (and the parked player's first frame also
     * pre-initializes the INCOMING clip's codec type, softening the body
     * player's own item-boundary switch at the window end — the other half
     * of the flash-black). Matches [COLD_GRACE_WALL_MS] so even a session's
     * first-ever mount fits inside the lead.
     */
    const val PREMOUNT_US = 2_500_000L

    /**
     * The spec to mount for PREMOUNT while playing forward (v1.5.3): the
     * earliest upcoming window whose start is within [premountUs] of the
     * playhead. Null while not playing (the paused path keeps the preroll +
     * hidden-keep scrub optimization unchanged) or when nothing is upcoming
     * in range.
     *
     * [inTail] suppresses premount while the CURRENT head is coasting out
     * its boundary-masking tail — releasing it mid-tail would re-expose the
     * exact item-boundary flash the tail exists to cover.
     */
    fun premountSpec(
        specs: List<CompositionBuilder.HeadOverlaySpec>,
        playheadUs: Long,
        isPlaying: Boolean,
        inTail: Boolean,
        premountUs: Long = PREMOUNT_US,
    ): CompositionBuilder.HeadOverlaySpec? {
        if (!isPlaying || inTail) return null
        return specs
            .filter { it.startUs > playheadUs && it.startUs - playheadUs <= premountUs }
            .minByOrNull { it.startUs }
    }

    /**
     * Wall-clock grace for a WARM effects mount (the session has already
     * rendered at least one effects head). Must EXCEED the preroll so a
     * merely-slow-but-working warm render is never mistaken for a broken
     * one (the v1.2.8 design put the latch inside the preroll; the actual
     * 450ms constant put it 50ms INTO the fade instead, the exact
     * mid-fade pop the comment described as the bug it fixed).
     * 1200ms covers a warm mount that re-compiles shaders plus decoder
     * spin-up on a mid-range device.
     */
    const val WARM_GRACE_WALL_MS = 1_200L

    /**
     * Wall-clock grace for the session's FIRST effects mount. Cold start
     * pays EGL context creation + program compilation + codec init on top
     * of everything a warm mount pays; on mid-range devices that is
     * commonly 0.8-2s. 2500ms is beyond any realistic working cold render
     * while still bounding a genuinely broken effects path.
     */
    const val COLD_GRACE_WALL_MS = 2_500L

    /**
     * The grace budget for the currently mounted effects head: cold gets
     * [COLD_GRACE_WALL_MS] until the session has ever rendered an effects
     * head, warm gets [WARM_GRACE_WALL_MS] afterwards.
     */
    fun graceWallMs(effectsEverRendered: Boolean): Long =
        if (effectsEverRendered) WARM_GRACE_WALL_MS else COLD_GRACE_WALL_MS

    /**
     * v1.5.6: how much EXTRA source time the PREVIEW head player's clip
     * carries past the transition window end.
     *
     * WHY (the field report the v1.5.5 alpha smoother could not fix: "转场
     * 结束的时候都有一个跳帧，不平滑"): the head's clip used to END exactly
     * at the window end, so through the 400ms fade-out TAIL the head showed a
     * FROZEN frame of B at the boundary while the body underneath kept
     * advancing into B — the composite's two copies of B drifted up to a full
     * 400ms apart, and the picture visibly "slides forward" as the fade
     * completes: the end-of-transition jump. The preview clip now runs
     * [tailUs] + [TAIL_MARGIN_US] PAST the window (converted to source
     * coordinates at the clip's speed), so the tail fades LIVE B content over
     * the catching-up body and the offset stays at the body's render lag
     * (~1-2 vsyncs) instead of growing to 400ms.
     *
     * The TRANSITION SHADER is unaffected: its fadeUs stays the window's own
     * source span, and the shader clamps progress to [0,1] with the p>=1
     * endpoint pinned to fully-opaque B (TransitionShaderProgram's endpoint
     * hygiene) — frames past the window end render as plain B. Export is
     * untouched (the export head item keeps the exact window; only the
     * preview player's clipping is extended).
     *
     * The MARGIN covers a head that lags the playhead slightly (typical
     * decoder spin-up at window entry, deliberately uncorrected per the
     * v1.2.9 constant-lag doctrine): the clip should not run dry before the
     * tail ends. If the SOURCE is shorter than the extension (B barely longer
     * than its transition), the clip simply ends early and the tail's last
     * stretch degrades gracefully to the old held-frame behavior.
     */
    fun previewTailExtensionSourceUs(
        speed: Float,
        tailUs: Long,
        marginUs: Long = TAIL_MARGIN_US,
    ): Long = ((tailUs + marginUs) * speed).toLong().coerceAtLeast(0L)

    /** Slack added to the tail extension; see [previewTailExtensionSourceUs]. */
    const val TAIL_MARGIN_US = 250_000L

    /**
     * True when the mounted head's spec has vanished from the CURRENT
     * spec list: the transition was deleted, or any identity field of the
     * window (style, length, speed, mute, volume) was edited while the
     * head sat mounted outside its window. Such a head must be RELEASED,
     * not hidden: driveHead runs after reconcileHead in the same sync
     * tick and drives the view from the stale spec, which resurrected the
     * deleted transition on the timeline (v1.5.1 bug 2).
     *
     * A spec still present (byte-equal) is NOT stale: that is the cheap
     * scrub back-and-forth case where keeping the head mounted but hidden
     * is the intended optimization.
     */
    fun staleSpec(
        mounted: CompositionBuilder.HeadOverlaySpec?,
        current: List<CompositionBuilder.HeadOverlaySpec>,
    ): Boolean = mounted != null && current.none { it == mounted }
}
