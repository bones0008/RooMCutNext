package com.RooMCutNext.export

import com.RooMCutNext.core.model.Clip
import com.RooMCutNext.core.model.MediaAsset
import com.RooMCutNext.core.model.MediaType
import com.RooMCutNext.core.model.PipLayer
import com.RooMCutNext.core.model.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.3 — the pure model math behind the view-layer preview overlays.
 *
 * headOverlaySpecs() must mirror EXACTLY the head-item construction inside
 * CompositionBuilder.build() (export path): a clip with effective transition
 * T fades in over [startUs, startUs+T), playing source
 * [sourceIn, sourceIn + T*speed). If these two ever drift apart, the preview
 * crossfade (view layer) and the exported crossfade (GL compositor) would
 * disagree — that is precisely what these tests pin down.
 */
class HeadOverlaySpecTest {

    private fun videoAsset(id: String, durationUs: Long = 20_000_000L) = MediaAsset(
        id = id, fileName = "$id.mp4", displayName = "$id.mp4",
        type = MediaType.VIDEO, durationUs = durationUs,
        width = 1920, height = 1080, fps = 30f, hasAudio = true,
        videoCodec = "H264", audioCodec = "AAC", fileBytes = 1L, addedAtMs = 0L,
    )

    private fun clip(
        id: String,
        assetId: String,
        sourceInUs: Long = 0L,
        sourceOutUs: Long = 10_000_000L,
        transitionInUs: Long = 0L,
        speed: Float = 1f,
        gapBeforeUs: Long = 0L,
    ) = Clip(
        id = id, assetId = assetId,
        sourceInUs = sourceInUs, sourceOutUs = sourceOutUs,
        transitionInUs = transitionInUs, speed = speed,
        gapBeforeUs = gapBeforeUs,
    )

    private fun project(vararg clips: Clip, assets: List<MediaAsset>) = Project(
        id = "p1", name = "P", width = 1080, height = 1920,
        assets = assets, clips = clips.toList(),
    )

    @Test
    fun `no transitions produce no heads`() {
        val a = videoAsset("a1")
        val p = project(
            clip("c1", "a1"),
            clip("c2", "a1", sourceInUs = 10_000_000L, sourceOutUs = 20_000_000L),
            assets = listOf(a),
        )
        assertTrue(CompositionBuilder.headOverlaySpecs(p).isEmpty())
    }

    @Test
    fun `head window and source span match the export head item`() {
        val a = videoAsset("a1")
        // 10s + 10s with a 1s crossfade on clip 2 -> clip 2 starts at 9s.
        val p = project(
            clip("c1", "a1"),
            clip("c2", "a1", sourceInUs = 4_000_000L, sourceOutUs = 14_000_000L, transitionInUs = 1_000_000L),
            assets = listOf(a),
        )
        val specs = CompositionBuilder.headOverlaySpecs(p)
        assertEquals(1, specs.size)
        val s = specs[0]
        assertEquals("c2", s.clipId)
        assertEquals(9_000_000L, s.startUs)          // timeline start of clip 2 (overlap)
        assertEquals(1_000_000L, s.durationUs)       // fade width = effective transition
        assertEquals(4_000_000L, s.sourceInUs)       // head plays the clip's source-IN span
        assertEquals(5_000_000L, s.sourceOutUs)      // sourceIn + T*speed
    }

    @Test
    fun `speed scales the head source span not the window`() {
        val a = videoAsset("a1")
        // 2x speed: 5s timeline per 10s source; 1s fade consumes 2s of source.
        val p = project(
            clip("c1", "a1"),
            clip("c2", "a1", sourceOutUs = 10_000_000L, transitionInUs = 1_000_000L, speed = 2f),
            assets = listOf(a),
        )
        val s = CompositionBuilder.headOverlaySpecs(p).single()
        assertEquals(1_000_000L, s.durationUs)
        assertEquals(0L, s.sourceInUs)
        assertEquals(2_000_000L, s.sourceOutUs)
        assertEquals(2f, s.speed)
    }

    @Test
    fun `transition behind a black hole is not a head`() {
        val a = videoAsset("a1")
        val p = project(
            clip("c1", "a1"),
            clip("c2", "a1", transitionInUs = 1_000_000L, gapBeforeUs = 2_000_000L),
            assets = listOf(a),
        )
        assertTrue(CompositionBuilder.headOverlaySpecs(p).isEmpty())
    }

    @Test
    fun `first clip transition is never effective`() {
        val a = videoAsset("a1")
        val p = project(clip("c1", "a1", transitionInUs = 1_000_000L), assets = listOf(a))
        assertTrue(CompositionBuilder.headOverlaySpecs(p).isEmpty())
    }

    @Test
    fun `missing asset yields no head`() {
        val a = videoAsset("a1")
        val p = project(clip("c1", "a1"), clip("c2", "GONE", transitionInUs = 1_000_000L), assets = listOf(a))
        assertTrue(CompositionBuilder.headOverlaySpecs(p).isEmpty())
    }

    @Test
    fun `multiple heads carry their own windows`() {
        val a = videoAsset("a1")
        // c1: 10s, c2: 10s (1s crossfade -> starts at 9s), c3: 10s (1s crossfade -> starts at 18s)
        val p = project(
            clip("c1", "a1"),
            clip("c2", "a1", transitionInUs = 1_000_000L),
            clip("c3", "a1", transitionInUs = 1_000_000L),
            assets = listOf(a),
        )
        val specs = CompositionBuilder.headOverlaySpecs(p)
        assertEquals(listOf("c2", "c3"), specs.map { it.clipId })
        assertEquals(9_000_000L, specs[0].startUs)
        assertEquals(18_000_000L, specs[1].startUs)
        assertEquals(1_000_000L, specs[1].durationUs)
    }

    @Test
    fun `pip windows do not leak into head specs`() {
        val a = videoAsset("a1")
        val p = project(
            clip("c1", "a1"),
            clip("c2", "a1", transitionInUs = 1_000_000L),
            assets = listOf(a),
        ).copy(pips = listOf(PipLayer(id = "pip1", assetId = "a1", startUs = 1_000_000L)))
        val specs = CompositionBuilder.headOverlaySpecs(p)
        assertEquals(1, specs.size)
        assertEquals("c2", specs[0].clipId)
    }
}
