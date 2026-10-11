package com.RooMCutNext.playback

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.transformer.Composition
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

    private var pollJob: Job? = null
    private var lastSyncedClipsHash: Int = 0
    private var lastSyncedTextsHash: Int = 0

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            onIsPlaying?.invoke(isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) onEnded?.invoke()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            onError?.invoke(error.errorCodeName)
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
        val clipsHash = project.clips.hashCode()
        val textsHash = project.texts.hashCode()
        if (!force && clipsHash == lastSyncedClipsHash && textsHash == lastSyncedTextsHash) {
            applyPerClipPreviewVolume(project, preservePositionUs)
            return
        }
        lastSyncedClipsHash = clipsHash
        lastSyncedTextsHash = textsHash

        val buildResult = try {
            CompositionBuilder.build(project, mediaDir)
        } catch (e: Exception) {
            onError?.invoke(e.message ?: "composition build failed")
            return
        }

        val wasPlaying = p.isPlaying
        val totalUs = project.totalDurationUs
        val seekUs = preservePositionUs.coerceIn(0L, if (totalUs > 0) totalUs else 0L)
        try {
            p.setComposition(buildResult.composition)
            if (seekUs > 0) p.seekTo(seekUs / 1000L)
            if (wasPlaying) p.play() else p.pause()
            applyPerClipPreviewVolume(project, seekUs)
        } catch (e: Exception) {
            onError?.invoke(e.message ?: "setComposition failed")
        }
    }

    fun setCompositionForEmpty() {
        // Nothing to play; keep the player idle.
        lastSyncedClipsHash = 0
        lastSyncedTextsHash = 0
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.Main.immediate) {
            var lastVolKey = ""
            while (isActive) {
                val p = player ?: break
                try {
                    val posMs = p.currentPosition
                    onPositionUs?.invoke(posMs * 1000L)
                    // Per-clip preview volume (incl. fades) driven from the timeline
                    // so it stays live even without rebuilding the composition.
                    val project = currentProjectRef
                    if (project != null) {
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
        if (clip.muted || asset == null || !asset.hasAudio) {
            p.volume = 0f
            return
        }
        var vol = clip.volume
        val start = TimelineOps.starts(project.clips)[idx]
        val local = timelineUs - start
        val fadeInUs = clip.fadeInMs * 1000L
        val fadeOutUs = clip.fadeOutMs * 1000L
        if (fadeInUs > 0 && local < fadeInUs) vol *= local.toFloat() / fadeInUs
        if (fadeOutUs > 0) {
            val fadeStart = clip.durationUs - fadeOutUs
            if (local > fadeStart) vol *= ((clip.durationUs - local).toFloat() / fadeOutUs).coerceIn(0f, 1f)
        }
        p.volume = vol.coerceIn(0f, 2f)
    }

    // ---------------------------------------------------------------
    // Transport
    // ---------------------------------------------------------------

    fun play() { player?.play() }
    fun pause() { player?.pause() }
    fun seekTo(us: Long) {
        val p = player ?: return
        p.seekTo((us / 1000L).coerceAtLeast(0L))
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
}
