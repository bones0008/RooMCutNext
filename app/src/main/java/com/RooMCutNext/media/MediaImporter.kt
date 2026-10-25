package com.RooMCutNext.media

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.RooMCutNext.R
import com.RooMCutNext.core.model.BLACK_FRAME_ASSET_ID
import com.RooMCutNext.core.model.BLACK_FRAME_FILE_NAME
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

    /**
     * v1.6.1 黑场：确保项目媒体目录里存在内置纯黑图片素材，返回带稳定
     * 身份（[BLACK_FRAME_ASSET_ID]，displayName 本地化）的资产对象。
     *
     * 文件只在首次缺失时从 APK assets/media/ 复制一次（6KB）；已存在则
     * 直接复用（项目重新打开、多段黑场共享同一资产）。返回 null 仅当
     * PNG 写入失败或元数据解码失败（几乎不可发生：文件随 APK 分发）。
     * 缩略图生成失败静默忽略 —— 时间线对黑场走专属渲染（斜纹+标签），
     * 不依赖缩略图。
     */
    suspend fun ensureBlackFrameAsset(mediaDir: File): MediaAsset? = withContext(Dispatchers.IO) {
        val target = File(mediaDir, BLACK_FRAME_FILE_NAME)
        runCatching {
            if (!target.exists()) {
                mediaDir.mkdirs()
                context.assets.open("media/$BLACK_FRAME_FILE_NAME").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            // 元数据来自真实文件（宽高/字节数），身份重写为稳定 id。
            val extracted = MetadataExtractor.extract(target)?.copy(
                id = BLACK_FRAME_ASSET_ID,
                displayName = context.getString(R.string.black_frame_name),
            ) ?: return@runCatching null
            runCatching { ThumbnailGenerator.generate(target, extracted) }
            extracted
        }.getOrNull()
    }

    companion object {
        val SUPPORTED_EXTENSIONS = setOf(
            "mp4", "mov", "m4v", "mkv", "webm", "3gp", "3g2",
            "jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif",
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus",
            "amr", "aiff", "aif", "wma", "mka", "3ga", "awb",
        )
    }
}
