package com.RooMCutNext.export

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.RooMCutNext.core.model.Project
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs the actual export with Media3 Transformer and publishes progress.
 *
 * Output goes to a temp file, then is inserted into MediaStore
 * (Movies/RooMCutNext) so it is visible to gallery apps and other consumers.
 */
class ExportEngine(private val context: Context) {

    var transformer: Transformer? = null
        private set

    /** Start the export; returns immediately, callbacks fire async. */
    fun start(
        project: Project,
        mediaDir: File,
        options: ExportOptions,
        onProgress: (Float) -> Unit,
        onCompleted: (Uri?) -> Unit,
        onError: (String) -> Unit,
    ) {
        val buildResult = try {
            CompositionBuilder.build(project, mediaDir, options.height)
        } catch (e: Exception) {
            onError(e.message ?: "无法构建合成 / cannot build composition")
            return
        }

        val tempOut = File(context.cacheDir, "export_${project.id}.mp4")
        if (tempOut.exists()) tempOut.delete()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                // Publishing to MediaStore is disk I/O — keep it off the main thread.
                Thread {
                    val uri = publishToMediaStore(tempOut, project.name)
                    onCompleted(uri)
                }.start()
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                onError(describeExportError(exportException))
            }
        }

        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(listener)
            .build()
        transformer = t
        t.start(buildResult.composition, tempOut.absolutePath)

        // Progress polling loop is driven by the caller (service coroutine).
    }

    /**
     * "ERROR_CODE_NAME · RootCauseClass: root message" — unwraps the full cause
     * chain so the user sees WHY (e.g. a message-less checkState from
     * ImageAssetLoader) instead of the useless generic "AssetLoader error".
     */
    private fun describeExportError(e: ExportException): String {
        Log.e(TAG, "export failed", e)
        var cause: Throwable = e
        while (cause.cause != null && cause.cause !== cause) {
            cause = cause.cause!!
        }
        val rootMsg = cause.message
            ?.lineSequence()?.firstOrNull { it.isNotBlank() }
            ?.take(160)?.trim()
        return buildString {
            append(e.errorCodeName)
            append(" · ")
            append(cause.javaClass.simpleName)
            if (!rootMsg.isNullOrBlank()) append(": ").append(rootMsg)
        }
    }

    /** @return current progress 0..1, or -1 (indeterminate). */
    fun currentProgress(): Float {
        val holder = ProgressHolder()
        val t = transformer ?: return -1f
        return try {
            if (t.getProgress(holder) != androidx.media3.transformer.Transformer.PROGRESS_STATE_AVAILABLE) -1f
            else holder.progress / 100f
        } catch (e: Exception) {
            -1f
        }
    }

    fun cancel() {
        try {
            transformer?.cancel()
        } catch (e: Exception) {
            // cancel after completion throws; ignore.
        }
    }

    private fun publishToMediaStore(tempFile: File, projectName: String): Uri? {
        if (!tempFile.exists() || tempFile.length() == 0L) return null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeName = projectName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "RooMCutNext" }
        val values = android.content.ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${safeName}_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RooMCutNext")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection =
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: run {
            tempFile.delete()
            return null
        }
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(tempFile).use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            tempFile.delete()
            uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            tempFile.delete()
            null
        }
    }

    private companion object {
        const val TAG = "RooMCutNextExport"
    }
}
