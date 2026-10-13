package com.RooMCutNext.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.ui.PlayerView
import com.RooMCutNext.core.model.Project
import com.RooMCutNext.core.model.TextOverlayItem
import com.RooMCutNext.core.ops.TimelineOps
import com.RooMCutNext.core.util.TextSignatures
import com.RooMCutNext.export.CompositionBuilder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the [CompositionPlayer] that previews the timeline.
 *
 * The player consumes the SAME [Composition] the exporter uses, so trimming,
 * speed, rotation, crop, color, volume and text overlays preview exactly as
 * they will be exported.
 *
 * Position mapping is 1:1: the composition timeline IS the app timeline
 * (prefix sums of clip durations), no extra bookkeeping is needed.
 *
 * Error strategy: a [PlaybackException] unwraps its root cause into the
 * user-visible message (the bare ERROR_CODE_UNSPECIFIED code name alone is
 * useless for debugging). On the FIRST error the controller retries once in
 * "compatibility mode" — same timeline, per-clip GL effects stripped — so a
 * device with a fragile effects pipeline can still preview, trim and export.
 * v1.2.2: the degraded state now RECOVERS automatically — once the project
 * structure changes (user removes the offending layer etc.) the next
 * refresh retries full effects instead of staying degraded forever (the
 * stuck chip was why "delete project & recreate" was the only way out).
 *
 * v1.2.3: the preview composition is ALWAYS a single video sequence (see
 * CompositionBuilder's PREVIEW vs EXPORT split). The v1.2.2 experiment that
 * rebuilt the player with MultipleInputVideoGraph for PiP / transitions is
 * GONE — upstream Media3 1.11.1 cannot seek a multi-video-sequence
 * CompositionPlayer (androidx/media #2439/#3058: playback dies outright)
 * and DefaultVideoCompositor deadlocks when an input's frame queue empties
 * at an item transition (#2742). Both broke the editor the moment a PiP was
 * added. PiP layers and crossfade heads now preview through the view-layer
 * OverlayPreviewController; export still uses the multi-sequence Composition
 * via Transformer, which does not go through CompositionPlayer at all.
 */
class PlaybackController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var player: CompositionPlayer? = null
        private set

    /**
     * v1.3.0: PREVIEW-ONLY live text source. When set, every TimedTextOverlay
     * baked into the preview composition reads the CURRENT item state on
     * every output frame (see TimedTextOverlay.liveTransform), so visual text
     * edits — gesture drags, slider moves, color/font/content changes — render
     * instantly with ZERO composition rebuilds. Export never sees this hook.
     * Set by EditorViewModel to a reader over its StateFlow (thread-safe:
     * StateFlow.value is a volatile read of immutable data).
     */
    var liveTextLookup: ((id: String) -> TextOverlayItem?)? = null
        set(value) {
            if (field == value) return
            field = value
            // The running composition's overlays hold the OLD lambda: rebind
            // them by forcing one rebuild (cheap and rare — set once at VM init).
            if (player != null) forceRebuildNextRefresh()
        }

    /** µs position of the preview, polled at ~33Hz. */
    var onPositionUs: ((Long) -> Unit)? = null
    var onIsPlaying: ((Boolean) -> Unit)? = null

    /**
     * v1.2.10: PLAY INTENT (the player's playWhenReady), echoed from
     * onPlayWhenReadyChanged. Unlike [onIsPlaying] this does NOT flicker to
     * false while the player buffers across an item boundary — which is
     * exactly when the overlay engine's crossfade fade-out tail must stay
     * engaged (see the v1.2.10 note in OverlayPreviewController). The
     * transport icon also prefers it so the button does not blink at every
     * clip boundary during playback.
     */
    var onWantsPlay: ((Boolean) -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /** Fired once, right before the automatic compatibility-mode retry. */
    var onDegraded: (() -> Unit)? = null

    /** Diagnostic event stream for the on-screen DIAG overlay (v1.0.6-diag). */
    var onDiag: ((String) -> Unit)? = null

    private fun diag(line: String) {
        Log.i(TAG, "DIAG $line")
        onDiag?.invoke(line)
    }

    private var pollJob: Job? = null
    private var lastSyncedClipsHash: Int = 0
    private var lastSyncedTextsHash: Int = 0
    private var lastSyncedAudiosHash: Int = 0
    // v1.2.2→v1.2.3: PiP WINDOWING (which clip spans which window + the
    // total duration they drive) is part of the preview composition's
    // structure; GEOMETRY (x/y/scale/opacity) is NOT — it renders in the
    // view layer now, so dragging a PiP slider never rebuilds the player.
    private var lastSyncedPipsHash: Int = 0
    // v1.3.1: video-track mute flag — see refresh() for why it gates a
    // rebuild even though the clips list is unchanged.
    private var lastSyncedTrackMuteHash: Int = 0

    /** Invalidate the synced hashes so the NEXT refresh rebuilds (v1.3.0). */
    private fun forceRebuildNextRefresh() {
        lastSyncedClipsHash = 0
        lastSyncedTextsHash = 0
        lastSyncedAudiosHash = 0
        lastSyncedPipsHash = 0
        lastSyncedTrackMuteHash = 0
    }

    // v1.2.2: structure snapshot at the time of the last degrade; a change
    // here means "the user touched the timeline, retry full effects".
    private var degradedStructureHash: Int = 0

    // v1.2.3: true while the controller itself is re-refreshing inside
    // degradeAndRetry() — suppresses the recovery logic for that one pass.
    private var insideDegradedRetry = false

    /** Preview view bound from the UI layer; the player is created eagerly so
     *  its output surface is attached BEFORE the first composition prepare. */
    private var boundView: PlayerView? = null

    /** True while the user drags the playhead: Media3 scrubbing mode on. */
    @Volatile
    private var scrubbing = false

    /** Pending seek target while throttling (non-null = a flush is scheduled). */
    private var pendingSeekUs: Long? = null
    private var seekFlushJob: Job? = null

    /** Compatibility preview active: per-clip effects are stripped. */
    @Volatile
    var degraded: Boolean = false
        private set

    private var lastProject: Project? = null
    private var lastMediaDir: File? = null
    private var lastPositionUs: Long = 0L

    /** Frames that reached the output surface (from onRenderedFirstFrame). */
    private var firstFrameCount = 0

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            diag("playing=$isPlaying")
            onIsPlaying?.invoke(isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            diag("state=${stateName(playbackState)}")
            if (playbackState == Player.STATE_ENDED) onEnded?.invoke()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            diag("pwr=$playWhenReady")
            onWantsPlay?.invoke(playWhenReady)
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            diag("video=${videoSize.width}x${videoSize.height}")
        }

        override fun onRenderedFirstFrame() {
            firstFrameCount++
            diag("FIRSTFRAME #$firstFrameCount ✓")
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "CompositionPlayer playback error", error)
            diag("ERR ${error.errorCodeName}")
            val detail = describeError(error)
            if (!degraded && lastProject != null && lastMediaDir != null) {
                degradeAndRetry()
            } else {
                onError?.invoke(detail)
            }
        }
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }

    fun ensurePlayer(): CompositionPlayer {
        if (player != null) return player!!
        // v1.2.3: ALWAYS the default single-input graph — the preview
        // composition carries exactly one video sequence (see class doc),
        // and the player is NEVER rebuilt mid-session: a stable surface and
        // player instance across every timeline edit (the v1.2.2 rebuild
        // dance existed only to switch video graph factories).
        //
        // v1.3.3: experimentalSetEnableReplayableCache(true) — the player's
        // video graph keeps a 2-frame texture cache so
        // experimentalRedrawLastFrame() can push the LAST frame back through
        // the effects pipeline on demand. This is media3's own mechanism for
        // "accurate effect update" (their words) and what finally makes
        // live text edits visible while PAUSED: the v1.3.2 same-position
        // seek nudge turned out not to render on real devices — the
        // re-decoded frame parks in the release control until playback
        // resumes, exactly the "must press play to see it" symptom. Export
        // is unaffected (Transformer builds its own pipeline).
        val p = CompositionPlayer.Builder(context)
            .experimentalSetEnableReplayableCache(true)
            .build()
        p.addListener(listener)
        p.volume = 1f
        player = p
        firstFrameCount = 0
        diag("player created")
        startPolling()
        return p
    }

    /**
     * Bind the preview [PlayerView] from the UI layer (AndroidView factory).
     *
     * v1.0.8 black-screen fix: on a FRESH project the player used to be created
     * only when the first import finished — i.e. setComposition()/prepare()
     * ran while the view still had player == null, so the GL pipeline was
     * configured with no output surface and rendered nothing (black). Leaving
     * and re-entering worked because the init-refresh then ran before the view
     * bound. Creating the player HERE (view factory time) and attaching it to
     * the view guarantees surface-before-prepare in every entry path.
     */
    fun bindView(view: PlayerView) {
        boundView = view
        view.player = ensurePlayer()
    }

    fun unbindView() {
        boundView?.player = null
        boundView = null
    }

    /**
     * (Re)build the composition from the project and hand it to the player,
     * preserving the given timeline position (clamped to the new duration).
     *
     * @param force rebuild even if clips/texts did not structurally change
     */
    fun refresh(project: Project, mediaDir: File, preservePositionUs: Long, force: Boolean = false) {
        lastProject = project
        lastMediaDir = mediaDir
        lastPositionUs = preservePositionUs
        val clipsHash = project.clips.hashCode()
        // v1.3.0: text signature covers WINDOWS ONLY (id/start/end + count).
        // Visual fields (position / size / rotation / color / font / content)
        // are read LIVE by TimedTextOverlay every frame (liveTextLookup) and
        // must NOT rebuild the composition — that churn was why every slider
        // tick re-prepared the whole pipeline. Same treatment PiP geometry
        // already gets below. See TextSignatures.
        val textsHash = TextSignatures.windows(project.texts)
        val audiosHash = project.audios.hashCode()
        // PiP signature = windows only (id/asset/start/duration). Geometry
        // (xRatio/yRatio/scale/opacity) is deliberately excluded: it lives in
        // the view-layer overlay and must not rebuild the composition — that
        // was why every slider drag churned the whole pipeline.
        val pipsHash = project.pips.fold(1) { acc, p ->
            31 * acc + listOf(p.id, p.assetId, p.startUs, p.durationUs).hashCode()
        }
        // v1.3.1: the video-track mute flag changes clip AUDIO inside the
        // baked body composition (setRemoveAudio / GainAudioProcessor) but
        // NOT the clips list — clipsHash alone would early-return and keep
        // the old audio. Fold the flag into the gate so a track-mute toggle
        // rebuilds exactly once. (pipTrackMuted is NOT here: the PiP audio
        // is applied live per overlay player in OverlayPreviewController.)
        val trackMuteHash = if (project.videoTrackMuted) 1 else 0
        val structureHash = listOf(clipsHash, textsHash, audiosHash, pipsHash, trackMuteHash).hashCode()

        // v1.2.2: recover from degraded mode as soon as the user changes
        // ANYTHING structural — the offending layer may be gone, so retry
        // full effects (worst case it degrades again, which is no worse than
        // the old permanently-stuck behavior).
        if (degraded && !insideDegradedRetry && structureHash != degradedStructureHash) {
            degraded = false
            diag("degraded RECOVERED (structure changed)")
        }

        if (!force && clipsHash == lastSyncedClipsHash && textsHash == lastSyncedTextsHash &&
            audiosHash == lastSyncedAudiosHash && pipsHash == lastSyncedPipsHash &&
            trackMuteHash == lastSyncedTrackMuteHash
        ) {
            return
        }
        lastSyncedClipsHash = clipsHash
        lastSyncedTextsHash = textsHash
        lastSyncedAudiosHash = audiosHash
        lastSyncedPipsHash = pipsHash
        lastSyncedTrackMuteHash = trackMuteHash

        val p = ensurePlayer()

        val buildResult = try {
            // forPreview=true: GL effects receive GLOBAL timeline timestamps in
            // CompositionPlayer (see CompositionBuilder class doc).
            // v1.3.0: liveTextLookup rides along so the baked text overlays
            // track live edits frame-by-frame.
            CompositionBuilder.build(
                project, mediaDir, stripEffects = degraded, forPreview = true,
                liveTextLookup = liveTextLookup,
            )
        } catch (e: Exception) {
            diag("build FAIL: ${e.message?.take(80) ?: e.javaClass.simpleName}")
            onError?.invoke(e.message ?: "composition build failed")
            return
        }

        val wasPlaying = p.isPlaying
        val totalUs = project.totalDurationUs
        val seekUs = preservePositionUs.coerceIn(0L, if (totalUs > 0) totalUs else 0L)
        val seqCount = buildResult.composition.sequences.size
        val itemCount = buildResult.composition.sequences.sumOf { it.editedMediaItems.size }
        diag("setComp: ${seqCount}seq/${itemCount}item dur=${totalUs / 1000}ms seek=${seekUs / 1000}ms")
        try {
            // v1.2.3: bake the start position into setComposition — the
            // sequence players receive it via setMediaSource(source, posMs)
            // BEFORE prepare, instead of a post-prepare seekTo(). The
            // single-video-sequence preview supports both, but this order is
            // the official CompositionPlayer flow and avoids one seek pass.
            p.setComposition(buildResult.composition, seekUs / 1000L)
            p.prepare()
            if (wasPlaying) p.play() else p.pause()
            diag("prepare OK")
        } catch (e: Exception) {
            Log.e(TAG, "setComposition/prepare failed", e)
            // Media3 throws several message-less checkState/checkArgument
            // exceptions (e.g. missing durationUs); always surface the class
            // name so field reports are diagnosable instead of a bare
            // "setComposition failed".
            val msg = e.message
            onError?.invoke(buildString {
                append(e.javaClass.simpleName)
                if (!msg.isNullOrBlank()) append(": ").append(msg.take(140))
            })
        }
    }

    fun setCompositionForEmpty() {
        // Nothing to play; keep the player idle.
        lastSyncedClipsHash = 0
        lastSyncedTextsHash = 0
        lastSyncedAudiosHash = 0
        lastSyncedPipsHash = 0
        lastSyncedTrackMuteHash = 0
        lastProject = null
        lastMediaDir = null
    }

    /** One-shot automatic fallback: rebuild without per-clip effects. */
    private fun degradeAndRetry() {
        degraded = true
        val project = lastProject
        val mediaDir = lastMediaDir
        // Snapshot the structure so the recovery check in refresh() can tell
        // "the timeline the user has now" from "the timeline that degraded".
        degradedStructureHash = project?.let {
            listOf(
                it.clips.hashCode(), it.texts.hashCode(),
                it.audios.hashCode(), it.pips.hashCode(),
            ).hashCode()
        } ?: 0
        diag("DEGRADED retry (effects stripped)")
        onDegraded?.invoke()
        if (project == null || mediaDir == null) return
        insideDegradedRetry = true
        try {
            refresh(project, mediaDir, lastPositionUs, force = true)
        } finally {
            insideDegradedRetry = false
        }
    }

    /** "ERROR_CODE_UNSPECIFIED · VideoFrameProcessingException: <root cause>". */
    private fun describeError(error: PlaybackException): String {
        var cause: Throwable = error
        while (cause.cause != null && cause.cause !== cause) {
            cause = cause.cause!!
        }
        val rootMsg = cause.message
            ?.lineSequence()?.firstOrNull { it.isNotBlank() }
            ?.take(140)
            ?.trim()
        return buildString {
            append(error.errorCodeName)
            append(" · ")
            append(cause.javaClass.simpleName)
            if (!rootMsg.isNullOrBlank() && rootMsg != error.message) append(": ").append(rootMsg)
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                val p = player ?: break
                try {
                    val posMs = p.currentPosition
                    lastPositionUs = posMs * 1000L
                    // Echo the position to the UI ONLY while actually playing.
                    // When paused, a seek is still being processed internally and
                    // echoing the stale position would visually cancel out
                    // frame-step / playhead jumps set by the ViewModel.
                    if (p.isPlaying) {
                        onPositionUs?.invoke(posMs * 1000L)
                    }
                } catch (e: Exception) {
                    // player released concurrently; exit quietly
                }
                delay(33)
            }
        }
    }

    // Per-clip volume / fades / mute are applied inside the composition via
    // GainAudioProcessor and setRemoveAudio (both honored by the preview audio
    // graph — verified in AudioGraphInput). The former player.volume polling
    // hack was removed: it zeroed ALL audio on muted video clips, which would
    // now also kill independent background audio clips.

    /** Set by the ViewModel right before refresh(); kept for error retries. */
    @Volatile
    var currentProjectRef: Project? = null

    /** The preview PlayerView, if bound (surface attachment diagnostics). */
    val hasBoundView: Boolean get() = boundView != null

    // ---------------------------------------------------------------
    // Transport
    // ---------------------------------------------------------------

    fun play() { player?.play() }
    fun pause() { player?.pause() }

    /**
     * v1.3.3: re-render the CURRENT frame while paused.
     *
     * The preview's baked TimedTextOverlays read the live project state on
     * every OUTPUT frame — but a paused player renders nothing, so live text
     * edits (gesture drags / pinch / panel sliders) stayed invisible until
     * playback resumed ("scaling only shows after you press play").
     *
     * The v1.3.2 attempt seeked to the current position, betting the decoder
     * would push a fresh frame through the effects graph. It does decode —
     * but on real devices the frame parks in the sink's release control
     * until playback resumes, so NOTHING redraws while paused. media3's
     * purpose-built path is [CompositionPlayer.experimentalRedrawLastFrame]
     * (requires the player built with replayable cache, see ensurePlayer):
     * it replays the last CACHED frame through the effects — the overlay's
     * onDraw re-reads the live state and the frame lands on the surface
     * immediately. The old same-position seek stays as a fallback for the
     * unlikely device where the redraw call itself throws.
     *
     * No-ops while playing (frames flow on their own), while nothing is
     * prepared (IDLE/BUFFERING — a seek there would restart preparation, the
     * seek-storm livelock driveHead already documents) and on throttling
     * (motion events arrive at 60-120Hz; ~66ms between nudges is plenty for
     * the eye). [force] bypasses the throttle for the final commit at
     * gesture end so the last finger position is never lost.
     */
    private var lastNudgeMs = 0L

    fun nudgeRedraw(force: Boolean = false) {
        val p = player ?: return
        if (p.isPlaying) return
        val state = p.playbackState
        if (state != Player.STATE_READY && state != Player.STATE_ENDED) return
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - lastNudgeMs < 66L) return
        lastNudgeMs = now
        try {
            p.experimentalRedrawLastFrame()
        } catch (e: IllegalStateException) {
            // Replayable cache unavailable (unexpected — the builder enables
            // it): keep the v1.3.2 behavior rather than losing live edits.
            runCatching { p.seekTo(p.currentPosition) }
        }
    }

    /**
     * Enter Media3 scrubbing mode for interactive playhead dragging: video
     * frames are released immediately (no vsync alignment), audio is parked,
     * and playWhenReady is retained (playback merely SUPPRESSED).
     */
    fun beginScrub() {
        if (scrubbing) return
        scrubbing = true
        try {
            player?.setScrubbingModeEnabled(true)
        } catch (e: Exception) {
            Log.w(TAG, "setScrubbingModeEnabled(true) failed", e)
        }
    }

    /**
     * Exit Media3 scrubbing mode.
     *
     * v1.2.6: releasing a playhead drag now PAUSES instead of resuming.
     * ExoPlayerImpl.setScrubbingModeEnabled(false) only lifts the playback
     * suppression (reason 4) while playWhenReady is still true — i.e. audio
     * instantly came back after the finger lifted, which is why "dragging
     * the timeline during playback kept playing on release". Editing-app
     * expectation is "drag = park the playhead here"; pressing play again
     * is the explicit intent to resume. The pause AFTER disabling scrub
     * mode is the order that wins: playWhenReady=false is already set when
     * the suppression lifts.
     */
    fun endScrub() {
        if (!scrubbing) return
        scrubbing = false
        // Flush any pending throttled seek first so the final position is the
        // one the finger last pointed at.
        flushPendingSeek()
        try {
            player?.setScrubbingModeEnabled(false)
        } catch (e: Exception) {
            Log.w(TAG, "setScrubbingModeEnabled(false) failed", e)
        }
        player?.pause()
    }

    /** Immediate (non-throttled) seek: frame-step, go-to-start/end, taps. */
    fun seekTo(us: Long) {
        pendingSeekUs = null
        seekFlushJob?.cancel()
        dispatchSeek(us)
    }

    /**
     * Throttled seek for drag gestures: dispatches at most once per 80ms and
     * coalesces intermediate targets, so ExoPlayer is not flooded with seeks
     * (a seek storm is what made the picture lag ~2s behind the finger).
     */
    fun seekThrottled(us: Long) {
        pendingSeekUs = us
        if (seekFlushJob?.isActive != true) {
            seekFlushJob = scope.launch(Dispatchers.Main.immediate) {
                while (true) {
                    val target = pendingSeekUs ?: break
                    pendingSeekUs = null
                    dispatchSeek(target)
                    delay(80)
                }
            }
        }
    }

    private fun flushPendingSeek() {
        val target = pendingSeekUs ?: return
        pendingSeekUs = null
        seekFlushJob?.cancel()
        dispatchSeek(target)
    }

    private fun dispatchSeek(us: Long) {
        val p = player ?: return
        try {
            p.seekTo((us / 1000L).coerceAtLeast(0L))
        } catch (e: Exception) {
            Log.w(TAG, "seekTo failed", e)
        }
    }

    fun stepFrame(project: Project, timelineUs: Long, forward: Boolean): Long {
        val assetLookup: (String) -> com.RooMCutNext.core.model.MediaAsset? = { id -> project.assetById(id) }
        val target = TimelineOps.stepFrame(
            project.clips, assetLookup, timelineUs, forward,
            totalDurationUs = project.totalDurationUs,
            frameDurationUs = project.frameDurationUs,
        )
        seekTo(target)
        return target
    }

    fun release() {
        pollJob?.cancel()
        pollJob = null
        player?.run {
            runCatching { removeListener(listener) }
            runCatching { release() }
        }
        player = null
    }

    private companion object {
        const val TAG = "RooMCutNextPlayback"
    }
}
