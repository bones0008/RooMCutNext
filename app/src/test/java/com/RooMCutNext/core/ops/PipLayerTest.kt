package com.RooMCutNext.core.ops

import com.RooMCutNext.core.model.MediaAsset
import com.RooMCutNext.core.model.MediaType
import com.RooMCutNext.core.model.PipLayer
import com.RooMCutNext.core.model.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipLayerTest {

    private fun videoAsset(id: String = "a1", durationUs: Long = 10_000_000L) = MediaAsset(
        id = id, fileName = "$id.mp4", displayName = "$id.mp4",
        type = MediaType.VIDEO, durationUs = durationUs,
        width = 1920, height = 1080, fps = 30f, hasAudio = true,
        videoCodec = "H264", audioCodec = "AAC", fileBytes = 1L, addedAtMs = 0L,
    )

    private fun imageAsset(id: String = "img1") = MediaAsset(
        id = id, fileName = "$id.png", displayName = "$id.png",
        type = MediaType.IMAGE, durationUs = 0L,
        width = 512, height = 512, fps = 0f, hasAudio = false,
        videoCodec = null, audioCodec = null, fileBytes = 1L, addedAtMs = 0L,
    )

    @Test
    fun `insertPip keeps list sorted by start`() {
        val mid = PipLayer(id = "p2", assetId = "a1", startUs = 5_000_000L)
        val early = PipLayer(id = "p1", assetId = "a1", startUs = 1_000_000L)
        val out = TimelineOps.insertPip(TimelineOps.insertPip(emptyList(), mid), early)
        assertEquals(listOf("p1", "p2"), out.map { it.id })
    }

    @Test
    fun `movePip slides whole block and clamps at zero`() {
        val p = PipLayer(id = "p", assetId = "a1", startUs = 2_000_000L, durationUs = 3_000_000L)
        val moved = TimelineOps.movePip(listOf(p), "p", 8_000_000L)
        assertEquals(8_000_000L, moved[0].startUs)
        assertEquals(3_000_000L, moved[0].durationUs)
        val clamped = TimelineOps.movePip(listOf(p), "p", -5L)
        assertEquals(0L, clamped[0].startUs)
    }

    @Test
    fun `trimPipEdge right edge capped by video source duration`() {
        val asset = videoAsset(durationUs = 10_000_000L)
        val p = PipLayer(id = "p", assetId = "a1", startUs = 2_000_000L, durationUs = 4_000_000L)
        // try to extend to 30s: capped at start + sourceDur = 12s
        val out = TimelineOps.trimPipEdge(listOf(p), "p", asset, isLeft = false, timelineTargetUs = 30_000_000L)
        assertEquals(10_000_000L, out[0].durationUs)
    }

    @Test
    fun `trimPipEdge right edge unbounded for images`() {
        val asset = imageAsset()
        val p = PipLayer(id = "p", assetId = "img1", startUs = 0L, durationUs = 3_000_000L)
        val out = TimelineOps.trimPipEdge(listOf(p), "p", asset, isLeft = false, timelineTargetUs = 60_000_000L)
        assertEquals(60_000_000L, out[0].durationUs)
    }

    @Test
    fun `trimPipEdge left edge keeps end fixed`() {
        val asset = videoAsset(durationUs = 30_000_000L)
        val p = PipLayer(id = "p", assetId = "a1", startUs = 2_000_000L, durationUs = 4_000_000L)
        val out = TimelineOps.trimPipEdge(listOf(p), "p", asset, isLeft = true, timelineTargetUs = 3_000_000L)
        assertEquals(3_000_000L, out[0].startUs)
        assertEquals(3_000_000L, out[0].durationUs) // end stays at 6s
    }

    @Test
    fun `trimPipEdge enforces minimum duration`() {
        val asset = videoAsset(durationUs = 30_000_000L)
        val p = PipLayer(id = "p", assetId = "a1", startUs = 0L, durationUs = 1_000_000L)
        val left = TimelineOps.trimPipEdge(listOf(p), "p", asset, isLeft = true, timelineTargetUs = 900_000L)
        assertTrue(left[0].durationUs >= TimelineOps.PIP_MIN_DURATION_US)
        val right = TimelineOps.trimPipEdge(listOf(p), "p", asset, isLeft = false, timelineTargetUs = 50_000L)
        assertTrue(right[0].durationUs >= TimelineOps.PIP_MIN_DURATION_US)
    }

    @Test
    fun `deletePip removes only the target`() {
        val a = PipLayer(id = "a", assetId = "x", startUs = 0L)
        val b = PipLayer(id = "b", assetId = "x", startUs = 1L)
        val out = TimelineOps.deletePip(listOf(a, b), "a")
        assertEquals(listOf("b"), out.map { it.id })
    }

    @Test
    fun `project duration spans pip layers`() {
        val project = Project(
            id = "p", name = "p",
            clips = emptyList(),
            pips = listOf(PipLayer(id = "p1", assetId = "a", startUs = 1_000_000L, durationUs = 9_000_000L)),
        )
        assertEquals(10_000_000L, project.totalDurationUs)
        assertTrue(project.hasContent)
        assertEquals("p1", project.pipById("p1")?.id)
        assertNull(project.pipById("missing"))
    }

    @Test
    fun `old project json without pips field deserializes`() {
        // Old persisted projects have no "pips" key; kotlinx uses the default.
        val json = """{"id":"p","name":"old","fps":30,"width":1920,"height":1080,"assets":[],"clips":[],"texts":[],"audios":[],"createdAtMs":0,"updatedAtMs":0,"schemaVersion":2}"""
        val project = kotlinx.serialization.json.Json.decodeFromString<Project>(json)
        assertTrue(project.pips.isEmpty())
        assertEquals(0L, project.totalDurationUs)
    }

    // ---------------- v1.5.5: split + source offset ----------------

    @Test
    fun `splitPipAt cuts the layer under the playhead`() {
        val p = PipLayer(id = "p", assetId = "a1", startUs = 2_000_000L, durationUs = 6_000_000L)
        val res = TimelineOps.splitPipAt(listOf(p), 4_000_000L, "p2")
        assertNotNull(res)
        val (out, rightId) = res!!
        assertEquals(2, out.size)
        val left = out[0]
        val right = out[1]
        assertEquals("p", left.id)
        assertEquals(2_000_000L, left.startUs)
        assertEquals(2_000_000L, left.durationUs) // 2s -> 4s
        assertEquals("p2", right.id)
        assertEquals(4_000_000L, right.startUs)
        assertEquals(4_000_000L, right.durationUs) // 4s -> 8s
        assertEquals(rightId, "p2")
    }

    @Test
    fun `splitPipAt right half plays the source from the cut point`() {
        // The whole point of sourceInUs: a split layer's halves show
        // SEQUENTIAL content, not two restarts of the source head.
        val p = PipLayer(id = "p", assetId = "a1", startUs = 1_000_000L, durationUs = 8_000_000L, sourceInUs = 3_000_000L)
        val res = TimelineOps.splitPipAt(listOf(p), 5_000_000L, "p2")
        assertNotNull(res)
        val (out, _) = res!!
        assertEquals(3_000_000L, out[0].sourceInUs)       // left keeps the offset
        assertEquals(4_000_000L, out[0].durationUs)       // 5s - 1s span
        assertEquals(7_000_000L, out[1].sourceInUs)       // 3s + the left span
        assertEquals(4_000_000L, out[1].durationUs)
    }

    @Test
    fun `splitPipAt geometry is inherited by both halves`() {
        val p = PipLayer(
            id = "p", assetId = "a1", startUs = 0L, durationUs = 4_000_000L,
            xRatio = 0.3f, yRatio = 0.7f, scale = 0.55f, opacity = 0.8f,
        )
        val (out, _) = TimelineOps.splitPipAt(listOf(p), 2_000_000L, "p2")!!
        assertEquals(0.3f, out[1].xRatio)
        assertEquals(0.7f, out[1].yRatio)
        assertEquals(0.55f, out[1].scale)
        assertEquals(0.8f, out[1].opacity)
    }

    @Test
    fun `splitPipAt returns null outside every layer`() {
        val p = PipLayer(id = "p", assetId = "a1", startUs = 1_000_000L, durationUs = 4_000_000L)
        assertNull(TimelineOps.splitPipAt(listOf(p), 0L, "p2"))
        assertNull(TimelineOps.splitPipAt(listOf(p), 5_000_000L, "p2")) // end is exclusive
        assertNull(TimelineOps.splitPipAt(emptyList(), 1L, "p2"))
    }

    @Test
    fun `splitPipAt enforces the minimum span on both halves`() {
        val p = PipLayer(id = "p", assetId = "a1", startUs = 0L, durationUs = 300_000L)
        assertNull(TimelineOps.splitPipAt(listOf(p), 50_000L, "p2"))   // left too short
        assertNull(TimelineOps.splitPipAt(listOf(p), 250_000L, "p2"))  // right too short
    }

    @Test
    fun `trimPipEdge left edge advances the video source window`() {
        val asset = videoAsset(durationUs = 30_000_000L)
        val p = PipLayer(id = "p", assetId = "a1", startUs = 2_000_000L, durationUs = 4_000_000L, sourceInUs = 5_000_000L)
        val out = TimelineOps.trimPipEdge(listOf(p), "p", asset, isLeft = true, timelineTargetUs = 3_000_000L)
        assertEquals(3_000_000L, out[0].startUs)
        assertEquals(6_000_000L, out[0].sourceInUs) // 5s + the 1s drag
        assertEquals(3_000_000L, out[0].durationUs) // end fixed at 6s
    }

    @Test
    fun `trimPipEdge left edge clamps the source offset to the source bounds`() {
        val asset = videoAsset(durationUs = 10_000_000L)
        // sourceIn 8s, drag the left edge 5s right: the offset would pass
        // duration(10s) - PIP_MIN, so it clamps; the layer simply cannot
        // show material that does not exist.
        val p = PipLayer(id = "p", assetId = "a1", startUs = 0L, durationUs = 9_000_000L, sourceInUs = 8_000_000L)
        val out = TimelineOps.trimPipEdge(listOf(p), "p", asset, isLeft = true, timelineTargetUs = 5_000_000L)
        assertTrue(out[0].sourceInUs <= 10_000_000L - TimelineOps.PIP_MIN_DURATION_US)
        assertEquals(4_000_000L, out[0].durationUs) // end fixed at 9s
    }

    @Test
    fun `trimPipEdge left edge keeps image offset untouched`() {
        val asset = imageAsset()
        val p = PipLayer(id = "p", assetId = "img1", startUs = 0L, durationUs = 4_000_000L, sourceInUs = 0L)
        val out = TimelineOps.trimPipEdge(listOf(p), "p", asset, isLeft = true, timelineTargetUs = 1_000_000L)
        assertEquals(0L, out[0].sourceInUs) // a still has no source time axis
        assertEquals(3_000_000L, out[0].durationUs)
    }

    @Test
    fun `trimPipEdge right edge capped by the REMAINING source`() {
        val asset = videoAsset(durationUs = 10_000_000L)
        // sourceIn 4s: only 6s of material remain, so the layer cannot be
        // extended past start(2s) + 6s even though the source is 10s long.
        val p = PipLayer(id = "p", assetId = "a1", startUs = 2_000_000L, durationUs = 4_000_000L, sourceInUs = 4_000_000L)
        val out = TimelineOps.trimPipEdge(listOf(p), "p", asset, isLeft = false, timelineTargetUs = 30_000_000L)
        assertEquals(6_000_000L, out[0].durationUs)
    }
}
