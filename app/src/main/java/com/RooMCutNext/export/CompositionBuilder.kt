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
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Composition
import androidx.media3.common.SpeedParameters
import androidx.media3.common.audio.SpeedProvider
import com.RooMCutNext.core.model.AudioClip
import com.RooMCutNext.core.model.Clip
import com.RooMCutNext.core.model.MediaAsset
import com.RooMCutNext.core.model.PipLayer
import com.RooMCutNext.core.model.Project
import com.RooMCutNext.core.ops.TimelineOps
import com.RooMCutNext.core.model.effectiveTransitions
import androidx.media3.effect.AlphaScale
import com.RooMCutNext.export.effects.FadeInEffect
import com.RooMCutNext.export.effects.GainAudioProcessor
import com.RooMCutNext.export.effects.MosaicEffect
import com.RooMCutNext.export.effects.PipPresentation
import com.RooMCutNext.export.effects.TimedTextOverlay
import com.RooMCutNext.media.LutRegistry
import java.io.File

/**
 * Builds the Media3 [Composition] that describes the whole timeline.
 *
 * The SAME builder output feeds both the preview player (CompositionPlayer)
 * and the exporter (Transformer) — one source of truth, so the preview is
 * exactly what gets exported (WYSIWYG by construction).
 *
 *  ⚠ Media3 1.11.1 gotchas verified against the shipped bytecode (v1.0.8):
 *
 *  - CROP: `Crop(left, right, bottom, top)` — the THIRD constructor argument
 *    is stored into the `bottom` field and the FOURTH into `top` (the ctor
 *    checks arg4 > arg3). Passing (left, right, top, bottom) in the intuitive
 *    order throws "top value X should be greater than bottom value Y".
 *    CropSpec stores GL-style (top=1..bottom=0), so we pass
 *    Crop(spec.left, spec.right, spec.bottom, spec.top).
 *
 *  - SATURATION: HslAdjustment.Builder.adjustSaturation takes [-100, 100]
 *    (Brightness/Contrast take [-1, 1]). Passing a [-1, 1] slider value
 *    directly is a 1% change — invisible to the eye.
 *
 *  - TIMED OVERLAYS: GL effects receive GLOBAL composition timestamps in BOTH
 *    consumers. For preview this follows from the window offset added by
 *    PlaybackVideoGraphWrapper's InputVideoSink. For export it was believed
 *    (v1.0.7) that SequenceAssetLoader feeds ITEM-LOCAL timestamps, but
 *    real-device behavior proves otherwise: text beyond the FIRST clip
 *    boundary disappeared in exports (the item-local window on clip 2 never
 *    matched the global presentation timestamps). Windows are therefore
 *    always computed in GLOBAL timeline coordinates.
 *
 * Effects chain per clip (order matters):
 *   rotate/flip -> crop -> color adjust -> text overlays
 */
object CompositionBuilder {

    class BuildException(message: String) : IllegalStateException(message)

    data class BuildResult(val composition: Composition, val skippedClipIds: List<String>)

    /** Max overlays per OverlayEffect (shader sampler limit is 15; stay safe). */
    private const val MAX_OVERLAYS_PER_EFFECT = 12

    /** Gaps below this are treated as adjacent (no silence padding item). */
    private const val MIN_GAP_US = 20_000L

