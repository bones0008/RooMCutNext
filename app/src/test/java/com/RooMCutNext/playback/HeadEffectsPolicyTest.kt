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
}
