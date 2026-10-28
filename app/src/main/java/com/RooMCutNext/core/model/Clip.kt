package com.RooMCutNext.core.model

import kotlinx.serialization.Serializable

/**
 * A single clip on the (single-layer) timeline.
 *
 * Timeline v3 — GAPS: each clip carries a [gapBeforeUs] hole in front of it,
 * so positions are the prefix sum of (gapBeforeUs + durationUs). Old project
 * JSON (schema v2, no gap field) deserializes with gapBeforeUs = 0 everywhere
 * = the old compact layout.
 *
 * v1.6.1 — RIPPLE DELETE + 黑场： deleting a clip no longer leaves its span
 * behind as a hole — the tail ripples closed, and black pauses are explicit
 * black-frame clips (see MediaAsset.BLACK_FRAME_ASSET_ID /
 * TimelineOps.insertBlackGap): an ordinary image clip the user stretches
 * (0.1s..60s), transitions into (dissolve into 黑场 = fade-to-black) and
 * deletes like any other media.
 *
 * v1.6.18 — GAPS ARE DEAD: dragging is a PURE REORDER (the drop always
 * lands tight — see TimelineView.computePlacement), so no gesture ever
 * punches a hole anymore. Legacy holes (from the old free-positioning
 * drags) are migrated on project load into equal-length 黑场 clips
 * (TimelineOps.migrateGapsToBlackClips — playback identical, but visible
 * / editable / deletable). Black pauses ONLY come from the explicit
 * 「加黑场」 action from now on; the gapBeforeUs field survives purely as
 * schema compatibility for old project JSON.
 *
 * Timeline v4 — TRANSITIONS (v1.2.1): a clip may carry a [transitionInUs]
 * crossfade with the PREVIOUS clip: the clip starts that much EARLIER,
 * overlapping the previous clip's tail, and fades in over the overlap. The
 * previous clip stays fully opaque underneath (bottom sequence), so the
 * composite is a true crossfade with no dip-to-black. Transitions are only
 * effective between ADJACENT clips (gapBeforeUs below
 * [TRANSITION_MIN_GAP_US]); a black hole between two clips disables the
 * transition. Old JSON deserializes with transitionInUs = 0 = hard cuts.
 *
 * v1.5.0 — [transitionType] selects WHICH transition renders over the
 * overlap (see [TransitionStyle]; "dissolve" = the classic crossfade every
 * version before v1.5.0 shipped). The type is meaningless while
 * transitionInUs is 0 (no overlap = hard cut); it is kept in the model so
 * toggling the duration off and back on preserves the chosen style.
 * Old JSON deserializes with transitionType = "dissolve".
 *
 * Source-side coordinates: [sourceInUs]/[sourceOutUs] address the source media
 * file directly; [durationUs] is the timeline duration after speed is applied.
 */
