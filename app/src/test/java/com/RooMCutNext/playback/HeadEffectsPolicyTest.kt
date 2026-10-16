package com.RooMCutNext.playback

import com.RooMCutNext.export.CompositionBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.2: the head mount/staleness policy as pinned-down invariants.
 *
 * The two preview bugs this file guards against are STATE-MACHINE bugs in
 * OverlayPreviewController (untestable on the JVM per the repo rule);
 * these tests pin the numeric contract the state machine must honor so
 * the v1.2.8 class of contradiction (a grace that comments say lands in
 * the preroll while the arithmetic puts it inside the fade) can never be
 * reintroduced silently.
 */
class HeadEffectsPolicyTest {

    private fun spec(
        clipId: String = "c1",
        startUs: Long = 1_000_000L,
        durationUs: Long = 800_000L,
        type: String = "clock_wipe",
        muted: Boolean = false,
    ) = CompositionBuilder.HeadOverlaySpec(
        clipId = clipId,
        assetId = "a1",
        startUs = startUs,
        durationUs = durationUs,
        sourceInUs = 0L,
        sourceOutUs = durationUs,
        speed = 1f,
        muted = muted,
        volume = 1f,
        type = type,
    )

    // ---- grace budget ----

    @Test
    fun `cold grace exceeds warm grace`() {
        // Cold start pays EGL + shader compilation + codec init on top of
        // everything warm pays; the budget must reflect that ordering.
        assertTrue(HeadEffectsPolicy.COLD_GRACE_WALL_MS > HeadEffectsPolicy.WARM_GRACE_WALL_MS)
        assertEquals(HeadEffectsPolicy.COLD_GRACE_WALL_MS, HeadEffectsPolicy.graceWallMs(effectsEverRendered = false))
        assertEquals(HeadEffectsPolicy.WARM_GRACE_WALL_MS, HeadEffectsPolicy.graceWallMs(effectsEverRendered = true))
    }

    @Test
    fun `warm grace covers the whole preroll so a slow-but-working warm render is not mistaken for a broken one`() {
        // The mount happens at preroll ENTRY; a working warm pipeline may
        // legitimately still be rendering when the playhead is inside the
        // window. v1.2.8 shipped 450ms against a 400ms preroll and the
        // latch fired 50ms INTO the fade, the mid-fade pop its own comment
        // described as the bug it was fixing. Pin: warm grace must exceed
        // the preroll with a whole decoder-spin-up of margin.
        assertTrue(
            "warm grace ${HeadEffectsPolicy.WARM_GRACE_WALL_MS}ms must exceed preroll ${HeadEffectsPolicy.PREROLL_US / 1000}ms",
            HeadEffectsPolicy.WARM_GRACE_WALL_MS > HeadEffectsPolicy.PREROLL_US / 1000L,
        )
    }

    @Test
    fun `preroll stays in the reference band`() {
        // Reference prescribes 300-500ms for a plain player; v1.5.2 raised
        // it to 600ms for the effects-bearing mount. Pin the band so a
        // future edit keeps the mount ahead of the fade without creeping
        // into "seconds of preroll" territory.
        val prerollMs = HeadEffectsPolicy.PREROLL_US / 1000L
        assertTrue(prerollMs in 500L..1000L)
    }

    // ---- staleness ----

    @Test
    fun `no mounted spec is never stale`() {
        assertFalse(HeadEffectsPolicy.staleSpec(null, listOf(spec())))
        assertFalse(HeadEffectsPolicy.staleSpec(null, emptyList()))
    }

    @Test
    fun `spec still in the list byte-equal is not stale (scrub optimization survives)`() {
        val mounted = spec()
        // A fresh, structurally identical instance: data-class equality
        // must keep the mounted-but-hidden head alive across the ~30Hz
        // re-emissions of an unchanged project.
        val current = listOf(spec(clipId = "other"), spec())
        assertFalse(HeadEffectsPolicy.staleSpec(mounted, current))
    }

    @Test
    fun `deleted transition is stale (v1 5 1 bug 2)`() {
        // The user deleted the transition: the spec list no longer carries
        // any window for that clip. The mounted head MUST be reported
        // stale so the controller releases it; keeping it hidden-alive let
        // driveHead resurrect the deleted transition every sync tick.
        val mounted = spec()
        assertTrue(HeadEffectsPolicy.staleSpec(mounted, emptyList()))
        assertTrue(HeadEffectsPolicy.staleSpec(mounted, listOf(spec(clipId = "other"))))
    }

