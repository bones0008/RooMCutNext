package com.RooMCutNext.core.model

import kotlinx.serialization.Serializable

/**
 * An audio clip on its own track, freely positioned on the timeline.
 *
 * Unlike video [Clip]s (ripple, prefix-sum positions), audio clips are
 * independent layer items anchored at [startUs] — they can overlap each
 * other and extend beyond the video, exactly like [TextOverlayItem]s.
 */
@Serializable
data class AudioClip(
    val id: String,
    val assetId: String,
    /** Timeline position where this clip starts, microseconds. */
    val startUs: Long = 0L,
    /** Trim-in point in the source media, microseconds. */
    val sourceInUs: Long = 0L,
    /** Trim-out point in the source media, microseconds (exclusive). */
    val sourceOutUs: Long = 0L,
    /** Linear audio volume multiplier, 0..2 (1 = original). */
    val volume: Float = 1f,
    val muted: Boolean = false,
    /** Audio fade-in duration, milliseconds (0..2000). */
    val fadeInMs: Int = 0,
    /** Audio fade-out duration, milliseconds (0..2000). */
    val fadeOutMs: Int = 0,
) {
    /** Span of source media used, microseconds. */
    val sourceSpanUs: Long get() = sourceOutUs - sourceInUs

    /** Duration of this clip on the timeline, microseconds. */
    val durationUs: Long get() = sourceSpanUs

    val endUs: Long get() = startUs + durationUs

    fun movedTo(newStartUs: Long): AudioClip =
        copy(startUs = newStartUs.coerceAtLeast(0L))
}

/** Minimum timeline duration of an audio clip (100ms). */
const val AUDIO_MIN_DURATION_US: Long = 100_000L
