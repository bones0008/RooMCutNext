package com.RooMCutNext.core.model

import kotlinx.serialization.Serializable

/** Type of an imported media file. */
@Serializable
enum class MediaType { VIDEO, IMAGE }

/**
 * An immutable piece of imported media living in the app's private storage.
 *
 * The file is copied into the project's media directory at import time so the
 * project never breaks when a SAF/picker URI permission expires.
 */
@Serializable
data class MediaAsset(
    val id: String,
    /** File name inside the project's media directory. */
    val fileName: String,
    /** Human readable name (usually the original file name). */
    val displayName: String,
    val type: MediaType,
    /** Duration of the source video in microseconds. 0 for images. */
    val durationUs: Long,
    /** Display width (already normalized for the container rotation). */
    val width: Int,
    /** Display height (already normalized for the container rotation). */
    val height: Int,
    /** Detected frame rate of the video. 0 for images. */
    val fps: Float,
    val hasAudio: Boolean,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val fileBytes: Long = 0L,
    val addedAtMs: Long = 0L,
) {
    val isImage: Boolean get() = type == MediaType.IMAGE

    /** Frame duration of the source in microseconds; only meaningful for video. */
    val sourceFrameDurationUs: Long
        get() = if (fps > 0f) (1_000_000f / fps).toLong() else 33_333L

    /** One-line technical summary, e.g. "1920×1080 · 30FPS · H.264". */
    fun techSummary(): String {
        return if (isImage) {
            "${width}×$height"
        } else {
            buildString {
                append("${width}×$height")
                if (fps > 0f) append(" · ${fps.toInt()}FPS")
                if (!videoCodec.isNullOrBlank()) append(" · $videoCodec")
                if (hasAudio && !audioCodec.isNullOrBlank()) append(" · $audioCodec")
                else if (!hasAudio) append(" · no audio")
            }
        }
    }
}

/** Maximum display duration of a still image clip on the timeline (60s). */
const val IMAGE_MAX_DURATION_US: Long = 60_000_000L

/** Default display duration of a still image when first added to the timeline. */
const val IMAGE_DEFAULT_DURATION_US: Long = 3_000_000L