    @Test
    fun `edited style is stale (v1 5 0 bug 1 precondition)`() {
        // Style changed while the head sat mounted outside its window: the
        // old-style head must be released so the next window entry mounts
        // the new style (the dissolve-forever symptom also needed the
        // grace fix, but a stale old-style mount would still show the
        // previous style).
        val mounted = spec(type = "flash")
        assertTrue(HeadEffectsPolicy.staleSpec(mounted, listOf(spec(type = "blinds"))))
    }

    @Test
    fun `edited window length or mute is stale`() {
        val mounted = spec()
        assertTrue(HeadEffectsPolicy.staleSpec(mounted, listOf(spec(durationUs = 400_000L))))
        assertTrue(HeadEffectsPolicy.staleSpec(mounted, listOf(spec(muted = true))))
    }

    // ---- v1.5.3 premount ----

    @Test
    fun `premount picks the earliest upcoming window while playing`() {
        val far = spec(clipId = "far", startUs = 10_000_000L)
        val near = spec(clipId = "near", startUs = 3_000_000L)
        val specs = listOf(far, near)
        // Playhead at 1s: `near` starts 2s ahead (inside the 2.5s lead), the
        // far one 9s ahead.
        assertEquals(
            near,
            HeadEffectsPolicy.premountSpec(specs, playheadUs = 1_000_000L, isPlaying = true, inTail = false),
        )
    }

    @Test
    fun `premount ignores windows behind or containing the playhead`() {
        // startUs must be strictly ahead: a window the playhead is INSIDE is
        // the normal active-mount path's job, and a past window is history.
        val w = spec(startUs = 1_000_000L, durationUs = 800_000L)
        assertEquals(
            null,
            HeadEffectsPolicy.premountSpec(listOf(w), playheadUs = 1_200_000L, isPlaying = true, inTail = false),
        )
        assertEquals(
            null,
            HeadEffectsPolicy.premountSpec(listOf(w), playheadUs = 5_000_000L, isPlaying = true, inTail = false),
        )
    }

    @Test
    fun `premount is suppressed while paused and mid-tail`() {
        val w = spec(startUs = 3_000_000L)
        // Paused: the scrub path keeps preroll + hidden-keep, no premount.
        assertEquals(
            null,
            HeadEffectsPolicy.premountSpec(listOf(w), playheadUs = 1_000_000L, isPlaying = false, inTail = false),
        )
        // Mid-tail of the previous window: releasing the coasting head would
        // re-expose the boundary flash the tail masks.
        assertEquals(
            null,
            HeadEffectsPolicy.premountSpec(listOf(w), playheadUs = 1_000_000L, isPlaying = true, inTail = true),
        )
    }

    @Test
    fun `premount lead covers the cold grace budget`() {
        // The whole point of v1.5.3: a session's FIRST effects mount (cold:
        // EGL + shader compile + codec init, 0.8-2s on mid-range devices)
        // must fit inside the lead so the warm-up lands while parked, not
        // inside the fade. Pin premount >= cold grace.
        assertTrue(
            "premount ${HeadEffectsPolicy.PREMOUNT_US / 1000}ms must cover cold grace ${HeadEffectsPolicy.COLD_GRACE_WALL_MS}ms",
            HeadEffectsPolicy.PREMOUNT_US / 1000L >= HeadEffectsPolicy.COLD_GRACE_WALL_MS,
        )
    }

    // ---------------- v1.5.6: live-tail clip extension ----------------

    @Test
    fun `tail extension converts the tail to source time at the clip speed`() {
        // speed 1x: the clip runs tail + margin past the window end.
        assertEquals(
            600_000L + HeadEffectsPolicy.TAIL_MARGIN_US,
            HeadEffectsPolicy.previewTailExtensionSourceUs(speed = 1f, tailUs = 600_000L),
        )
        // 2x speed: the source advances twice as fast, so the same tail
        // duration needs twice the source extension.
        assertEquals(
            (600_000L + HeadEffectsPolicy.TAIL_MARGIN_US) * 2L,
            HeadEffectsPolicy.previewTailExtensionSourceUs(speed = 2f, tailUs = 600_000L),
        )
        // 0.5x slow-mo: half the source time covers the same wall-clock tail.
        assertEquals(
            ((600_000L + HeadEffectsPolicy.TAIL_MARGIN_US) / 2L),
            HeadEffectsPolicy.previewTailExtensionSourceUs(speed = 0.5f, tailUs = 600_000L),
        )
    }

