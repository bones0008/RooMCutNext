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
 *
 *  v1.2.3 — PREVIEW vs EXPORT split (upstream Media3 workaround):
 *  CompositionPlayer with MORE THAN ONE VIDEO sequence is broken in
 *  Media3 1.11.1 — seeking a multi-video-sequence composition kills
 *  playback entirely (androidx/media #2439, closed as dup of #2439/#3058:
 *  "Seeking is currently not supported for multiple video sequences like
 *  PiP and Grid view"), and DefaultVideoCompositor deadlocks whenever any
 *  input's frame queue runs empty during an item transition (#2742) — which
 *  our padded PiP sequences hit at every pad→video boundary. An EDITOR
 *  cannot live without seek/scrub, so:
 *
 *    forPreview=true  → ONE video sequence only (the body — bodies + gap
 *                       pads serialize 1:1 with the timeline; during a
 *                       crossfade window the body shows the PREVIOUS clip's
 *                       tail, exactly the crossfade's bottom layer). PiP
 *                       layers and crossfade heads are drawn by the
 *                       view-layer OverlayPreviewController instead.
 *    forPreview=false → the full multi-sequence Composition for Transformer
 *                       (export multi-input compositing is a supported
 *                       upstream path and does NOT go through
 *                       CompositionPlayer).
 *
 *  v1.2.5 — EXPORT TIMING DRIVER. DefaultVideoCompositor (1.11.1, verified
 *  against the shipped AAR bytecode) derives EVERY output frame timestamp
 *  from the PRIMARY input — the first registered sequence — and releases
 *  (drops) secondary frames that fall between primary timestamps
 *  (releaseExcessFramesInSecondaryStream). The primary is also drawn ON
 *  TOP (DefaultCompositorGlProgram draws the frame list back-to-front), so
 *  the v1.2.2 stacking order [PiP..., heads, body] — chosen so the body
 *  lands at the BOTTOM — silently made a PiP layer the timing driver:
 *  the export's frame grid followed the PiP asset's own fps/phase, and the
 *  MAIN video was nearest-sampled down onto it. Field reports on v1.2.3/
 *  v1.2.4: "PiP plays smooth, the main video underneath stutters, audio
 *  fine" — exactly that mechanism (audio never touches the compositor).
 *  Fix: whenever more than one video sequence would exist, a FULL-LENGTH
 *  fully transparent pad sequence at a frame rate at least as dense as
 *  EVERY video source is listed FIRST. It becomes the primary: output runs
 *  on a fixed CFR grid dense enough that nearest-frame sampling never
 *  skips a source frame (main video smooth again), and because it is drawn
 *  last but is entirely invisible (AlphaScale 0), the visual stack is
 *  byte-for-byte unchanged: body < heads < PiPs.
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

            // Head: the fading-in overlap. In EXPORT it goes into a second
            // parallel sequence above the body; in PREVIEW it is supplied by
            // the view-layer overlay engine (see headOverlaySpecs) — building
            // it here would make the preview composition multi-video-sequence,
            // which CompositionPlayer cannot seek (Media3 #2439).
            if (tUs > 0L && !forPreview) {
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

        // v1.2.2: EMPTY-body guard. Clips may all be gone (user deleted them)
        // while texts / audios / PiP layers still exist — pad the body with a
        // full-length black canvas item instead of failing. The old
        // BuildException here left the editor unusable with no way out except
        // deleting the project (degraded mode stuck, "no playable clips").
        if (items.isEmpty()) {
            if (totalDurationUs <= 0L) {
                throw BuildException("No playable clips (media files missing?)")
            }
            items += buildBlackPadItem(project, mediaDir, 0L, totalDurationUs, stripEffects)
        }

        // ---- sequence STACKING ----
        // PREVIEW (v1.2.3): exactly ONE video sequence — the body. The body
        // items + gap pads + tail pad serialize 1:1 with the timeline (a
        // crossfade head's window is covered by the previous clip's body
        // tail), so the playhead maps to the player position unchanged and
        // every CompositionPlayer feature (seek, scrubbing mode, frame step)
        // keeps working. PiP layers and crossfade heads render above this via
        // OverlayPreviewController (TextureViews synced to the master
        // position) — geometry mirrors PipPresentation/export math.
        //
        // EXPORT: Media3 1.11.1's DefaultVideoCompositor draws its inputs
        // back-to-front with the PRIMARY sequence (index 0) ALWAYS ON TOP —
        // verified against the shipped bytecode (custom draw order is still
        // a TODO upstream, b/262694346). The stack we need is (bottom -> top):
        //     main body -> crossfade heads -> PiP layers (later = higher)
        // so the video sequences are listed TOP-FIRST:
        //     [timing driver (v1.2.5), PiP reversed..., transition heads,
        //      main body, ... audio lanes]
        // The timing driver is a transparent CFR pad that occupies the primary
        // slot (output timestamps + draw-on-top but invisible) — see the
        // v1.2.5 section of the class doc.
        // Audio lanes carry no video track and never register a compositor
        // input (audio mixing only), so their position is irrelevant — but
        // sequences[0] must be a full-length VIDEO sequence because
        // CompositionPlayer derives the preview duration from it. Every
        // video sequence below is therefore padded out to totalDurationUs.
        val sequences = mutableListOf<EditedMediaItemSequence>()

        if (forPreview) {
            // Single video sequence: the body alone. PiP layers and crossfade
            // heads are view-layer overlays (see OverlayPreviewController).
        } else {
            // PiP layers — reversed, so the LAST-added layer sits HIGHEST.
            // (Structural: kept even in degraded mode — see buildPipSequence.)
            val pipSequences = if (!stripEffects || project.pips.isNotEmpty()) {
                project.pips.asReversed().mapNotNull { pip ->
                    buildPipSequence(project, mediaDir, pip, totalDurationUs)
                }
            } else {
                emptyList()
            }

            // Crossfade overlay sequence: fading heads land ABOVE the bodies.
            var headSequence: EditedMediaItemSequence? = null
            if (transitionItems.isNotEmpty()) {
                if (totalDurationUs - transitionCursorUs >= MIN_GAP_US) {
                    transitionItems += buildTransparentPadItem(
                        project, mediaDir, totalDurationUs - transitionCursorUs,
                    )
                }
                headSequence = EditedMediaItemSequence.Builder(transitionItems).build()
            }

            // v1.2.5: TIMING DRIVER (see class doc). More than one video
            // sequence would put a PiP/head layer into the compositor's
            // primary slot and retime the whole export to ITS frame grid —
            // the main-video-judder bug. A transparent CFR pad at least as
            // dense as every source takes that slot instead. Projects with
            // a single video sequence (no PiP, no transitions) take the
            // SingleInputVideoGraph path with no compositor at all and are
            // deliberately left untouched.
            if (pipSequences.isNotEmpty() || headSequence != null) {
                buildTimingDriverSequence(
                    project, mediaDir, totalDurationUs, timingDriverFps(project),
                )?.let { sequences += it }
            }
            sequences += pipSequences
            headSequence?.let { sequences += it }
        }

        // Main body: the BOTTOM visual layer (or the only sequence when no
        // PiP / transitions exist).
        sequences += EditedMediaItemSequence.Builder(items).build()

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
    // View-layer preview overlay specs (v1.2.3)
    // ------------------------------------------------------------------

    /**
     * One crossfade HEAD window for the preview overlay engine.
     *
     * In preview the body sequence alone covers the timeline; during a
     * crossfade window the body shows the PREVIOUS clip's tail (the bottom
     * layer) and the overlay engine plays THIS head on top with an alpha ramp
     * — the same decomposition the export composition uses (head sequence
     * over body sequence). Pure data, no Media3 types, so the window math is
     * unit-testable.
     */
    data class HeadOverlaySpec(
        val clipId: String,
        val assetId: String,
        /** Timeline window [startUs, startUs+durationUs) of the fading head. */
        val startUs: Long,
        /** Effective (clamped) transition length — also the fade ramp width. */
        val durationUs: Long,
        /** Source span of the head, already divided by the clip speed. */
        val sourceInUs: Long,
        val sourceOutUs: Long,
        val speed: Float,
        val muted: Boolean,
        val volume: Float,
    )

    /**
     * The crossfade head windows of the project (pure function of the model).
     * Mirrors the head-item construction inside [build]: for each clip with an
     * effective transition T, the head covers [startUs, startUs+T) and plays
     * source [sourceIn, sourceIn + T*speed).
     */
    fun headOverlaySpecs(project: Project): List<HeadOverlaySpec> {
        val starts = TimelineOps.starts(project.clips)
        val transitions = effectiveTransitions(project.clips)
        val specs = mutableListOf<HeadOverlaySpec>()
        project.clips.forEachIndexed { i, clip ->
            val tUs = transitions[i]
            if (tUs <= 0L) return@forEachIndexed
            val asset = project.assetById(clip.assetId) ?: return@forEachIndexed
            val headSpanUs = (tUs * clip.speed).toLong()
            specs += HeadOverlaySpec(
                clipId = clip.id,
                assetId = clip.assetId,
                startUs = starts[i],
                durationUs = tUs,
                sourceInUs = clip.sourceInUs,
                sourceOutUs = (clip.sourceInUs + headSpanUs).coerceAtMost(clip.sourceOutUs),
                speed = clip.speed,
                muted = clip.muted,
                volume = clip.volume,
            )
        }
        return specs
    }

    // ------------------------------------------------------------------
    // Video items
    // ------------------------------------------------------------------

    /**
     * The per-clip "look" effects shared by every render path (composition
     * items AND the preview overlay engine's head player):
     * rotate/flip -> crop -> LUT -> brightness/contrast/saturation -> RGB
     * gains -> mosaic. Positioning (canvas Presentation), text overlays and
     * fades are path-specific and stay in the callers.
     */
    fun clipLookEffects(clip: Clip, stripEffects: Boolean): List<Effect> {
        if (stripEffects) return emptyList()
        val effects = mutableListOf<Effect>()
        if (clip.rotationDeg != 0 || clip.flipH || clip.flipV) {
            effects += ScaleAndRotateTransformation.Builder()
                .setScale(if (clip.flipH) -1f else 1f, if (clip.flipV) -1f else 1f)
                .setRotationDegrees(clip.rotationDeg.toFloat())
                .build()
        }
        // ⚠ Crop argument order in Media3 1.11.1 is (left, right, BOTTOM, TOP)
        // — see class doc. CropSpec stores GL-style top/bottom.
        clip.crop?.let { effects += Crop(it.left, it.right, it.bottom, it.top) }

        // Color grading — LUT first so the sliders below act on the graded
        // image (users expect the brightness slider to "fix" a dark filter).
        if (clip.adjust.lutId != null) {
            LutRegistry.effectFor(clip.adjust.lutId, clip.adjust.lutStrength)
                ?.let { effects += it }
        }
        if (clip.adjust.brightness != 0f) effects += Brightness(clip.adjust.brightness)
        if (clip.adjust.contrast != 0f) effects += Contrast(clip.adjust.contrast)
        if (clip.adjust.saturation != 0f) {
            // HslAdjustment saturation domain is [-100, 100] (unlike
            // Brightness/Contrast which are [-1, 1]): scale the slider.
            effects += HslAdjustment.Builder()
                .adjustSaturation((clip.adjust.saturation * 100f).coerceIn(-100f, 100f))
                .build()
        }
        // Temperature + tint + direct RGB gains compose into ONE matrix
        // effect (see ColorAdjust.channelGains) — one shader pass.
        if (!clip.adjust.isGainNeutral) {
            val gains = clip.adjust.channelGains()
            effects += RgbAdjustment.Builder()
                .setRedScale(gains[0])
                .setGreenScale(gains[1])
                .setBlueScale(gains[2])
                .build()
        }

        // Mosaic regions (privacy) — must survive onto every render path.
        clip.mosaics.forEach { region -> effects += MosaicEffect(region) }
        return effects
    }

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
        videoEffects += clipLookEffects(clip, stripEffects)

        // ---- canvas fit (v1.2.2, STRUCTURAL: also active in degraded mode) ----
        // Letterboxes the clip onto the project canvas with transparent bars
        // so a landscape source in a portrait project no longer stretches
        // edge to edge — it scales into the canvas and the compositor (or the
        // final surface) shows black bars. Order matters:
        //   AFTER  rotate/crop/grading/mosaic — those run in SOURCE space;
        //   BEFORE text overlays — text is positioned in CANVAS space.
        // A 90/270-degree rotation swaps the source aspect; matching aspects
        // skip the pass entirely (Presentation would be a no-op).
        if (asset.width > 0 && asset.height > 0) {
            val rotatedQuarter = clip.rotationDeg == 90 || clip.rotationDeg == 270
            val srcW = if (rotatedQuarter) asset.height else asset.width
            val srcH = if (rotatedQuarter) asset.width else asset.height
            val aspectDelta = kotlin.math.abs(
                srcW.toDouble() / srcH - project.width.toDouble() / project.height,
            )
            if (aspectDelta > 1e-4) {
                videoEffects += Presentation.createForWidthAndHeight(
                    project.width, project.height, Presentation.LAYOUT_SCALE_TO_FIT,
                )
            }
        }

        if (!stripEffects) {
            // ---- text overlays whose window overlaps THIS item's window ----
            // Windows are in GLOBAL timeline coordinates for BOTH preview and
            // export (see class doc for the v1.0.8 real-device verification).
            // Attaching the same overlays to head and body is safe: each item
            // only covers its own window, so a text never renders twice.
            // Canvas-space: the canvas Presentation above runs BEFORE these in
            // the chain, so the overlay canvas maps to the project canvas.
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
     * The export timing driver's CFR frame rate — at least as dense as every
     * video source in the project (nearest-frame sampling then never skips a
     * source frame), floored at 30 and capped at 60 (encoder sanity). Pure
     * function of the model; unit-tested.
     */
    fun timingDriverFps(project: Project): Int {
        var fps = maxOf(project.fps, 30)
        fun absorb(asset: MediaAsset?) {
            if (asset != null && !asset.isImage && asset.fps > 0f) {
                fps = maxOf(fps, kotlin.math.ceil(asset.fps).toInt().coerceAtLeast(1))
            }
        }
        project.clips.forEach { absorb(project.assetById(it.assetId)) }
        project.pips.forEach { absorb(project.assetById(it.assetId)) }
        return fps.coerceAtMost(60)
    }

    /**
     * v1.2.5: the transparent CFR sequence that occupies compositor slot 0
     * so the MAIN BODY is never retimed to an overlay layer's frame grid.
     * One full-length transparent pad at [fps] (see class doc + the pad item
     * builder). Export-only; the preview path never builds this.
     */
    private fun buildTimingDriverSequence(
        project: Project,
        mediaDir: File,
        totalDurationUs: Long,
        fps: Int,
    ): EditedMediaItemSequence? {
        if (totalDurationUs <= 0L) return null
        return EditedMediaItemSequence.Builder(
            listOf(buildTransparentPadItem(project, mediaDir, totalDurationUs, fps))
        ).build()
    }

    /**
     * Fully transparent still covering [padUs] — the video analog of the
     * audio silence pad, used by the crossfade overlay sequence so its fading
     * heads land at the right global positions (v1.2.1), and by the export
     * timing driver (v1.2.5). Structural like the
     * PiP pads: its AlphaScale(0)+Presentation stay on even in degraded
     // compatibility preview, otherwise it would flash black.
     *
     * @param fps frame rate the image item generates frames at (the timing
     *   driver needs this ≥ every source fps; pad/heads callers keep the
     *   project fps default).
     */
    private fun buildTransparentPadItem(
        project: Project,
        mediaDir: File,
        padUs: Long,
        fps: Int = project.fps.toInt().coerceAtLeast(1),
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
            .setFrameRate(fps.coerceAtLeast(1))
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
     * One sequence per PiP layer, padded to FULL timeline length:
     *   [transparent pad 0 -> startUs] + [layer media, clipped]
     *   + [transparent pad endUs -> totalDurationUs]
     *
     * v1.2.2: the layer renders into a FULL-CANVAS frame (PipPresentation
     * outputs the canvas size with transparent surround), and the sequence
     * must run the whole timeline because it may sit in slot 0 of the
     * composition (the primary sequence, whose duration drives the preview)
     * and because the compositor needs frames from every input at all times.
     * The pads reuse the black-PNG generator at a tiny 2×2 size, fully
     * transparent (AlphaScale 0), stretched to the canvas. The layer item
     * carries the PipPresentation matrix for position/scale; its own audio
     * (if any) is kept and mixed automatically by the Composition.
     */
    private fun buildPipSequence(
        project: Project,
        mediaDir: File,
        pip: PipLayer,
        totalDurationUs: Long,
    ): EditedMediaItemSequence? {
        val asset = project.assetById(pip.assetId) ?: return null
        val file = File(mediaDir, asset.fileName)
        if (!file.exists()) return null

        val items = mutableListOf<EditedMediaItem>()

        if (pip.startUs >= MIN_GAP_US) {
            items += buildTransparentPadItem(project, mediaDir, pip.startUs)
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

        // Tail pad to full timeline length (v1.2.2) — the sequence may be the
        // composition's primary (duration driver) and the compositor needs a
        // frame from every input at every primary timestamp.
        val tailUs = totalDurationUs - (pip.startUs + pip.durationUs)
        if (tailUs >= MIN_GAP_US) {
            items += buildTransparentPadItem(project, mediaDir, tailUs)
        }

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
