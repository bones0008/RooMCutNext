package com.RooMCutNext.media

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.RooMCutNext.core.model.MediaAsset
import com.RooMCutNext.core.util.IdGen
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports media by COPYING the picked files into the project's private media
 * directory. This is deliberate: photo-picker and SAF permissions are
 * ephemeral, and a project that references stale content URIs breaks after a
 * reboot. Owning the files also makes metadata/thumbnail generation reliable.
 */
class MediaImporter(private val context: Context) {

    data class Outcome(
        val assets: List<MediaAsset>,
        val failedNames: List<String>,
    )

    suspend fun import(
        uris: List<Uri>,
        mediaDir: File,
        onProgress: (doneCount: Int, totalCount: Int, currentName: String) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val assets = mutableListOf<MediaAsset>()
        val failed = mutableListOf<String>()
        uris.forEachIndexed { index, uri ->
            val display = uri.queryDisplayName(context) ?: "file_${index + 1}"
            onProgress(index, uris.size, display)
            runCatching {
                val file = copyInto(mediaDir, uri, display)
                val asset = MetadataExtractor.extract(file)
                if (asset == null) {
                    file.delete()
                    failed.add(display)
                } else {
                    ThumbnailGenerator.generate(file, asset)
                    assets.add(asset)
                }
            }.onFailure { failed.add(display) }
        }
        onProgress(uris.size, uris.size, "")
        Outcome(assets, failed)
    }

    private fun copyInto(mediaDir: File, uri: Uri, displayName: String): File {
        val ext = extensionOf(uri, displayName)
        val safeName = "${IdGen.new("imp")}.$ext"
        val target = File(mediaDir, safeName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("cannot open $uri")
        return target
    }

    private fun extensionOf(uri: Uri, displayName: String): String {
        val fromName = displayName.substringAfterLast('.', "").lowercase()
        if (fromName in SUPPORTED_EXTENSIONS) return fromName
        val mime = context.contentResolver.getType(uri)
        if (mime != null) {
            val fromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            if (fromMime != null) return fromMime
        }
        return "bin"
    }

    companion object {
        val SUPPORTED_EXTENSIONS = setOf(
            "mp4", "mov", "m4v", "mkv", "webm", "3gp", "3g2",
            "jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif",
        )
    }
}
