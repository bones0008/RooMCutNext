package com.RooMCutNext.core.ops

import com.RooMCutNext.core.model.Clip
import com.RooMCutNext.core.model.MediaAsset
import com.RooMCutNext.core.model.MediaType
import com.RooMCutNext.core.model.TextOverlayItem
import com.RooMCutNext.core.undo.SnapshotHistory
import com.RooMCutNext.core.util.TimeCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineOpsTest {

    private fun asset(
        id: String = "a1",
        durationUs: Long = 10_000_000L,
        fps: Float = 30f,
        image: Boolean = false,
    ) = MediaAsset(
        id = id, fileName = "$id.mp4", displayName = "$id.mp4",
        type = if (image) MediaType.IMAGE else MediaType.VIDEO,
        durationUs = if (image) 0L else durationUs,
        width = 1920, height = 1080, fps = if (image) 0f else fps,
        hasAudio = true, videoCodec = "H264", audioCodec = "AAC",
        fileBytes = 123L, addedAtMs = 0L,
    )

    private fun clip(assetId: String = "a1", inUs: Long = 0L, outUs: Long = 10_000_000L) =
        Clip(id = "c-$assetId-$inUs", assetId = assetId, sourceInUs = inUs, sourceOutUs = outUs)

    private val lookup: (String) -> MediaAsset? = { id -> if (id == "a1") asset() else null }

    // ---------------- position mapping ----------------

    @Test
    fun `starts are prefix sums`() {
        val clips = listOf(clip(outUs = 2_000_000), clip(inUs = 0, outUs = 1_000_000))
        val st = TimelineOps.starts(clips)
        assertEquals(longArrayOf(0, 2_000_000, 3_000_000).toList(), st.toList())
        // zero gaps (legacy compact projects) keep the exact old layout
        assertEquals(0L, clips.sumOf { it.gapBeforeUs })
    }

    @Test
    fun `clipIndexAt finds correct clip`() {
        val clips = listOf(clip(outUs = 2_000_000), clip(outUs = 1_000_000))
        assertEquals(0, TimelineOps.clipIndexAt(clips, 0))
        assertEquals(0, TimelineOps.clipIndexAt(clips, 1_999_999))
        assertEquals(1, TimelineOps.clipIndexAt(clips, 2_000_000))
        assertEquals(-1, TimelineOps.clipIndexAt(clips, 3_000_000))
        assertEquals(-1, TimelineOps.clipIndexAt(clips, -1))
    }

    @Test
    fun `timelineToLocal roundtrip`() {
        val clips = listOf(clip(outUs = 2_000_000), clip(outUs = 1_000_000))
        val (idx, local) = TimelineOps.timelineToLocal(clips, 2_500_000)
        assertEquals(1, idx)
        assertEquals(500_000L, local)
        assertEquals(2_500_000L, TimelineOps.localToTimeline(clips, idx, local))
    }

    // ---------------- trim ----------------

    @Test
    fun `trim clamps to source bounds`() {
        var clips = listOf(clip())
        clips = TimelineOps.trim(clips, 0, lookup("a1"), -5_000_000L, 12_000_000L)
        // snapped to 30fps grid: in -> 0, out -> 10_000_000 (already on grid)
        assertEquals(0L, clips[0].sourceInUs)
        assertEquals(10_000_000L, clips[0].sourceOutUs)
    }

    @Test
    fun `trim snaps moving edge to source frame grid`() {
        // 30fps -> frame = 33333us. Request 1_000_000 -> snap -> 1_000_000? 1000000/33333 = 30.0003 -> 30*33333 = 999_990
        var clips = listOf(clip())
        clips = TimelineOps.trim(clips, 0, lookup("a1"), 0L, 1_000_000L)
        assertEquals(999_990L, clips[0].sourceOutUs) // snapped once
    }

    @Test
    fun `trim enforces minimum span`() {
        var clips = listOf(clip())
        clips = TimelineOps.trim(clips, 0, lookup("a1"), 9_950_000L, 10_000_000L)
        // min span 100ms -> in <= 9_900_000 (then snapped down within range)
        assertTrue(clips[0].sourceOutUs - clips[0].sourceInUs >= 100_000L)
    }

    @Test
    fun `image trim extends up to 60s`() {
        val img = asset(id = "img", image = true)
        val imgLookup: (String) -> MediaAsset? = { if (it == "img") img else null }
        var clips = listOf(Clip(id = "c1", assetId = "img", sourceInUs = 0, sourceOutUs = 3_000_000))
        clips = TimelineOps.trim(clips, 0, imgLookup("img"), 0L, 90_000_000L)
        assertEquals(60_000_000L, clips[0].sourceOutUs)
        // images are not frame-snapped
        clips = TimelineOps.trim(clips, 0, imgLookup("img"), 0L, 2_500_000L)
        assertEquals(2_500_000L, clips[0].sourceOutUs)
    }

    // ---------------- split ----------------

    @Test
    fun `split conserves span exactly`() {
        val clips = listOf(clip())
        val res = TimelineOps.splitAtTimeline(clips, lookup, 5_000_000L, "new1")
        assertNotNull(res)
        val left = res!!.clips[0]
        val right = res.clips[1]
        assertEquals(left.sourceSpanUs + right.sourceSpanUs, clips[0].sourceSpanUs)
        assertEquals(left.sourceOutUs, right.sourceInUs) // shared boundary, snapped once
        // 5_000_000 timeline -> snapped to the 30fps grid: 150 * 33333 = 4_999_950
        assertEquals(4_999_950L, left.sourceOutUs)
    }

    @Test
    fun `split at playhead on boundary or too short returns null`() {
        val clips = listOf(clip())
        assertNull(TimelineOps.splitAtTimeline(clips, lookup, 0L, "n"))
        assertNull(TimelineOps.splitAtTimeline(clips, lookup, 10_000_000L, "n"))
        val tiny = listOf(clip(outUs = 150_000))
        assertNull(TimelineOps.splitAtTimeline(tiny, lookup, 75_000, "n"))
    }

    @Test
    fun `split honors speed for boundary conversion`() {
        // 2x speed: timeline 2.5s = source 5s
        val clips = listOf(clip().copy(speed = 2f))
        val res = TimelineOps.splitAtTimeline(clips, lookup, 2_500_000L, "new1")
        assertNotNull(res)
        assertEquals(4_999_950L, res!!.clips[0].sourceOutUs)
        assertEquals(5_000_000L, res.clips[0].durationUs + res.clips[1].durationUs)
        assertEquals(clips[0].durationUs, res.clips[0].durationUs + res.clips[1].durationUs)
    }

    // ---------------- delete / move / duplicate ----------------

    @Test
    fun `delete keeps a black gap in the middle`() {
        // v1.0.8: deleting a middle clip leaves the hole — the tail clips
        // STAY WHERE THEY ARE (the user lays text over the black segment).
        val clips = listOf(clip("a", 0, 2_000_000), clip("a", 0, 3_000_000), clip("a", 0, 1_000_000))
        val after = TimelineOps.delete(clips, 1)
        assertEquals(2, after.size)
        val st = TimelineOps.starts(after)
        // st[1] is the third clip's REAL start (2s of A + 3s hole), not 2s.
        assertEquals(0L, st[0])
        assertEquals(5_000_000L, st[1])
        assertEquals(6_000_000L, st[2])
        assertEquals(3_000_000L, after[1].gapBeforeUs)
    }

    @Test
    fun `delete last clip drops the tail`() {
        val clips = listOf(clip("a", 0, 2_000_000), clip("a", 0, 3_000_000))
        val after = TimelineOps.delete(clips, 1)
        assertEquals(1, after.size)
        assertEquals(0L, after[0].gapBeforeUs)
        assertEquals(2_000_000L, TimelineOps.starts(after)[1])
    }

    @Test
    fun `delete merges the hole with an existing gap`() {
        // A | hole(1s) | B : deleting A leaves ONE 3s hole before B.
        val a = clip("a", 0, 2_000_000)
        val b = clip("b", 0, 1_000_000).copy(gapBeforeUs = 1_000_000L)
        val after = TimelineOps.delete(listOf(a, b), 0)
        assertEquals(1, after.size)
        assertEquals(3_000_000L, after[0].gapBeforeUs)
    }

    @Test
    fun `move reorders`() {
        val clips = listOf(clip("a", 0, 1_000_000), clip("b", 0, 1_000_000), clip("c", 0, 1_000_000))
        val after = TimelineOps.move(clips, 2, 0)
        assertEquals(listOf("c", "a", "b"), after.map { it.assetId })
        val after2 = TimelineOps.move(clips, 0, 2)
        assertEquals(listOf("b", "c", "a"), after2.map { it.assetId })
    }

    @Test
    fun `move on a compact list stays compact`() {
        // Reordering must NOT punch new holes: B|C|A with all gaps zero.
        val clips = listOf(clip("a", 0, 1_000_000), clip("b", 0, 1_000_000), clip("c", 0, 1_000_000))
        val after = TimelineOps.move(clips, 0, 2)
        assertEquals(0L, after.sumOf { it.gapBeforeUs })
        assertEquals(3_000_000L, TimelineOps.starts(after)[3])
    }

    @Test
    fun `move leaves existing holes in place`() {
        // A | hole(1s) | B : moving A to the end keeps the 1s hole at its position.
        val a = clip("a", 0, 1_000_000)
        val b = clip("b", 0, 1_000_000).copy(gapBeforeUs = 1_000_000L)
        val after = TimelineOps.move(listOf(a, b), 0, 1)
        assertEquals(listOf("b", "a"), after.map { it.assetId })
        // hole stayed in front of B
        assertEquals(1_000_000L, after[0].gapBeforeUs)
        assertEquals(0L, after[1].gapBeforeUs)
        assertEquals(3_000_000L, TimelineOps.starts(after)[2])
    }

    @Test
    fun `duplicate inserts copy right after`() {
        val clips = listOf(clip("a", 0, 1_000_000), clip("b", 0, 1_000_000))
        val after = TimelineOps.duplicate(clips, 0, "dup1")
        assertEquals(3, after.size)
        assertEquals("dup1", after[1].id)
        assertEquals(after[0].copy(id = "dup1"), after[1])
    }

    @Test
    fun `duplicate does not copy the leading hole`() {
        val a = clip("a", 0, 1_000_000).copy(gapBeforeUs = 2_000_000L)
        val b = clip("b", 0, 1_000_000)
        val after = TimelineOps.duplicate(listOf(a, b), 0, "dup1")
        assertEquals(0L, after[1].gapBeforeUs) // copy hugs the original
        assertEquals(2_000_000L, after[0].gapBeforeUs) // original keeps its hole
    }

    // ---------------- gaps (v1.0.8) ----------------

    @Test
    fun `starts include leading gaps`() {
        val a = clip("a", 0, 1_000_000)
        val b = clip("b", 0, 2_000_000).copy(gapBeforeUs = 500_000L)
        val st = TimelineOps.starts(listOf(a, b))
        assertEquals(0L, st[0])
        // st[1] = b's REAL start on the timeline (after its leading hole)
        assertEquals(1_500_000L, st[1])
        assertEquals(3_500_000L, st[2]) // 1s + 0.5s gap + 2s
    }

    @Test
    fun `clipIndexAt maps gap positions to the previous clip`() {
        val a = clip("a", 0, 1_000_000)
        val b = clip("b", 0, 1_000_000).copy(gapBeforeUs = 1_000_000L)
        val clips = listOf(a, b)
        assertEquals(0, TimelineOps.clipIndexAt(clips, 1_499_999)) // inside the gap
        assertEquals(1, TimelineOps.clipIndexAt(clips, 2_000_000)) // clip b start
    }

    @Test
    fun `split right half is tight`() {
        val a = clip("a1", 0, 4_000_000).copy(gapBeforeUs = 700_000L)
        val res = TimelineOps.splitAtTimeline(listOf(a), lookup, 2_700_000L, "new1")
        assertNotNull(res)
        assertEquals(700_000L, res!!.clips[0].gapBeforeUs) // left keeps the hole
        assertEquals(0L, res.clips[1].gapBeforeUs)          // right hugs the left
    }

    @Test
    fun `stepFrame backwards from inside a gap lands on the clip end`() {
        val a = clip("a", 0, 1_000_000)
        val b = clip("b", 0, 1_000_000).copy(gapBeforeUs = 1_000_000L)
        val clips = listOf(a, b)
        // playhead in the middle of the gap (1.5s); stepping back -> 1s (A's end)
        val back = TimelineOps.stepFrame(clips, lookup, 1_500_000L, forward = false)
        assertEquals(1_000_000L, back)
        // forward from inside the gap -> B's start (2s), skipping the gap tail
        val fwd = TimelineOps.stepFrame(clips, lookup, 1_500_000L, forward = true)
        assertEquals(2_000_000L, fwd)
    }

    // ---------------- place (v1.0.9 free positioning) ----------------

    @Test
    fun `place drops a clip after another with the dragged gap`() {
        // A(1s) + B(1s) compact; drop B at 3s -> A, 2s black, B.
        val a = clip("a", 0, 1_000_000)
        val b = clip("b", 0, 1_000_000)
        val after = TimelineOps.place(listOf(a, b), fromIndex = 1, insertAt = 1, gapBeforeUs = 2_000_000L)
        assertEquals(0L, after[0].gapBeforeUs)
        assertEquals(2_000_000L, after[1].gapBeforeUs)
        assertEquals(listOf(a.id, b.id), after.map { it.id })
        val st = TimelineOps.starts(after)
        assertEquals(3_000_000L, st[1]) // B starts after A + 2s hole
    }

    @Test
    fun `place before a clip pushes it right and absorbs its gap`() {
        // A(1s), B(gap 1s, 1s): drop A back into slot 0 with a 0.5s gap ->
        // B hugs the drop, its own 1s hole is replaced by the drop gap.
        val a = clip("a", 0, 1_000_000)
        val b = clip("b", 0, 1_000_000).copy(gapBeforeUs = 1_000_000L)
        val after = TimelineOps.place(listOf(a, b), fromIndex = 0, insertAt = 0, gapBeforeUs = 500_000L)
        assertEquals(listOf(a.id, b.id), after.map { it.id })
        assertEquals(500_000L, after[0].gapBeforeUs) // A dropped with its hole
        assertEquals(0L, after[1].gapBeforeUs)       // B hugs the drop
    }

    @Test
    fun `place keeps the removed clip's pre-existing hole in place`() {
        // A(1s), B(gap 2s, 1s), C(1s): move B to the FRONT with a 0 gap ->
        // B first, the 2s hole transfers to B's old follower C.
        val a = clip("a", 0, 1_000_000)
        val b = clip("b", 0, 1_000_000).copy(gapBeforeUs = 2_000_000L)
        val c = clip("c", 0, 1_000_000)
        val after = TimelineOps.place(listOf(a, b, c), fromIndex = 1, insertAt = 0, gapBeforeUs = 0L)
        assertEquals(listOf(b.id, a.id, c.id), after.map { it.id })
        assertEquals(0L, after[0].gapBeforeUs)
        assertEquals(0L, after[1].gapBeforeUs)
        assertEquals(2_000_000L, after[2].gapBeforeUs) // hole stayed in the timeline
    }

    @Test
    fun `place same slot zero gap is a no-op reorder`() {
        val a = clip("a", 0, 1_000_000)
        val b = clip("b", 0, 1_000_000)
        val after = TimelineOps.place(listOf(a, b), fromIndex = 1, insertAt = 1, gapBeforeUs = 0L)
        assertEquals(listOf(a.id, b.id), after.map { it.id })
        assertEquals(0L, after.sumOf { it.gapBeforeUs })
    }

    @Test
    fun `place with invalid index returns the list unchanged`() {
        val a = clip("a", 0, 1_000_000)
        assertEquals(listOf(a), TimelineOps.place(listOf(a), fromIndex = 5, insertAt = 0, gapBeforeUs = 1L))
    }

    // ---------------- speed ----------------

    @Test
    fun `speed changes timeline duration`() {
        val c = clip().copy(speed = 2f)
        assertEquals(5_000_000L, c.durationUs)
        val half = clip().copy(speed = 0.5f)
        assertEquals(20_000_000L, half.durationUs)
    }

    // ---------------- frame stepping ----------------

    @Test
    fun `stepFrame steps on the active clip source grid`() {
        // 24fps clip inside a 30fps-project timeline: frame = 41666us
        val a24 = asset(fps = 24f)
        val l24: (String) -> MediaAsset? = { a24 }
        val clips = listOf(clip().copy(sourceOutUs = 10_000_000))
        val t0 = 0L
        val t1 = TimelineOps.stepFrame(clips, l24, t0, forward = true)
        assertEquals(41_666L, t1)
        val t2 = TimelineOps.stepFrame(clips, l24, t1, forward = true)
        assertEquals(83_332L, t2)
        val back = TimelineOps.stepFrame(clips, l24, t2, forward = false)
        assertEquals(41_666L, back)
    }

    @Test
    fun `stepFrame crosses clip boundaries`() {
        val clips = listOf(clip("a", 0, 2_000_000), clip("b", 0, 1_000_000))
        val lookup2: (String) -> MediaAsset? = { if (it == "a") asset() else asset(id = "b") }
        // step forward from just before the boundary
        val nearEnd = 1_999_990L
        val t = TimelineOps.stepFrame(clips, lookup2, nearEnd, forward = true)
        assertEquals(2_000_000L, t) // boundary of clip b
        // step backward from boundary of b
        val back = TimelineOps.stepFrame(clips, lookup2, 2_000_000L, forward = false)
        assertTrue(back in 1_999_000L..2_000_000L)
        // at the very start
        assertEquals(0L, TimelineOps.stepFrame(clips, lookup2, 0L, forward = false))
    }

    @Test
    fun `stepFrame with speed converts through source grid`() {
        // 2x speed clip: one source frame = 33333us source = 16666us timeline
        val clips = listOf(clip().copy(speed = 2f))
        val t1 = TimelineOps.stepFrame(clips, lookup, 0L, forward = true)
        assertEquals(16_666L, t1)
    }

    // ---------------- texts ----------------

    @Test
    fun `clampTexts drops fully outside and clamps partially outside`() {
        val texts = listOf(
            TextOverlayItem(id = "t1", text = "a", startUs = 0, endUs = 1_000_000),
            TextOverlayItem(id = "t2", text = "b", startUs = 900_000, endUs = 2_000_000),
            TextOverlayItem(id = "t3", text = "c", startUs = 1_500_000, endUs = 3_000_000),
        )
        val out = TimelineOps.clampTexts(texts, totalDurationUs = 1_000_000)
        assertEquals(2, out.size)
        assertEquals(1_000_000L, out[1].endUs)
    }

    // ---------------- history ----------------

    @Test
    fun `history undo redo roundtrip`() {
        val h = SnapshotHistory<Int>()
        h.record("add", 0, 1)
        h.record("split", 1, 2)
        assertTrue(h.canUndo)
        assertEquals("split", h.undoLabel)
        assertEquals(1, h.undo(2))
        assertEquals(0, h.undo(1))
        assertFalse(h.canUndo)
        assertTrue(h.canRedo)
        assertEquals(1, h.redo(0))
        assertEquals(2, h.redo(1))
        assertFalse(h.canRedo)
    }

    @Test
    fun `record clears redo`() {
        val h = SnapshotHistory<Int>()
        h.record("a", 0, 1)
        h.undo(1)
        assertTrue(h.canRedo)
        h.record("b", 0, 5)
        assertFalse(h.canRedo)
    }

    @Test
    fun `history capacity trims oldest`() {
        val h = SnapshotHistory<Int>(capacity = 3)
        for (i in 0 until 10) h.record("op$i", i, i + 1)
        assertEquals(3, h.undoDepth)
        assertEquals("op9", h.undoLabel)
    }

    // ---------------- timecode ----------------

    @Test
    fun `timecode formatting`() {
        assertEquals("0:02", TimeCode.formatShort(2_000_000L))
        assertEquals("1:00:00", TimeCode.formatShort(3_600_000_000L))
        assertEquals("00:05.000", TimeCode.formatPrecise(5_000_000L))
        // 5,000,000us / 33,333us = frame 150 -> second 5, frame-in-second 0
        assertEquals("00:00:05:00", TimeCode.formatTimecodeFrames(5_000_000L, 30))
    }

    @Test
    fun `timecode snapping`() {
        assertEquals(999_990L, TimeCode.snapToFrame(1_000_000L, 30))
        assertEquals(0L, TimeCode.snapToFrame(10_000L, 30))
        assertEquals(1_000_000L, TimeCode.snapToFrame(1_000_000L, 25)) // 25fps grid hits exactly
    }

    @Test
    fun `timecode parse`() {
        assertEquals(83_500_000L, TimeCode.parseMs("1:23.5"))
        assertEquals(5_000_000L, TimeCode.parseMs("5"))
        assertEquals(5_000_000L, TimeCode.parseMs("0:05"))
        assertNull(TimeCode.parseMs("abc"))
        assertNull(TimeCode.parseMs(""))
    }

    @Test
    fun `trim with null asset keeps domain`() {
        val clips = listOf(clip())
        val out = TimelineOps.trim(clips, 0, null, 0L, 10_000_000L)
        assertEquals(clips, out)
    }

    // ---------------- audio track ops ----------------

    private fun audioAsset(id: String = "au1", durationUs: Long = 30_000_000L) =
        MediaAsset(
            id = id, fileName = "$id.mp3", displayName = "$id.mp3",
            type = MediaType.AUDIO, durationUs = durationUs,
            width = 0, height = 0, fps = 0f, hasAudio = true,
            audioCodec = "MP3", fileBytes = 1L, addedAtMs = 0L,
        )

    private fun audioClip(id: String, assetId: String = "au1", startUs: Long, inUs: Long = 0L, outUs: Long = 30_000_000L) =
        com.RooMCutNext.core.model.AudioClip(
            id = id, assetId = assetId, startUs = startUs, sourceInUs = inUs, sourceOutUs = outUs,
        )

    @Test
    fun `insertAudio keeps list sorted by start`() {
        val a = audioClip("x", startUs = 5_000_000L)
        val b = audioClip("y", startUs = 1_000_000L)
        val list = TimelineOps.insertAudio(TimelineOps.insertAudio(emptyList(), a), b)
        assertEquals(listOf("y", "x"), list.map { it.id })
    }

    // ---------------- audio split (v1.0.9) ----------------

    @Test
    fun `splitAudioAt cuts the clip under the playhead`() {
        // clip [2s, 6s) on the timeline, source [10s, 14s); cut at 4s.
        val item = audioClip("a", startUs = 2_000_000L, inUs = 10_000_000L, outUs = 14_000_000L)
        val res = TimelineOps.splitAudioAt(listOf(item), 4_000_000L, "a2")
        assertNotNull(res)
        val (list, rightId) = res!!
        assertEquals(2, list.size)
        assertEquals("a2", rightId)
        val left = list[0]
        val right = list[1]
        assertEquals(2_000_000L, left.startUs)
        assertEquals(10_000_000L, left.sourceInUs)
        assertEquals(12_000_000L, left.sourceOutUs) // 10s + 2s into the clip
        assertEquals(4_000_000L, right.startUs)     // at the playhead
        assertEquals(12_000_000L, right.sourceInUs)
        assertEquals(14_000_000L, right.sourceOutUs)
        // The two halves are contiguous: no audio lost, none duplicated.
        assertEquals(left.endUs, right.startUs)
        assertEquals(4_000_000L, left.durationUs + right.durationUs)
    }

    @Test
    fun `splitAudioAt returns null outside every clip`() {
        // start 2s, source span 2s -> timeline [2s, 4s).
        val item = audioClip("a", startUs = 2_000_000L, outUs = 2_000_000L)
        assertNull(TimelineOps.splitAudioAt(listOf(item), 0L, "a2"))
        assertNull(TimelineOps.splitAudioAt(listOf(item), 1_999_999L, "a2"))
        assertNull(TimelineOps.splitAudioAt(listOf(item), 4_000_000L, "a2")) // end is exclusive
        assertNull(TimelineOps.splitAudioAt(emptyList(), 1L, "a2"))
    }

    @Test
    fun `splitAudioAt enforces the minimum span on both halves`() {
        // 300ms clip: 100ms min span means only the 100..200ms range can cut.
        val item = audioClip("a", startUs = 0L, outUs = 300_000L)
        assertNull(TimelineOps.splitAudioAt(listOf(item), 50_000L, "a2"))   // left too short
        assertNull(TimelineOps.splitAudioAt(listOf(item), 250_000L, "a2")) // right too short
        assertNotNull(TimelineOps.splitAudioAt(listOf(item), 150_000L, "a2"))
    }

    @Test
    fun `splitAudioAt leaves other clips untouched`() {
        val a = audioClip("a", startUs = 0L, outUs = 2_000_000L)
        val b = audioClip("b", startUs = 5_000_000L, outUs = 7_000_000L)
        val res = TimelineOps.splitAudioAt(listOf(a, b), 1_000_000L, "a2")!!
        assertEquals(3, res.first.size)
        assertEquals(b, res.first.last()) // untouched
    }

    @Test
    fun `trimAudioEdge left keeps end fixed and consumes source headroom`() {
        // clip: start 5s, source [10s, 40s] -> span 30s, timeline end 35s
        val item = audioClip("a", startUs = 5_000_000L, inUs = 10_000_000L, outUs = 40_000_000L)
        val out = TimelineOps.trimAudioEdge(listOf(item), "a", audioAsset(), isLeft = true, timelineTargetUs = 2_000_000L)
        val t = out.first()
        assertEquals(2_000_000L, t.startUs)
        assertEquals(35_000_000L, t.endUs) // end unchanged
        assertEquals(7_000_000L, t.sourceInUs) // moved back with the edge
        assertEquals(40_000_000L, t.sourceOutUs)
    }

    @Test
    fun `trimAudioEdge left cannot extend past source start`() {
        val item = audioClip("a", startUs = 5_000_000L, inUs = 1_000_000L, outUs = 6_000_000L)
        val out = TimelineOps.trimAudioEdge(listOf(item), "a", audioAsset(), isLeft = true, timelineTargetUs = 0L)
        val t = out.first()
        // max left extension = sourceIn headroom: start >= end - in - span = 10s - 1s - 5s = 4s
        assertEquals(4_000_000L, t.startUs)
        assertEquals(0L, t.sourceInUs)
    }

    @Test
    fun `trimAudioEdge right keeps start fixed and respects source tail`() {
        val item = audioClip("a", startUs = 5_000_000L, inUs = 0L, outUs = 10_000_000L)
        val out = TimelineOps.trimAudioEdge(listOf(item), "a", audioAsset(durationUs = 15_000_000L), isLeft = false, timelineTargetUs = 9_000_000L)
        val t = out.first()
        assertEquals(5_000_000L, t.startUs)
        assertEquals(9_000_000L, t.endUs)
        assertEquals(4_000_000L, t.sourceOutUs)
    }

    @Test
    fun `trimAudioEdge enforces minimum span`() {
        val item = audioClip("a", startUs = 5_000_000L, inUs = 0L, outUs = 2_000_000L)
        val out = TimelineOps.trimAudioEdge(listOf(item), "a", audioAsset(durationUs = 5_000_000L), isLeft = false, timelineTargetUs = 5_050_000L)
        assertEquals(100_000L, out.first().durationUs)
    }

    @Test
    fun `laneIndexes packs overlaps into separate lanes`() {
        val lanes = TimelineOps.laneIndexes(
            listOf(
                0L to 3_000_000L,
                1_000_000L to 4_000_000L, // overlaps the first -> lane 1
                3_000_000L to 5_000_000L, // starts when first ends -> lane 0
                4_200_000L to 7_000_000L, // fits after lane1's 4s end -> lane 1
                4_500_000L to 6_000_000L, // overlaps lane0(5s) and lane1(7s) -> lane 2
            )
        )
        assertEquals(listOf(0, 1, 0, 1, 2), lanes.toList())
    }

    @Test
    fun `audioLanes groups non-overlapping clips into one lane`() {
        val a = audioClip("a", startUs = 0L, outUs = 3_000_000L)
        val b = audioClip("b", startUs = 3_000_000L, outUs = 5_000_000L)
        val c = audioClip("c", startUs = 1_000_000L, outUs = 2_000_000L)
        val lanes = TimelineOps.audioLanes(listOf(a, b, c))
        assertEquals(2, lanes.size)
        assertEquals(setOf("a", "b"), lanes[0].map { it.id }.toSet())
        assertEquals(listOf("c"), lanes[1].map { it.id })
    }

    @Test
    fun `stepFrame on empty clips uses project grid`() {
        val target = TimelineOps.stepFrame(
            emptyList(), lookup, 1_000_000L, forward = true,
            totalDurationUs = 10_000_000L, frameDurationUs = 40_000L,
        )
        assertEquals(1_040_000L, target)
        val back = TimelineOps.stepFrame(
            emptyList(), lookup, 1_000_000L, forward = false,
            totalDurationUs = 10_000_000L, frameDurationUs = 40_000L,
        )
        assertEquals(960_000L, back)
    }

    @Test
    fun `project total duration spans all layers`() {
        val project = com.RooMCutNext.core.model.Project(
            id = "p", name = "p",
            clips = listOf(clip(outUs = 5_000_000L)),
            texts = listOf(TextOverlayItem(id = "t", text = "x", startUs = 0L, endUs = 8_000_000L)),
            audios = listOf(audioClip("a", startUs = 2_000_000L, outUs = 6_000_000L)),
        )
        assertEquals(8_000_000L, project.totalDurationUs)
        assertTrue(project.hasContent)
    }

    // ---------------- apply adjust to all (v1.1.2) ----------------

    @Test
    fun `applyAdjustToAll copies grading onto every clip`() {
        val grading = com.RooMCutNext.core.model.ColorAdjust(
            brightness = 0.2f, contrast = -0.1f, saturation = 0.4f,
            temperature = 0.3f, tint = -0.05f,
            rGain = 0.1f, gGain = 0f, bGain = -0.2f,
            lutId = "warm", lutStrength = 0.7f,
        )
        val clips = listOf(clip(), clip(), clip())
        val out = TimelineOps.applyAdjustToAll(clips, grading)
        assertEquals(3, out.size)
        out.forEach { assertEquals(grading, it.adjust) }
    }

    @Test
    fun `applyAdjustToAll touches nothing but adjust`() {
        val grading = com.RooMCutNext.core.model.ColorAdjust(brightness = 0.9f)
        val original = clip(inUs = 1_000_000L, outUs = 4_000_000L).copy(speed = 2f, volume = 0.5f)
        val out = TimelineOps.applyAdjustToAll(listOf(original), grading)
        assertEquals(original.id, out[0].id)
        assertEquals(original.sourceInUs, out[0].sourceInUs)
        assertEquals(original.sourceOutUs, out[0].sourceOutUs)
        assertEquals(original.speed, out[0].speed)
        assertEquals(original.volume, out[0].volume)
        assertEquals(grading, out[0].adjust)
    }

    @Test
    fun `applyAdjustToAll with neutral grading clears existing filters`() {
        val graded = clip().copy(adjust = com.RooMCutNext.core.model.ColorAdjust(lutId = "cinema"))
        val out = TimelineOps.applyAdjustToAll(listOf(graded), com.RooMCutNext.core.model.ColorAdjust())
        assertNull(out[0].adjust.lutId)
        assertTrue(out[0].adjust.isNeutral)
    }
}