    /** Constant-speed provider for per-clip speed (pitch preserved). */
    private class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timestampUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timestampUs: Long): Long =
            androidx.media3.common.C.TIME_UNSET
    }

    fun build(
        project: Project,
        mediaDir: File,
        exportHeight: Int? = null,
        stripEffects: Boolean = false,
        forPreview: Boolean = false,
    ): BuildResult {
        val starts = TimelineOps.starts(project.clips)
        // Effective (clamped) crossfade-in per clip — see effectiveTransitions.
        val transitions = effectiveTransitions(project.clips)
        val skipped = mutableListOf<String>()
        val items = mutableListOf<EditedMediaItem>()
        val transitionItems = mutableListOf<EditedMediaItem>()
        var transitionCursorUs = 0L

        // ---- primary (video) sequence + transition overlay sequence (v1.2.1) ----
        // Gap-aware: a clip's leading hole becomes a black background item,
        // so the hole renders as true black (text overlays can sit on it) in
        // BOTH preview and export.
        //
        // CROSSFADES: a clip with an effective transition T is split into a
        // HEAD (its first T, fading in) and a BODY (the rest). The BODY stays
        // in the primary sequence — fully opaque, covering the overlap from
        // underneath; the HEAD goes into a second parallel sequence stacked
        // ABOVE the primary one, padded transparent up to its start. Because
        // only the TOP layer fades (the one underneath is opaque), the
        // composite is a true crossfade with no dip-to-black and no dependence
        // on how the compositor resolves two semi-transparent layers.
        var emittedEndUs = 0L
        project.clips.forEachIndexed { index, clip ->
            val clipStartUs = starts[index]
            if (clip.gapBeforeUs >= MIN_GAP_US) {
                items += buildBlackPadItem(
                    project, mediaDir, clipStartUs - clip.gapBeforeUs, clip.gapBeforeUs, stripEffects,
                )
                emittedEndUs = clipStartUs
            }
            val asset = project.assetById(clip.assetId)
            val file = asset?.let { File(mediaDir, it.fileName) }
            if (asset == null || file == null || !file.exists()) {
                skipped += clip.id
                return@forEachIndexed
            }
            val tUs = transitions[index]
            val headSourceSpanUs = (tUs * clip.speed).toLong()

            // Body: everything after the fading head (the whole clip when no
            // transition). Opaque, in the primary sequence.
            val bodyDurationUs = clip.durationUs - tUs
            if (bodyDurationUs > 0L) {
                items += buildClipItem(
                    project = project, clip = clip, asset = asset, file = file,
                    sourceInUs = clip.sourceInUs + headSourceSpanUs,
                    sourceOutUs = clip.sourceOutUs,
                    timelineStartUs = clipStartUs + tUs,
                    timelineDurationUs = bodyDurationUs,
                    fadeInUs = 0L,
                    audioFadeInUs = 0L,
                    audioFadeOutUs = if (tUs > 0L) 0L else clip.fadeOutMs * 1000L,
                    stripEffects = stripEffects,
                )
            }

            // Head: the fading-in overlap, in the transition overlay sequence.
            if (tUs > 0L) {
                if (clipStartUs - transitionCursorUs > 0L) {
                    transitionItems += buildTransparentPadItem(
                        project, mediaDir, clipStartUs - transitionCursorUs,
                    )
                }
                transitionItems += buildClipItem(
                    project = project, clip = clip, asset = asset, file = file,
                    sourceInUs = clip.sourceInUs,
                    sourceOutUs = clip.sourceInUs + headSourceSpanUs,
                    timelineStartUs = clipStartUs,
                    timelineDurationUs = tUs,
                    fadeInUs = if (stripEffects) 0L else tUs,
                    audioFadeInUs = clip.fadeInMs * 1000L,
                    audioFadeOutUs = 0L,
                    stripEffects = stripEffects,
                )
                transitionCursorUs = clipStartUs + tUs
            }
            emittedEndUs = clipStartUs + clip.durationUs
        }

        // Video track duration; the composition must be as long as the longest
        // layer (texts / audios are independent layers now) — pad with a black
        // background item so text and audio tails have frames to attach to.
        val totalDurationUs = project.totalDurationUs
        if (totalDurationUs > emittedEndUs + MIN_GAP_US) {
            items += buildBlackPadItem(
                project, mediaDir, emittedEndUs, totalDurationUs - emittedEndUs, stripEffects,
            )
        }

        if (items.isEmpty()) {
            throw BuildException("No playable clips (media files missing?)")
        }

        val sequences = mutableListOf<EditedMediaItemSequence>()
        sequences += EditedMediaItemSequence.Builder(items).build()

        // ---- crossfade overlay sequence (v1.2.1) ----
        // Added BEFORE the PiP sequences so stickers and picture-in-picture
        // stay ABOVE the fading heads (an overlay must not disappear behind
        // a crossfade). No-op for projects without transitions.
        if (transitionItems.isNotEmpty()) {
            sequences += EditedMediaItemSequence.Builder(transitionItems).build()
        }

        // ---- picture-in-picture sequences (v1.2), one per layer ----
        // Each layer is its own parallel video sequence stacked over the
        // primary one: a transparent leading pad (so the layer only shows
        // during its window) followed by the layer's media with a
        // PipPresentation matrix (position + scale) and AlphaScale
        // (opacity). Stickers are PiP layers over transparent PNG assets.
        if (!stripEffects || project.pips.isNotEmpty()) {
            project.pips.forEach { pip ->
                buildPipSequence(project, mediaDir, pip)?.let { sequences += it }
            }
        }

        // ---- audio sequences (one per non-overlapping lane) ----
        if (!stripEffects || project.audios.isNotEmpty()) {
            TimelineOps.audioLanes(project.audios).forEach { lane ->
                buildAudioSequence(project, mediaDir, lane)?.let { sequences += it }
            }
        }

        val builder = Composition.Builder(sequences)
        // HDR footage (HLG/HDR10, the default on modern phone cameras) must be
        // tone-mapped to SDR in the GL pipeline: the library default
        // HDR_MODE_KEEP_HDR crashes at first prepare on devices whose GL stack
        // cannot render HDR, surfacing as ERROR_CODE_UNSPECIFIED. Tone-mapping
        // is a no-op for SDR input, and preview/export stay consistent because
        // both consume this same Composition.
        builder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
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

    // ------------------------------------------------------------------
    // Video items
    // ------------------------------------------------------------------

    /**
     * One video/image item covering a WINDOW of a clip (v1.2.1): the source
     * span [sourceInUs, sourceOutUs) plays for [timelineDurationUs] starting
     * at [timelineStartUs] on the global timeline.
     *
     * Crossfades split a clip into two windows over this one builder: a HEAD
     * ([fadeInUs] > 0 — carries the crossfade-in effect and the clip's audio
     * fade-in) and a BODY (opaque remainder, carries the audio fade-out).
     * Windows with no transition are simply the whole clip.
     */
    private fun buildClipItem(
        project: Project,
        clip: Clip,
        asset: MediaAsset,
        file: File,
        sourceInUs: Long,
        sourceOutUs: Long,
        timelineStartUs: Long,
        timelineDurationUs: Long,
        fadeInUs: Long,
        audioFadeInUs: Long,
        audioFadeOutUs: Long,
        stripEffects: Boolean,
    ): EditedMediaItem {
        val uri = Uri.fromFile(file)

        val mediaItem = if (asset.isImage) {
            MediaItem.Builder()
                .setUri(uri)
                .setImageDurationMs((timelineDurationUs / 1000L).coerceAtLeast(1L))
                .build()
        } else {
            MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(sourceInUs)
                        .setEndPositionUs(sourceOutUs)
                        .build()
                )
                .build()
        }

        // ---- video effects ----
        // (skipped entirely in compatibility/degraded preview mode; clipping,
        // speed and mute stay so the timeline geometry is preserved)
        val videoEffects = mutableListOf<Effect>()

        if (!stripEffects) {
            if (clip.rotationDeg != 0 || clip.flipH || clip.flipV) {
                videoEffects += ScaleAndRotateTransformation.Builder()
                    .setScale(if (clip.flipH) -1f else 1f, if (clip.flipV) -1f else 1f)
                    .setRotationDegrees(clip.rotationDeg.toFloat())
                    .build()
            }
            // ⚠ Crop argument order in Media3 1.11.1 is (left, right, BOTTOM, TOP)
            // — see class doc. CropSpec stores GL-style top/bottom.
            clip.crop?.let { videoEffects += Crop(it.left, it.right, it.bottom, it.top) }

            // ---- color grading (v1.1) ----
            // Order matters: the LUT look goes on FIRST so the parameter
            // sliders below act on the graded image (users expect the
            // brightness slider to "fix" a dark filter, matching CapCut).
            if (clip.adjust.lutId != null) {
                LutRegistry.effectFor(clip.adjust.lutId, clip.adjust.lutStrength)
                    ?.let { videoEffects += it }
            }
            if (clip.adjust.brightness != 0f) videoEffects += Brightness(clip.adjust.brightness)
            if (clip.adjust.contrast != 0f) videoEffects += Contrast(clip.adjust.contrast)
            if (clip.adjust.saturation != 0f) {
                // HslAdjustment saturation domain is [-100, 100] (unlike
                // Brightness/Contrast which are [-1, 1]): scale the slider.
                videoEffects += HslAdjustment.Builder()
                    .adjustSaturation((clip.adjust.saturation * 100f).coerceIn(-100f, 100f))
                    .build()
            }
            // Temperature + tint + direct RGB gains compose into ONE matrix
            // effect (see ColorAdjust.channelGains) — one shader pass.
            if (!clip.adjust.isGainNeutral) {
                val gains = clip.adjust.channelGains()
                videoEffects += RgbAdjustment.Builder()
                    .setRedScale(gains[0])
                    .setGreenScale(gains[1])
                    .setBlueScale(gains[2])
                    .build()
            }

            // ---- mosaic regions (v1.2.2) ----
            clip.mosaics.forEach { region ->
                videoEffects += MosaicEffect(region)
            }

            // ---- text overlays whose window overlaps THIS item's window ----
            // Windows are in GLOBAL timeline coordinates for BOTH preview and
            // export (see class doc for the v1.0.8 real-device verification).
            // Attaching the same overlays to head and body is safe: each item
            // only covers its own window, so a text never renders twice.
            val itemEndUs = timelineStartUs + timelineDurationUs
            val overlapping = project.texts
                .filter { it.startUs < itemEndUs && it.endUs > timelineStartUs }
                .sortedBy { it.startUs }
            overlapping.chunked(MAX_OVERLAYS_PER_EFFECT).forEach { chunk ->
                videoEffects += OverlayEffect(
                    chunk.map { text ->
                        TimedTextOverlay(
                            item = text,
                            windowStartUs = text.startUs,
                            windowEndUs = text.endUs,
                        )
                    }
                )
            }

            // ---- crossfade-in: alpha ramp over the head's first fadeInUs ----
            // Global window + item-local fallback live inside FadeMath; see
            // FadeInEffect for the timestamp-convention rationale.
            if (fadeInUs > 0L) {
                videoEffects += FadeInEffect(fadeInUs, timelineStartUs)
            }
        }

        // ---- audio ----
        val audioEffects = mutableListOf<AudioProcessor>()
        if (!stripEffects && !asset.isImage && !clip.muted &&
            (clip.volume != 1f || audioFadeInUs > 0 || audioFadeOutUs > 0)
        ) {
            audioEffects += GainAudioProcessor(
                clip.volume, audioFadeInUs, audioFadeOutUs, timelineDurationUs,
            )
        }

        val effects = Effects(audioEffects, videoEffects)

        val builder = EditedMediaItem.Builder(mediaItem).setEffects(effects)
        if (asset.isImage) {
            // Images need BOTH durationUs and frameRate: Transformer's
            // ImageAssetLoader throws a message-less checkState (surfacing as
            // the cryptic "AssetLoader error") when frameRate == RATE_UNSET.
            // Preview (CompositionPlayer) never checks it, which is why this
            // only breaks EXPORT. Frames are generated at frameRate via
            // ConstantRateTimestampIterator, so the project fps is correct.
            builder.setDurationUs(timelineDurationUs)
            builder.setFrameRate(project.fps.toInt().coerceAtLeast(1))
        } else {
            // MANDATORY on Media3 1.11.1: CompositionPlayer requires every
            // EditedMediaItem to carry a non-TIME_UNSET durationUs — otherwise
            // createNonLoopingMediaSource() throws a message-less
            // IllegalArgumentException synchronously inside setComposition()
            // (surfacing as the cryptic "setComposition failed").
            //
            // Semantics (EditedMediaItem.getClippedDuration): durationUs is the
            // source-time duration measured from position 0 and must satisfy
            // clippingConfiguration.endPositionUs <= durationUs; the actual
            // window duration is then end - start (divided by speed). The
            // silent/blank-frame aux sources are built in the same coordinates
            // and clipped identically, so the full source duration is the
            // correct value here. maxOf() guards against metadata skew.
            val sourceDurationUs = maxOf(asset.durationUs, sourceOutUs).coerceAtLeast(1L)
            builder.setDurationUs(sourceDurationUs)
            if (clip.speed != 1f) {
                builder.setSpeed(SpeedParameters(ConstantSpeedProvider(clip.speed), /* shouldMaintainPitch = */ true))
            }
            if (clip.muted) builder.setRemoveAudio(true)
        }
        return builder.build()
    }

    /**
     * Fully transparent still covering [padUs] — the video analog of the
     * audio silence pad, used by the crossfade overlay sequence so its fading
     * heads land at the right global positions (v1.2.1). Structural like the
     * PiP pads: its AlphaScale(0)+Presentation stay on even in degraded
     // compatibility preview, otherwise it would flash black.
     */
    private fun buildTransparentPadItem(
        project: Project,
        mediaDir: File,
        padUs: Long,
    ): EditedMediaItem {
        val pad = GeneratedMedia.blackPng(mediaDir, 2, 2)
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(pad))
            .setImageDurationMs((padUs / 1000L).coerceAtLeast(1L))
            .build()
        return EditedMediaItem.Builder(mediaItem)
            .setEffects(
                Effects(
                    emptyList<AudioProcessor>(),
                    listOf(
                        alphaScale(0f),
                        androidx.media3.effect.Presentation.createForWidthAndHeight(
                            project.width, project.height,
                            androidx.media3.effect.Presentation.LAYOUT_STRETCH_TO_FIT,
                        ),
                    ),
                )
            )
            .setDurationUs(padUs)
            .setFrameRate(project.fps.toInt().coerceAtLeast(1))
            .build()
    }

    /**
     * Black background item covering [padUs] starting at [padStartUs] on the
     * timeline (gap holes AND the tail pad use this). Text overlays overlapping
     * this window are attached here in GLOBAL coordinates so text renders on
     * black gaps and beyond the last video clip, in preview AND export.
     */
    private fun buildBlackPadItem(
        project: Project,
        mediaDir: File,
        padStartUs: Long,
        padUs: Long,
        stripEffects: Boolean,
    ): EditedMediaItem {
        val black = GeneratedMedia.blackPng(mediaDir, project.width, project.height)
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(black))
            .setImageDurationMs((padUs / 1000L).coerceAtLeast(1L))
            .build()

        val videoEffects = mutableListOf<Effect>()
        if (!stripEffects) {
            val padEndUs = padStartUs + padUs
            val overlapping = project.texts
                .filter { it.startUs < padEndUs && it.endUs > padStartUs }
                .sortedBy { it.startUs }
            overlapping.chunked(MAX_OVERLAYS_PER_EFFECT).forEach { chunk ->
                videoEffects += OverlayEffect(
                    chunk.map { text ->
                        TimedTextOverlay(
                            item = text,
                            windowStartUs = text.startUs,
                            windowEndUs = text.endUs,
                        )
                    }
                )
            }
        }

        return EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList<AudioProcessor>(), videoEffects))
            .setDurationUs(padUs)
            // Mandatory alongside durationUs for Transformer's ImageAssetLoader
            // (see buildItem): without it export fails with "AssetLoader error".
            .setFrameRate(project.fps.toInt().coerceAtLeast(1))
            .build()
    }

    // ------------------------------------------------------------------
    // Picture-in-picture sequences
    // ------------------------------------------------------------------

    /** Alias import so the effect reads as what it does; see buildPipSequence. */
    private fun alphaScale(value: Float): Effect = AlphaScale(value)

    /**
     * One sequence per PiP layer:
     *   [optional transparent pad 0 -> startUs] + [layer media, clipped]
     *
     * The pad reuses the black-PNG generator at a tiny 2×2 size and is fully
     * transparent (AlphaScale 0), stretched to the canvas — the video analog
     * of the silence pad used by audio lanes. The layer item carries the
     * PipPresentation matrix for position/scale; its own audio (if any) is
     * kept and mixed automatically by the Composition.
     */
    private fun buildPipSequence(
        project: Project,
        mediaDir: File,
        pip: PipLayer,
    ): EditedMediaItemSequence? {
        val asset = project.assetById(pip.assetId) ?: return null
        val file = File(mediaDir, asset.fileName)
        if (!file.exists()) return null

        val items = mutableListOf<EditedMediaItem>()

        if (pip.startUs >= MIN_GAP_US) {
            val pad = GeneratedMedia.blackPng(mediaDir, 2, 2)
            val padItem = MediaItem.Builder()
                .setUri(Uri.fromFile(pad))
                .setImageDurationMs((pip.startUs / 1000L).coerceAtLeast(1L))
                .build()
            items += EditedMediaItem.Builder(padItem)
                .setEffects(
                    Effects(
                        emptyList<androidx.media3.common.audio.AudioProcessor>(),
                        listOf(
                            alphaScale(0f),
                            androidx.media3.effect.Presentation.createForWidthAndHeight(
                                project.width, project.height,
                                androidx.media3.effect.Presentation.LAYOUT_STRETCH_TO_FIT,
                            ),
                        ),
                    )
                )
                .setDurationUs(pip.startUs)
                .setFrameRate(project.fps.toInt().coerceAtLeast(1))
                .build()
        }

        val clippedDurationUs = pip.durationUs.coerceAtMost(
            if (asset.isImage) pip.durationUs else asset.durationUs
        )
        val mediaItem = if (asset.isImage) {
            MediaItem.Builder()
                .setUri(Uri.fromFile(file))
                .setImageDurationMs((pip.durationUs / 1000L).coerceAtLeast(1L))
                .build()
        } else {
            MediaItem.Builder()
                .setUri(Uri.fromFile(file))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(0L)
                        .setEndPositionUs(pip.durationUs.coerceAtMost(asset.durationUs))
                        .build()
                )
                .build()
        }

        val pipEffects = mutableListOf<Effect>()
        pipEffects += PipPresentation(
            canvasWidth = project.width,
            canvasHeight = project.height,
            centerX = pip.xRatio,
            centerY = pip.yRatio,
            displayWidthRatio = pip.scale,
        )
        if (pip.opacity < 1f) {
            pipEffects += alphaScale(pip.opacity.coerceIn(0f, 1f))
        }

        val builder = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList<androidx.media3.common.audio.AudioProcessor>(), pipEffects))
        if (asset.isImage) {
            builder.setDurationUs(pip.durationUs)
            builder.setFrameRate(project.fps.toInt().coerceAtLeast(1))
        } else {
            builder.setDurationUs(maxOf(asset.durationUs, pip.durationUs).coerceAtLeast(1L))
        }
        items += builder.build()

        return EditedMediaItemSequence.Builder(items).build().takeIf { clippedDurationUs > 0L }
    }

    // ------------------------------------------------------------------
    // Audio sequences
    // ------------------------------------------------------------------

    /**
     * One EditedMediaItemSequence per non-overlapping lane. Items inside a
     * sequence are strictly concatenated, so gaps become silence padding items
     * (generated PCM WAV) and each audio clip is trimmed via clipping.
     */
    private fun buildAudioSequence(
        project: Project,
        mediaDir: File,
        lane: List<AudioClip>,
    ): EditedMediaItemSequence? {
        val items = mutableListOf<EditedMediaItem>()
        var cursorUs = 0L
        lane.sortedBy { it.startUs }.forEach { audio ->
            val gap = audio.startUs - cursorUs
            if (gap >= MIN_GAP_US) {
                val silence = GeneratedMedia.silenceWav(mediaDir, gap)
                val silenceUs = GeneratedMedia.silenceDurationUs(gap)
                items += EditedMediaItem.Builder(
                    MediaItem.Builder().setUri(Uri.fromFile(silence)).build()
                )
                    .setDurationUs(silenceUs)
                    .build()
                cursorUs += silenceUs
            } else if (gap < 0) {
                // Lane packing guarantees no overlap; defensive clamp.
                return@forEach
            }

            val asset = project.assetById(audio.assetId)
            val file = asset?.let { File(mediaDir, it.fileName) }
            if (asset == null || file == null || !file.exists()) return@forEach

            val mediaItem = MediaItem.Builder()
                .setUri(Uri.fromFile(file))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(audio.sourceInUs)
                        .setEndPositionUs(audio.sourceOutUs)
                        .build()
                )
                .build()

            val audioEffects = mutableListOf<AudioProcessor>()
            if (!audio.muted && (audio.volume != 1f || audio.fadeInMs > 0 || audio.fadeOutMs > 0)) {
                audioEffects += GainAudioProcessor(
                    audio.volume,
                    audio.fadeInMs * 1000L,
                    audio.fadeOutMs * 1000L,
                    audio.durationUs,
                )
            }

            items += EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(audioEffects, emptyList<Effect>()))
                .setDurationUs(maxOf(asset.durationUs, audio.sourceOutUs).coerceAtLeast(1L))
                .apply { if (audio.muted) setRemoveAudio(true) }
                .build()
            cursorUs = audio.endUs
        }
        if (items.isEmpty()) return null
        return EditedMediaItemSequence.Builder(items).build()
    }
}
