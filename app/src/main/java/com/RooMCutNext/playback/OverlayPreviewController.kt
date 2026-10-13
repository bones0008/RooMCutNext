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
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import com.RooMCutNext.core.model.Clip
import com.RooMCutNext.core.model.MediaAsset
import com.RooMCutNext.core.model.PipLayer
import com.RooMCutNext.core.model.Project
import com.RooMCutNext.core.model.TextOverlayItem
import com.RooMCutNext.core.util.TextSignatures
import com.RooMCutNext.export.CompositionBuilder
import com.RooMCutNext.export.effects.TimedTextOverlay
import com.RooMCutNext.export.effects.TransitionEffect
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
 *  - v1.2.10: text overlays DO render on the fading head now (see the
 *    v1.2.10 note below) — the remaining approximation is IMAGE heads (a
 *    fading still has no effects pipeline): text still dips there;
 *  - "save frame" grabs the PlayerView only (PiPs are separate views);
 *  - v1.2.5→v1.2.8: if this device's effects-playback path stalls on a head
 *    (no first frame 450ms after MOUNT — typically still preroll, where the
 *    swap is invisible), heads fall back to a PLAIN player for the rest of
 *    the session — the fade renders without the clip's grading/mosaic for
 *    those ≤2s (geometry stays aspect-correct);
 *  - v1.2.8: a playing forward window exit fades out over a 400ms TAIL
 *    (HeadWindowMath) that covers the body player's item-boundary crossing;
 *    the composite during the tail is the incoming clip over itself at a
 *    ≤400ms offset — imperceptible, and the price of never flashing the
 *    outgoing clip. (Was 200ms: on devices where the body's decoder re-init
 *    at the crossing takes longer than that, the outgoing clip's last frame
 *    leaked through full-screen for a few frames after the tail expired —
 *    "the first clip's picture flashes once right after the transition".)
 *
 *  v1.2.10 — three preview defects fixed in this controller:
 *
 *  1. TEXT FADED WITH THE TRANSITION. Text overlays render as GL effects
 *     baked into the BODY composition, so the fading head COVERED the text
 *     during the window — "the transition fades my text away too", while
 *     the export (which bakes the same texts into the head ITEM as well:
 *     two identical copies crossfading at the same spot compose to CONSTANT
 *     visibility, head*alpha + body*(1-alpha) = text) was correct. The head
 *     PLAYER now bakes the same TimedTextOverlay effects, with windows
 *     mapped into its LOCAL position space (HeadTextWindows — plain-ExoPlayer
 *     effect timestamps are player positions, verified against media3 1.11.1
 *     source) plus the canvas Presentation first when the source aspect
 *     differs, mirroring CompositionBuilder.buildClipItem's chain order.
 *
 *  2. EXIT FLASH SURVIVED THE TAIL. The tail engages on isPlaying — but at
 *     the item boundary the CompositionPlayer briefly drops to BUFFERING and
 *     reports isPlaying=false at the EXACT instant the tail is needed: the
 *     tail disengaged, the head vanished in one tick over the body still
 *     showing the outgoing clip's last frame — the flash the tail existed to
 *     prevent (it was dead code for its own target scenario). sync() now
 *     receives PLAY INTENT (playWhenReady, stable across buffering) instead
 *     of the flickering actual-isPlaying — and because the position poll
 *     freezes with the stalled body, the playhead freezes mid-tail and the
 *     head holds its coverage until the crossing actually completes.
 *
 *  3. STILL-CHOPPY HEAD + CHOPPY AUDIO-BEARING PiPs. Both were corrective-seek
 *     cycles below their thresholds (see the v1.2.10 notes in driveHead and
 *     drivePip): any (re)start latency the threshold can't absorb re-arms the
 *     cooldown-gated seek forever — the metronome. Head hard-realign is now
 *     1.5s (beyond any decoder/effects spin-up), and drivePip got the same
 *     discipline v1.2.9 gave driveHead (400ms + cooldown while playing).
 *
 * v1.4.0 — TWO changes:
 *
 *  1. PiP hard-realign gate 400ms → 1.5s, moved into PipSyncPolicy (pure,
 *     unit-tested). The 400ms gate was still inside the restart-latency
 *     range when BGM + clip audio + PiP audio are ALL active (the audio
 *     pipeline opens slower with three sources): the drift parked above
 *     the gate and the cooldown re-fired the seek metronome — the random
 *     "PiP stutters / everything freezes but sound keeps going / plays
 *     fine the second time" field reports. See PipSyncPolicyTest.
 *
 *  2. TRANSITIONS RE-ENABLED (UI entries restored in EditorScreen +
 *     TimelineView). The v1.3.1 concealment waited for a redesign; the
 *     转场开发技术参考 audit confirmed this controller already IS the
 *     reference architecture: overlapping timeline model (v1.2.1), dual
 *     players with view-layer alpha blend (the reference's "TextureView B
 *     over TextureView A" dissolve), preroll warm-up of the incoming
 *     player (headPrerollUs, reference prescribes 300-500ms), text baked
 *     into BOTH layers (constant composite — global text never fades with
 *     the clip), and an untouched export path.
 *
 * v1.5.0 — THE FULL TRANSITION CATALOG (the reference's phase 5, GPU
 * shader transitions). The head player's effect chain now ends with the
 * SAME TransitionEffect the export head item bakes (wipe/slide/zoom/spin/
 * blur/mosaic/flash/blinds/iris/clock/through-color — see TransitionStyle),
 * so preview and export render pixel-identical transitions by construction.
 * Timestamps: the head is a PLAIN ExoPlayer whose GL effects receive
 * SOURCE-clock positions, so the effect is constructed with fadeUs = the
 * head's source span (T*speed) and globalStartUs = -1 — TransitionMath's
 * item-local window then maps source time onto timeline progress.
 *
 * View alpha is now SPLIT by path: while the transition shader is mounted
 * the shader owns the head's per-pixel alpha and the TextureView sits at
 * alpha 1 in-window (driveHead); the view-layer ramp remains for the two
 * shader-less fallbacks — image heads (an ImageView has no pipeline) and
 * the effects-fallback session mode (headEffectsFallback) — where any
 * style degrades to the classic dissolve ramp. The post-window tail
 * (HeadWindowMath) still uses the view alpha on every path: at tail time
 * the shader is at progress 1 (opaque) and the fade-out is the view's job.
 *
 * v1.5.1 — the transition shader is mounted with premultipliedAlpha = true
 * (see reconcileHead): this view's SurfaceTexture composites premultiplied,
 * and the straight-alpha output v1.5.0 shipped made every style preview as
 * the same ghosted crossfade while exports stayed correct (field report:
 * "选不同的转场并没有变化，导出以后都正常"). Known remaining approximation:
 * IMAGE heads still use the view-layer ramp (an ImageView cannot run the
 * shader); slide/zoom/spin could be view-animated later if image-clip
 * transitions ever need WYSIWYG.
 */
class OverlayPreviewController(private val context: Context) {

    /**
     * v1.3.0: PREVIEW-ONLY live text source — same contract as
     * PlaybackController.liveTextLookup. The head player's baked
     * TimedTextOverlays read the CURRENT item state every frame, so text
     * visual edits follow live while a crossfade head is mounted, WITHOUT
     * the head rebuild v1.2.10 needed for the same effect.
     */
    var liveTextLookup: ((id: String) -> TextOverlayItem?)? = null

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

    // v1.4.0: the PiP corrective-seek thresholds moved into PipSyncPolicy
    // (pure, unit-tested — see PipSyncPolicyTest for the metronome
    // regression this extraction locks down). The constants below that the
    // HEAD path still shares delegate to the policy so there is ONE source
    // of truth per number.

    /** Minimum drift to seek a PAUSED overlay (scrubbing) — under a frame. */
    private val scrubDriftThresholdUs = PipSyncPolicy.SCRUB_DRIFT_US

    /** Mount a head this far BEFORE its window so it is ready to fade in. */
    private val headPrerollUs = 400_000L

    /** v1.2.7: an ENDED overlay player is only "held" (last frame kept)
     * while the playhead is this close to, or past, its source span end —
     * further back it rewinds and plays again (scrub-back / replay). */
    private val endedHoldMarginUs = PipSyncPolicy.ENDED_HOLD_MARGIN_US

    /**
     * v1.2.8→v1.2.9: fade-out tail after a FORWARD window exit (playing only).
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
     *
     * v1.2.9: 200ms → 400ms. On devices where the body's decoder re-init +
     * effects reconfigure at the crossing exceeds 200ms, the outgoing clip's
     * last frame leaked through FULL-SCREEN for the frames after the tail
     * expired — a shorter re-run of the same flash ("right when the 2
     * seconds end, the first clip's last frame pops for one blink"). The
     * composite during the tail remains the incoming clip over itself at a
     * ≤400ms offset — still imperceptible.
     */
    private val headHoldTailUs = 400_000L

    /**
     * v1.2.9: cooldown between HARD realign seeks of a playing head player
     * (see the v1.2.9 note in driveHead — ordinary drift is no longer seeked
     * at all while playing, so this only bounds the genuinely-broken case).
     */
    private val headCorrectiveSeekCooldownMs = 500L

    /**
     * v1.2.9→v1.2.10: drift big enough to hard-realign a PLAYING head
     * (cooldown aside). Below this a constant lag is deliberately left
     * alone — see the v1.2.9 note in driveHead.
     *
     * v1.2.10: 400ms → 1.5s. v1.2.9's 400ms was still INSIDE the (re)start
     * latency range of heavy sources (a 4K clip's decoder + effects-graph
     * spin-up can exceed 400ms), so on those devices the cooldown-gated
     * hard-realign re-armed on every cooldown expiry — the 500ms seek
     * metronome SURVIVED the v1.2.9 fix ("the transition is still choppy").
     * Every seek re-opens the same start latency, so any threshold a restart
     * can exceed never settles; 1.5s is beyond any realistic spin-up, while
     * a genuinely broken alignment (a mid-window USER seek, seconds off)
     * still realigns — exactly once, and then stays settled.
     */
    private val headHardRealignDriftUs = 1_500_000L

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

        /** v1.2.10: uptime millis of the last hard-realign seek of this
         * player (see pipCorrectiveSeekCooldownMs). */
        var lastCorrectiveSeekMs = 0L

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
                // v1.2.10 — WHILE PLAYING, moderate drift is DELIBERATELY LEFT
                // ALONE. The old blanket 120ms rule seek-stormed any PiP whose
                // source file carries an AUDIO track: ExoPlayer paces video on
                // the audio clock, and the track opens with a ~100-250ms start
                // latency during which the reported position FREEZES. A silent
                // file opens no audio track, never crossed the threshold and
                // played smooth — exactly the split field reports showed.
                //
                // v1.4.0 — the gate lives in PipSyncPolicy now, at 1.5s (was
                // 400ms). With BGM + clip audio + PiP audio ALL active, the
                // PiP's audio pipeline opens slower than the single-source
                // 250ms ceiling — the constant drift parked above 400ms and
                // every 500ms cooldown expiry re-fired a corrective seek,
                // each one re-opening the decoder + audio latency it was
                // trying to fix: the PiP stuttered on a 500ms metronome and
                // the seek bursts also starved the body pipeline ("everything
                // freezes, sound keeps going"; warm second plays dipped under
                // the threshold and looked fine). Same fix the head got in
                // v1.2.10: a constant sub-1.5s lag has no visual reference
                // (the layer's own audio stays in sync with its own video),
                // so it settles instead of being fought. Paused (scrub)
                // tracking keeps the tight sub-frame behavior.
                if (PipSyncPolicy.shouldCorrectiveSeek(
                        driftAbsUs = kotlin.math.abs(drift),
                        isPlaying = isPlaying,
                        nowMs = android.os.SystemClock.uptimeMillis(),
                        lastSeekMs = overlay.lastCorrectiveSeekMs,
                    )
                ) {
                    runCatching { player.seekTo((localUs / 1000L).coerceAtLeast(0L)) }
                    overlay.lastCorrectiveSeekMs = android.os.SystemClock.uptimeMillis()
                    if (isPlaying) diag("pip hard-realign ${drift / 1000}ms")
                }
            }
            Player.STATE_ENDED -> {
                if (PipSyncPolicy.endedRewind(localUs, overlay.sourceSpanUs)) {
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
        // v1.3.1: PiP-track mute — applied live on every sync (the toggle
        // never rebuilds anything). preparePipPlayer keeps volume at 1 so
        // this is the single owner of the value once the layer exists.
        val pipVolume = if (currentProject?.pipTrackMuted == true) 0f else 1f
        if (player.volume != pipVolume) player.volume = pipVolume
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
        // v1.2.10: the signature also covers the texts overlapping the
        // window — editing a text while the head is mounted now rebuilds the
        // player so the baked text overlay follows (see headTextEffectsFor).
        val effectsSig = headEffectsSignature(project, active)
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
        // v1.2.10: TEXT ON THE HEAD + canvas Presentation — see the class doc.
        // The same texts the export bakes into the head ITEM are baked into
        // the head PLAYER here (local windows, HeadTextWindows), preceded by
        // the canvas-fit Presentation when the source aspect differs so the
        // overlay canvas maps to the project canvas — the exact effect-chain
        // order CompositionBuilder.buildClipItem uses for head/body items.
        // With the Presentation in the chain the head's OUTPUT frame is
        // canvas-sized, so the view must fill the whole canvas rect instead
        // of the content-aspect fit rect.
        val textEffects: List<Effect> = headTextEffectsFor(project, active, asset)
        val canvasPresentationInChain = textEffects.isNotEmpty() &&
            headNeedsCanvasPresentation(project, clip, asset)
        headContentAspect = if (canvasPresentationInChain) {
            project.width.toFloat() / project.height
        } else {
            contentAspectOf(clip, asset)
        }
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
            //
            // v1.2.10: + the overlapping texts (local windows) and, when
            // needed, the canvas Presentation ahead of them — chain order
            // mirrors buildClipItem: look → canvas fit → text.
            //
            // v1.5.0: + the TRANSITION itself, same chain tail as the export
            // head item (look → canvas → text → transition). fadeUs is the
            // head's SOURCE span (durationUs × speed) because plain-player
            // effect timestamps run on the source clock; globalStartUs = -1
            // disables the global window so only the item-local one rules.
            // While this is mounted the shader owns the head's alpha and
            // driveHead keeps the view at 1 in-window (see the class doc).
            val lookEffects: List<Effect> = if (headEffectsFallback) {
                emptyList()
            } else {
                CompositionBuilder.clipLookEffects(clip, stripEffects = false)
            }
            val effects: List<Effect> = buildList {
                addAll(lookEffects)
                if (canvasPresentationInChain) {
                    add(
                        Presentation.createForWidthAndHeight(
                            project.width, project.height, Presentation.LAYOUT_SCALE_TO_FIT,
                        )
                    )
                }
                addAll(textEffects)
                // v1.5.0: the transition shader. SKIPPED in the fallback
                // session (headEffectsFallback) — a device whose effects
                // path is broken gets the plain player + the view-layer
                // dissolve ramp instead (driveHead's shaderOwnsAlpha=false).
                //
                // v1.5.1: premultipliedAlpha = TRUE — this player's output
                // goes straight into the TextureView's SurfaceTexture, which
                // Android HWUI composites PREMULTIPLIED; a straight-alpha
                // buffer made every position-stable style read as a ghosted
                // dissolve (B at full brightness wherever alpha > 0, A
                // bleeding through). The EXPORT head item keeps straight
                // alpha because DefaultVideoCompositor blends GL_SRC_ALPHA
                // — see TransitionEffect's v1.5.1 section.
                if (!headEffectsFallback) {
                    add(
                        TransitionEffect(
                            active.type,
                            (active.sourceOutUs - active.sourceInUs).coerceAtLeast(1L),
                            -1L,
                            premultipliedAlpha = true,
                        )
                    )
                }
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

    // ------------------------------------------------------------------
    // v1.2.10: head text baking (pure helpers)
    // ------------------------------------------------------------------

    /**
     * The TimedTextOverlay effects for a head window — the preview half of
     * the export's "bake the text into the head item too" behavior (see the
     * class doc). Windows are mapped into the head player's LOCAL position
     * space via [HeadTextWindows]: a plain ExoPlayer's GL effects receive
     * PLAYER-POSITION timestamps (media3 1.11.1, source-verified), while the
     * composition path the body uses receives GLOBAL timestamps — the mapping
     * bridges the two.
     *
     * Empty when the device's effects path already fell back to plain
     * players ([headEffectsFallback]) or the head is an image (a fading
     * still has no effects pipeline — text dips on image-head transitions,
     * documented approximation).
     */
    private fun headTextEffectsFor(
        project: Project,
        active: CompositionBuilder.HeadOverlaySpec,
        asset: MediaAsset,
    ): List<Effect> {
        if (headEffectsFallback || asset.isImage) return emptyList()
        val spanUs = (active.sourceOutUs - active.sourceInUs).coerceAtLeast(1L)
        val overlays = overlappingHeadTexts(project, active).mapNotNull { text ->
            HeadTextWindows.localWindowUs(
                textStartUs = text.startUs,
                textEndUs = text.endUs,
                windowStartUs = active.startUs,
                windowDurationUs = active.durationUs,
                speed = active.speed,
                sourceSpanUs = spanUs,
            )?.let { local ->
                TimedTextOverlay(
                    item = text,
                    windowStartUs = local.startUs,
                    windowEndUs = local.endUs,
                    liveTransform = liveTextLookup?.let { lookup ->
                        { lookup(text.id) }
                    },
                )
            }
        }
        // Same shader-sampler cap the composition path uses.
        return overlays.chunked(CompositionBuilder.MAX_OVERLAYS_PER_EFFECT)
            .map { chunk -> OverlayEffect(chunk) }
    }

    /** Texts whose global windows intersect a head's window, start-ordered. */
    private fun overlappingHeadTexts(
        project: Project,
        active: CompositionBuilder.HeadOverlaySpec,
    ): List<TextOverlayItem> =
        project.texts
            .filter { it.startUs < active.startUs + active.durationUs && it.endUs > active.startUs }
            .sortedBy { it.startUs }

    /**
     * Whether the head's effect chain needs the canvas-fit Presentation in
     * front of its text overlays — the same aspect-delta condition
     * CompositionBuilder.buildClipItem applies (look effects run in SOURCE
     * space; text is positioned in CANVAS space, so a differing aspect needs
     * the Presentation to bridge them).
     */
    private fun headNeedsCanvasPresentation(project: Project, clip: Clip, asset: MediaAsset): Boolean {
        if (asset.width <= 0 || asset.height <= 0) return false
        val rotatedQuarter = clip.rotationDeg == 90 || clip.rotationDeg == 270
        val srcW = if (rotatedQuarter) asset.height else asset.width
        val srcH = if (rotatedQuarter) asset.width else asset.height
        val aspectDelta = kotlin.math.abs(
            srcW.toDouble() / srcH - project.width.toDouble() / project.height,
        )
        return aspectDelta > 1e-4
    }

    /**
     * Mount signature of the head's effect chain: the clip's look effects
     * (as before) plus the texts overlapping the window plus the transition
     * STYLE (v1.5.0), so editing any of them while mounted triggers a
     * rebuild.
     */
    private fun headEffectsSignature(project: Project, active: CompositionBuilder.HeadOverlaySpec): Int {
        val lookSig = project.clips.firstOrNull { it.id == active.clipId }?.lookSignature() ?: 0
        // v1.3.0: WINDOWS ONLY (TextSignatures) — visual text edits render
        // live via liveTextLookup and must not churn the head player anymore.
        val textSig = TextSignatures.windows(overlappingHeadTexts(project, active))
        // v1.5.0: the style selects the shader branch — a type change must
        // rebuild the player to remount the effect.
        return 31 * (31 * lookSig + textSig) + active.type.hashCode()
    }

    /**
     * v1.3.2: re-render the head player's CURRENT frame while paused.
     *
     * Head windows can carry baked TimedTextOverlays too (headTextEffectsFor)
     * that read the live project state per output frame — the same
     * paused-staleness PlaybackController.nudgeRedraw fixes for the body.
     * Same-position seek, same throttle; no-op while playing, while no head
     * is mounted, or when the head is an IMAGE (an ImageView has no pipeline
     * to nudge — it re-lays out on the next sync tick anyway).
     */
    private var lastHeadNudgeMs = 0L

    fun nudgeHeadRedraw(force: Boolean = false) {
        if (headIsImage) return
        val p = headPlayer ?: return
        if (p.isPlaying) return
        val state = p.playbackState
        if (state != Player.STATE_READY && state != Player.STATE_ENDED) return
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - lastHeadNudgeMs < 66L) return
        lastHeadNudgeMs = now
        runCatching { p.seekTo(p.currentPosition) }
    }

    private fun driveHead(playheadUs: Long, isPlaying: Boolean) {
        val spec = headSpec ?: run {
            headView?.let { if (it.alpha != 0f) it.alpha = 0f }
            headImageView?.let { if (it.visibility != View.INVISIBLE) it.visibility = View.INVISIBLE }
            return
        }

        /** v1.5.0: view alpha in-window — 1 while the transition shader
         * owns the head's per-pixel alpha, the dissolve ramp otherwise. */
        fun viewAlphaFor(ramp: Float): Float = if (headEffectsActive) 1f else ramp
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
        headTargetAlpha = viewAlphaFor(ramp)
        // v1.2.5: alpha follows the ramp DIRECTLY — no first-frame gate (same
        // reasoning as drivePip: an unrendered TextureView is transparent, so
        // the fade can never deadlock on a missing first-frame callback).
        //
        // v1.5.0: with the transition shader mounted the SHADER owns the
        // head's per-pixel alpha (masks, coverage, ramps — see the class
        // doc), so the view sits at 1 in-window and the GL output composites
        // directly over the body. The classic view-layer ramp survives only
        // for the shader-less fallbacks: image heads (this branch is video-
        // only; images use the ImageView path above) and the effects-
        // fallback session, where headEffectsActive is false and every style
        // degrades to the dissolve ramp.
        if (tv.alpha != headTargetAlpha) tv.alpha = headTargetAlpha

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
        // v1.2.9 — WHILE PLAYING, moderate drift is DELIBERATELY LEFT ALONE.
        // v1.2.8 rate-limited the old "seek whenever drift > 120ms" rule
        // (cooldown + hysteresis landing) instead of removing it, and the
        // result was still a self-sustaining hiccup cycle locked to the
        // cooldown: ExoPlayer never time-compresses to close a position gap,
        // so a player that started ~200ms late (normal spin-up latency at
        // window entry) sits at a CONSTANT ~200ms lag — permanently over the
        // 120ms threshold. Every cooldown expiry re-seeked; each seek
        // re-decoded from a keyframe, re-opened the lag and visibly froze
        // the picture for its decoder restart: "the second clip stutters
        // exactly every 500ms" (4 hiccups in a 2s window — the cooldown WAS
        // the frame clock; dragging the timeline was smooth because the
        // paused path tracks tightly with no cooldown at all). During a 2s
        // crossfade a constant sub-400ms lag has no visual reference to
        // betray it (the fading layer is brand-new content — ±200ms of WHICH
        // frame of it is showing is invisible), and the head's audio skew is
        // buried under the outgoing clip's audio. The seeks were the only
        // visible harm. So while playing, ONLY a genuinely broken alignment
        // (> [headHardRealignDriftUs], e.g. after a seek landed mid-window)
        // hard-realigns, cooldown-gated. Paused (scrub) tracking keeps the
        // old tight behavior: seeks are cheap and frame-accurate when
        // nothing is rendering.
        val expectedPosUs = (localUs * spec.speed).toLong()
        when (player.playbackState) {
            Player.STATE_READY -> {
                val posUs = player.currentPosition * 1000L
                val drift = expectedPosUs - posUs
                if (!isPlaying) {
                    if (kotlin.math.abs(drift) > scrubDriftThresholdUs) {
                        runCatching { player.seekTo((expectedPosUs / 1000L).coerceAtLeast(0L)) }
                    }
                } else if (kotlin.math.abs(drift) > headHardRealignDriftUs) {
                    val nowMs = android.os.SystemClock.uptimeMillis()
                    if (nowMs - headLastCorrectiveSeekMs >= headCorrectiveSeekCooldownMs) {
                        runCatching { player.seekTo((expectedPosUs / 1000L).coerceAtLeast(0L)) }
                        headLastCorrectiveSeekMs = nowMs
                        diag("head hard-realign ${drift / 1000}ms")
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
        // v1.3.1: live per-sync volume — a video-track mute toggle must
        // silence an already-mounted head immediately (the reconcile path
        // also rebuilds it because the spec's muted field changed, but the
        // volume catch-up guarantees silence even before that lands).
        val headVol = if (spec.muted || currentProject?.videoTrackMuted == true) 0f
        else spec.volume.coerceIn(0f, 1f)
        if (player.volume != headVol) player.volume = headVol
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
