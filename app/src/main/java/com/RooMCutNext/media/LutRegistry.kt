package com.RooMCutNext.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.effect.SingleColorLut
import com.RooMCutNext.core.util.CubeLut
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Central LUT (.cube) filter catalog: 8 bundled looks + user-imported files.
 *
 * Media3's [SingleColorLut] wants an ARGB_8888 bitmap of N x N^2 where the
 * z-plane (RED) is stacked vertically — i.e. pixel (x=b, y=g + N*r) holds
 * LUT(r, g, b). This is `LUT[R][G][B] = LUT[z][y][x]` per the shipped
 * fragment_shader_lut_es2.glsl, and matches SingleColorLut's own
 * transformCubeIntoBitmap (verified against the 1.11.1 AAR bytecode).
 *
 * A .cube file lists samples with RED varying fastest (index = r + N*g +
 * N^2*b), which is exactly the parse order [CubeLut] keeps — so the
 * conversion below is a straight index remap, no axis juggling.
 */
object LutRegistry {

    /** A selectable LUT in the UI. */
    data class LutSpec(
        /** Stable id stored in ColorAdjust.lutId: "builtin:<name>" / "user:<fileName>". */
        val id: String,
        val nameZh: String,
        val nameEn: String,
        /** .cube source file (assets are extracted to cacheDir at first use). */
        val file: File?,
        /** Builtin asset path when [file] points at the extracted copy. */
        val assetPath: String? = null,
    ) {
        val isUser: Boolean get() = id.startsWith("user:")

        /** Locale-aware display name. */
        fun displayName(): String =
            if (java.util.Locale.getDefault().language == "zh") nameZh else nameEn
    }

    /** Bundled looks — display order matters. */
    val BUNDLED: List<LutSpec> = listOf(
        LutSpec("builtin:warm", "暖阳", "Warm", null, "luts/lut_warm.cube"),
        LutSpec("builtin:cool", "冷调", "Cool", null, "luts/lut_cool.cube"),
        LutSpec("builtin:cine", "电影感", "Cinematic", null, "luts/lut_cine.cube"),
        LutSpec("builtin:vintage", "复古", "Vintage", null, "luts/lut_vintage.cube"),
        LutSpec("builtin:soft", "日系", "Soft", null, "luts/lut_soft.cube"),
        LutSpec("builtin:punch", "高对比", "Punch", null, "luts/lut_punch.cube"),
        LutSpec("builtin:mono", "黑白", "Mono", null, "luts/lut_mono.cube"),
        LutSpec("builtin:sepia", "怀旧", "Sepia", null, "luts/lut_sepia.cube"),
    )

    private const val USER_DIR = "luts"
    private const val MAX_CUBE_BYTES = 64L * 1024 * 1024

    private var appContext: Context? = null

    /** id -> parsed cube (null entry = failed to load). */
    private val cubeCache = ConcurrentHashMap<String, Any?>()

    /** "(id):(strength rounded to 5%)" -> bitmap, capped. */
    private val bitmapCache = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
    private val bitmapLock = Any()
    private const val MAX_CACHED_BITMAPS = 16

    @Volatile
    private var userSpecsCache: List<LutSpec> = emptyList()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun specs(): List<LutSpec> {
        refreshUserSpecs()
        return BUNDLED + userSpecsCache
    }

    // ------------------------------------------------------------------
    // Effect creation (called from CompositionBuilder)
    // ------------------------------------------------------------------

    /**
     * Build a [SingleColorLut] effect for a lutId at the given strength
     * (0..1). Returns null when the id is unknown, its cube failed to load,
     * or strength is ~0 (identity — cheaper to skip).
     *
     * A NEW effect instance is returned on every call: effect instances are
     * per-EditedMediaItem and own their GL texture lifecycle (release() is
     * invoked by the framework per item), while the source bitmap is shared
     * from the cache.
     */
    fun effectFor(lutId: String?, strength: Float): SingleColorLut? {
        if (lutId == null || strength <= 0.02f) return null
        val bitmap = bitmapFor(lutId, strength) ?: return null
        return runCatching { SingleColorLut.createFromBitmap(bitmap) }.getOrNull()
    }

