package com.RooMCutNext.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.RooMCutNext.core.model.MediaAsset
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * In-memory cache of filmstrip thumbnails for the timeline.
 * Keys are asset ids; values are ordered [ImageBitmap] lists.
 * Loads lazily from disk on the IO dispatcher.
 */
class ThumbnailStore(private val maxBitmaps: Int = 240) {

    private val cache = LinkedHashMap<String, List<ImageBitmap>>(16, 0.75f, true)
    private val mutex = Mutex()
    private val inFlight = mutableSetOf<String>()

    /** Blocking load — call from IO dispatcher. Thread-safe and idempotent. */
    suspend fun load(mediaDir: File, asset: MediaAsset): List<ImageBitmap> = mutex.withLock {
        cache[asset.id]?.let { return it }
        if (asset.id in inFlight) return emptyList()
        inFlight += asset.id
        val bitmaps = withContext(Dispatchers.IO) {
            ThumbnailGenerator.thumbFiles(mediaDir, asset)
                .mapNotNull { f ->
                    if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null
                }
        }
        inFlight -= asset.id
        if (bitmaps.isNotEmpty()) {
            cache[asset.id] = bitmaps
            trimLocked()
        }
        bitmaps
    }

    private suspend fun trimLocked() {
        var total = cache.values.sumOf { it.size }
        if (total <= maxBitmaps) return
        val newCache = LinkedHashMap<String, List<ImageBitmap>>(16, 0.75f, true)
        for (key in cache.keys.toList()) {
            val v = cache[key] ?: continue
            if (total > maxBitmaps && v.size > 1) {
                newCache[key] = v.take(1)
                total -= v.size - 1
            } else {
                newCache[key] = v
            }
        }
        cache.clear()
        cache.putAll(newCache)
    }

    fun peek(assetId: String): List<ImageBitmap>? = cache[assetId]

    suspend fun clear() = mutex.withLock { cache.clear() }
}
