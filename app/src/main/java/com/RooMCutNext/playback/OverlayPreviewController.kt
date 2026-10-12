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
 * v1.2.4 — TWO preview bugs fixed here, both verified against framework
 * source (AOSP) and decompiled media3 1.11.1:
 *
 * 1. "PiP layer invisible but its audio plays" was a DEADLOCK created by
 *    gating visibility on the first rendered frame. A TextureView creates
 *    its SurfaceTexture inside draw() → getTextureLayer() (AOSP), and
 *    INVISIBLE views are never drawn — so a TextureView born INVISIBLE never
 *    becomes available, ExoPlayerImpl.setVideoTextureView() then parks the
 *    video renderer with a null output (setVideoOutputInternal(null)) and
 *    onRenderedFirstFrame() can never fire; our visibility gate then kept
 *    the view INVISIBLE forever. Audio needs no surface — hence "audio
 *    plays but no picture". Fix: overlay TextureViews are VISIBLE from
 *    birth with setOpaque(false) and gate their CONTENT with alpha (alpha-0
 *    views still draw, so the surface forms and frames render).
 *
 * 2. "Main video stutters while a PiP exists" was a 30Hz requestLayout
 *    storm: View.setLayoutParams() ALWAYS calls requestLayout() (no
 *    equality check in AOSP), and layoutOverlays() assigned layoutParams on
 *    every sync tick — a full measure/layout/draw traversal ~30×/s of the
 *    whole stage (PlayerView included). Fix: place() now compares before
 *    assigning, and the steady-state sync performs ZERO view mutations and
 *    ZERO filesystem calls (reconcile* touch File only on build/reclip;
 *    headOverlaySpecs is memoized by project identity).
 *
 * Known preview approximations (documented, deliberate):
 *  - crossfade AUDIO ramp (clip fadeIn) plays at constant volume;
 *  - text overlays do not render ON the fading head (they render on the
 *    body, which carries them right after the window);
 *  - "save frame" grabs the PlayerView only (PiPs are separate views);
 *  - v1.2.5→v1.2.8: if this device's effects-playback path stalls on a head
 *    (no first frame 450ms after MOUNT — typically still preroll, where the
 *    swap is invisible), heads fall back to a PLAIN player for the rest of
 *    the session — the fade renders without the clip's grading/mosaic for
 *    those ≤2s (geometry stays aspect-correct);
 *  - v1.2.8: a playing forward window exit fades out over a 200ms TAIL
 *    (HeadWindowMath) that covers the body player's item-boundary crossing;
 *    the composite during the tail is the incoming clip over itself at a
 *    ≤200ms offset — imperceptible, and the price of never flashing the
 *    outgoing clip.
 */
class OverlayPreviewController(private val context: Context) {

    /** Overlay host (a FrameLayout layer on top of the PlayerView). */
    private var layer: FrameLayout? = null
    private var layerW = 0
    private var layerH = 0
    private var canvasW = 0
    private var canvasH = 0

    /** Optional diagnostic sink (overlay player events → DIAG overlay). */
    var onDiag: ((String) -> Unit)? = null

    private fun diag(line: String) {
        android.util.Log.i("RooMCutNextOverlay", "DIAG $line")
        onDiag?.invoke(line)
    }

    /** Re-seek an overlay player only when its drift exceeds this. */
    private val seekDriftThresholdUs = 120_000L

    /** Minimum drift to seek a PAUSED overlay (scrubbing) — under a frame. */
    private val scrubDriftThresholdUs = 20_000L

    /** Mount a head this far BEFORE its window so it is ready to fade in. */
    private val headPrerollUs = 400_000L

    /** v1.2.7: an ENDED overlay player is only "held" (last frame kept)
     * while the playhead is this close to, or past, its source span end —
     * further back it rewinds and plays again (scrub-back / replay). */
    private val endedHoldMarginUs = 100_000L

