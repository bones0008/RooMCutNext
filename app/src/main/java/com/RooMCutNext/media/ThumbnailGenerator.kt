package com.RooMCutNext.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.RooMCutNext.core.model.MediaAsset
import java.io.File
import java.io.FileOutputStream

/**
 * Generates filmstrip thumbnails next to the media file:
 *   {stem}_th0.jpg .. {stem}_th{N-1}.jpg
 *
 * Videos get [FRAMES_PER_VIDEO] evenly spaced frames; images get one scaled
 * copy. Thumbnails are small (height ~100px) and tiled by the timeline UI.
 */
object ThumbnailGenerator {

    const val FRAMES_PER_VIDEO = 8
    private const val THUMB_HEIGHT = 100

    fun generate(source: File, asset: MediaAsset) {
        when {
            asset.isImage -> generateImageThumb(source)
            asset.isAudio -> Unit // audio has no thumbnails
            else -> generateVideoThumbs(source, asset)
        }
    }

    fun thumbFiles(mediaDir: File, asset: MediaAsset): List<File> {
        val stem = asset.fileName.substringBeforeLast('.')
        val n = when {
            asset.isImage -> 1
            asset.isAudio -> 0
            else -> FRAMES_PER_VIDEO
        }
        return (0 until n).map { File(mediaDir, "${stem}_th$it.jpg") }
    }

    private fun generateImageThumb(source: File) {
        if (thumbExists(source, 0)) return
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, opts)
        if (opts.outWidth <= 0) return
        val sample = maxOf(1, opts.outHeight / THUMB_HEIGHT)
        val bitmap = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return
        writeThumb(source, 0, bitmap)
    }

    private fun generateVideoThumbs(source: File, asset: MediaAsset) {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(source.absolutePath)
            val durationUs = asset.durationUs
            val aspect = if (asset.height > 0) asset.width.toFloat() / asset.height else 16f / 9f
            val th = THUMB_HEIGHT
            val tw = (th * aspect).toInt().coerceAtLeast(1)
            for (i in 0 until FRAMES_PER_VIDEO) {
                if (thumbExists(source, i)) continue
                // Center of each slice — avoids duplicate first/last frames.
                val us = durationUs * (i + 1) / (FRAMES_PER_VIDEO + 1)
                val frame = try {
                    if (android.os.Build.VERSION.SDK_INT >= 27) {
                        mmr.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th)
                    } else {
                        mmr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                } catch (e: Exception) {
                    null
                } ?: continue
                val scaled = if (frame.height != th) {
                    Bitmap.createScaledBitmap(frame, tw, th, true).also { if (it != frame) frame.recycle() }
                } else frame
                writeThumb(source, i, scaled)
            }
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun thumbExists(source: File, index: Int): Boolean {
        val stem = source.name.substringBeforeLast('.')
        return File(source.parentFile, "${stem}_th$index.jpg").exists()
    }

    private fun writeThumb(source: File, index: Int, bitmap: Bitmap) {
        val stem = source.name.substringBeforeLast('.')
        val out = File(source.parentFile, "${stem}_th$index.jpg")
        runCatching {
            FileOutputStream(out).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, fos)
            }
        }
        if (bitmap.recycleAfterWrite()) { /* hint only */ }
    }
}

private fun Bitmap.recycleAfterWrite(): Boolean = false
