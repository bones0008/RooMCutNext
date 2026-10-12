package com.RooMCutNext.core.model

import kotlinx.serialization.Serializable

/** Visual style of a text overlay. Sizes are fractions of the video height. */
@Serializable
data class TextStyleSpec(
    /** Text height as a fraction of the video height (0.02..0.3). */
    val fontSizeRatio: Float = 0.08f,
    /** ARGB color of the text. */
    val color: Long = 0xFFFFFFFF,
    /** ARGB color of the outline. Ignored when [strokeWidthRatio] == 0. */
    val strokeColor: Long = 0xFF000000,
    /** Outline width as a fraction of the font size (0 = no outline). */
    val strokeWidthRatio: Float = 0.08f,
    /** Shadow blur radius as a fraction of the font size (0 = no shadow). */
    val shadowRadiusRatio: Float = 0f,
    val bold: Boolean = true,
    /**
     * Font id (FontRegistry). "system" = platform default (pre-v1.1 behavior);
     * "hei"/"song"/"kai"/"duyi"/"kuaile" = bundled; "user:<fileName>" = imported.
     */
    val fontId: String = FONT_SYSTEM,
) {
    fun withColor(color: Long) = copy(color = color)

    companion object {
        const val FONT_SYSTEM = "system"
    }
}

/**
 * A text overlay anchored to the project timeline (not to a clip).
 *
 * The text is rendered on top of the video between [startUs] and [endUs]
 * (timeline coordinates), centered at ([xRatio], [yRatio]).
 */
@Serializable
data class TextOverlayItem(
    val id: String,
    val text: String,
    /** Center X in the frame, 0..1 (left to right). */
    val xRatio: Float = 0.5f,
    /** Center Y in the frame, 0..1 (top to bottom). */
    val yRatio: Float = 0.5f,
    /** Rotation of the text block in degrees. */
    val rotationDeg: Float = 0f,
    /** Start time on the timeline, microseconds. */
    val startUs: Long = 0L,
    /** End time on the timeline, microseconds (exclusive). */
    val endUs: Long = 3_000_000L,
    val style: TextStyleSpec = TextStyleSpec(),
) {
    init {
        require(endUs > startUs) { "text endUs must be greater than startUs" }
    }

    val durationUs: Long get() = endUs - startUs
    fun overlaps(timeUs: Long): Boolean = timeUs >= startUs && timeUs < endUs
    fun coversRange(fromUs: Long, toUs: Long): Boolean = startUs <= fromUs && endUs >= toUs

    fun movedTo(newStartUs: Long): TextOverlayItem {
        val duration = durationUs
        return copy(startUs = newStartUs.coerceAtLeast(0L), endUs = newStartUs.coerceAtLeast(0L) + duration)
    }
}