    /**
     * v1.2.8: fade-out tail after a FORWARD window exit (playing only).
     *
     * The flash bug this fixes: at the window end the head's alpha used to
     * drop 1 → 0 in one tick, handing the picture to the body player at the
     * exact instant it crosses its "previous clip tail → second clip body"
     * item boundary. That crossing is not free — the next period's decoder
     * may re-initialize, the GL effects chain re-configures, and the
     * player's REPORTED position leads its DISPLAYED frame by 1-2 vsyncs —
     * so for a handful of frames the body still showed the OUTGOING clip's
     * last frame, full screen, head gone: "the first clip's picture
     * suddenly flashes once". Fading the head (its held frame is the second
     * clip's own content at the window end) across this tail covers the
     * crossing; a paused playhead skips the tail so frame-stepping stays
     * sample-accurate. See HeadWindowMath.
     */
    private val headHoldTailUs = 200_000L

    /**
     * v1.2.8: cooldown between CORRECTIVE seeks of a playing head player.
     *
     * The lag bug this fixes: drift-correction used to re-seek whenever the
     * player fell >120ms behind. A seek flushes the decoder and re-decodes
     * from the previous keyframe — the player emerges from the seek FARTHER
     * behind, the next tick seeks again: a seek-storm cycle that read as
     * "the second clip is choppy during the transition". Now: at most one
     * corrective seek per cooldown, landing [headSeekHysteresisUs] BEHIND
     * the playhead so the player is not instantly re-eligible, and hard
     * realignment (no cooldown) only for big jumps (> [headHardRealignDriftUs],
     * e.g. a seek that landed mid-window).
     */
    private val headCorrectiveSeekCooldownMs = 500L

    /** v1.2.8: land corrective seeks this far BEHIND the playhead. */
    private val headSeekHysteresisUs = 60_000L

    /** v1.2.8: drift big enough to hard-realign immediately, cooldown aside. */
    private val headHardRealignDriftUs = 400_000L

    /**
     * v1.2.8: wall-clock grace before an effects-bearing head that STILL
     * hasn't rendered falls back to a plain player — measured from MOUNT,
     * not from window entry (the old localUs > 250ms trigger fired the
     * rebuild a quarter-second INTO the fade: the player release + fresh
     * alpha-0 view visibly flashed the underlying first clip and then had
     * to catch up = both halves of the user-visible bug). 450ms from mount
     * lands the rebuild in the PREROLL for normal mounts, where alpha is 0
     * anyway and the swap is invisible.
     */
    private val headEffectsGraceWallMs = 450L

    /** One record per PiP layer (video → TextureView+player, image → ImageView). */
    private class PipOverlay {
        var textureView: TextureView? = null
        var imageView: ImageView? = null
        var player: ExoPlayer? = null
        var assetId: String? = null
        var windowStartUs = -1L
        var windowDurationUs = -1L

        /** v1.2.7: source span the player is clipped to (window-relative) —
         * an ENDED player only stays held while the playhead is past this. */
        var sourceSpanUs = Long.MAX_VALUE

        var firstFrameRendered = false

        /** Alpha to apply the moment the first frame renders (kept fresh by
         * drivePip so the post() in onRenderedFirstFrame uses current data). */
        var targetAlpha = 0f

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

    /** v1.2.5: the mounted head player currently carries clipLookEffects. */
    private var headEffectsActive = false

    /** v1.2.8: uptime millis when the current head player was mounted —
     * anchors the effects-fallback grace in WALL time so the (invisible)
     * rebuild can happen during preroll instead of mid-fade. */
    private var headMountedAtMs = 0L

    /** v1.2.8: uptime millis of the last CORRECTIVE seek on the head player
     * (see headCorrectiveSeekCooldownMs). */
    private var headLastCorrectiveSeekMs = 0L

    /**
     * v1.2.5: set when an effects-bearing head fails to render within the
     * grace window — the DEVICE's effects playback path (ExoPlayer
     * setVideoEffects → PlaybackVideoGraphWrapper) is then treated as
     * broken for the session and every head rebuilds as a plain player
     * (like the proven PiP path). Sticky: one failed attempt costs one
     * broken transition window, so it is not retried per head.
     */
    private var headEffectsFallback = false

    /** Set by driveHead to force reconcileHead to rebuild the head player. */
    private var headNeedsRebuild = false

    /** Alpha to apply when the head's first frame renders (see PipOverlay.targetAlpha). */
    private var headTargetAlpha = 0f

    /** Memoized headOverlaySpecs — keyed by Project identity; during playback
     * the state flow re-emits the SAME immutable instance ~30×/s. */
    private var headSpecsFor: Project? = null
    private var headSpecsCache: List<CompositionBuilder.HeadOverlaySpec> = emptyList()

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

