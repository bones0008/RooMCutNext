package com.RooMCutNext.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.transformer.CompositionPlayer
import com.RooMCutNext.core.model.Project
import com.RooMCutNext.core.ops.TimelineOps
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
 */
class PlaybackController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var player: CompositionPlayer? = null
        private set

    /** µs position of the preview, polled at ~33Hz. */
    var onPositionUs: ((Long) -> Unit)? = null
    var onIsPlaying: ((Boolean) -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /** Fired once, right before the automatic compatibility-mode retry. */
    var onDegraded: (() -> Unit)? = null

    private var pollJob: Job? = null
    private var lastSyncedClipsHash: Int = 0
    private var lastSyncedTextsHash: Int = 0

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

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            onIsPlaying?.invoke(isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) onEnded?.invoke()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "CompositionPlayer playback error", error)
            val detail = describeError(error)
            if (!degraded && lastProject != null && lastMediaDir != null) {
                degradeAndRetry()
            } else {
                onError?.invoke(detail)
            }
        }
    }

    fun ensurePlayer(): CompositionPlayer {
        if (player != null) return player!!
        val p = CompositionPlayer.Builder(context).build()
        p.addListener(listener)
        p.volume = 1f
        player = p
        startPolling()
        return p
    }

    /**
     * (Re)build the composition from the project and hand it to the player,
     * preserving the given timeline position (clamped to the new duration).
     *
     * @param force rebuild even if clips/texts did not structurally change
     */
    fun refresh(project: Project, mediaDir: File, preservePositionUs: Long, force: Boolean = false) {
        val p = ensurePlayer()
        lastProject = project
        lastMediaDir = mediaDir
        lastPositionUs = preservePositionUs
        val clipsHash = project.clips.hashCode()
        val textsHash = project.texts.hashCode()
        if (!force && clipsHash == lastSyncedClipsHash && textsHash == lastSyncedTextsHash) {
            applyPerClipPreviewVolume(project, preservePositionUs)
            return
        }
        lastSyncedClipsHash = clipsHash
        lastSyncedTextsHash = textsHash

        val buildResult = try {
            CompositionBuilder.build(project, mediaDir, stripEffects = degraded)
        } catch (e: Exception) {
            onError?.invoke(e.message ?: "composition build failed")
            return
        }

        val wasPlaying = p.isPlaying
        val totalUs = project.totalDurationUs
        val seekUs = preservePositionUs.coerceIn(0L, if (totalUs > 0) totalUs else 0L)
        try {
            p.setComposition(buildResult.composition)
            p.prepare()
            if (seekUs > 0) p.seekTo(seekUs / 1000L)
            if (wasPlaying) p.play() else p.pause()
            applyPerClipPreviewVolume(project, seekUs)
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
        lastProject = null
        lastMediaDir = null
    }

    /** One-shot automatic fallback: rebuild without per-clip effects. */
    private fun degradeAndRetry() {
        degraded = true
        onDegraded?.invoke()
        val project = lastProject ?: return
        val mediaDir = lastMediaDir ?: return
        refresh(project, mediaDir, lastPositionUs, force = true)
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
                    // Per-clip preview volume (incl. fades) driven from the timeline
                    // so it stays live even without rebuilding the composition.
                    val project = currentProjectRef
                    if (project != null && !scrubbing) {
                        applyPerClipPreviewVolume(project, posMs * 1000L)
                    }
                } catch (e: Exception) {
                    // player released concurrently; exit quietly
                }
                delay(33)
            }
        }
    }

    /** Set by the ViewModel right before refresh(); used by the volume poll. */
    @Volatile
    var currentProjectRef: Project? = null

    private fun applyPerClipPreviewVolume(project: Project, timelineUs: Long) {
        val p = player ?: return
        val idx = TimelineOps.clipIndexAt(project.clips, timelineUs)
        if (idx == -1) {
            p.volume = 1f
            return
        }
        val clip = project.clips[idx]
        val asset = project.assetById(clip.assetId)
        // Only a deliberate mute forces silence. A missing/"no audio" asset flag
        // must NOT zero the volume: the hasAudio metadata is device-dependent
        // (OEMs return "yes"/"true"/"1") and a false negative silently killed
        // ALL preview audio in v1.0.0-v1.0.2. With no real audio track the
        // volume setting is simply a no-op.
        if (clip.muted) {
            p.volume = 0f
            return
        }
        var vol = clip.volume
        // Fade curves only make sense when the clip actually spans this time.
        val assetRef = asset
        if (assetRef != null) {
            val start = TimelineOps.starts(project.clips)[idx]
            val local = timelineUs - start
            val fadeInUs = clip.fadeInMs * 1000L
            val fadeOutUs = clip.fadeOutMs * 1000L
            if (fadeInUs > 0 && local < fadeInUs) vol *= local.toFloat() / fadeInUs
            if (fadeOutUs > 0) {
                val fadeStart = clip.durationUs - fadeOutUs
                if (local > fadeStart) vol *= ((clip.durationUs - local).toFloat() / fadeOutUs).coerceIn(0f, 1f)
            }
        }
        p.volume = vol.coerceIn(0f, 2f)
    }

    // ---------------------------------------------------------------
    // Transport
    // ---------------------------------------------------------------

    fun play() { player?.play() }
    fun pause() { player?.pause() }

    /**
     * Enter Media3 scrubbing mode for interactive playhead dragging: video
     * frames are released immediately (no vsync alignment), audio is parked,
     * and playWhenReady is automatically restored on [endScrub].
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
        val target = TimelineOps.stepFrame(project.clips, assetLookup, timelineUs, forward)
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
