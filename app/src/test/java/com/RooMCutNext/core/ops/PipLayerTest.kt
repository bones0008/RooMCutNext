package com.RooMCutNext.core.ops

import com.RooMCutNext.core.model.MediaAsset
import com.RooMCutNext.core.model.MediaType
import com.RooMCutNext.core.model.PipLayer
import com.RooMCutNext.core.model.Project
import org.junit.Assert.assertEquals
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
}