    /**
     * Thumbnail swatches (8 ARGB ints) for the UI; neutral grays when the
     * cube is not loaded (yet).
     */
    fun swatchesFor(lutId: String?): IntArray {
        if (lutId == null) return NEUTRAL_SWATCHES
        val cube = cubeFor(lutId) ?: return NEUTRAL_SWATCHES
        return cube.previewSwatches()
    }

    private val NEUTRAL_SWATCHES = intArrayOf(
        0xFF212121.toInt(), 0xFF404040.toInt(), 0xFF606060.toInt(), 0xFF808080.toInt(),
        0xFFA0A0A0.toInt(), 0xFFC0C0C0.toInt(), 0xFFE0E0E0.toInt(), 0xFFF5F5F5.toInt(),
    )

    // ------------------------------------------------------------------
    // Parsing / bitmap building
    // ------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun cubeFor(lutId: String): CubeLut? {
        cubeCache[lutId]?.let { return it as? CubeLut }
        val cube = loadCube(lutId) ?: run {
            cubeCache[lutId] = null
            return null
        }
        cubeCache[lutId] = cube
        return cube
    }

    /** Parse a .cube from its text; public for tests/tools. */
    fun parseCube(text: String): CubeLut = CubeLut.parse(text)

    private fun loadCube(lutId: String): CubeLut? {
        val context = appContext ?: return null
        val text = when {
            lutId.startsWith("builtin:") -> {
                val spec = BUNDLED.firstOrNull { it.id == lutId } ?: return null
                runCatching { context.assets.open(spec.assetPath ?: return null) }
                    .getOrNull()?.use { it.bufferedReader().readText() } ?: return null
            }
            lutId.startsWith("user:") -> {
                val f = userFile(lutId) ?: return null
                if (!f.exists()) return null
                runCatching { f.readText() }.getOrNull() ?: return null
            }
            else -> return null
        }
        return runCatching { CubeLut.parse(text) }.getOrNull()
    }

    /**
     * Shared bitmap for (lutId, strength). Strength is rounded to 5% steps
     * in the cache key so a slider drag cannot flood the cache; each cached
     * bitmap for a 65^3 LUT is ~1MB, 17^3 ~130KB.
     */
    private fun bitmapFor(lutId: String, strength: Float): Bitmap? {
        val cube = cubeFor(lutId) ?: return null
        val s = strength.coerceIn(0f, 1f)
        val key = "$lutId:${(s * 20f).toInt()}"
        synchronized(bitmapLock) {
            bitmapCache[key]?.let { return it }
        }
        val bmp = toBitmap(cube, s)
        synchronized(bitmapLock) {
            bitmapCache[key] = bmp
            while (bitmapCache.size > MAX_CACHED_BITMAPS) {
                val eldest = bitmapCache.keys.firstOrNull() ?: break
                bitmapCache.remove(eldest)
            }
        }
        return bmp
    }

    /**
     * Convert a parsed cube into Media3's N x N^2 ARGB_8888 strip.
     * With strength < 1 the LUT output is blended with the identity grid
     * (out = lut*strength + in*(1-strength)) BEFORE quantization, so the
     * interpolation stays smooth.
     */
    fun toBitmap(cube: CubeLut, strength: Float): Bitmap {
        val n = cube.size
        val s = strength.coerceIn(0f, 1f)
        val blend = s < 0.999f
        val colors = IntArray(n * n * n)
        for (b in 0 until n) {
            for (g in 0 until n) {
                for (r in 0 until n) {
                    val i = (r + n * g + n * n * b) * 3
                    var rr = cube.rgb[i]
                    var gg = cube.rgb[i + 1]
                    var bb = cube.rgb[i + 2]
                    if (blend) {
                        val inv = 1f - s
                        rr = rr * s + (r.toFloat() / (n - 1)) * inv
                        gg = gg * s + (g.toFloat() / (n - 1)) * inv
                        bb = bb * s + (b.toFloat() / (n - 1)) * inv
                    }
                    // Bitmap pixel: x = blue, y = green + N * red.
                    colors[b + n * (g + n * r)] =
                        (0xFF shl 24) or
                            (to255(rr) shl 16) or (to255(gg) shl 8) or to255(bb)
                }
            }
        }
        return Bitmap.createBitmap(colors, n, n * n, Bitmap.Config.ARGB_8888)
    }

    private fun to255(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    /** Preload all builtin cubes (background call at app start). */
    fun preloadBundled() {
        BUNDLED.forEach { cubeFor(it.id) }
    }

    /**
     * Preload builtin AND user-imported cubes (background call at app
     * start). Keeps the color panel's swatch rendering off the main thread
     * even for freshly-restarted apps with big 33^3 user LUTs.
     */
    fun preloadAll() {
        refreshUserSpecs(force = true)
        BUNDLED.forEach { cubeFor(it.id) }
        userSpecsCache.forEach { cubeFor(it.id) }
    }

    // ------------------------------------------------------------------
    // User LUT import / delete
    // ------------------------------------------------------------------

    /**
     * Import a picked .cube file: read, parse (validation), persist.
     * Returns the new spec or null when the file is not a valid 3D LUT.
     * Call from a background thread (65^3 files parse in ~1-2s).
     */
    fun importUri(context: Context, uri: Uri): LutSpec? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return null
        if (bytes.size < 16 || bytes.size > MAX_CUBE_BYTES) return null
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
        val cube = runCatching { CubeLut.parse(text) }.getOrNull() ?: return null

        val dir = File(context.filesDir, USER_DIR).apply { mkdirs() }
        val rawName = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
        val base = FontRegistry.sanitizeFileName(rawName ?: "lut")
            .removeSuffix(".cube").ifBlank { "lut" }
        var file = File(dir, "$base.cube")
        if (file.exists()) {
            var i = 2
            while (file.exists() && i < 999) {
                file = File(dir, "$base-$i.cube"); i++
            }
        }
        file.writeText(text)

        refreshUserSpecs(force = true)
        // Warm the cache right away so the effect is instant on apply.
        cubeCache[fileSpecId(file.name)] = cube
        return userSpecsCache.firstOrNull { it.file == file }
            ?: LutSpec(fileSpecId(file.name), base, base, file)
    }

    /** Delete a user-imported LUT. Returns false for non-user ids. */
    fun deleteUserLut(id: String): Boolean {
        if (!id.startsWith("user:")) return false
        val f = userFile(id) ?: return false
        val ok = f.delete()
        cubeCache.remove(id)
        synchronized(bitmapLock) {
            bitmapCache.keys.filter { it.startsWith("$id:") }.forEach { bitmapCache.remove(it) }
        }
        refreshUserSpecs(force = true)
        return ok
    }

    private fun fileSpecId(fileName: String): String = "user:$fileName"

    private fun userFile(id: String): File? {
        val name = id.removePrefix("user:")
        if (name.isBlank() || name.contains('/') || name.contains('\\')) return null
        val ctx = appContext ?: return null
        return File(File(ctx.filesDir, USER_DIR), name)
    }

    private fun refreshUserSpecs(force: Boolean = false) {
        val ctx = appContext ?: return
        val dir = File(ctx.filesDir, USER_DIR)
        val files = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
        if (!force) {
            val sig = files.joinToString(",") { it.name }
            val oldSig = userSpecsCache.joinToString(",") { it.file?.name ?: "" }
            if (sig == oldSig) return
        }
        userSpecsCache = files.mapNotNull { f ->
            val base = f.nameWithoutExtension
            if (base.isBlank()) null else LutSpec(fileSpecId(f.name), base, base, f)
        }
    }
}