    @Test
    fun `tail extension never goes negative and honors a custom margin`() {
        assertEquals(0L, HeadEffectsPolicy.previewTailExtensionSourceUs(speed = 0f, tailUs = 0L))
        assertEquals(
            500_000L,
            HeadEffectsPolicy.previewTailExtensionSourceUs(speed = 1f, tailUs = 300_000L, marginUs = 200_000L),
        )
    }

    @Test
    fun `tail margin covers a typical decoder spin-up lag`() {
        // The margin exists so a head that lags the playhead by the usual
        // window-entry spin-up (uncorrected per the v1.2.9 constant-lag
        // doctrine) does not run its clip dry before the tail ends.
        // Typical spin-up on mid-range devices is 100-200ms.
        assertTrue(HeadEffectsPolicy.TAIL_MARGIN_US >= 200_000L)
    }

    // ---------------- v1.5.7: capped extension / retarget gate / recycle ----------------

    // media3 Player states (the policy stays Android-free; ints pinned here):
    // 1=IDLE, 2=BUFFERING, 3=READY, 4=ENDED.
    private val idle = 1
    private val buffering = 2
    private val ready = 3
    private val ended = 4

    @Test
    fun `capped tail extension passes through when the gap to the next window is wide`() {
        // window ends at 1.8s, the next window starts at 5s: the whole 850ms
        // extension (600ms live tail + 250ms margin) fits with room to spare.
        assertEquals(
            850_000L,
            HeadEffectsPolicy.cappedTailExtensionTimelineUs(
                desiredTimelineUs = 850_000L, windowEndUs = 1_800_000L, nextWindowStartUs = 5_000_000L,
            ),
        )
    }

    @Test
    fun `capped tail extension clamps to the settle budget on short clips`() {
        // Next window 700ms after this one: budget = 700-100(settle) = 600ms,
        // so the extension degrades to a 600ms PARTIALLY-live tail rather
        // than overrunning the next window and forcing a mid-flight stop.
        assertEquals(
            600_000L,
            HeadEffectsPolicy.cappedTailExtensionTimelineUs(
                desiredTimelineUs = 850_000L, windowEndUs = 1_800_000L, nextWindowStartUs = 2_500_000L,
            ),
        )
    }

    @Test
    fun `capped tail extension collapses to zero on degenerate gaps`() {
        // The next window at or before this window's end: no safe extension
        // exists — the tail falls back to the pre-v1.5.6 held-frame behavior
        // (the correct trade against a wedged pipeline).
        assertEquals(
            0L,
            HeadEffectsPolicy.cappedTailExtensionTimelineUs(
                desiredTimelineUs = 850_000L, windowEndUs = 1_800_000L, nextWindowStartUs = 1_800_000L,
            ),
        )
        assertEquals(
            0L,
            HeadEffectsPolicy.cappedTailExtensionTimelineUs(
                desiredTimelineUs = 850_000L, windowEndUs = 1_800_000L, nextWindowStartUs = 1_500_000L,
            ),
        )
    }

    @Test
    fun `retarget gate passes drained or never-started players`() {
        // ENDED: the clipped item played out — the graph drained. IDLE:
        // nothing was ever queued.
        assertTrue(HeadEffectsPolicy.retargetSinkClearToProceed(ended, playWhenReady = true, positionMs = 999L, itemEndMs = 900L))
        assertTrue(HeadEffectsPolicy.retargetSinkClearToProceed(idle, playWhenReady = true, positionMs = 0L, itemEndMs = 900L))
    }

    @Test
    fun `retarget gate blocks a playing player before its item end (v1 5 6 wedge)`() {
        // The live-stream case: stopping here strands the in-flight frames
        // against the fresh clock — the 16474ms first-frame wedge. Defer.
        assertFalse(
            HeadEffectsPolicy.retargetSinkClearToProceed(
                ready, playWhenReady = true, positionMs = 500L, itemEndMs = 900L,
            ),
        )
    }

    @Test
    fun `retarget gate passes the parked player (paused short of the item end)`() {
        // THE ROUTINE v1.5.6 path: driveHead pauses the out-of-window head
        // ~250ms (the margin) short of the extended item end. Nothing is
        // flowing; the pre-stop sink scrub flushes the parked stragglers, so
        // the retarget must NOT wait for a drain that will never come.
        assertTrue(
            HeadEffectsPolicy.retargetSinkClearToProceed(
                ready, playWhenReady = false, positionMs = 500L, itemEndMs = 900L,
            ),
        )
    }

