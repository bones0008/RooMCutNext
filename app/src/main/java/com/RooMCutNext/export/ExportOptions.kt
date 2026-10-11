package com.RooMCutNext.export

import kotlinx.serialization.Serializable

/**
 * User-facing export settings.
 *
 * V1 encodes H.264 + AAC into MP4 (Media3 Transformer). Frame rate follows the
 * sources (VFR timeline) — 30FPS phone footage and 24FPS AI footage both keep
 * their native cadence, which avoids judder from forced resampling.
 */
@Serializable
data class ExportOptions(
    /** Target height in px, or null for original resolution. */
    val height: Int? = null,
    /** Video bitrate hints per height tier (used as a quality preset label). */
    val quality: Quality = Quality.HIGH,
) {
    @Serializable
    enum class Quality(val label: String) { STANDARD("标准"), HIGH("高") }

    companion object {
        /** Height choices offered in the export sheet. null = original. */
        val HEIGHT_CHOICES: List<Int?> = listOf(null, 1080, 720, 480)

        fun heightLabel(height: Int?): String =
            height?.let { "${it}P" } ?: "原始"
    }
}
