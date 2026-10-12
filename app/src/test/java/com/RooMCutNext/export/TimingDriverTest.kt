package com.RooMCutNext.export

import com.RooMCutNext.core.model.Clip
import com.RooMCutNext.core.model.MediaAsset
import com.RooMCutNext.core.model.MediaType
import com.RooMCutNext.core.model.PipLayer
import com.RooMCutNext.core.model.Project
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.2.5 — the export timing driver's frame rate.
 *
 * DefaultVideoCompositor (1.11.1) derives every output timestamp from the
 * PRIMARY sequence and nearest-samples SECONDARY frames onto that grid,
 * dropping the ones in between. The timing driver pad must therefore run at
 * a frame rate at least as dense as EVERY video source in the project — if
 * it is ever sparser than a source, that source's in-between frames get
 * dropped and it judders (the exact bug this sequence exists to prevent).
 */
class TimingDriverTest {

    private fun videoAsset(id: String, fps: Float = 30f) = MediaAsset(
        id = id, fileName = "$id.mp4", displayName = "$id.mp4",
        type = MediaType.VIDEO, durationUs = 20_000_000L,
        width = 1920, height = 1080, fps = fps, hasAudio = true,
        videoCodec = "H264", audioCodec = "AAC", fileBytes = 1L, addedAtMs = 0L,
    )

    private fun imageAsset(id: String) = MediaAsset(
        id = id, fileName = "$id.jpg", displayName = "$id.jpg",
        type = MediaType.IMAGE, durationUs = 0L,
        width = 1080, height = 1920, fps = 0f, hasAudio = false,
        videoCodec = "", audioCodec = "", fileBytes = 1L, addedAtMs = 0L,
    )

    private fun clip(id: String, assetId: String) = Clip(
        id = id, assetId = assetId,
        sourceInUs = 0L, sourceOutUs = 10_000_000L,
    )

    private fun project(
        clips: List<Clip> = emptyList(),
        pips: List<PipLayer> = emptyList(),
        assets: List<MediaAsset>,
        fps: Int = 30,
    ) = Project(
        id = "p1", name = "P", width = 1080, height = 1920,
        assets = assets, clips = clips, pips = pips, fps = fps,
    )

    @Test
    fun `defaults to at least 30 when no video assets`() {
        assertEquals(30, CompositionBuilder.timingDriverFps(project(assets = listOf())))
    }

    @Test
    fun `floors at 30 even when project fps is lower`() {
        val a = videoAsset("a1", fps = 24f)
        assertEquals(30, CompositionBuilder.timingDriverFps(project(assets = listOf(a), fps = 24)))
    }

    @Test
    fun `fractional source fps rounds up`() {
        // 29.97 must not round DOWN: a 29fps grid would drop one 29.97fps
        // frame roughly every second.
        val a = videoAsset("a1", fps = 29.97f)
        assertEquals(30, CompositionBuilder.timingDriverFps(project(assets = listOf(a))))
    }

    @Test
    fun `absorbs the densest clip source`() {
        val a = videoAsset("a1", fps = 30f)
        val b = videoAsset("a2", fps = 60f)
        val p = project(
            clips = listOf(
                clip("c1", "a1"),
                clip("c2", "a2"),
            ),
            assets = listOf(a, b),
        )
        assertEquals(60, CompositionBuilder.timingDriverFps(p))
    }

    @Test
    fun `absorbs pip sources too`() {
        val a = videoAsset("a1", fps = 30f)
        val pip60 = videoAsset("pip", fps = 60f)
        val p = project(
            clips = listOf(clip("c1", "a1")),
            pips = listOf(PipLayer(id = "pip1", assetId = "pip", startUs = 0L)),
            assets = listOf(a, pip60),
        )
        assertEquals(60, CompositionBuilder.timingDriverFps(p))
    }

    @Test
    fun `caps pathological sources at 60`() {
        val a = videoAsset("a1", fps = 240f)
        val p = project(clips = listOf(clip("c1", "a1")), assets = listOf(a))
        assertEquals(60, CompositionBuilder.timingDriverFps(p))
    }

    @Test
    fun `images and unknown assets are ignored`() {
        val img = imageAsset("img")
        val p = project(
            clips = listOf(clip("c1", "img")),
            pips = listOf(PipLayer(id = "pip1", assetId = "GONE", startUs = 0L)),
            assets = listOf(img),
        )
        assertEquals(30, CompositionBuilder.timingDriverFps(p))
    }

    @Test
    fun `project fps itself is absorbed`() {
        val a = videoAsset("a1", fps = 30f)
        assertEquals(50, CompositionBuilder.timingDriverFps(project(assets = listOf(a), fps = 50)))
    }
}
