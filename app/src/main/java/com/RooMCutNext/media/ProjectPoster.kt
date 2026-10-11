package com.RooMCutNext.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.RooMCutNext.core.model.Project
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Home-screen project posters: a single frame grabbed from the timeline at
 * [FRAME_INDEX] (the 20th frame — frame 0 is very often black on faded-in
 * footage, which is exactly what this rule avoids).
 *
 * Cache layout inside the project dir:
 *   poster_<fingerprint>.jpg      — poster for exactly that timeline content
 *   poster_fail_<fingerprint>.txt — extraction failed for that content (memoized
 *                                   so failed extractions don't retry forever)
 *
 * The fingerprint hashes the video-track fields that decide WHICH source frame
 * timeline frame 20 lands on (clip order, gaps, trims, speed, rotation). Any
 * relevant edit produces a new fingerprint, so stale posters self-heal on the
 * next list refresh, and superseded files are pruned to keep the dir small.
 *
 * NOTE: Project.updatedAtMs is NOT usable as a staleness key — the editor's
 * autosave persists the project without bumping it, so content hashing is the
 * only reliable invalidation signal.
 *
 * All methods are blocking — call from the IO dispatcher.
 */
object ProjectPoster {

    /** Timeline frame captured as the poster (0-based → ~0.67s at 30fps). */
    const val FRAME_INDEX = 20

    private const val MAX_DIM = 512
    private const val JPEG_QUALITY = 85

    /** Content fingerprint of everything that decides what frame 20 shows. */
    fun fingerprint(project: Project): String {
        val sb = StringBuilder("fps=${project.fps}")
        for (c in project.clips) {
            sb.append('|')
                .append(c.assetId).append(',')
                .append(c.gapBeforeUs).append(',')
                .append(c.sourceInUs).append(',')
                .append(c.sourceOutUs).append(',')
                .append(c.speed).append(',')
                .append(c.rotationDeg)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sb.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    /** Poster already on disk for this content (cheap check, no decoding). */
    fun cachedPoster(projectDir: File, project: Project): File? {
        val fp = fingerprint(project)
        if (File(projectDir, "poster_$fp.jpg").exists()) {
            return File(projectDir, "poster_$fp.jpg")
        }
        return null
    }

    /**
     * Ensure a poster exists for the project's CURRENT content. Returns the
     * poster file (cached or freshly extracted), or null when the timeline has
     * no usable visual content or extraction failed (failure is memoized so it
     * won't be retried until the timeline changes again).
     */
    fun ensurePoster(projectDir: File, mediaDir: File, project: Project): File? {
        cachedPoster(projectDir, project)?.let { return it }
        val fp = fingerprint(project)

        // Where does timeline frame FRAME_INDEX land?
        val frameUs = FRAME_INDEX * 1_000_000L / project.fps.coerceAtLeast(1)
        var acc = 0L
        var covering: com.RooMCutNext.core.model.Clip? = null
        var clipStartUs = 0L
        for (c in project.clips) {
            acc += c.gapBeforeUs
            if (frameUs >= acc && frameUs < acc + c.durationUs) {
                covering = c
                clipStartUs = acc
                break
            }
            acc += c.durationUs
        }

        val bitmap: Bitmap?
        if (covering != null) {
            val asset = project.assetById(covering.assetId) ?: return markFailed(projectDir, fp)
            if (asset.isAudio) return markFailed(projectDir, fp)
            val source = File(mediaDir, asset.fileName)
            if (!source.exists()) return markFailed(projectDir, fp)
            if (covering.sourceSpanUs <= 0) return markFailed(projectDir, fp)
            val sourceUs = (covering.sourceInUs + (frameUs - clipStartUs) * covering.speed)
                .toLong()
                .coerceIn(covering.sourceInUs, covering.sourceOutUs - 1)
            bitmap = grabVisual(source, asset.isImage, sourceUs)
        } else {
            // Timeline shorter than 20 frames, or frame 20 sits inside a gap:
            // fall back to the 20th source frame of the first clip.
            val first = project.clips.firstOrNull() ?: return markFailed(projectDir, fp)
            val asset = project.assetById(first.assetId) ?: return markFailed(projectDir, fp)
            if (asset.isAudio) return markFailed(projectDir, fp)
            val source = File(mediaDir, asset.fileName)
            if (!source.exists()) return markFailed(projectDir, fp)
            if (first.sourceSpanUs <= 0) return markFailed(projectDir, fp)
            val sourceUs = (first.sourceInUs + asset.sourceFrameDurationUs * FRAME_INDEX)
                .coerceIn(first.sourceInUs, first.sourceOutUs - 1)
            bitmap = grabVisual(source, asset.isImage, sourceUs)
        }
        if (bitmap == null) return markFailed(projectDir, fp)

        val out = File(projectDir, "poster_$fp.jpg")
        val written = runCatching {
            FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        }.isSuccess
        bitmap.recycle()
        prune(projectDir, keep = out.name)
        return if (written && out.exists()) out else null
    }

    /** Video: the frame at [sourceUs]; image: the (scaled) file itself. */
    private fun grabVisual(source: File, isImage: Boolean, sourceUs: Long): Bitmap? = try {
        if (isImage) {
            decodeScaled(source)
        } else {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(source.absolutePath)
                // OPTION_CLOSEST, not CLOSEST_SYNC: the previous sync sample at
                // ~0.7s into a clip is usually frame 0 — exactly the black
                // frame the 20th-frame rule exists to avoid.
                mmr.getScaledFrameAtTime(
                    sourceUs, MediaMetadataRetriever.OPTION_CLOSEST, MAX_DIM, MAX_DIM,
                )
            } finally {
                runCatching { mmr.release() }
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun decodeScaled(source: File): Bitmap? {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, probe)
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return null
        var sample = 1
        while (probe.outWidth / (sample * 2) >= MAX_DIM || probe.outHeight / (sample * 2) >= MAX_DIM) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    /** Memoize a failure for this content and prune superseded cache files. */
    private fun markFailed(projectDir: File, fp: String): File? {
        val marker = File(projectDir, "poster_fail_$fp.txt")
        runCatching { marker.writeText("1") }
        prune(projectDir, keep = marker.name)
        return null
    }

    /** Delete posters / failure markers belonging to other fingerprints. */
    private fun prune(projectDir: File, keep: String) {
        projectDir.listFiles()?.forEach { f ->
            val n = f.name
            if ((n.startsWith("poster_") || n.startsWith("poster_fail_")) && n != keep) {
                f.delete()
            }
        }
    }
}