@Serializable
data class Clip(
    val id: String,
    val assetId: String,
    /** Black hole in front of this clip on the timeline, microseconds. */
    val gapBeforeUs: Long = 0L,
    /** Trim-in point in the source media, microseconds. */
    val sourceInUs: Long,
    /** Trim-out point in the source media, microseconds (exclusive). */
    val sourceOutUs: Long,
    /** Playback speed multiplier, 0.25..4. */
    val speed: Float = 1f,
    /** Extra rotation on top of the source's own rotation: 0 / 90 / 180 / 270. */
    val rotationDeg: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    /** Linear audio volume multiplier, 0..2 (1 = original). */
    val volume: Float = 1f,
    val muted: Boolean = false,
    /** Audio fade-in duration, milliseconds (0..2000). */
    val fadeInMs: Int = 0,
    /** Audio fade-out duration, milliseconds (0..2000). */
    val fadeOutMs: Int = 0,
    val adjust: ColorAdjust = ColorAdjust(),
    val crop: CropSpec? = null,
    /**
     * Crossfade duration with the PREVIOUS clip, microseconds (0 = hard cut).
     * Only effective when the clip is adjacent to its predecessor (no gap);
     * see the class doc. The raw stored value is clamped into the legal
     * domain by [com.RooMCutNext.core.ops.TimelineOps.effectiveTransitions]
     * before any geometry is derived from it.
     */
    val transitionInUs: Long = 0L,
    /** v1.5.0: which transition renders over the overlap — a
     * [TransitionStyle] id. Only meaningful while [transitionInUs] > 0;
     * unknown/missing ids degrade to "dissolve" ([TransitionStyle.fromId]). */
    val transitionType: String = TransitionStyle.DISSOLVE.id,
    /** Per-clip mosaic regions (v1.2.2), normalized to this clip's frame. */
    val mosaics: List<MosaicRegion> = emptyList(),
    /**
     * v1.6.21: 希区柯克变焦（dolly zoom）— a time-animated magnification of
     * the clip's frame over [DollyZoomSpec.durationUs] from the clip's
     * TIMELINE start (the transition head included). null = no effect (also
     * what every pre-v1.6.21 project JSON decodes to — the field is absent
     * there). See [DollyZoomSpec] for the semantics.
     */
    val dollyZoom: DollyZoomSpec? = null,
) {
    /** Span of source media used by this clip, microseconds. */
    val sourceSpanUs: Long get() = sourceOutUs - sourceInUs

    /** Duration of this clip on the timeline, microseconds. */
    val durationUs: Long get() = (sourceSpanUs / speed).toLong()

    val isTrimmed: Boolean get() = sourceInUs > 0 || sourceOutUs < Long.MAX_VALUE

    /** Normalized speed for display, e.g. "1.5x". */
    fun speedLabel(): String = if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"
}

/**
 * Color adjustment applied to the whole clip. Slider values in -1..1 (0 = neutral).
 *
 * Besides the classic brightness/contrast/saturation, v1.1 adds:
 *  - [temperature]/[tint]: white-balance style adjustments;
 *  - [rGain]/[gGain]/[bGain]: direct per-channel gains;
 *  - [lutId]/[lutStrength]: a LUT look-up filter (see LutRegistry).
 *
 * All gain-ish values compose multiplicatively into ONE matrix effect in
 * [com.RooMCutNext.export.CompositionBuilder] — see [channelGains].
 */
@Serializable
data class ColorAdjust(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    /** White balance: -1 cool (blue) .. +1 warm (orange). */
    val temperature: Float = 0f,
    /** Tint: -1 green .. +1 magenta. */
    val tint: Float = 0f,
    /** Direct red channel gain, -1..1 (rendered as 1+v, clamped to 0..2). */
    val rGain: Float = 0f,
    /** Direct green channel gain, -1..1. */
    val gGain: Float = 0f,
    /** Direct blue channel gain, -1..1. */
    val bGain: Float = 0f,
    /** LUT filter id (LutRegistry), null = no filter. */
    val lutId: String? = null,
    /** LUT opacity/strength, 0..1 (1 = full effect). */
    val lutStrength: Float = 1f,
) {
    val isNeutral: Boolean get() =
        brightness == 0f && contrast == 0f && saturation == 0f &&
            temperature == 0f && tint == 0f &&
            rGain == 0f && gGain == 0f && bGain == 0f && lutId == null

    /** True when the per-channel matrix part has no effect (LUT may still apply). */
    val isGainNeutral: Boolean get() =
        temperature == 0f && tint == 0f && rGain == 0f && gGain == 0f && bGain == 0f

    /**
     * Combined per-channel scale factors (R, G, B) for a single RgbAdjustment.
     *
     * temperature shifts R vs B (warm/cool); tint shifts G vs R+B
     * (green/magenta); r/g/bGain are direct multipliers. Everything composes
     * multiplicatively and each factor is clamped to 0..2.5.
     */
    fun channelGains(): FloatArray {
        val tempR = 1f + 0.4f * temperature
        val tempB = 1f - 0.4f * temperature
        val tintG = 1f - 0.35f * tint
        val tintRB = 1f + 0.175f * tint
        fun gain(direct: Float, vararg factors: Float): Float {
            var v = (1f + direct).coerceIn(0f, 2f)
            factors.forEach { f -> v *= f }
            return v.coerceIn(0f, 2.5f)
        }
        return floatArrayOf(
            gain(rGain, tempR, tintRB),
            gain(gGain, tintG),
            gain(bGain, tempB, tintRB),
        )
    }
}

