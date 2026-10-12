package com.RooMCutNext.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.RooMCutNext.core.model.Clip
import com.RooMCutNext.core.model.MediaAsset
import com.RooMCutNext.core.model.PipLayer
import com.RooMCutNext.core.model.Project
import com.RooMCutNext.export.CompositionBuilder
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * View-layer preview engine for PiP layers and crossfade heads (v1.2.3).
 *
 * WHY this exists — Media3 1.11.1's CompositionPlayer cannot preview more
 * than ONE video sequence reliably:
 *  - seeking a multi-video-sequence composition kills playback outright
 *    (androidx/media #2439 / #3058 — "Seeking is currently not supported for
 *    multiple video sequences like PiP and Grid view");
 *  - DefaultVideoCompositor stops producing output while ANY input's frame
 *    queue is empty, e.g. at every pad→video item boundary inside a padded
 *    PiP sequence (androidx/media #2742 — permanent freeze).
 * An editor cannot live without seek/scrub, so the preview keeps a SINGLE
 * video sequence (the body — the code path that has worked since v1.0.0) and
 * everything that would have been a second video sequence (PiP layers,
 * crossfade heads) is composited here, in the view hierarchy, on top of the
 * PlayerView:
 *
 *   [PlayerView: body composition]  ← bottom
 *   [head view]                     ← crossfade top layer, alpha ramp
 *   [PiP views in list order]       ← later-added = higher, like export
 *
 * Export is unaffected: the Transformer composition still composites the
 * same layers through the GL multi-input path (a SUPPORTED upstream path —
 * the bugs above are CompositionPlayer-specific).
 *
 * WYSIWYG contract: the geometry below mirrors PipPresentation exactly —
 * displayWidth = scale × canvasWidth, aspect from the asset's DISPLAY
 * dimensions, center at (xRatio, yRatio) with y pointing DOWN (UI
 * convention; PipPresentation flips it for GL). Heads use the canvas-fit
 * rect of the rotated+cropped content aspect — the same result the export
 * chain produces with ScaleAndRotate → Crop → canvas Presentation.
 *
 * Position sync: [sync] is called by the ViewModel at the UI-state rate
 * (≈30Hz while playing, on user actions while paused) with the timeline
 * playhead; each overlay maps it to its own local window
 * (local = playhead − windowStart) and follows play/pause. Seeks are
 * drift-thresholded so steady playback does not spam the small players.
 *
 * Known preview approximations (documented, deliberate):
 *  - crossfade AUDIO ramp (clip fadeIn) plays at constant volume;
 *  - text overlays do not render ON the fading head (they render on the
 *    body, which carries them right after the window);
 *  - "save frame" grabs the PlayerView only (PiPs are separate views).
 */
class OverlayPreviewController(private val context: Context) {

    /** Overlay host (a FrameLayout layer on top of the PlayerView). */
    private var layer: FrameLayout? = null
    private var layerW = 0
    private var layerH = 0
    private var canvasW = 0
    private var canvasH = 0

    /** Re-seek an overlay player only when its drift exceeds this. */
    private val seekDriftThresholdUs = 120_000L

    /** Minimum drift to seek a PAUSED overlay (scrubbing) — under a frame. */
    private val scrubDriftThresholdUs = 20_000L

    /** Mount a head this far BEFORE its window so it is ready to fade in. */
    private val headPrerollUs = 400_000L

    /** One record per PiP layer (video → TextureView+player, image → ImageView). */
    private class PipOverlay {
        var textureView: TextureView? = null
        var imageView: ImageView? = null
        var player: ExoPlayer? = null
        var assetId: String? = null
        var windowStartUs = -1L
        var windowDurationUs = -1L
        var firstFrameRendered = false
        val views: List<View> get() = listOfNotNull(textureView, imageView)
    }

    private val pipOverlays = LinkedHashMap<String, PipOverlay>()

    // ---- crossfade head slot ----
    private var headView: TextureView? = null
    private var headImageView: ImageView? = null
    private var headPlayer: ExoPlayer? = null
    private var headIsImage = false
    private var headSpec: CompositionBuilder.HeadOverlaySpec? = null
    private var headEffectsSig = 0
    private var headFirstFrame = false
    private var headContentAspect = 1f

    /** Decoded sticker bitmaps, keyed by file path. */
    private val bitmapCache = ConcurrentHashMap<String, Bitmap>()
    private val bitmapFileStamps = ConcurrentHashMap<String, Long>()

    private var currentProject: Project? = null

    /** Last sync arguments — replayed when the host view is re-attached. */
    private class SyncArgs(
        val project: Project,
        val mediaDir: File,
        val playheadUs: Long,
        val isPlaying: Boolean,
    )

    private var lastSyncArgs: SyncArgs? = null

    // ------------------------------------------------------------------
    // Attachment
    // ------------------------------------------------------------------

    /**
     * Bind the overlay host layer. The layer must sit on top of the
     * PlayerView, fill the same bounds, and stay in the hierarchy for the
     * whole editor session. If the host is re-created mid-session (screen
     * rotation, recomposition), the last sync is replayed once laid out so
     * the overlays re-appear even when no state change follows.
     */
    fun attach(host: FrameLayout) {
        detachViews()
        layer = host
        host.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            layerW = right - left
            layerH = bottom - top
            layoutOverlays()
        }
        layerW = host.width
        layerH = host.height
        if (lastSyncArgs != null) {
            host.post {
                lastSyncArgs?.let { sync(it.project, it.mediaDir, it.playheadUs, it.isPlaying) }
            }
        }
    }

    fun detach() = detachViews()

    private fun detachViews() {
        releaseHeadPlayer()
        pipOverlays.values.forEach(::releasePip)
        pipOverlays.clear()
        layer?.removeAllViews()
        layer = null
    }

    // ------------------------------------------------------------------
    // Sync — called by the ViewModel on every relevant state change
    // ------------------------------------------------------------------

    fun sync(project: Project, mediaDir: File, playheadUs: Long, isPlaying: Boolean) {
        if (layer == null) return
        lastSyncArgs = SyncArgs(project, mediaDir, playheadUs, isPlaying)
        currentProject = project
        if (canvasW != project.width || canvasH != project.height) {
            canvasW = project.width
            canvasH = project.height
        }

        reconcilePips(project, mediaDir)
        reconcileHead(project, mediaDir, playheadUs)

        pipOverlays.forEach { (id, overlay) ->
            val pip = project.pips.firstOrNull { it.id == id } ?: return@forEach
            drivePip(pip, overlay, playheadUs, isPlaying)
        }
        driveHead(playheadUs, isPlaying)

        layoutOverlays()
    }

    // ------------------------------------------------------------------
    // PiP reconciliation (add / remove / rewindow)
    // ------------------------------------------------------------------

    private fun reconcilePips(project: Project, mediaDir: File) {
        val host = layer ?: return

        // Drop overlays whose layer was deleted.
        val liveIds = project.pips.mapTo(mutableSetOf()) { it.id }
        pipOverlays.keys.filter { it !in liveIds }.forEach { id ->
            pipOverlays.remove(id)?.let { overlay ->
                releasePip(overlay)
                overlay.views.forEach(host::removeView)
            }
        }

        project.pips.forEach { pip ->
            val asset = project.assetById(pip.assetId) ?: return@forEach
            val file = File(mediaDir, asset.fileName)
            if (!file.exists()) return@forEach

            val existing = pipOverlays[pip.id]
            if (existing == null) {
                val overlay = buildPipOverlay(asset, file, pip)
                pipOverlays[pip.id] = overlay
                // Z-order: add order = export stacking (later PiP = higher).
                overlay.views.forEach(host::addView)
            } else if (
                existing.windowStartUs != pip.startUs ||
                existing.windowDurationUs != pip.durationUs ||
                existing.assetId != pip.assetId
            ) {
                existing.windowStartUs = pip.startUs
                existing.windowDurationUs = pip.durationUs
                if (existing.assetId != pip.assetId) {
                    // Different asset: rebuild views + player entirely.
                    releasePip(existing)
                    existing.views.forEach(host::removeView)
                    val fresh = buildPipOverlay(asset, file, pip)
                    existing.textureView = fresh.textureView
                    existing.imageView = fresh.imageView
                    existing.player = fresh.player
                    existing.assetId = fresh.assetId
                    existing.firstFrameRendered = false
                    existing.views.forEach(host::addView)
                } else if (existing.player != null) {
                    // Same asset, window moved/trimmed: just re-clip.
                    reclipPipPlayer(existing.player!!, asset, file, pip)
                    existing.firstFrameRendered = false
                }
            }
        }
    }

    private fun buildPipOverlay(asset: MediaAsset, file: File, pip: PipLayer): PipOverlay {
        val overlay = PipOverlay()
        overlay.assetId = pip.assetId
        overlay.windowStartUs = pip.startUs
        overlay.windowDurationUs = pip.durationUs
        if (asset.isImage) {
            val iv = ImageView(context).apply {
                // Bounds carry the exact aspect (see place()).
                scaleType = ImageView.ScaleType.FIT_XY
                visibility = View.INVISIBLE
                setImageBitmap(decodedSticker(file))
            }
            overlay.imageView = iv
        } else {
            val tv = TextureView(context).apply { visibility = View.INVISIBLE }
            overlay.textureView = tv
            val player = ExoPlayer.Builder(context).build()
            player.setVideoTextureView(tv)
            player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    overlay.firstFrameRendered = true
                    tv.post { if (tv.isAttachedToWindow) tv.visibility = View.VISIBLE }
                }
            })
            overlay.player = player
            preparePipPlayer(player, asset, file, pip)
        }
        return overlay
    }

    /** PiP source window: [0, min(duration, asset duration)] — same clip as export. */
    private fun pipClipEndUs(asset: MediaAsset, pip: PipLayer): Long =
        if (asset.isImage) pip.durationUs else pip.durationUs.coerceAtMost(asset.durationUs)

    private fun preparePipPlayer(player: ExoPlayer, asset: MediaAsset, file: File, pip: PipLayer) {
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .apply {
                if (!asset.isImage) {
                    setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionUs(0L)
                            .setEndPositionUs(pipClipEndUs(asset, pip).coerceAtLeast(1L))
                            .build(),
                    )
                } else {
                    setImageDurationMs((pipClipEndUs(asset, pip) / 1000L).coerceAtLeast(1L))
                }
            }
            .build()
        player.setMediaItem(item, 0L)
        player.volume = 1f
        player.playWhenReady = false
        player.prepare()
    }

    private fun reclipPipPlayer(player: ExoPlayer, asset: MediaAsset, file: File, pip: PipLayer) {
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .apply {
                if (!asset.isImage) {
                    setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionUs(0L)
                            .setEndPositionUs(pipClipEndUs(asset, pip).coerceAtLeast(1L))
                            .build(),
                    )
                } else {
                    setImageDurationMs((pipClipEndUs(asset, pip) / 1000L).coerceAtLeast(1L))
                }
            }
            .build()
        player.setMediaItem(item, 0L)
        player.prepare()
    }

    private fun releasePip(overlay: PipOverlay) {
        overlay.player?.run { runCatching { release() } }
        overlay.player = null
        overlay.textureView = null
        overlay.imageView = null
    }

    private fun drivePip(pip: PipLayer, overlay: PipOverlay, playheadUs: Long, isPlaying: Boolean) {
        val localUs = playheadUs - overlay.windowStartUs
        val inWindow = localUs in 0 until overlay.windowDurationUs
        val player = overlay.player

        if (player == null) {
            // Sticker: visibility + opacity only.
            overlay.imageView?.let { iv ->
                iv.visibility = if (inWindow) View.VISIBLE else View.INVISIBLE
                iv.alpha = pip.opacity.coerceIn(0f, 1f)
            }
            return
        }

        val tv = overlay.textureView ?: return
        if (!inWindow) {
            if (player.playWhenReady) player.playWhenReady = false
            tv.visibility = View.INVISIBLE
            overlay.firstFrameRendered = false
            return
        }
        tv.visibility = if (overlay.firstFrameRendered) View.VISIBLE else View.INVISIBLE
        tv.alpha = pip.opacity.coerceIn(0f, 1f)

        // Drift-corrected follow. STATE_ENDED is deliberately NOT seeked: the
        // TextureView keeps presenting the last decoded frame, which is the
        // correct "hold" behavior when the window outlives the source (the
        // GL compositor holds the input's last frame in export the same way),
        // and re-seeking an ended player at sync rate would spin the decoder.
        if (player.playbackState != Player.STATE_ENDED) {
            val posUs = player.currentPosition * 1000L
            val drift = localUs - posUs
            val threshold = if (isPlaying) seekDriftThresholdUs else scrubDriftThresholdUs
            if (kotlin.math.abs(drift) > threshold) {
                runCatching { player.seekTo((localUs / 1000L).coerceAtLeast(0L)) }
            }
        }
        if (isPlaying && !player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
            player.playWhenReady = true
        } else if (!isPlaying && player.playWhenReady) {
            player.playWhenReady = false
        }
    }

    // ------------------------------------------------------------------
    // Crossfade head (the fading top layer of a transition)
    // ------------------------------------------------------------------

    private fun reconcileHead(project: Project, mediaDir: File, playheadUs: Long) {
        val host = layer ?: return
        val specs = CompositionBuilder.headOverlaySpecs(project)

        val active = specs.firstOrNull { playheadUs >= it.startUs && playheadUs < it.startUs + it.durationUs }
            ?: specs.filter { playheadUs >= it.startUs - headPrerollUs && playheadUs < it.startUs + it.durationUs }
                .minByOrNull { it.startUs }

        if (active == null) {
            // Keep the last head mounted but hidden (cheap scrub back/forth).
            headView?.visibility = View.INVISIBLE
            headImageView?.visibility = View.INVISIBLE
            headPlayer?.let { if (it.playWhenReady) it.playWhenReady = false }
            return
        }

        val clip = project.clips.firstOrNull { it.id == active.clipId } ?: return
        val asset = project.assetById(active.assetId) ?: return
        val file = File(mediaDir, asset.fileName)
        if (!file.exists()) return

        val effectsSig = clip.lookSignature()
        if (headPlayer != null || headImageView != null) {
            if (headSpec != active || headEffectsSig != effectsSig) {
                releaseHeadPlayer()
            }
        }

        if (headPlayer == null && headImageView == null) {
            headSpec = active
            headEffectsSig = effectsSig
            headFirstFrame = false
            headContentAspect = contentAspectOf(clip, asset)

            if (asset.isImage) {
                // Image head: a fading still, canvas-fitted.
                val iv = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_XY
                    visibility = View.INVISIBLE
                    setImageBitmap(decodedSticker(file))
                }
                headImageView = iv
                headIsImage = true
                host.addView(iv, 0) // index 0: above the body, below all PiPs
            } else {
                val tv = TextureView(context).apply { visibility = View.INVISIBLE }
                val player = ExoPlayer.Builder(context).build()
                player.setVideoTextureView(tv)
                player.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        headFirstFrame = true
                        tv.post { if (tv.isAttachedToWindow) tv.visibility = View.VISIBLE }
                    }
                })
                // Look effects (rotate/flip, crop, grading, LUT, mosaic) — the
                // same list the composition's head item carries. Positioning
                // is the TextureView's job (canvas-fit of the content aspect).
                val effects: List<Effect> = CompositionBuilder.clipLookEffects(clip, stripEffects = false)
                if (effects.isNotEmpty()) runCatching { player.setVideoEffects(effects) }
                if (active.speed != 1f) player.setPlaybackSpeed(active.speed)
                player.volume = if (active.muted) 0f else active.volume.coerceIn(0f, 1f)
                val item = MediaItem.Builder()
                    .setUri(Uri.fromFile(file))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionUs(active.sourceInUs)
                            .setEndPositionUs(active.sourceOutUs.coerceAtLeast(active.sourceInUs + 1L))
                            .build(),
                    )
                    .build()
                player.setMediaItem(item, 0L)
                player.playWhenReady = false
                player.prepare()
                headPlayer = player
                headView = tv
                headIsImage = false
                host.addView(tv, 0)
            }
        }
    }

    private fun driveHead(playheadUs: Long, isPlaying: Boolean) {
        val spec = headSpec ?: run {
            headView?.visibility = View.INVISIBLE
            headImageView?.visibility = View.INVISIBLE
            return
        }
        val localUs = playheadUs - spec.startUs
        val inWindow = localUs in 0 until spec.durationUs
        // Alpha ramp 0→1 across the window — the crossfade itself.
        val ramp = if (spec.durationUs <= 0L) 1f
        else (localUs.toFloat() / spec.durationUs).coerceIn(0f, 1f)

        if (headIsImage) {
            headImageView?.let { iv ->
                iv.visibility = if (inWindow) View.VISIBLE else View.INVISIBLE
                iv.alpha = ramp
            }
            return
        }

        val player = headPlayer ?: return
        val tv = headView ?: return
        if (!inWindow) {
            if (player.playWhenReady) player.playWhenReady = false
            tv.visibility = View.INVISIBLE
            headFirstFrame = false
            return
        }
        tv.visibility = if (headFirstFrame) View.VISIBLE else View.INVISIBLE
        tv.alpha = ramp

        if (player.playbackState != Player.STATE_ENDED) {
            val posUs = player.currentPosition * 1000L
            val drift = localUs - posUs
            val threshold = if (isPlaying) seekDriftThresholdUs else scrubDriftThresholdUs
            if (kotlin.math.abs(drift) > threshold) {
                runCatching { player.seekTo((localUs / 1000L).coerceAtLeast(0L)) }
            }
        }
        if (isPlaying && !player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
            player.playWhenReady = true
        } else if (!isPlaying && player.playWhenReady) {
            player.playWhenReady = false
        }
    }

    private fun releaseHeadPlayer() {
        headPlayer?.run { runCatching { release() } }
        headPlayer = null
        headView?.let { layer?.removeView(it) }
        headView = null
        headImageView?.let { layer?.removeView(it) }
        headImageView = null
        headSpec = null
        headFirstFrame = false
        headIsImage = false
    }

    // ------------------------------------------------------------------
    // Geometry — mirrors PipPresentation / canvas-fit semantics
    // ------------------------------------------------------------------

    private fun layoutOverlays() {
        if (layerW <= 0 || layerH <= 0 || canvasW <= 0 || canvasH <= 0) return

        // Content rect: aspect-fit of the canvas inside the layer — the exact
        // rect PlayerView's RESIZE_MODE_FIT gives the composition frame.
        val canvasAspect = canvasW.toFloat() / canvasH
        val layerAspect = layerW.toFloat() / layerH
        val contentW: Int
        val contentH: Int
        val contentLeft: Int
        val contentTop: Int
        if (layerAspect > canvasAspect) {
            contentH = layerH
            contentW = (layerH * canvasAspect).toInt()
            contentLeft = (layerW - contentW) / 2
            contentTop = 0
        } else {
            contentW = layerW
            contentH = (layerW / canvasAspect).toInt()
            contentLeft = 0
            contentTop = (layerH - contentH) / 2
        }

        // Place a view whose content has aspect [contentAspect] so that its
        // DISPLAY WIDTH is displayWRatio × contentW, centered at the given
        // canvas ratios (y down) — exactly PipPresentation's matrix.
        fun place(
            view: View,
            centerXRatio: Float,
            centerYRatio: Float,
            displayWRatio: Float,
            contentAspect: Float,
            alpha: Float,
        ) {
            val displayW = (displayWRatio.coerceIn(0.02f, 1f) * contentW).toInt().coerceAtLeast(1)
            val displayH = (displayW / contentAspect.coerceAtLeast(0.01f)).toInt().coerceAtLeast(1)
            val cx = contentLeft + centerXRatio.coerceIn(0f, 1f) * contentW
            val cy = contentTop + centerYRatio.coerceIn(0f, 1f) * contentH
            val lp = view.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(displayW, displayH)
            lp.width = displayW
            lp.height = displayH
            lp.leftMargin = (cx - displayW / 2f).toInt()
            lp.topMargin = (cy - displayH / 2f).toInt()
            view.layoutParams = lp
            view.alpha = alpha
        }

        // Canvas-fit (SCALE_TO_FIT of [contentAspect] inside the canvas,
        // centered) — the rect the export chain's canvas Presentation makes.
        fun placeCanvasFit(view: View, contentAspect: Float, alpha: Float) {
            val canvasAspectF = canvasW.toFloat() / canvasH
            val displayWRatio = if (contentAspect >= canvasAspectF) 1f else contentAspect / canvasAspectF
            place(view, 0.5f, 0.5f, displayWRatio, contentAspect, alpha)
        }

        currentProject?.let { project ->
            project.pips.forEach { pip ->
                val overlay = pipOverlays[pip.id] ?: return@forEach
                val asset = project.assetById(pip.assetId) ?: return@forEach
                val aspect = if (asset.height > 0) asset.width.toFloat() / asset.height else 1f
                val view = overlay.textureView ?: overlay.imageView ?: return@forEach
                place(view, pip.xRatio, pip.yRatio, pip.scale, aspect, pip.opacity.coerceIn(0f, 1f))
            }
        }

        if (headIsImage || headPlayer != null) {
            val hv = headView ?: headImageView
            hv?.let { placeCanvasFit(it, headContentAspect, it.alpha) }
        }
    }

    /**
     * Display content aspect of a clip after rotation and crop — the aspect
     * the export chain's ScaleAndRotate → Crop stages produce.
     */
    private fun contentAspectOf(clip: Clip, asset: MediaAsset): Float {
        val rotated = clip.rotationDeg == 90 || clip.rotationDeg == 270
        var w = (if (rotated) asset.height else asset.width).toFloat()
        var h = (if (rotated) asset.width else asset.height).toFloat()
        clip.crop?.let { c ->
            w *= (1f - c.left - c.right).coerceAtLeast(0.05f)
            h *= (1f - c.bottom - c.top).coerceAtLeast(0.05f)
        }
        return if (h > 0f) w / h else 1f
    }

    private fun Clip.lookSignature(): Int {
        var r = rotationDeg
        r = 31 * r + if (flipH) 1 else 0
        r = 31 * r + if (flipV) 1 else 0
        r = 31 * r + (crop?.hashCode() ?: 0)
        r = 31 * r + adjust.hashCode()
        r = 31 * r + mosaics.hashCode()
        return 31 * r + speed.hashCode()
    }

    // ------------------------------------------------------------------
    // Sticker decoding (bounded to ~512px long edge — preview only)
    // ------------------------------------------------------------------

    private fun decodedSticker(file: File): Bitmap? {
        val stamp = file.lastModified()
        val cached = bitmapCache[file.path]
        if (cached != null && !cached.isRecycled && bitmapFileStamps[file.path] == stamp) {
            return cached
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 512 || bounds.outHeight / (sample * 2) >= 512) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        bitmapCache[file.path] = bmp
        bitmapFileStamps[file.path] = stamp
        return bmp
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    fun release() {
        detachViews()
        bitmapCache.values.forEach { runCatching { it.recycle() } }
        bitmapCache.clear()
        bitmapFileStamps.clear()
        currentProject = null
    }
}
