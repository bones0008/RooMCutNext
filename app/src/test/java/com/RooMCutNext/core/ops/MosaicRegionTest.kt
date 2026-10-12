package com.RooMCutNext.core.ops

import com.RooMCutNext.core.model.Clip
import com.RooMCutNext.core.model.MosaicRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mosaic region CRUD on clips (v1.2.2). */
class MosaicRegionTest {

    private fun clip(id: String = "c1", regions: List<MosaicRegion> = emptyList()) =
        Clip(id = id, assetId = "a", sourceInUs = 0L, sourceOutUs = 2_000_000L, mosaics = regions)

    private fun region(id: String) = MosaicRegion(id = id)

    @Test
    fun `addMosaicRegion appends and returns the region`() {
        val result = TimelineOps.addMosaicRegion(listOf(clip()), "c1", region("m1"))
        assertNotNull(result)
        assertEquals(listOf("m1"), result!!.first[0].mosaics.map { it.id })
        assertEquals("m1", result.second.id)
    }

    @Test
    fun `cap of four regions per clip`() {
        val clips = listOf(clip(regions = listOf(region("m1"), region("m2"), region("m3"), region("m4"))))
        assertNull(TimelineOps.addMosaicRegion(clips, "c1", region("m5")))
        assertEquals(4, clips[0].mosaics.size)
    }

    @Test
    fun `addMosaicRegion on a missing clip is a no-op`() {
        assertNull(TimelineOps.addMosaicRegion(listOf(clip()), "missing", region("m1")))
    }

    @Test
    fun `updateMosaicRegion replaces by id and keeps order`() {
        val clips = listOf(clip(regions = listOf(region("m1"), region("m2"))))
        val updated = MosaicRegion(id = "m2", x = 0.7f, y = 0.2f, w = 0.5f, h = 0.25f, blockRatio = 0.08f)
        val out = TimelineOps.updateMosaicRegion(clips, "c1", updated)
        assertEquals(listOf("m1", "m2"), out[0].mosaics.map { it.id })
        assertEquals(0.7f, out[0].mosaics[1].x, 0f)
        assertEquals(0.08f, out[0].mosaics[1].blockRatio, 0f)
    }

    @Test
    fun `removeMosaicRegion deletes only the target`() {
        val clips = listOf(clip(regions = listOf(region("m1"), region("m2"))))
        val out = TimelineOps.removeMosaicRegion(clips, "c1", "m1")
        assertEquals(listOf("m2"), out[0].mosaics.map { it.id })
        val untouched = TimelineOps.removeMosaicRegion(clips, "other", "m1")
        assertEquals(2, untouched[0].mosaics.size)
    }

    @Test
    fun `regions only affect their own clip`() {
        val clips = listOf(clip("c1", listOf(region("m1"))), clip("c2", listOf(region("m2"))))
        val out = TimelineOps.removeMosaicRegion(clips, "c1", "m1")
        assertTrue(out[0].mosaics.isEmpty())
        assertEquals(1, out[1].mosaics.size)
    }
}
