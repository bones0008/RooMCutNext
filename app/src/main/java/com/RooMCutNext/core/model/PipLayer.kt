package com.RooMCutNext.core.model

import kotlinx.serialization.Serializable

/**
 * A picture-in-picture layer (v1.2): an independent video/image overlay on
 * top of the primary timeline, with its own time window, position, scale
 * and opacity.
 *
 * The layer model mirrors [TextOverlayItem]/[AudioClip]: anchored to the
 * timeline (not to any clip), may overlap the primary clips or sit on empty
 * timeline space, and is serialized as part of the project JSON. Old
 * projects without the `pips` field deserialize to an empty list.
 *
 * Stickers fall out of this for free: a transparent PNG imported via
 * 添加素材 and inserted as a PiP layer IS a sticker (PNG alpha is preserved
 * by the image pipeline).
 */
@Serializable
data class PipLayer(
    val id: String,
    val assetId: String,
    /** Timeline position where this layer starts, microseconds. */
    val startUs: Long = 0L,
    /** How long the layer stays on screen, microseconds. */
    val durationUs: Long = 3_000_000L,
    /** Center X of the layer, normalized 0..1 of the output canvas. */
    val xRatio: Float = 0.5f,
    /** Center Y of the layer, normalized 0..1 of the output canvas. */
    val yRatio: Float = 0.5f,
    /** Display width relative to the output canvas width, 0.1..1. */
    val scale: Float = 0.4f,
    /** Layer opacity 0..1 (1 = fully opaque). */
    val opacity: Float = 1f,
) {
    val endUs: Long get() = startUs + durationUs
}
