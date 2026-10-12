package com.RooMCutNext.core.ops

import com.RooMCutNext.core.model.Clip
import com.RooMCutNext.core.model.Project
import com.RooMCutNext.core.model.TRANSITION_MAX_US
import com.RooMCutNext.core.model.TRANSITION_MIN_BODY_US
import com.RooMCutNext.core.model.effectiveTransitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crossfade transition geometry (v1.2.1): starts() overlap math,
 * effectiveTransitions clamping, setTransitionIn, Project duration and
 * old-JSON compatibility.
 */
class TransitionTest {

    /** Video clip with the given source span (speed 1x). */
    private fun clip(id: String, durUs: Long, gapBeforeUs: Long = 0L, transitionInUs: Long = 0L) =
        Clip(
            id = id, assetId = "a-$id", gapBeforeUs = gapBeforeUs,
            sourceInUs = 0L, sourceOutUs = durUs,
            transitionInUs = transitionInUs,
        )

    @Test
    fun `starts without transitions keeps legacy prefix-sum layout`() {
        val clips = listOf(clip("a", 2_000_000L), clip("b", 3_000_000L, gapBeforeUs = 500_000L))
        val st = TimelineOps.starts(clips)
        assertEquals(0L, st[0])
        assertEquals(2_500_000L, st[1])
        assertEquals(5_500_000L, st[2])
    }

    @Test
    fun `transition pulls the clip back onto the previous tail`() {
        val clips = listOf(
            clip("a", 2_000_000L),
            clip("b", 3_000_000L, transitionInUs = 500_000L),
        )
        val st = TimelineOps.starts(clips)
        assertEquals(0L, st[0])
        // b starts 0.5s before a ends.
        assertEquals(1_500_000L, st[1])
        assertEquals(4_500_000L, st[2])
        // Total shortens by the overlap.
        val project = Project(id = "p", name = "p", clips = clips)
        assertEquals(4_500_000L, project.clipsDurationUs)
    }

    @Test
    fun `overlaps accumulate across a chain`() {
        val clips = listOf(
            clip("a", 2_000_000L),
            clip("b", 3_000_000L, transitionInUs = 500_000L),
            clip("c", 1_000_000L, transitionInUs = 300_000L),
        )
        val st = TimelineOps.starts(clips)
        assertEquals(1_500_000L, st[1])   // 2s - 0.5s
        assertEquals(4_200_000L, st[2])   // b end 4.5s - 0.3s
        assertEquals(5_200_000L, st[3])   // 6s - 0.5s - 0.3s total
    }

    @Test
    fun `starts stay monotonic when clamped`() {
        val clips = listOf(
            clip("a", 300_000L),
            // Requests far more than a's duration: effective clamps hard.
            clip("b", 3_000_000L, transitionInUs = TRANSITION_MAX_US),
            clip("c", 3_000_000L, transitionInUs = TRANSITION_MAX_US),
        )
        val st = TimelineOps.starts(clips)
        for (i in 0 until clips.size) {
            assertTrue("start[$i+1] >= start[$i]", st[i + 1] >= st[i])
        }
    }

    @Test
    fun `gap in front disables the transition`() {
        val clips = listOf(
            clip("a", 2_000_000L),
            clip("b", 3_000_000L, gapBeforeUs = 400_000L, transitionInUs = 800_000L),
        )
        val eff = effectiveTransitions(clips)
        assertEquals(0L, eff[1])
        val st = TimelineOps.starts(clips)
        assertEquals(2_400_000L, st[1]) // nominal position, no pull-back
    }

    @Test
    fun `first clip never has a transition`() {
        val clips = listOf(clip("a", 2_000_000L, transitionInUs = 1_000_000L))
        assertEquals(0L, effectiveTransitions(clips)[0])
    }

    @Test
    fun `transition cannot eat the previous clip's fading head`() {
        // a=2s; b fades 1.4s into a; c asks for 1.8s into b: c's fade must
        // fit into b's BODY (3s − 1.4s = 1.6s), so it clamps to 1.6s.
        val clips = listOf(
            clip("a", 2_000_000L),
            clip("b", 3_000_000L, transitionInUs = 1_400_000L),
            clip("c", 3_000_000L, transitionInUs = 1_800_000L),
        )
        val eff = effectiveTransitions(clips)
        assertEquals(1_400_000L, eff[1])
        assertEquals(1_600_000L, eff[2])
    }

    @Test
    fun `own body minimum is enforced`() {
        val clips = listOf(
            clip("a", 10_000_000L),
            clip("b", 1_000_000L, transitionInUs = 999_999L),
        )
        val eff = effectiveTransitions(clips)
        assertEquals(1_000_000L - TRANSITION_MIN_BODY_US, eff[1])
    }

    @Test
    fun `setTransitionIn clamps the raw stored value`() {
        val clips = listOf(clip("a", 10_000_000L), clip("b", 2_000_000L))
        val out = TimelineOps.setTransitionIn(clips, 1, 99_000_000L)
        // Capped at min(2s cap, b duration - body, a duration) = 1.9s.
        assertEquals(1_900_000L, out[1].transitionInUs)
        val zero = TimelineOps.setTransitionIn(clips, 1, -5L)
        assertEquals(0L, zero[1].transitionInUs)
    }

    @Test
    fun `setTransitionIn refuses the first clip`() {
        val clips = listOf(clip("a", 10_000_000L))
        val out = TimelineOps.setTransitionIn(clips, 0, 500_000L)
        assertEquals(0L, out[0].transitionInUs)
    }

    @Test
    fun `trimming a neighbor shortens the effective fade not the saved one`() {
        // b has 1s fade into a; a gets trimmed to 0.4s — effective follows,
        // the stored transitionInUs is untouched (no corruption on undo).
        var clips = listOf(clip("a", 2_000_000L), clip("b", 3_000_000L, transitionInUs = 1_000_000L))
        assertEquals(1_000_000L, effectiveTransitions(clips)[1])
        clips = listOf(clips[0].copy(sourceOutUs = 400_000L), clips[1])
        assertEquals(400_000L, effectiveTransitions(clips)[1])
        assertEquals(1_000_000L, clips[1].transitionInUs)
    }

    @Test
    fun `clipIndexAt resolves the overlap to the incoming clip`() {
        val clips = listOf(
            clip("a", 2_000_000L),
            clip("b", 3_000_000L, transitionInUs = 500_000L),
        )
        // 1.6s sits inside the overlap [1.5s, 2s): both play, b is current.
        assertEquals(1, TimelineOps.clipIndexAt(clips, 1_600_000L))
        // Before the overlap: a.
        assertEquals(0, TimelineOps.clipIndexAt(clips, 1_000_000L))
    }

    @Test
    fun `old clip json without transition field deserializes to hard cut`() {
        val json = """{"id":"c1","assetId":"a","gapBeforeUs":0,"sourceInUs":0,"sourceOutUs":2000000}"""
        val c = kotlinx.serialization.json.Json.decodeFromString<Clip>(json)
        assertEquals(0L, c.transitionInUs)
        assertTrue(c.mosaics.isEmpty())
    }
}
