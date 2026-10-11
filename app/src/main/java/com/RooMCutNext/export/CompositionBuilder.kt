package com.RooMCutNext.export

import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Composition
import androidx.media3.common.SpeedParameters
import androidx.media3.common.audio.SpeedProvider
import com.RooMCutNext.core.model.Clip
import com.RooMCutNext.core.model.MediaAsset
import com.RooMCutNext.core.model.Project
import com.RooMCutNext.core.ops.TimelineOps
import com.RooMCutNext.export.effects.GainAudioProcessor
import com.RooMCutNext.export.effects.TimedTextOverlay
import java.io.File

/**
 * Builds the single Media3 [Composition] that describes the whole timeline.
 *
 * The SAME builder output feeds both the preview player (CompositionPlayer)
 * and the exporter (Transformer) — one source of truth, so the preview is
 * exactly what gets exported (WYSIWYG by construction).
 *
 * Effects chain per clip (order matters):
 *   rotate/flip -> crop -> color adjust -> text overlays
 */
object CompositionBuilder {

    class BuildException(message: String) : IllegalStateException(message)

    data class BuildResult(val composition: Composition, val skippedClipIds: List<String>)

    /** Constant-speed provider for per-clip speed (pitch preserved). */
    private class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timestampUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timestampUs: Long): Long =
            androidx.media3.common.C.TIME_UNSET
    }

    fun build(project: Project, mediaDir: File, exportHeight: Int? = null): BuildResult {
        val starts = TimelineOps.starts(project.clips)
        val skipped = mutableListOf<String>()
        val items = mutableListOf<EditedMediaItem>()

        project.clips.forEachIndexed { index, clip ->
            val asset = project.assetById(clip.assetId)
            val file = asset?.let { File(mediaDir, it.fileName) }
            if (asset == null || file == null || !file.exists()) {
                skipped += clip.id
                return@forEachIndexed
            }
            items += buildItem(project, clip, asset, file, starts[index])
        }

        if (items.isEmpty()) {
            throw BuildException("No playable clips (media files missing?)")
        }

        val sequence = EditedMediaItemSequence.Builder(items).build()
        val builder = Composition.Builder(sequence)
        if (exportHeight != null) {
            builder.setEffects(
                Effects(
                    emptyList<AudioProcessor>(),
                    listOf<Effect>(Presentation.createForHeight(exportHeight)),
                )
            )
        }
        return BuildResult(builder.build(), skipped)
    }

    private fun buildItem(
        project: Project,
        clip: Clip,
        asset: MediaAsset,
        file: File,
        clipStartUs: Long,
    ): EditedMediaItem {
        val uri = Uri.fromFile(file)

        val mediaItem = if (asset.isImage) {
            MediaItem.Builder()
                .setUri(uri)
                .setImageDurationMs((clip.durationUs / 1000L).coerceAtLeast(1L))
                .build()
        } else {
            MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(clip.sourceInUs)
                        .setEndPositionUs(clip.sourceOutUs)
                        .build()
                )
                .build()
        }

        // ---- video effects ----
        val videoEffects = mutableListOf<Effect>()

        if (clip.rotationDeg != 0 || clip.flipH || clip.flipV) {
            videoEffects += ScaleAndRotateTransformation.Builder()
                .setScale(if (clip.flipH) -1f else 1f, if (clip.flipV) -1f else 1f)
                .setRotationDegrees(clip.rotationDeg.toFloat())
                .build()
        }
        clip.crop?.let { videoEffects += Crop(it.left, it.right, it.top, it.bottom) }
        if (clip.adjust.brightness != 0f) videoEffects += Brightness(clip.adjust.brightness)
        if (clip.adjust.contrast != 0f) videoEffects += Contrast(clip.adjust.contrast)
        if (clip.adjust.saturation != 0f) {
            videoEffects += HslAdjustment.Builder().adjustSaturation(clip.adjust.saturation).build()
        }

        // ---- text overlays whose window overlaps this clip ----
        val clipEndUs = clipStartUs + clip.durationUs
        val overlapping = project.texts.filter { it.startUs < clipEndUs && it.endUs > clipStartUs }
        if (overlapping.isNotEmpty()) {
            videoEffects += OverlayEffect(
                overlapping.map { text ->
                    TimedTextOverlay(
                        item = text,
                        windowStartUs = (text.startUs - clipStartUs).coerceAtLeast(0L),
                        windowEndUs = (text.endUs - clipStartUs).coerceAtMost(clip.durationUs),
                    )
                }
            )
        }

        // ---- audio ----
        val audioEffects = mutableListOf<AudioProcessor>()
        if (!asset.isImage && !clip.muted &&
            (clip.volume != 1f || clip.fadeInMs > 0 || clip.fadeOutMs > 0)
        ) {
            audioEffects += GainAudioProcessor(clip.volume, clip.fadeInMs * 1000L, clip.fadeOutMs * 1000L, clip.durationUs)
        }

        val effects = Effects(audioEffects, videoEffects)

        val builder = EditedMediaItem.Builder(mediaItem).setEffects(effects)
        if (asset.isImage) {
            builder.setDurationUs(clip.durationUs)
        } else {
            if (clip.speed != 1f) {
                builder.setSpeed(SpeedParameters(ConstantSpeedProvider(clip.speed), /* shouldMaintainPitch = */ true))
            }
            if (clip.muted) builder.setRemoveAudio(true)
        }
        return builder.build()
    }
}