        // Drop overlays whose layer was deleted. Capture the views BEFORE
        // releasePip() — it nulls the view fields, so the old code removed
        // NOTHING and left zombie views in the stage forever (latent bug
        // that became visible the moment layers started rendering, v1.2.4).
        val liveIds = project.pips.mapTo(mutableSetOf()) { it.id }
        pipOverlays.keys.filter { it !in liveIds }.forEach { id ->
            pipOverlays.remove(id)?.let { overlay ->
                val views = overlay.views
                releasePip(overlay)
                views.forEach(host::removeView)
            }
        }

        project.pips.forEach { pip ->
            val asset = project.assetById(pip.assetId) ?: return@forEach
            val existing = pipOverlays[pip.id]
            val assetChanged = existing != null && existing.assetId != pip.assetId
            val windowChanged = existing != null && !assetChanged &&
                (existing.windowStartUs != pip.startUs || existing.windowDurationUs != pip.durationUs)

            // v1.2.4: the steady-state path (nothing changed — the case at
            // ~30Hz during playback) must not touch the filesystem AT ALL;
            // the old code ran File.exists() (a stat syscall on the main
            // thread) for every PiP on every sync tick.
            if (existing == null || assetChanged || windowChanged) {
                val file = File(mediaDir, asset.fileName)
                if (!file.exists()) return@forEach

                if (existing == null) {
                    val overlay = buildPipOverlay(asset, file, pip)
                    pipOverlays[pip.id] = overlay
                    // Z-order: add order = export stacking (later PiP = higher).
                    overlay.views.forEach(host::addView)
                } else if (assetChanged) {
                    // Different asset: rebuild views + player entirely.
                    // (Views captured before releasePip — see zombie note above.)
                    val views = existing.views
                    releasePip(existing)
                    views.forEach(host::removeView)
                    val fresh = buildPipOverlay(asset, file, pip)
                    existing.textureView = fresh.textureView
                    existing.imageView = fresh.imageView
                    existing.player = fresh.player
                    existing.assetId = fresh.assetId
                    existing.windowStartUs = fresh.windowStartUs
                    existing.windowDurationUs = fresh.windowDurationUs
                    existing.sourceSpanUs = fresh.sourceSpanUs
                    existing.firstFrameRendered = false
                    existing.targetAlpha = 0f
                    existing.views.forEach(host::addView)
                } else {
                    // Same asset, window moved/trimmed: just re-clip.
                    existing.windowStartUs = pip.startUs
                    existing.windowDurationUs = pip.durationUs
                    existing.sourceSpanUs = pipClipEndUs(asset, pip).coerceAtLeast(1L)
                    existing.player?.let { reclipPipPlayer(it, asset, file, pip) }
                    existing.firstFrameRendered = false
                    existing.targetAlpha = 0f
                }
            }
        }
    }

