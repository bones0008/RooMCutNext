package com.RooMCutNext.core.model

import kotlinx.serialization.Serializable

/** Type of an imported media file. */
@Serializable
enum class MediaType { VIDEO, IMAGE, AUDIO }

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
    /** MediaFormat COLOR_TRANSFER_* of the source video (6=ST2084 HDR10, 7=HLG); 0 = unknown/SDR. */
    val colorTransfer: Int = 0,
) {
    val isImage: Boolean get() = type == MediaType.IMAGE
    val isAudio: Boolean get() = type == MediaType.AUDIO

    /** True when this is the built-in 黑场 black-frame asset (see [BLACK_FRAME_ASSET_ID]). */
    val isBlackFrame: Boolean get() = id == BLACK_FRAME_ASSET_ID

    /** Frame duration of the source in microseconds; only meaningful for video. */
    val sourceFrameDurationUs: Long
        get() = if (fps > 0f) (1_000_000f / fps).toLong() else 33_333L

    /** One-line technical summary, e.g. "1920×1080 · 30FPS · H.264". */
    fun techSummary(): String {
        return when {
            isImage -> "${width}×$height"
            isAudio -> buildString {
                append("AUDIO")
                if (!audioCodec.isNullOrBlank()) append(" · $audioCodec")
                if (durationUs > 0) append(" · ${durationUs / 1_000_000f}s")
            }
            else -> buildString {
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

// ---------------------------------------------------------------------
// v1.6.1 — 黑场（black frame）内置素材
// ---------------------------------------------------------------------

/**
 * Stable well-known asset id of the built-in black-frame clip (黑场).
 *
 * The black frame is an ordinary IMAGE asset bundled in the APK
 * (assets/media/black_frame.png, 1920×1080 solid opaque black) and copied
 * into the project's media dir on first use, so every existing pipeline —
 * trim (0.1s..60s free stretching), transitions (dissolve into a black
 * clip = professional fade-to-black), playback, export, thumbnails — works
 * with zero special cases. Code that needs to RENDER it distinctively
 * (timeline filmstrip) detects it via [MediaAsset.isBlackFrame].
 */
const val BLACK_FRAME_ASSET_ID: String = "builtin-black"

/** File name of the bundled black frame inside the project media dir. */
const val BLACK_FRAME_FILE_NAME: String = "black_frame.png"

/** Default 黑场 duration when inserted (2s; stretch by dragging edges). */
const val BLACK_DEFAULT_DURATION_US: Long = 2_000_000L
