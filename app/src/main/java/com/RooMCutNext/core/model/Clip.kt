package com.RooMCutNext.core.model

import kotlinx.serialization.Serializable

/**
 * A single clip on the (single-layer) timeline.
 *
 * The timeline is an ordered list of [Clip]s whose positions are the prefix sum
 * of their durations (ripple model: no gaps, no overlaps by construction).
 *
 * Source-side coordinates: [sourceInUs]/[sourceOutUs] address the source media
 * file directly; [durationUs] is the timeline duration after speed is applied.
 */
@Serializable
data class Clip(
    val id: String,
    val assetId: String,
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
) {
    /** Span of source media used by this clip, microseconds. */
    val sourceSpanUs: Long get() = sourceOutUs - sourceInUs

    /** Duration of this clip on the timeline, microseconds. */
    val durationUs: Long get() = (sourceSpanUs / speed).toLong()

    val isTrimmed: Boolean get() = sourceInUs > 0 || sourceOutUs < Long.MAX_VALUE

    /** Normalized speed for display, e.g. "1.5x". */
    fun speedLabel(): String = if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"
}

/** Basic color adjustment applied to the whole clip. All values in -1..1 (0 = neutral). */
@Serializable
data class ColorAdjust(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
) {
    val isNeutral: Boolean get() = brightness == 0f && contrast == 0f && saturation == 0f
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
