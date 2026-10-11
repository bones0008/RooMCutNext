package com.RooMCutNext.core.ops

import com.RooMCutNext.core.model.AUDIO_MIN_DURATION_US
import com.RooMCutNext.core.model.AudioClip
import com.RooMCutNext.core.model.Clip
import com.RooMCutNext.core.model.IMAGE_MAX_DURATION_US
import com.RooMCutNext.core.model.MediaAsset
import com.RooMCutNext.core.model.TextOverlayItem
import kotlin.math.max
import kotlin.math.min

/**
 * Pure, immutable timeline operations for the single-layer editor.
 *
 * The timeline is an ordered [Clip] list; positions are prefix sums of clip
 * durations, so the layout is always ripple (no gaps, no overlaps).
 *
 * Conventions (from studying OpenCut / LibreCuts):
 *  - All times are integer microseconds.
 *  - "Snap-once" discipline: when splitting or trimming, the moving boundary is
 *    snapped to the source frame grid exactly once and both sides are derived
 *    from it by subtraction, preserving span-conservation invariants.
 *  - Every function is total: inputs are clamped into the legal domain instead
 *    of throwing (UI gestures generate wild inputs).
 */
object TimelineOps {

    /** Minimum span of source media a clip may use (100ms). */
    const val MIN_SOURCE_SPAN_US: Long = 100_000L

    // ------------------------------------------------------------------
    // Position mapping (prefix sums)
    // ------------------------------------------------------------------

    /** Cumulative start time of each clip, microseconds. clips.size + 1 entries. */
    fun starts(clips: List<Clip>): LongArray {
        val out = LongArray(clips.size + 1)
        var acc = 0L
        for (i in clips.indices) {
            out[i] = acc
            acc += clips[i].durationUs
        }
        out[clips.size] = acc
        return out
    }