    @Test
    fun `retarget gate passes a playing player at or past its item end`() {
        assertTrue(
            HeadEffectsPolicy.retargetSinkClearToProceed(
                ready, playWhenReady = true, positionMs = 900L, itemEndMs = 900L,
            ),
        )
    }

    @Test
    fun `retarget gate blocks buffering players`() {
        // Preparation or a mid-item stall — frames may be queued in flight.
        assertFalse(
            HeadEffectsPolicy.retargetSinkClearToProceed(
                buffering, playWhenReady = false, positionMs = 0L, itemEndMs = 900L,
            ),
        )
    }

    @Test
    fun `head recycle fires at the retarget budget`() {
        // The accumulation bound: a persistent player serves at most
        // MAX_HEAD_RETARGETS windows before it is retired and rebuilt. Pin
        // the boundary and a sanity band (large enough that a normal edit
        // session rarely pays a cold rebuild, small enough that a wedging
        // session recovers within a couple of playthroughs).
        val max = HeadEffectsPolicy.MAX_HEAD_RETARGETS
        assertTrue(max in 8..24)
        assertFalse(HeadEffectsPolicy.shouldRecycleHead(max - 1))
        assertTrue(HeadEffectsPolicy.shouldRecycleHead(max))
        assertTrue(HeadEffectsPolicy.shouldRecycleHead(max + 5))
    }

    // ---------------- v1.5.8: replay cross-back ----------------

    @Test
    fun `replay cross-back fires when a played-through window is re-entered from before`() {
        // THE FIELD CASE: the playhead played through the window (high-water
        // 6.7s ≥ window end 1.8s), then a replay seeked it back to 0. The
        // mounted head carries the previous pass's residue — the retarget
        // MUST run even though the spec is byte-equal.
        assertTrue(
            HeadEffectsPolicy.replayCrossBackRetarget(
                furthestPlayheadUs = 6_700_000L, mounted = spec(), playheadUs = 0L,
            ),
        )
    }

    @Test
    fun `replay cross-back fires when the playhead lands back inside the window`() {
        // A backward seek straight INTO the window (e.g. the user drags the
        // playhead to mid-transition while playing): same residue, and the
        // head must re-point immediately for correctness.
        assertTrue(
            HeadEffectsPolicy.replayCrossBackRetarget(
                furthestPlayheadUs = 6_700_000L, mounted = spec(), playheadUs = 1_200_000L,
            ),
        )
    }

    @Test
    fun `replay cross-back holds while the mount has not yet played through the window`() {
        // Normal premount/preroll: the high-water mark is still ahead of the
        // window (or inside it) — the hot-path early-out keeps its cheap
        // scrub optimization. This is the guard against turning EVERY
        // premount tick into a retarget storm.
        assertFalse(
            HeadEffectsPolicy.replayCrossBackRetarget(
                furthestPlayheadUs = 900_000L, mounted = spec(), playheadUs = 900_000L,
            ),
        )
        // Boundary: the mark exactly at the window START is still "not
        // played through" (the pass has not reached the window END).
        assertFalse(
            HeadEffectsPolicy.replayCrossBackRetarget(
                furthestPlayheadUs = 1_000_000L, mounted = spec(), playheadUs = 999_999L,
            ),
        )
    }

    @Test
    fun `replay cross-back holds while the playhead is still past the window`() {
        // Normal forward playback after the window: the mark and the
        // playhead are both past the window end — nothing crossed BACK.
        assertFalse(
            HeadEffectsPolicy.replayCrossBackRetarget(
                furthestPlayheadUs = 6_700_000L, mounted = spec(), playheadUs = 6_700_000L,
            ),
        )
    }

    @Test
    fun `replay cross-back boundary is exactly the window end`() {
        // The mark reaching the window END is what "played through" means
        // (the tail is driveHead's business, not the cross-back's); the
        // playhead at exactly the window end is still "inside" the outgoing
        // coast — not a cross-back either.
        assertTrue(
            HeadEffectsPolicy.replayCrossBackRetarget(
                furthestPlayheadUs = 1_800_000L, mounted = spec(), playheadUs = 1_799_999L,
            ),
        )
        assertFalse(
            HeadEffectsPolicy.replayCrossBackRetarget(
                furthestPlayheadUs = 1_800_000L, mounted = spec(), playheadUs = 1_800_000L,
            ),
        )
    }
}