/**
 * A rectangular pixelation region on a clip's frame (v1.2.2).
 *
 * Coordinates are normalized to the CLIP's own frame (after its rotation /
 * crop effects, before compositing onto the project canvas), using the
 * intuitive convention: x right, y DOWN, origin at the top-left corner of
 * the visible frame, all values in 0..1. [x]/[y] is the region CENTER and
 * [w]/[h] its size as fractions of the frame's width/height.
 *
 * [blockRatio] is the mosaic block size as a fraction of the frame WIDTH
 * (e.g. 0.03 = blocks of ~3% frame width ≈ 58px on a 1920-wide frame);
 * larger = coarser pixelation. Blocks are square in PIXEL space.
 */
@Serializable
data class MosaicRegion(
    val id: String,
    /** Region center X, 0..1 of frame width (left = 0). */
    val x: Float = 0.5f,
    /** Region center Y, 0..1 of frame height (top = 0). */
    val y: Float = 0.5f,
    /** Region width, fraction of frame width. */
    val w: Float = 0.4f,
    /** Region height, fraction of frame height. */
    val h: Float = 0.4f,
    /** Mosaic block size, fraction of frame width (see class doc). */
    val blockRatio: Float = 0.03f,
)

/**
 * v1.6.21 — 希区柯克变焦（Hitchcock / dolly zoom）spec.
 *
 * 语义：在 [durationUs] 内对素材画面做时间驱动的放大——采样窗口（居中于
 * [centerX]/[centerY]，边长从全幅线性走向 1/[maxScale]）扫过素材，输出=窗
 * 内容拉满整帧。数学的唯一权威是 [com.RooMCutNext.core.util.DollyZoomMath]
 * （JVM 可测），GL 侧只做逐帧转录（WYSIWYG 铁律）。
 *
 * 真正的希区柯克观感来自素材本身：拍摄时"边变焦边移动"（或边走边推拉变焦
 * 滑杆），编辑器反向裁剪抵消主体尺寸变化后，剩下的就是背景视野的剧烈胀缩。
 * 对普通素材（拍摄时没变焦）本效果退化为匀速推近/拉远（Ken Burns 式）。
 * 放大是裁剪实现 → 建议用 4K 素材（1080p 素材放大 2x 会明显变软）。
 *
 * [zoomIn] = true：窗口从全幅收到 1/maxScale（画面推近、背景向中心收拢，
 * 《迷魂记》正方向）；false：反向展开（从放大态还原到全景）。
 */
@Serializable
data class DollyZoomSpec(
    /** 效果窗口时长，从素材时间线起点起算，0.3s..8s。 */
    val durationUs: Long = 2_000_000L,
    /** true = 推近（窗口收缩/画面放大），false = 拉远还原（窗口展开）。 */
    val zoomIn: Boolean = true,
    /** 终点放大倍数 1.2..4（采样窗终点边长 = 1/maxScale）。 */
    val maxScale: Float = 2.0f,
    /** 采样窗中心 X，0..1（素材帧域，0=左）。窗口越界部分被画幅钳制。 */
    val centerX: Float = 0.5f,
    /** 采样窗中心 Y，0..1（0=上）。 */
    val centerY: Float = 0.5f,
)

/** v1.6.21: dolly-zoom 效果窗口时长下限（0.3s — 更短读不出胀缩过程）。 */
const val DOLLY_MIN_DURATION_US: Long = 300_000L