    /** Index of the clip playing at [timelineUs], or -1 if past the end / empty. */
    fun clipIndexAt(clips: List<Clip>, timelineUs: Long): Int {
        if (clips.isEmpty()) return -1
        val st = starts(clips)
        if (timelineUs < 0 || timelineUs >= st[clips.size]) return -1
        var lo = 0
        var hi = clips.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (st[mid] <= timelineUs) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Timeline position of a clip's local time (local = since clip start). */
    fun localToTimeline(clips: List<Clip>, index: Int, localUs: Long): Long {
        val st = starts(clips)
        return (st[index] + localUs).coerceIn(st[index], st[index + 1])
    }

    /** Convert a timeline position to (clipIndex, localUs). Past-the-end clamps to the last clip end. */
    fun timelineToLocal(clips: List<Clip>, timelineUs: Long): Pair<Int, Long> {
        val idx = clipIndexAt(clips, timelineUs)
        if (idx == -1) {
            if (clips.isEmpty()) return -1 to 0L
            val st = starts(clips)
            return clips.size - 1 to (st[clips.size] - st[clips.size - 1])
        }
        val st = starts(clips)
        return idx to (timelineUs - st[idx])
    }

    /** Usable source duration of an asset (images may be extended up to 60s). */
    fun assetDurationUs(asset: MediaAsset): Long =
        if (asset.isImage) IMAGE_MAX_DURATION_US else asset.durationUs

    // ------------------------------------------------------------------
    // Structural operations (all return new lists, ripple by construction)
    // ------------------------------------------------------------------

    fun insert(clips: List<Clip>, clip: Clip, atIndex: Int): List<Clip> {
        val idx = atIndex.coerceIn(0, clips.size)
        return clips.subList(0, idx) + clip + clips.subList(idx, clips.size)
    }

    fun append(clips: List<Clip>, clip: Clip): List<Clip> = clips + clip

    /** Ripple delete: the following clips close the gap automatically. */
    fun delete(clips: List<Clip>, index: Int): List<Clip> {
        if (index < 0 || index >= clips.size) return clips
        return clips.filterIndexed { i, _ -> i != index }
    }

    fun deleteById(clips: List<Clip>, clipId: String): List<Clip> =
        clips.filter { it.id != clipId }

    /**
     * Move a clip (reorder): remove it, then insert at [toIndex] of the
     * remaining list (0..clips.size-1). Ripple semantics keep the timeline
     * contiguous.
     */
    fun move(clips: List<Clip>, fromIndex: Int, toIndex: Int): List<Clip> {
        if (fromIndex !in clips.indices) return clips
        if (toIndex !in 0 until clips.size) return clips
        if (fromIndex == toIndex) return clips
        val item = clips[fromIndex]
        val without = clips.toMutableList().apply { removeAt(fromIndex) }
        // After removal the valid insertion range is 0..without.size.
        without.add(toIndex.coerceIn(0, without.size), item)
        return without
    }

    fun duplicate(clips: List<Clip>, index: Int, newId: String): List<Clip> {
        if (index !in clips.indices) return clips
        val copy = clips[index].copy(id = newId)
        return clips.subList(0, index + 1) + copy + clips.subList(index + 1, clips.size)
    }

    fun update(clips: List<Clip>, clipId: String, transform: (Clip) -> Clip): List<Clip> =
        clips.map { if (it.id == clipId) transform(it) else it }

    // ------------------------------------------------------------------
    // Trim
    // ------------------------------------------------------------------

    /**
     * Set new trim points. Each requested edge is clamped to the legal domain
     * and snapped to the source frame grid independently (the user only ever
     * moves one edge at a time). The other edge is never touched, so the
     * "snap-once" invariant holds per edge.
     *
     * @param requestedInUs  new source-in in source coordinates
     * @param requestedOutUs new source-out in source coordinates
     * @return the updated clip list
     */
    fun trim(
        clips: List<Clip>,
        index: Int,
        asset: MediaAsset?,
        requestedInUs: Long,
        requestedOutUs: Long,
    ): List<Clip> {
        if (index !in clips.indices) return clips
        val clip = clips[index]
        val dur = assetDurationUs(asset ?: return clips) // null asset: keep current span domain
        val minSpan = min(MIN_SOURCE_SPAN_US, max(dur / 2, 1L))

        var newIn = requestedInUs.coerceIn(0L, max(0L, clip.sourceOutUs - minSpan))
        var newOut = requestedOutUs.coerceIn(newIn + minSpan, dur)

        // Snap the moving side to the source frame grid (only when it moved).
        val frameUs = asset?.sourceFrameDurationUs ?: 0L
        if (frameUs > 0 && asset != null && !asset.isImage) {
            if (newIn != clip.sourceInUs) {
                newIn = snapWithin(newIn, 0L, clip.sourceOutUs - minSpan, frameUs)
            }
            if (newOut != clip.sourceOutUs) {
                newOut = snapWithin(newOut, clip.sourceInUs + minSpan, dur, frameUs)
            }
        }

        val updated = clip.copy(sourceInUs = newIn, sourceOutUs = newOut)
        return clips.toMutableList().also { it[index] = updated }
    }

    private fun snapWithin(value: Long, low: Long, high: Long, frameUs: Long): Long {
        if (high <= low) return value.coerceIn(low, high)
        val snapped = ((value + frameUs / 2) / frameUs) * frameUs
        return snapped.coerceIn(low, high)
    }

    // ------------------------------------------------------------------
    // Split
    // ------------------------------------------------------------------

    data class SplitResult(val clips: List<Clip>, val leftId: String, val rightId: String)

    /**
     * Split the clip under [timelineUs] at that timeline position.
     *
     * The boundary is converted to source coordinates and snapped to the source
     * frame grid ONCE; both halves share the same boundary value, so
     * leftSpan + rightSpan == originalSpan holds exactly.
     *
     * @return null when there is no clip under the playhead or it is too short to split.
     */
    fun splitAtTimeline(
        clips: List<Clip>,
        assetLookup: (String) -> MediaAsset?,
        timelineUs: Long,
        newId: String,
    ): SplitResult? {
        val idx = clipIndexAt(clips, timelineUs)
        if (idx == -1) return null
        val clip = clips[idx]
        val asset = assetLookup(clip.assetId) ?: return null

        val st = starts(clips)
        val localUs = timelineUs - st[idx]

        // Timeline-local -> source coordinate (double precision), snapped once.
        val rawSourceBoundary = clip.sourceInUs + localUs * clip.speed
        val frameUs = if (asset.isImage) 0L else asset.sourceFrameDurationUs
        val boundary = if (frameUs > 0) {
            snapWithin(rawSourceBoundary.toLong(), clip.sourceInUs, clip.sourceOutUs, frameUs)
        } else {
            rawSourceBoundary.toLong()
        }

        if (boundary - clip.sourceInUs < MIN_SOURCE_SPAN_US) return null
        if (clip.sourceOutUs - boundary < MIN_SOURCE_SPAN_US) return null

        val left = clip.copy(sourceOutUs = boundary)
        val right = clip.copy(id = newId, sourceInUs = boundary)
        val out = clips.toMutableList()
        out[idx] = left
        out.add(idx + 1, right)
        return SplitResult(out, left.id, right.id)
    }

    // ------------------------------------------------------------------
    // Frame stepping (source-grid accurate inside the active clip)
    // ------------------------------------------------------------------

    /**
     * Step one frame forward/backward on the timeline. Inside a clip this steps
     * on the CLIP'S OWN source frame grid (e.g. 24FPS AI video inside a 30FPS
     * project steps by 1/24s), crossing to the neighboring clip at boundaries.
     * On an empty video track (text/audio-only project) steps on the project
     * frame grid.
     */
    fun stepFrame(
        clips: List<Clip>,
        assetLookup: (String) -> MediaAsset?,
        timelineUs: Long,
        forward: Boolean,
        totalDurationUs: Long = 0L,
        frameDurationUs: Long = 33_333L,
    ): Long {
        if (clips.isEmpty()) {
            // Text/audio-only timeline: step on the project grid.
            val total = totalDurationUs
            if (total <= 0L) return 0L
            val frame = frameDurationUs.coerceAtLeast(1L)
            return (if (forward) timelineUs + frame else timelineUs - frame)
                .coerceIn(0L, total)
        }
        val st = starts(clips)
        val total = st[clips.size]
        if (total <= 0) return 0L

        if (timelineUs <= 0 && !forward) return 0L
        if (timelineUs >= total && forward) return total

        val idx = clipIndexAt(clips, timelineUs)
        if (idx == -1) return if (forward) total else 0L

        val clip = clips[idx]
        val localUs = timelineUs - st[idx]
        val asset = assetLookup(clip.assetId)

        // Current position inside the source, on the source frame grid.
        // Double math: Float (24-bit mantissa) loses whole frames once
        // timeline positions reach minutes-scale microsecond values.
        val rawSource = clip.sourceInUs + localUs * clip.speed.toDouble()
        val frameUs = asset?.takeIf { !it.isImage }?.sourceFrameDurationUs ?: 0L
        val currentSource = if (frameUs > 0) {
            (rawSource.toLong() / frameUs) * frameUs
        } else rawSource.toLong()

        val nextSource = if (forward) currentSource + max(frameUs, 1L) else currentSource - max(frameUs, 1L)

        return if (nextSource > clip.sourceInUs && nextSource < clip.sourceOutUs) {
            // Still inside this clip -> convert back to timeline.
            st[idx] + ((nextSource - clip.sourceInUs).toDouble() / clip.speed).toLong()
        } else {
            // Cross the boundary.
            if (forward) {
                if (idx + 1 < clips.size) st[idx + 1] else total
            } else {
                if (idx - 1 >= 0) st[idx] else 0L
            }
        }
    }

    // ------------------------------------------------------------------
    // Text overlays
    // ------------------------------------------------------------------

    /** Clamp texts into the timeline after it got shorter; fully-outside texts are dropped. */
    fun clampTexts(texts: List<TextOverlayItem>, totalDurationUs: Long): List<TextOverlayItem> {
        if (texts.isEmpty() || totalDurationUs <= 0) return emptyList()
        return texts.mapNotNull { t ->
            when {
                t.startUs >= totalDurationUs -> null
                t.endUs > totalDurationUs -> t.copy(endUs = max(t.startUs + 1L, totalDurationUs))
                else -> t
            }
        }
    }

    /** Insert an item keeping the list sorted by start time. */
    fun insertText(texts: List<TextOverlayItem>, item: TextOverlayItem): List<TextOverlayItem> =
        (texts + item).sortedBy { it.startUs }

    fun moveText(texts: List<TextOverlayItem>, id: String, newStartUs: Long): List<TextOverlayItem> =
        texts.map { if (it.id == id) it.movedTo(newStartUs.coerceAtLeast(0L)) else it }

    // ------------------------------------------------------------------
    // Audio track (independent layer)
    // ------------------------------------------------------------------

    /** Insert an audio clip keeping the list sorted by start time. */
    fun insertAudio(audios: List<AudioClip>, item: AudioClip): List<AudioClip> =
        (audios + item).sortedBy { it.startUs }

    fun updateAudio(audios: List<AudioClip>, id: String, transform: (AudioClip) -> AudioClip): List<AudioClip> =
        audios.map { if (it.id == id) transform(it) else it }

    fun moveAudio(audios: List<AudioClip>, id: String, newStartUs: Long): List<AudioClip> =
        audios.map { if (it.id == id) it.movedTo(newStartUs.coerceAtLeast(0L)) else it }

    /**
     * Trim one edge of an audio clip in TIMELINE coordinates, keeping the
     * opposite edge fixed on the timeline (classic NLE semantics):
     *  - left edge: [timelineTargetUs] becomes the new start; the clip's end
     *    stays put; the source in-point follows (extending left consumes source
     *    before the current in-point).
     *  - right edge: [timelineTargetUs] becomes the new end; the start stays.
     */
    fun trimAudioEdge(
        audios: List<AudioClip>,
        id: String,
        asset: MediaAsset?,
        isLeft: Boolean,
        timelineTargetUs: Long,
    ): List<AudioClip> {
        val sourceDur = asset?.durationUs ?: Long.MAX_VALUE
        return audios.map { audio ->
            if (audio.id != id) audio
            else {
                val span = audio.sourceSpanUs
                val minSpan = if (asset != null) min(AUDIO_MIN_DURATION_US, max(sourceDur / 2, 1L)) else AUDIO_MIN_DURATION_US
                val endUs = audio.startUs + span
                if (isLeft) {
                    // Extending left is limited by the source before the in-point.
                    val minStart = (endUs - audio.sourceInUs - span).coerceAtLeast(0L)
                    val maxStart = (endUs - minSpan).coerceAtLeast(0L)
                    val newStart = timelineTargetUs.coerceIn(minStart, maxStart)
                    val newSpan = endUs - newStart
                    audio.copy(
                        startUs = newStart,
                        sourceInUs = audio.sourceOutUs - newSpan,
                        sourceOutUs = audio.sourceOutUs,
                    )
                } else {
                    // Extending right is limited by the source after the out-point.
                    val maxEnd = audio.startUs + (sourceDur - audio.sourceOutUs) + span
                    val minEnd = audio.startUs + minSpan
                    val newEnd = timelineTargetUs.coerceIn(minEnd, maxEnd)
                    val newSpan = newEnd - audio.startUs
                    audio.copy(
                        sourceInUs = audio.sourceInUs,
                        sourceOutUs = audio.sourceInUs + newSpan,
                    )
                }
            }
        }
    }

    fun deleteAudio(audios: List<AudioClip>, id: String): List<AudioClip> =
        audios.filter { it.id != id }

    // ------------------------------------------------------------------
    // Layer lane packing (shared by the timeline UI and composition build)
    // ------------------------------------------------------------------

    /**
     * Pack overlapping independent-layer items into horizontal lanes.
     * Items are sorted by start; each goes into the first lane whose last
     * item ends before it starts. Return value: lane index per item.
     */
    fun laneIndexes(startsAndEnds: List<Pair<Long, Long>>): IntArray {
        val order = startsAndEnds.indices.sortedBy { startsAndEnds[it].first }
        val laneEnds = mutableListOf<Long>()
        val result = IntArray(startsAndEnds.size)
        for (i in order) {
            val (start, end) = startsAndEnds[i]
            var lane = laneEnds.indexOfFirst { it <= start }
            if (lane == -1) {
                laneEnds += end
                lane = laneEnds.size - 1
            } else {
                laneEnds[lane] = end
            }
            result[i] = lane
        }
        return result
    }

    /**
     * Pack audio clips into non-overlapping lanes for sequence building:
     * each lane becomes one concatenated EditedMediaItemSequence.
     */
    fun audioLanes(audios: List<AudioClip>): List<List<AudioClip>> {
        if (audios.isEmpty()) return emptyList()
        val sorted = audios.sortedBy { it.startUs }
        val lanes = mutableListOf<MutableList<AudioClip>>()
        val laneEnds = mutableListOf<Long>()
        for (clip in sorted) {
            var lane = -1
            for (i in laneEnds.indices) {
                if (laneEnds[i] <= clip.startUs) { lane = i; break }
            }
            if (lane == -1) {
                lanes += mutableListOf(clip)
                laneEnds += clip.endUs
            } else {
                lanes[lane] += clip
                laneEnds[lane] = clip.endUs
            }
        }
        return lanes
    }
}
