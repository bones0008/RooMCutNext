package com.RooMCutNext.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.RooMCutNext.core.model.MediaAsset
import com.RooMCutNext.core.model.MediaType
import com.RooMCutNext.core.util.IdGen
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts technical metadata (resolution / fps / codecs / audio presence)
 * from an imported media file.
 *
 * fps detection uses [MediaExtractor] (KEY_FRAME_RATE of the video track),
 * which is reliable for camera footage and AI generated MP4/WebM alike.
 */
object MetadataExtractor {

    fun extract(file: File): MediaAsset? = try {
        val name = file.name
        val isImage = isImageName(name)
        if (isImage) extractImage(file) else extractVideo(file)
    } catch (e: Exception) {
        null
    }

    private fun isImageName(name: String): Boolean {
        val lower = name.lowercase()
        return listOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".bmp", ".gif")
            .any { lower.endsWith(it) }
    }

    private fun extractImage(file: File): MediaAsset? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return MediaAsset(
            id = IdGen.new("ast"),
            fileName = file.name,
            displayName = file.name,
            type = MediaType.IMAGE,
            durationUs = 0L,
            width = bounds.outWidth,
            height = bounds.outHeight,
            fps = 0f,
            hasAudio = false,
            fileBytes = file.length(),
            addedAtMs = System.currentTimeMillis(),
        )
    }

    private fun extractVideo(file: File): MediaAsset? {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)
            val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return null
            var width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            // Normalize orientation so width/height describe the displayed frame.
            if (rotation == 90 || rotation == 270) {
                val t = width; width = height; height = t
            }
            if (width <= 0 || height <= 0 || durationMs <= 0) return null

            val hasAudio = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "1"

            var fps = 0f
            var videoCodec: String? = null
            var audioCodec: String? = null
            try {
                val extractor = MediaExtractor()
                extractor.setDataSource(file.absolutePath)
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/")) {
                        videoCodec = prettifyVideoMime(mime)
                        fps = fmt.frameRate()
                    } else if (mime.startsWith("audio/")) {
                        audioCodec = prettifyAudioMime(mime)
                    }
                }
                extractor.release()
            } catch (e: Exception) {
                // fps stays unknown; UI falls back to the project fps.
            }
            if (fps <= 0f) {
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull()?.let { fps = it }
            }

            return MediaAsset(
                id = IdGen.new("ast"),
                fileName = file.name,
                displayName = file.name,
                type = MediaType.VIDEO,
                durationUs = durationMs * 1000L,
                width = width,
                height = height,
                fps = fps,
                hasAudio = hasAudio,
                videoCodec = videoCodec,
                audioCodec = audioCodec,
                fileBytes = file.length(),
                addedAtMs = System.currentTimeMillis(),
            )
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun MediaFormat.frameRate(): Float = try {
        getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
    } catch (e: ClassCastException) {
        try { getFloat(MediaFormat.KEY_FRAME_RATE) } catch (e2: Exception) { 0f }
    } catch (e: Exception) {
        0f
    }

    private fun prettifyVideoMime(mime: String): String = when {
        mime.contains("avc") -> "H.264"
        mime.contains("hev1") || mime.contains("hevc") -> "H.265"
        mime.contains("av01") -> "AV1"
        mime.contains("vp09") || mime.contains("vp9") -> "VP9"
        mime.contains("vp8") -> "VP8"
        else -> mime.removePrefix("video/")
    }

    private fun prettifyAudioMime(mime: String): String = when {
        mime.contains("mp4a") -> "AAC"
        mime.contains("opus") -> "Opus"
        mime.contains("vorbis") -> "Vorbis"
        mime.contains("3gpp") || mime.contains("amr") -> "AMR"
        mime.contains("pcm") -> "PCM"
        else -> mime.removePrefix("audio/")
    }
}

/** Resolves a display name for a content URI without crashing on bad providers. */
fun Uri.queryDisplayName(context: Context): String? = try {
    context.contentResolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (e: Exception) {
    null
}