/** v1.6.21: dolly-zoom 效果窗口时长上限（8s）。 */
const val DOLLY_MAX_DURATION_US: Long = 8_000_000L

/** v1.6.21: 终点放大倍数下限。 */
const val DOLLY_MIN_SCALE: Float = 1.2f

/** v1.6.21: 终点放大倍数上限（再大会明显糊 + 眩晕过头）。 */
const val DOLLY_MAX_SCALE: Float = 4.0f

/** Transitions are disabled when the hole in front of a clip is at least this long. */
const val TRANSITION_MIN_GAP_US: Long = 20_000L

/** Longest crossfade offered (also the slider upper bound). */
const val TRANSITION_MAX_US: Long = 2_000_000L

/** A clip must keep at least this much body (non-fading) timeline duration. */
const val TRANSITION_MIN_BODY_US: Long = 100_000L

/**
 * Effective (legally clamped) crossfade-in duration of every clip,
 * microseconds — the single authority all geometry derives from.
 *
 * Rules applied to the RAW [Clip.transitionInUs] values:
 *  - clip 0 and clips behind a black hole (gapBeforeUs ≥ [TRANSITION_MIN_GAP_US]): 0;
 *  - a clip's crossfade may not eat into the PREVIOUS clip's fading head:
 *    T[i] ≤ prevDuration − T[i−1] (the previous clip's body);
 *  - a clip must keep a body of at least [TRANSITION_MIN_BODY_US];
 *  - capped at [TRANSITION_MAX_US].
 *
 * The clamped values are computed with a forward pass, so T[i−1] inside the
 * second rule is itself already clamped. Model-layer (not ops) because both
 * [Project.clipsDurationUs] and TimelineOps.starts() must agree on it.
 */
fun effectiveTransitions(clips: List<Clip>): LongArray {
    val out = LongArray(clips.size)
    var prevEffectiveUs = 0L
    for (i in clips.indices) {
        val c = clips[i]
        var t = 0L
        if (i > 0 && c.gapBeforeUs < TRANSITION_MIN_GAP_US && c.transitionInUs > 0L) {
            val prevBodyUs = clips[i - 1].durationUs - prevEffectiveUs
            t = c.transitionInUs.coerceIn(
                0L,
                minOf(prevBodyUs, c.durationUs - TRANSITION_MIN_BODY_US, TRANSITION_MAX_US),
            )
        }
        out[i] = t
        prevEffectiveUs = t
    }
    return out
}

/**
 * Picture crop (normalized insets from the edges of the frame).
 * Values are fractions of width/height in 0..1; after crop, [right]-[left] and
 * [top]-[bottom] must stay positive.
 */
@Serializable
data class CropSpec(
    val left: Float = 0f,
    val right: Float = 0f,
    val top: Float = 0f,
    val bottom: Float = 0f,
) {
    init {
        require(right > left) { "right must be greater than left" }
        require(top > bottom) { "top must be greater than bottom" }
    }

    val widthFraction: Float get() = right - left
    val heightFraction: Float get() = top - bottom
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (bottom + top) / 2f

    companion object {
        /** Built-in aspect presets: (widthFraction is derived from source aspect at runtime). */
        val NONE = CropSpec(0f, 1f, 1f, 0f)

        /**
         * Compute the crop insets that frame the given [aspect] (w/h) from the center
         * of a source with [sourceAspect] (w/h), without distortion.
         */
        fun forAspect(aspect: Float, sourceAspect: Float): CropSpec {
            if (sourceAspect <= 0f || aspect <= 0f) return NONE
            return if (aspect >= sourceAspect) {
                // Target is wider than the source: crop top & bottom.
                val keep = sourceAspect / aspect
                val inset = (1f - keep) / 2f
                CropSpec(0f, 1f, 1f - inset, inset)
            } else {
                // Target is taller than the source: crop left & right.
                val keep = aspect / sourceAspect
                val inset = (1f - keep) / 2f
                CropSpec(inset, 1f - inset, 1f, 0f)
            }
        }
    }
}
