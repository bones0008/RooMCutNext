package com.RooMCutNext.core.model

import kotlinx.serialization.Serializable

/**
 * A video editing project: the timeline data (never a rendered file).
 *
 * Persistence format is JSON (see data/ProjectRepository); media files live in
 * the app's private directory, referenced by [MediaAsset.fileName].
 *
 * Timeline model (v3):
 *  - [clips]: video/image clips with optional leading gaps ([Clip.gapBeforeUs]).
 *  - [texts]: text overlays, independent layer anchored to the timeline.
 *  - [audios]: audio clips, independent layer anchored to the timeline.
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    /** Project frame rate used for snapping, frame stepping and timecodes. */
    val fps: Int = 30,
    /** Canvas (output) size derived from the first clip. */
    val width: Int = 1920,
    val height: Int = 1080,
    val assets: List<MediaAsset> = emptyList(),
    val clips: List<Clip> = emptyList(),
    val texts: List<TextOverlayItem> = emptyList(),
    val audios: List<AudioClip> = emptyList(),
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val schemaVersion: Int = 2,
) {
    /** Video track duration (gaps + clips). */
    val clipsDurationUs: Long get() = clips.fold(0L) { acc, c -> acc + c.gapBeforeUs + c.durationUs }

    /** Total timeline duration: the longest of video, texts and audio. */
    val totalDurationUs: Long get() = maxOf(
        clipsDurationUs,
        texts.maxOfOrNull { it.endUs } ?: 0L,
        audios.maxOfOrNull { it.endUs } ?: 0L,
    )

    val hasContent: Boolean get() = clips.isNotEmpty() || texts.isNotEmpty() || audios.isNotEmpty()

    fun assetById(id: String): MediaAsset? = assets.firstOrNull { it.id == id }
    fun clipById(id: String): Clip? = clips.firstOrNull { it.id == id }
    fun textById(id: String): TextOverlayItem? = texts.firstOrNull { it.id == id }
    fun audioById(id: String): AudioClip? = audios.firstOrNull { it.id == id }

    /** Frame duration of the project timeline grid. */
    val frameDurationUs: Long get() = 1_000_000L / fps

    fun withTimeline(clips: List<Clip>, texts: List<TextOverlayItem>, updatedAt: Long): Project =
        copy(clips = clips, texts = texts, updatedAtMs = updatedAt)
}

/** Supported project frame rates offered to the user. */
val SUPPORTED_FPS = listOf(24, 25, 30, 50, 60)

/**
 * Snap a raw detected source fps to a sensible project fps.
 * Phone footage is commonly 30, AI generated footage commonly 24.
 */
fun snapProjectFps(detected: Float): Int {
    val rounded = detected.roundToIntSafe()
    return when {
        rounded <= 0 -> 30
        SUPPORTED_FPS.any { it == rounded } -> rounded
        else -> SUPPORTED_FPS.minBy { kotlin.math.abs(it - rounded) }
    }
}

private fun Float.roundToIntSafe(): Int = kotlin.math.round(this).toInt()
