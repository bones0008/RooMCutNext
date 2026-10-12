package com.RooMCutNext.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.SurfaceView
import androidx.media3.ui.PlayerView
import com.RooMCutNext.core.util.aspectFitRect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Exports the CURRENT preview frame as a JPEG into the gallery (v1.1.4).
 *
 * Source of truth is the preview surface itself (PixelCopy from the
 * PlayerView's SurfaceView), which guarantees WYSIWYG: everything the user
 * sees — rotate/crop, color grading, LUT filters, text overlays rendered by
 * the preview — is baked into the saved image. The trade-off is preview
 * resolution rather than source resolution, which is exactly what "save this
 * frame as a cover / check my grading" wants.
 */
object FrameExporter {

    /**
     * Copy the current frame of [playerView]'s video surface into a bitmap.
     * Returns null when the view has no surface yet (player idle / black).
     */
    suspend fun grabCurrentFrame(playerView: PlayerView): Bitmap? {
        val surface = playerView.videoSurfaceView as? SurfaceView ?: return null
        val w = surface.width
        val h = surface.height
        if (w <= 0 || h <= 0) return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val ok = suspendCancellableCoroutine { cont ->
            try {
                PixelCopyHelper.request(surface, bitmap) { result ->
                    cont.resume(result == android.view.PixelCopy.SUCCESS)
                }
            } catch (e: Exception) {
                cont.resume(false)
            }
        }
        if (!ok) return null
        return bitmap
    }

    /**
     * Crop the letterbox/pillarbox bars around the fitted video content.
     * [contentW]/[contentH] is the video's (or project's) aspect; degenerate
     * values keep the whole bitmap.
     */
    fun cropToContent(bitmap: Bitmap, contentW: Int, contentH: Int): Bitmap {
        val rect = aspectFitRect(bitmap.width, bitmap.height, contentW, contentH)
        if (rect.width <= 0 || rect.height <= 0) return bitmap
        if (rect.width == bitmap.width && rect.height == bitmap.height) return bitmap
        return Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
    }

    /**
     * Insert the bitmap as a JPEG into Pictures/RooMCutNext and return the
     * gallery Uri (null on failure). Mirrors ExportEngine's MediaStore flow.
     */
    suspend fun saveToGallery(context: Context, bitmap: Bitmap, projectName: String): Uri? =
        withContext(Dispatchers.IO) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safeName = projectName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "RooMCutNext" }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "${safeName}_frame_$stamp.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RooMCutNext")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return@withContext null
            val written = runCatching {
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                } ?: false
            }.getOrDefault(false)
            if (written) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                runCatching { resolver.delete(uri, null, null) }
                null
            }
        }
}

/** Thin indirection over PixelCopy so the API levels stay in one place. */
private object PixelCopyHelper {
    fun request(surface: SurfaceView, bitmap: Bitmap, onDone: (Int) -> Unit) {
        android.view.PixelCopy.request(surface, bitmap, { result ->
            onDone(result)
        }, Handler(Looper.getMainLooper()))
    }
}
