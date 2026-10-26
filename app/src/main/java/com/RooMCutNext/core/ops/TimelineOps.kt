package com.RooMCutNext.core.ops

import com.RooMCutNext.core.model.AUDIO_MIN_DURATION_US
import com.RooMCutNext.core.model.BLACK_FRAME_ASSET_ID
import com.RooMCutNext.core.model.PipLayer
import com.RooMCutNext.core.model.AudioClip
import com.RooMCutNext.core.model.Clip
import com.RooMCutNext.core.model.ColorAdjust
import com.RooMCutNext.core.model.IMAGE_MAX_DURATION_US
import com.RooMCutNext.core.model.MediaAsset
import com.RooMCutNext.core.model.MosaicRegion
import com.RooMCutNext.core.model.TextOverlayItem
import com.RooMCutNext.core.model.TRANSITION_MAX_US
import com.RooMCutNext.core.model.TRANSITION_MIN_BODY_US
import com.RooMCutNext.core.model.effectiveTransitions
import kotlin.math.max
import kotlin.math.min

/**
 * Pure, immutable timeline operations for the single-layer editor.
 *
 * Timeline v3 — GAPS: each clip carries a `gapBeforeUs` hole, so positions
 * are prefix sums of (gapBeforeUs + durationUs).
 *
 * v1.6.1 — RIPPLE DELETE + 黑场： deleting a clip now CLOSES the hole (the
 * tail clips ripple left; only the clip's PRE-EXISTING leading gap, e.g.
 * one the user dragged in deliberately, survives). Black pauses are added
 * explicitly as black-frame clips ([insertBlackGap]) — an ordinary image
 * clip the user can stretch (0.1s..60s), split, transition into/out of
 * (dissolve into 黑场 = fade-to-black) and delete — instead of appearing
 * implicitly as leftover holes. This matches CapCut / Premiere semantics:
 * the timeline is compact by default, silence is a deliberate asset.
 * Legacy projects that still carry holes keep rendering them as before.
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

    /** Shortest PiP layer on the timeline (0.1s). */
    const val PIP_MIN_DURATION_US: Long = 100_000L

    // ------------------------------------------------------------------
    // Position mapping (prefix sums, gap-aware)
    // ------------------------------------------------------------------

    /**
     * Cumulative start time of each clip on the timeline, microseconds.
     * clips.size + 1 entries; entry i includes clips[i].gapBeforeUs (the hole
     * in front of clip i belongs to the timeline span BEFORE the clip).
     *
     * v1.2.1 TRANSITIONS: a clip with an effective crossfade-in (see
     * [effectiveTransitions]) starts that much EARLIER — its start is pulled
     * back onto the previous clip's tail, so the two overlap and the total
     * shortens cumulatively. The running cursor uses TRUE positions, so each
     * clip's overlap only pulls itself back, never the clips after it.
     */
    fun starts(clips: List<Clip>): LongArray {
        val transitions = effectiveTransitions(clips)
        val out = LongArray(clips.size + 1)
        var acc = 0L
        for (i in clips.indices) {
            acc += clips[i].gapBeforeUs
            val start = (acc - transitions[i]).coerceAtLeast(0L)
            out[i] = start
            acc = start + clips[i].durationUs
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
    // Structural operations (v1.6.1: ripple delete + explicit black clips)
    // ------------------------------------------------------------------

    fun insert(clips: List<Clip>, clip: Clip, atIndex: Int): List<Clip> {
        val idx = atIndex.coerceIn(0, clips.size)
        return clips.subList(0, idx) + clip + clips.subList(idx, clips.size)
    }

    fun append(clips: List<Clip>, clip: Clip): List<Clip> = clips + clip

    /**
     * v1.6.1: RIPPLE delete — the removed clip's own time span does NOT
     * become a hole; the following clips close up. Only the clip's
     * PRE-EXISTING leading gap (one the user dragged in deliberately, or a
     * legacy-project hole) survives, transferred to the follower. Black
     pauses are the user's explicit choice now: [insertBlackGap].
     */
    fun delete(clips: List<Clip>, index: Int): List<Clip> {
        if (index < 0 || index >= clips.size) return clips
        return removeTransferringGapOnly(clips, index)
    }

    fun deleteById(clips: List<Clip>, clipId: String): List<Clip> {
        val index = clips.indexOfFirst { it.id == clipId }
        if (index < 0) return clips
        return removeTransferringGapOnly(clips, index)
    }

    /**
     * Remove clips[index] transferring ONLY its pre-existing hole (gapBeforeUs)
     * to the follower — the clip's own time span does NOT become a hole.
     * Used by [delete] (ripple) and [move]: relocating content must not punch
     * new holes into the timeline.
     */
    private fun removeTransferringGapOnly(clips: List<Clip>, index: Int): MutableList<Clip> {
        val out = clips.toMutableList()
        val removed = out.removeAt(index)
        if (index < out.size) {
            val next = out[index]
            out[index] = next.copy(gapBeforeUs = next.gapBeforeUs + removed.gapBeforeUs)
        }
        return out
    }

    /**
     * Move a clip (reorder) with holes staying in place: the removed clip's
     * existing hole stays at its position (transferred to the follower); at the
     * destination slot the moved clip TAKES OVER the slot's gap and the
     * pushed-back clip hugs it. Semantics: content moves, holes don't, and no
     * NEW holes appear (a compact list stays compact after a reorder).
     */
    fun move(clips: List<Clip>, fromIndex: Int, toIndex: Int): List<Clip> {
        if (fromIndex !in clips.indices) return clips
        if (toIndex !in 0 until clips.size) return clips
        if (fromIndex == toIndex) return clips
        val item = clips[fromIndex]
        val without = removeTransferringGapOnly(clips, fromIndex)
        val idx = toIndex.coerceIn(0, without.size)
        val slotGap = without.getOrNull(idx)?.gapBeforeUs ?: 0L
        val out = without.toMutableList()
        if (idx < out.size) {
            // The clip that used to start at this slot is pushed right; its
            // leading hole now belongs to the moved clip.
            out[idx] = out[idx].copy(gapBeforeUs = 0L)
            out.add(idx, item.copy(gapBeforeUs = slotGap))
        } else {
            out.add(item.copy(gapBeforeUs = 0L))
        }
        return out
    }

    /**
     * PLACE a clip at a target list position with an explicit leading gap
     * (v1.0.9 drag-to-position). Unlike [move] (pure reorder, never punches
     * holes), this is the free-positioning gesture: the dropped clip starts
     * at (previous clip end + [gapBeforeUs]); when it is inserted before an
     * existing clip that clip is pushed right and hugs the drop (its own
     * leading hole is absorbed by the moved clip's new gap).
     *
     * The removed clip's PRE-EXISTING hole stays where it was (transferred to
     * the follower), exactly like [move] — only the destination gap is new.
     */
    fun place(clips: List<Clip>, fromIndex: Int, insertAt: Int, gapBeforeUs: Long): List<Clip> {
        if (fromIndex !in clips.indices) return clips
        val item = clips[fromIndex]
        val without = removeTransferringGapOnly(clips, fromIndex)
        val idx = insertAt.coerceIn(0, without.size)
        val gap = gapBeforeUs.coerceAtLeast(0L)
        val out = without.toMutableList()
        if (idx < out.size) {
            out[idx] = out[idx].copy(gapBeforeUs = 0L)
            out.add(idx, item.copy(gapBeforeUs = gap))
        } else {
            out.add(item.copy(gapBeforeUs = gap))
        }
        return out
    }

    fun duplicate(clips: List<Clip>, index: Int, newId: String): List<Clip> {
        if (index !in clips.indices) return clips
        // The copy is inserted tightly after the original (no copied hole).
        val copy = clips[index].copy(id = newId, gapBeforeUs = 0L)
        return clips.subList(0, index + 1) + copy + clips.subList(index + 1, clips.size)
    }

    /**
     * v1.6.18: 「加黑场」菜单的 before 语义 → [insertBlackGap] 的 after 语义。
     *
     * 手动菜单回调传 before：true=在素材前添加 / false=在素材后添加 /
     * null=追加到时间线末尾（此时锚点无效，after 取值不参与定位）。
     * 两套命名互为取反——v1.6.1..v1.6.17 的 ViewModel 里写成
     * `after = before != false`，双重否定把前后彻底颠倒（「在素材前
     * 添加」黑场落到素材后面）。抽出纯映射并单测钉死，防止这个
     * 双重否定再次悄悄反转。
     */
    fun blackGapAfter(before: Boolean?): Boolean = before == false

    /**
     * v1.6.1: 插入黑场 —— 一段可自由拉伸的纯黑图片素材（0.1s..60s）。
     *
     * 锚定语义（与 move/place 的“洞归槽位”纪律一致）：
     *  - [anchorIndex] 无效（-1/越界/空时间线）→ 追加到末尾；
     *  - after=true  → 紧贴锚素材之后插入（黑场 gapBeforeUs=0，后续素材右移）；
     *  - after=false → 紧贴锚素材之前插入：黑场接管锚点原有的 gapBeforeUs
     *    （锚点前的洞留在槽位前端），锚素材 gapBeforeUs 清零右移 —— 锚素材
     *    的内容起点恰好右移一个黑场时长，相对位置语义最直观。
     *
     * [durationUs] 被钳入 [MIN_SOURCE_SPAN_US]..[IMAGE_MAX_DURATION_US]；
     * 60s 以上的停顿用多段黑场拼接即可。
     */
    fun insertBlackGap(
        clips: List<Clip>,
        anchorIndex: Int,
        after: Boolean,
        durationUs: Long,
        newId: String,
    ): List<Clip> {
        val dur = durationUs.coerceIn(MIN_SOURCE_SPAN_US, IMAGE_MAX_DURATION_US)
        val black = Clip(
            id = newId,
            assetId = BLACK_FRAME_ASSET_ID,
            sourceInUs = 0L,
            sourceOutUs = dur,
        )
        if (anchorIndex !in clips.indices) {
            return clips + black
        }
        return if (after) {
            insert(clips, black, anchorIndex + 1)
        } else {
            val anchor = clips[anchorIndex]
            val out = clips.toMutableList()
            out[anchorIndex] = anchor.copy(gapBeforeUs = 0L)
            out.add(anchorIndex, black.copy(gapBeforeUs = anchor.gapBeforeUs))
            out
        }
    }

    fun update(clips: List<Clip>, clipId: String, transform: (Clip) -> Clip): List<Clip> =
        clips.map { if (it.id == clipId) transform(it) else it }

    /**
     * v1.6.18: 一次性迁移 —— 把旧版"自由定位"拖拽留下的素材间隙
     * （gapBeforeUs > 0）原地转成等时长的黑场素材；间隙不足
     * [MIN_SOURCE_SPAN_US]（0.1s）的零头直接抹掉（视觉不可辨）。
     * 迁移后所有素材 gapBeforeUs == 0：时间线从此无空隙，黑底停顿
     * 只能由显式黑场（[insertBlackGap] / AI 的 add_black_clip）表达。
     *
     * 时间轴坐标保持不变：黑场占位时长 == 原间隙（超过
     * [IMAGE_MAX_DURATION_US] 的间隙按上限分段拼接，段和 == 间隙），
     * 后续素材起点与文字/音乐/画中画的绝对锚点零位移。
     *
     * 纯函数：干净列表（无间隙）原样返回同一实例，调用方可用
     * 引用相等短路。黑场素材 id 由 [newId] 生成。
     */
    fun migrateGapsToBlackClips(clips: List<Clip>, newId: () -> String): List<Clip> {
        if (clips.none { it.gapBeforeUs > 0L }) return clips
        val out = mutableListOf<Clip>()
        for (clip in clips) {
            var gap = clip.gapBeforeUs
            while (gap >= MIN_SOURCE_SPAN_US) {
                val chunk = gap.coerceAtMost(IMAGE_MAX_DURATION_US)
                out += Clip(
                    id = newId(),
                    assetId = BLACK_FRAME_ASSET_ID,
                    sourceInUs = 0L,
                    sourceOutUs = chunk,
                )
                gap -= chunk
            }
            // 不足 0.1s 的残余间隙直接并入（后续素材前移 <0.1s）。
            out += if (clip.gapBeforeUs > 0L) clip.copy(gapBeforeUs = 0L) else clip
        }
        return out
    }

    // ------------------------------------------------------------------
    // Crossfade transitions (v1.2.1)
    // ------------------------------------------------------------------

    /**
     * Set the RAW crossfade-in duration of clips[index] (microseconds).
     *
     * The stored value is clamped to what the UI can meaningfully offer:
     * 0 (first clip / explicit none) .. min(TRANSITION_MAX_US, the clip's own
     * duration minus a minimal body, the PREVIOUS clip's full duration).
     * Tighter chain constraints (a previous fading head, a gap in front) are
     * NOT baked into the stored value — they are applied on every read by
     * [effectiveTransitions], so trimming a neighbor later never corrupts
     * the saved project, it just shortens the effective fade.
     */
    fun setTransitionIn(clips: List<Clip>, index: Int, requestedUs: Long): List<Clip> {
        if (index !in clips.indices) return clips
        val clip = clips[index]
        val maxUs = if (index == 0) 0L else minOf(
            TRANSITION_MAX_US,
            clip.durationUs - TRANSITION_MIN_BODY_US,
            clips[index - 1].durationUs,
        )
        val t = requestedUs.coerceIn(0L, maxUs.coerceAtLeast(0L))
        val updated = clip.copy(transitionInUs = t)
        if (updated == clip) return clips
        return clips.toMutableList().also { it[index] = updated }
    }

    /**
     * v1.5.0: set the transition STYLE of clips[index] — a
     * [com.RooMCutNext.core.model.TransitionStyle] id.
     *
     * Deliberately orthogonal to [setTransitionIn]: picking a look never
     * touches the duration (and a style stored with duration 0 is kept, so
     * toggling "none" off and back on restores the look). Unknown ids are
     * stored as-is; every READ side normalizes via
     * [com.RooMCutNext.core.model.TransitionStyle.fromId] (dissolve).
     */
    fun setTransitionType(clips: List<Clip>, index: Int, styleId: String): List<Clip> {
        if (index !in clips.indices) return clips
        val clip = clips[index]
        if (clip.transitionType == styleId) return clips
        return clips.toMutableList().also { it[index] = clip.copy(transitionType = styleId) }
    }

    // ------------------------------------------------------------------
    // Mosaic regions (v1.2.2)
    // ------------------------------------------------------------------

    /** Most mosaic regions a single clip may carry (each is one GL pass). */
    const val MOSAIC_MAX_REGIONS: Int = 4

    /** Add a mosaic region to a clip; returns the new region (null when full / no clip). */
    fun addMosaicRegion(clips: List<Clip>, clipId: String, region: MosaicRegion): Pair<List<Clip>, MosaicRegion>? {
        val clip = clips.firstOrNull { it.id == clipId } ?: return null
        if (clip.mosaics.size >= MOSAIC_MAX_REGIONS) return null
        val updated = clip.copy(mosaics = clip.mosaics + region)
        return clips.map { if (it.id == clipId) updated else it } to region
    }

    /** Replace one region of a clip (same id), keeping list order. */
    fun updateMosaicRegion(clips: List<Clip>, clipId: String, region: MosaicRegion): List<Clip> =
        clips.map { clip ->
            if (clip.id != clipId) clip
            else clip.copy(mosaics = clip.mosaics.map { if (it.id == region.id) region else it })
        }

    /** Remove one region of a clip by id. */
    fun removeMosaicRegion(clips: List<Clip>, clipId: String, regionId: String): List<Clip> =
        clips.map { clip ->
            if (clip.id != clipId) clip
            else clip.copy(mosaics = clip.mosaics.filter { it.id != regionId })
        }

    /**
     * v1.1.2: copy one clip's full color grading (LUT + strength + basic
     * parameters) onto EVERY clip on the timeline — videos and images alike
     * (images are ordinary clips with an image asset). Trim/speed/etc stay
     * untouched; only the [Clip.adjust] payload is replaced.
     */
    fun applyAdjustToAll(clips: List<Clip>, adjust: ColorAdjust): List<Clip> =
        clips.map { it.copy(adjust = adjust) }

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
        val right = clip.copy(id = newId, sourceInUs = boundary, gapBeforeUs = 0L)
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
            // Cross the boundary. Going backwards from INSIDE the gap after
            // this clip, land on this clip's END (not its start).
            if (forward) {
                if (idx + 1 < clips.size) st[idx + 1] else total
            } else {
                val clipEnd = st[idx] + clips[idx].durationUs
                when {
                    timelineUs > clipEnd -> clipEnd
                    idx - 1 >= 0 -> st[idx]
                    else -> 0L
                }
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
    // PiP layers (independent overlay layer, v1.2)
    // ------------------------------------------------------------------

    /** Insert a PiP layer keeping the list sorted by start time. */
    fun insertPip(pips: List<PipLayer>, item: PipLayer): List<PipLayer> =
        (pips + item).sortedBy { it.startUs }

    fun updatePip(pips: List<PipLayer>, id: String, transform: (PipLayer) -> PipLayer): List<PipLayer> =
        pips.map { if (it.id == id) transform(it) else it }

    /** Move a layer's start; the duration follows (whole block slides). */
    fun movePip(pips: List<PipLayer>, id: String, newStartUs: Long): List<PipLayer> =
        pips.map { if (it.id == id) it.copy(startUs = newStartUs.coerceAtLeast(0L)) else it }

    /**
     * Trim one edge of a PiP layer in TIMELINE coordinates (opposite edge
     * fixed). Video layers are limited by the source duration on BOTH edges
     * (the layer plays the source from [PipLayer.sourceInUs]); image layers
     * have no source limit.
     *
     * v1.5.5: dragging the LEFT edge of a video layer now ADVANCES the source
     * window with the edge (sourceInUs += the dragged delta, clamped to the
     * source bounds) instead of restarting the source from 0 — the content
     * that was on screen at the new start keeps playing seamlessly, and a
     * left-then-right trim pair can finally select ANY sub-window of the
     * material (the old semantics could only ever cut the tail).
     */
    fun trimPipEdge(
        pips: List<PipLayer>,
        id: String,
        asset: MediaAsset?,
        isLeft: Boolean,
        timelineTargetUs: Long,
    ): List<PipLayer> {
        return pips.map { pip ->
            if (pip.id != id) pip
            else {
                val sourceLimit = if (asset != null && !asset.isImage) asset.durationUs else Long.MAX_VALUE
                val endUs = pip.endUs
                if (isLeft) {
                    val newStart = timelineTargetUs.coerceIn(0L, (endUs - PIP_MIN_DURATION_US).coerceAtLeast(0L))
                    val newSourceIn = if (sourceLimit == Long.MAX_VALUE) pip.sourceInUs
                    else (pip.sourceInUs + (newStart - pip.startUs))
                        .coerceIn(0L, (sourceLimit - PIP_MIN_DURATION_US).coerceAtLeast(0L))
                    pip.copy(
                        startUs = newStart,
                        sourceInUs = newSourceIn,
                        durationUs = (endUs - newStart).coerceAtLeast(PIP_MIN_DURATION_US),
                    )
                } else {
                    val minEnd = pip.startUs + PIP_MIN_DURATION_US
                    val maxEnd = if (sourceLimit == Long.MAX_VALUE) Long.MAX_VALUE
                    else pip.startUs + (sourceLimit - pip.sourceInUs).coerceAtLeast(PIP_MIN_DURATION_US)
                    val newEnd = timelineTargetUs.coerceIn(minEnd, maxEnd)
                    pip.copy(durationUs = newEnd - pip.startUs)
                }
            }
        }
    }

    fun deletePip(pips: List<PipLayer>, id: String): List<PipLayer> =
        pips.filter { it.id != id }

    /**
     * Split the audio clip under [timelineUs] at that timeline position
     * (v1.0.9: audio must be editable like video — the split button used to
     * be a video-only feature). Audio has no speed, so timeline and source
     * offsets are identical. Returns null when no clip covers the position
     * or either half would be shorter than [AUDIO_MIN_DURATION_US].
     */
    fun splitAudioAt(
        audios: List<AudioClip>,
        timelineUs: Long,
        newId: String,
    ): Pair<List<AudioClip>, String>? {
        val idx = audios.indexOfFirst { timelineUs >= it.startUs && timelineUs < it.endUs }
        if (idx < 0) return null
        val audio = audios[idx]
        val boundary = audio.sourceInUs + (timelineUs - audio.startUs)
        if (boundary - audio.sourceInUs < AUDIO_MIN_DURATION_US) return null
        if (audio.sourceOutUs - boundary < AUDIO_MIN_DURATION_US) return null
        val left = audio.copy(sourceOutUs = boundary)
        val right = audio.copy(id = newId, startUs = timelineUs, sourceInUs = boundary)
        val out = audios.toMutableList()
        out[idx] = left
        out.add(idx + 1, right)
        return out to right.id
    }

    /**
     * v1.5.5: split the PiP layer under [timelineUs] at that timeline
     * position — the missing half of "画中画无法剪辑，只能拖拽和缩短". The
     * right half plays the source from the cut point
     * ([PipLayer.sourceInUs] + the left half's span), so the two halves show
     * SEQUENTIAL content, exactly like splitting a video clip; without the
     * offset the right half would restart the source from its head. Geometry
     * (x/y/scale/opacity) is inherited by both halves — a split is a cut, not
     * a restyle. Image layers split too (the still simply continues).
     *
     * Returns null when no layer covers the position or either half would be
     * shorter than [PIP_MIN_DURATION_US] — the same contract as
     * [splitAudioAt].
     */
    fun splitPipAt(
        pips: List<PipLayer>,
        timelineUs: Long,
        newId: String,
    ): Pair<List<PipLayer>, String>? {
        val idx = pips.indexOfFirst { timelineUs >= it.startUs && timelineUs < it.endUs }
        if (idx < 0) return null
        val pip = pips[idx]
        val leftSpanUs = timelineUs - pip.startUs
        if (leftSpanUs < PIP_MIN_DURATION_US) return null
        if (pip.endUs - timelineUs < PIP_MIN_DURATION_US) return null
        val left = pip.copy(durationUs = leftSpanUs)
        val right = pip.copy(
            id = newId,
            startUs = timelineUs,
            sourceInUs = pip.sourceInUs + leftSpanUs,
            durationUs = pip.endUs - timelineUs,
        )
        val out = pips.toMutableList()
        out[idx] = left
        out.add(idx + 1, right)
        return out to right.id
    }

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