    private fun buildPipOverlay(asset: MediaAsset, file: File, pip: PipLayer): PipOverlay {
        val overlay = PipOverlay()
        overlay.assetId = pip.assetId
        overlay.windowStartUs = pip.startUs
        overlay.windowDurationUs = pip.durationUs
        overlay.sourceSpanUs = pipClipEndUs(asset, pip).coerceAtLeast(1L)
        if (asset.isImage) {
            val iv = ImageView(context).apply {
                // Bounds carry the exact aspect (see place()).
                scaleType = ImageView.ScaleType.FIT_XY
                visibility = View.INVISIBLE
                setImageBitmap(decodedSticker(file))
            }
            overlay.imageView = iv
        } else {
            // v1.2.4: VISIBLE from birth with zero alpha. A TextureView
            // creates its SurfaceTexture in draw() (AOSP getTextureLayer),
            // and INVISIBLE views are never drawn — a TextureView born
            // INVISIBLE never becomes available, ExoPlayer parks its video
            // renderer with a null output, onRenderedFirstFrame never fires
            // and the old "show only after the first frame" visibility gate
            // deadlocked the layer into permanent invisibility (audio kept
            // playing). Alpha-0 views ARE still drawn, so the surface forms
            // and frames render; setOpaque(false) keeps the not-yet-rendered
            // state transparent instead of black.
            val tv = TextureView(context).apply {
                setOpaque(false)
                alpha = 0f
            }
            overlay.textureView = tv
            val player = ExoPlayer.Builder(context).build()
            player.setVideoTextureView(tv)
            player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    overlay.firstFrameRendered = true
                    tv.post { if (tv.isAttachedToWindow) tv.alpha = overlay.targetAlpha }
                }

                // v1.2.5: overlay players used to fail SILENTLY (no error
                // listener) — an invisible layer with no hint why. Surface
                // it in the DIAG overlay so field reports are diagnosable.
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    diag("pip ERR ${error.errorCodeName}: ${(error.message ?: "").take(60)}")
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
        // v1.2.4: the TextureView stays VISIBLE forever — its CONTENT is
        // gated with alpha (see buildPipOverlay). Toggling visibility was
        // the deadlock: before the first frame the view must be drawn so
        // the SurfaceTexture forms and the renderer has an output.
        if (!inWindow) {
            if (player.playWhenReady) player.playWhenReady = false
            overlay.targetAlpha = 0f
            if (tv.alpha != 0f) tv.alpha = 0f
            overlay.firstFrameRendered = false
            return
        }
        overlay.targetAlpha = pip.opacity.coerceIn(0f, 1f)
        // v1.2.5: alpha follows the layer opacity DIRECTLY — no first-frame
        // gate. A TextureView with setOpaque(false) composites as fully
        // transparent until its first buffer arrives, so raising alpha early
        // is visually identical to staying hidden but can never DEADLOCK on
        // a late/missing first frame (the old `if (firstFrameRendered)` gate
        // was one missed callback away from an invisible layer).
        val desired = overlay.targetAlpha
        if (tv.alpha != desired) tv.alpha = desired

        // v1.2.7: drift-correct ONLY a READY player, and rewind an ENDED
        // player whose playhead has gone back before its source span.
        //
        // The old unconditional seek had two freeze bugs:
        //  1) re-seeking a BUFFERING player at the ~30Hz sync rate restarts
        //     its preparation every tick — a seek-storm livelock that left
        //     the picture frozen on whatever frame rendered before the storm;
        //  2) STATE_ENDED skipped seeking entirely, so after the playhead
        //     once passed the window (or a scrub-through), re-entering it
        //     showed the player's held LAST frame forever — "the second
        //     clip is static". An ended player CAN be seeked back to life:
        //     seekTo() exits ENDED and (with playWhenReady) resumes playing.
        //     Holding the last frame stays correct near/past the span end,
        //     which is also the desired "window outlives source" behavior.
        when (player.playbackState) {
            Player.STATE_READY -> {
                val posUs = player.currentPosition * 1000L
                val drift = localUs - posUs
                val threshold = if (isPlaying) seekDriftThresholdUs else scrubDriftThresholdUs
                if (kotlin.math.abs(drift) > threshold) {
                    runCatching { player.seekTo((localUs / 1000L).coerceAtLeast(0L)) }
                }
            }
            Player.STATE_ENDED -> {
                if (localUs + endedHoldMarginUs < overlay.sourceSpanUs) {
                    runCatching { player.seekTo((localUs / 1000L).coerceAtLeast(0L)) }
                    if (isPlaying) player.playWhenReady = true
                }
            }
            else -> Unit // BUFFERING/IDLE: leave it be; it catches up once READY
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

    /** headOverlaySpecs is a pure function of the clips — memoized so the
     * ~30Hz sync during playback does not recompute it (the state flow
     * re-emits the SAME immutable Project instance while only the playhead
     * changes). */
    private fun headSpecsOf(project: Project): List<CompositionBuilder.HeadOverlaySpec> {
        if (headSpecsFor !== project) {
            headSpecsCache = CompositionBuilder.headOverlaySpecs(project)
            headSpecsFor = project
        }
        return headSpecsCache
    }

    private fun reconcileHead(project: Project, mediaDir: File, playheadUs: Long) {
        val host = layer ?: return
        val specs = headSpecsOf(project)

        val active = specs.firstOrNull { playheadUs >= it.startUs && playheadUs < it.startUs + it.durationUs }
            ?: specs.filter { playheadUs >= it.startUs - headPrerollUs && playheadUs < it.startUs + it.durationUs }
                .minByOrNull { it.startUs }

        if (active == null) {
            // v1.2.8: HOLD-TAIL — the playhead just left this window forward
            // (while playing). Keep the mounted head alive through its
            // fade-out tail: driveHead is still fading it over the body
            // player's item-boundary crossing, and yanking the view here
            // (the old unconditional hide) would re-create exactly the
            // outgoing-clip flash the tail exists to mask. Position-only
            // check — a paused playhead skips the tail in driveHead and
            // hides via the path below.
            val mounted = headSpec
            if (mounted != null && playheadUs >= mounted.startUs + mounted.durationUs &&
                playheadUs < mounted.startUs + mounted.durationUs + headHoldTailUs
            ) {
                return
            }
            // Keep the last head mounted but hidden (cheap scrub back/forth).
            // v1.2.4: alpha, not visibility — same TextureView rule as PiPs.
            headView?.let { if (it.alpha != 0f) it.alpha = 0f }
            headImageView?.let { if (it.visibility != View.INVISIBLE) it.visibility = View.INVISIBLE }
            headPlayer?.let { if (it.playWhenReady) it.playWhenReady = false }
            return
        }

        // Mounted and unchanged → nothing to do: no File allocation, no
        // exists() stat syscall on this ~30Hz hot path (v1.2.4).
        // v1.2.5: headNeedsRebuild forces a rebuild (effects→plain fallback).
        val effectsSig = project.clips.firstOrNull { it.id == active.clipId }?.lookSignature() ?: 0
        if (!headNeedsRebuild && headSpec == active && headEffectsSig == effectsSig &&
            (headPlayer != null || headImageView != null)
        ) {
            return
        }

        val clip = project.clips.firstOrNull { it.id == active.clipId } ?: return
        val asset = project.assetById(active.assetId) ?: return
        val file = File(mediaDir, asset.fileName)
        if (!file.exists()) return

        if (headPlayer != null || headImageView != null) {
            releaseHeadPlayer()
        }
        headNeedsRebuild = false

        headSpec = active
        headEffectsSig = effectsSig
        headFirstFrame = false
        headTargetAlpha = 0f
        headContentAspect = contentAspectOf(clip, asset)
        // v1.2.8: anchor the effects-fallback grace to MOUNT time (wall
        // clock) — see headEffectsGraceWallMs.
        headMountedAtMs = android.os.SystemClock.uptimeMillis()
        headLastCorrectiveSeekMs = 0L

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
            // v1.2.4: VISIBLE from birth + alpha gating (same deadlock fix
            // as the PiP layers — see buildPipOverlay).
            val tv = TextureView(context).apply {
                setOpaque(false)
                alpha = 0f
            }
            val player = ExoPlayer.Builder(context).build()
            player.setVideoTextureView(tv)
            player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    headFirstFrame = true
                    diag("head firstframe ✓")
                    tv.post { if (tv.isAttachedToWindow) tv.alpha = headTargetAlpha }
                }

                // v1.2.5: silent failures made transitions invisible with no
                // trace — surface overlay-player errors in the DIAG overlay.
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    diag("head ERR ${error.errorCodeName}: ${(error.message ?: "").take(60)}")
                }
            })
            // Look effects (rotate/flip, crop, grading, LUT, mosaic) — the
            // same list the composition's head item carries. Positioning
            // is the TextureView's job (canvas-fit of the content aspect).
            //
            // v1.2.5: once the device's effects-playback path (setVideoEffects
            // → PlaybackVideoGraphWrapper) has proven unable to render a head
            // in time, heads rebuild PLAIN — byte-for-byte the proven PiP
            // player path — so a transition never stays invisible for a
            // cosmetic grading pass. See headEffectsFallback.
            val effects: List<Effect> = if (headEffectsFallback) {
                emptyList()
            } else {
                CompositionBuilder.clipLookEffects(clip, stripEffects = false)
            }
            headEffectsActive = effects.isNotEmpty()
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

    private fun driveHead(playheadUs: Long, isPlaying: Boolean) {
        val spec = headSpec ?: run {
            headView?.let { if (it.alpha != 0f) it.alpha = 0f }
            headImageView?.let { if (it.visibility != View.INVISIBLE) it.visibility = View.INVISIBLE }
            return
        }
        val localUs = playheadUs - spec.startUs
        val inWindow = localUs in 0 until spec.durationUs
        // v1.2.8: forward fade-out tail (playing only) — see HeadWindowMath.
        val inTail = HeadWindowMath.inTail(localUs, spec.durationUs, headHoldTailUs, isPlaying)
        // Alpha ramp 0→1 across the window — the crossfade itself.
        val ramp = if (spec.durationUs <= 0L) 1f
        else (localUs.toFloat() / spec.durationUs).coerceIn(0f, 1f)

        if (headIsImage) {
            headImageView?.let { iv ->
                iv.visibility = if (inWindow || inTail) View.VISIBLE else View.INVISIBLE
                iv.alpha = if (inTail) {
                    HeadWindowMath.alphaAt(localUs, spec.durationUs, headHoldTailUs, isPlaying)
                } else ramp
            }
            return
        }

        val player = headPlayer ?: return
        val tv = headView ?: return
        // v1.2.4: alpha gating (VISIBLE view, never toggled) — same fix as PiP.
        if (!inWindow && !inTail) {
            if (player.playWhenReady) player.playWhenReady = false
            headTargetAlpha = 0f
            if (tv.alpha != 0f) tv.alpha = 0f
            headFirstFrame = false
            return
        }
        if (inTail) {
            // v1.2.8: coasting out. Fade the head's (held or still-playing)
            // frame across the tail — its content IS the incoming clip at the
            // window end, so it seamlessly covers the body player's item
            // boundary crossing underneath. No pauses, no drift correction,
            // no ENDED rewind: alignment is over, only the alpha matters
            // here. A corrective seek in the tail would clamp to the item end
            // and cause a visible hiccup at mid-alpha.
            val tailAlpha = HeadWindowMath.alphaAt(localUs, spec.durationUs, headHoldTailUs, isPlaying)
            headTargetAlpha = tailAlpha
            if (tv.alpha != tailAlpha) tv.alpha = tailAlpha
            return
        }
        // v1.2.5: effects-grace fallback — v1.2.8 measures the grace in WALL
        // time from MOUNT (not localUs): a stalled effects path is detected
        // during preroll, where the plain-player rebuild is INVISIBLE, instead
        // of a quarter-second into the fade where the release + fresh alpha-0
        // view flashed the underlying first clip and then had to catch up.
        if (headEffectsActive && !headFirstFrame && !headEffectsFallback) {
            val sinceMountMs = android.os.SystemClock.uptimeMillis() - headMountedAtMs
            if (sinceMountMs > headEffectsGraceWallMs) {
                headEffectsFallback = true
                headNeedsRebuild = true
                diag("head effects→plain (no first frame ${sinceMountMs}ms after mount)")
            }
        }
        headTargetAlpha = ramp
        // v1.2.5: alpha follows the ramp DIRECTLY — no first-frame gate (same
        // reasoning as drivePip: an unrendered TextureView is transparent, so
        // the fade can never deadlock on a missing first-frame callback).
        if (tv.alpha != ramp) tv.alpha = ramp

        // v1.2.7: position math is SPEED-AWARE (the clipped player advances
        // at spec.speed × wall time while the playhead advances 1×) and the
        // seek discipline matches drivePip:
        //  - drift-correct ONLY a READY player (re-seeking a BUFFERING one
        //    every ~30Hz sync tick restarts its preparation — the seek-storm
        //    livelock that froze the head on the frame it managed to render
        //    during preroll: "only the first clip fades, the second is
        //    static");
        //  - REWIND an ENDED player when the playhead has gone back before
        //    its source span (scrub back / replay after one pass) — the old
        //    blanket ENDED skip showed the held last frame under the ramping
        //    alpha forever, which read as "the second clip never plays".
        //
        // v1.2.8: WHILE PLAYING, corrective seeks are additionally rate-
        // limited (cooldown + hysteresis landing) — the old "seek whenever
        // drift > 120ms" looped: every seek flushes the decoder and re-decodes
        // from the previous keyframe, so the player emerged from each seek
        // FARTHER behind and was instantly re-eligible → a seek-storm that
        // read as "the second clip is choppy during the transition". Paused
        // (scrub) tracking keeps the old tight behavior: seeks are cheap and
        // frame-accurate when nothing is rendering.
        val expectedPosUs = (localUs * spec.speed).toLong()
        when (player.playbackState) {
            Player.STATE_READY -> {
                val posUs = player.currentPosition * 1000L
                val drift = expectedPosUs - posUs
                val threshold = if (isPlaying) seekDriftThresholdUs else scrubDriftThresholdUs
                if (kotlin.math.abs(drift) > threshold) {
                    val nowMs = android.os.SystemClock.uptimeMillis()
                    val hardRealign = !isPlaying ||
                        kotlin.math.abs(drift) > headHardRealignDriftUs
                    if (hardRealign || nowMs - headLastCorrectiveSeekMs >= headCorrectiveSeekCooldownMs) {
                        val targetUs = if (isPlaying) {
                            // Land slightly BEHIND the playhead: below the
                            // drift threshold, so the player is not instantly
                            // re-eligible for another seek.
                            (expectedPosUs - headSeekHysteresisUs).coerceAtLeast(0L)
                        } else {
                            expectedPosUs
                        }
                        runCatching { player.seekTo((targetUs / 1000L).coerceAtLeast(0L)) }
                        if (isPlaying) headLastCorrectiveSeekMs = nowMs
                    }
                }
            }
            Player.STATE_ENDED -> {
                val spanUs = spec.sourceOutUs - spec.sourceInUs
                if (expectedPosUs + endedHoldMarginUs < spanUs) {
                    runCatching { player.seekTo((expectedPosUs / 1000L).coerceAtLeast(0L)) }
                    if (isPlaying) player.playWhenReady = true
                }
            }
            else -> Unit // BUFFERING/IDLE: let it finish preparing on its own
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
        //
        // v1.2.4: compare-before-assign. View.setLayoutParams() ALWAYS calls
        // requestLayout() (AOSP has no equality check), and this function
        // runs at the ~30Hz sync rate — the old unconditional assignment
        // triggered a full measure/layout/draw traversal of the whole stage
        // (PlayerView included) ~30×/s while playing, which is what starved
        // the main video pipeline whenever a PiP layer existed.
        //
        // alpha is deliberately NOT touched here: the drive functions own
        // it (first-frame gating), and place() used to overwrite the
        // pre-first-frame 0f with the layer opacity.
        fun place(
            view: View,
            centerXRatio: Float,
            centerYRatio: Float,
            displayWRatio: Float,
            contentAspect: Float,
        ) {
            val displayW = (displayWRatio.coerceIn(0.02f, 1f) * contentW).toInt().coerceAtLeast(1)
            val displayH = (displayW / contentAspect.coerceAtLeast(0.01f)).toInt().coerceAtLeast(1)
            val cx = contentLeft + centerXRatio.coerceIn(0f, 1f) * contentW
            val cy = contentTop + centerYRatio.coerceIn(0f, 1f) * contentH
            val left = (cx - displayW / 2f).toInt()
            val top = (cy - displayH / 2f).toInt()
            val lp = view.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(displayW, displayH)
            if (lp.width != displayW || lp.height != displayH ||
                lp.leftMargin != left || lp.topMargin != top
            ) {
                lp.width = displayW
                lp.height = displayH
                lp.leftMargin = left
                lp.topMargin = top
                view.layoutParams = lp
            }
        }

        // Canvas-fit (SCALE_TO_FIT of [contentAspect] inside the canvas,
        // centered) — the rect the export chain's canvas Presentation makes.
        fun placeCanvasFit(view: View, contentAspect: Float) {
            val canvasAspectF = canvasW.toFloat() / canvasH
            val displayWRatio = if (contentAspect >= canvasAspectF) 1f else contentAspect / canvasAspectF
            place(view, 0.5f, 0.5f, displayWRatio, contentAspect)
        }

        currentProject?.let { project ->
            project.pips.forEach { pip ->
                val overlay = pipOverlays[pip.id] ?: return@forEach
                val asset = project.assetById(pip.assetId) ?: return@forEach
                val aspect = if (asset.height > 0) asset.width.toFloat() / asset.height else 1f
                val view = overlay.textureView ?: overlay.imageView ?: return@forEach
                place(view, pip.xRatio, pip.yRatio, pip.scale, aspect)
            }
        }

        if (headIsImage || headPlayer != null) {
            val hv = headView ?: headImageView
            hv?.let { placeCanvasFit(it, headContentAspect) }
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
