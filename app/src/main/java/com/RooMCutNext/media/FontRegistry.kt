package com.RooMCutNext.media

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Central font catalog: bundled subset fonts + user-imported TTF/OTF files.
 *
 * Initialized once from [com.RooMCutNext.RooMCutNextApp] with the application
 * context (stored, never an Activity). [resolve] is called from
 * TimedTextOverlay on BOTH the preview and export paths, so the resolved
 * Typeface must be identical in both — it is, by construction.
 *
 * Missing glyphs (rare characters not in the bundled subset, emoji) fall
 * back to the system font automatically via Android's per-glyph minikin
 * fallback — nothing special to do.
 */
object FontRegistry {

    /** A selectable font in the UI. */
    data class FontSpec(
        /** Stable id stored in TextStyleSpec.fontId. */
        val id: String,
        val nameZh: String,
        val nameEn: String,
        /** Path inside assets/, null for user fonts. */
        val assetPath: String?,
        /** Imported file, null for bundled fonts. */
        val file: File?,
        /** Resolved family name from the font file (informational). */
        val familyName: String? = null,
    ) {
        val isUser: Boolean get() = file != null

        /** Locale-aware display name (user fonts usually have zh == en). */
        fun displayName(): String =
            if (java.util.Locale.getDefault().language == "zh") nameZh else nameEn
    }

    const val ID_SYSTEM = "system"

    /** Bundled fonts — display order matters (most useful first). */
    val BUNDLED: List<FontSpec> = listOf(
        FontSpec("hei", "黑体", "Sans", "fonts/font_hei.ttf", null, "Noto Sans SC Medium"),
        FontSpec("song", "宋体", "Serif", "fonts/font_song.ttf", null, "Noto Serif SC SemiBold"),
        FontSpec("kai", "楷体", "Handwriting", "fonts/font_kai.ttf", null, "LXGW WenKai"),
        FontSpec("duyi", "得意黑", "Oblique Sans", "fonts/font_duyi.ttf", null, "Smiley Sans Oblique"),
        FontSpec("kuaile", "快乐体", "Playful", "fonts/font_kuaile.ttf", null, "ZCOOL KuaiLe"),
    )

    private const val USER_DIR = "fonts"
    private const val MAX_FONT_BYTES = 30L * 1024 * 1024

    private var appContext: Context? = null

    /** Typeface cache: id -> base typeface (null entry = failed to load). */
    private val cache = ConcurrentHashMap<String, Any?>()

    @Volatile
    private var userSpecsCache: List<FontSpec> = emptyList()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** All selectable fonts: system default + bundled + user-imported. */
    fun specs(): List<FontSpec> {
        refreshUserSpecs()
        return listOf(systemSpec()) + BUNDLED + userSpecsCache
    }

    fun systemSpec(): FontSpec = FontSpec(
        ID_SYSTEM, "默认", "Default", null, null, null,
    )

    /**
     * Resolve a fontId into a ready-to-use [Typeface].
     *
     * - "system" (or unknown/failed ids) -> platform DEFAULT/_BOLD
     *   (pre-v1.1 behavior, and graceful degradation if a user font was
     *   deleted after being used in a project);
     * - bundled/user fonts -> the font file, with synthetic bold when the
     *   style asks for bold (bundled fonts ship single-weight).
     */
    fun resolve(fontId: String?, bold: Boolean): Typeface {
        if (fontId == null || fontId == ID_SYSTEM || fontId.isBlank()) {
            return if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val base = baseTypeface(fontId) ?: return if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        return if (bold) Typeface.create(base, Typeface.BOLD) else base
    }

    /** Base (non-synthesized) typeface for an id, or null when unavailable. */
    @Suppress("UNCHECKED_CAST")
    private fun baseTypeface(id: String): Typeface? {
        cache[id]?.let { return it as? Typeface }
        val loaded = loadTypeface(id)
        cache[id] = loaded
        return loaded
    }

    private fun loadTypeface(id: String): Typeface? {
        val context = appContext ?: return null
        return runCatching {
            when {
                id.startsWith("user:") -> {
                    val f = userFile(id) ?: return@runCatching null
                    if (f.exists()) Typeface.createFromFile(f) else null
                }
                else -> {
                    val spec = BUNDLED.firstOrNull { it.id == id } ?: return@runCatching null
                    spec.assetPath?.let { Typeface.createFromAsset(context.assets, it) }
                }
            }
        }.getOrNull()
    }

    /** Warm the cache for all bundled fonts (background call at app start). */
    fun preloadBundled() {
        BUNDLED.forEach { baseTypeface(it.id) }
    }

    // ------------------------------------------------------------------
    // User font import / delete
    // ------------------------------------------------------------------

    /**
     * Copy a picked font file into private storage and validate it parses.
     * Returns the new spec, or null when the file is not a usable font.
     * Call from a background thread.
     */
    fun importUri(context: Context, uri: Uri): FontSpec? {
        val resolver = context.contentResolver
        val dir = File(context.filesDir, USER_DIR).apply { mkdirs() }
        // Read into memory first (SAF streams are one-shot and fonts need
        // seekable access for validation anyway).
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size < 100 || bytes.size > MAX_FONT_BYTES) return null
        if (!looksLikeFont(bytes)) return null

        // Validate it actually parses as a typeface before keeping it.
        val tmp = File.createTempFile("fontcheck", ".ttf", context.cacheDir)
        try {
            tmp.writeBytes(bytes)
            runCatching { Typeface.createFromFile(tmp) }.getOrNull() ?: return null
            val name = sanitizeFileName(queryDisplayName(context, uri) ?: "font")
                .ifBlank { "font" }
            val base = name.removeSuffix(".ttf").removeSuffix(".otf").ifBlank { "font" }
            val file = uniqueTarget(dir, "${base}.ttf")
            file.writeBytes(bytes)
            refreshUserSpecs(force = true)
            return userSpecsCache.firstOrNull { it.file == file }
                ?: FontSpec("user:${file.name}", base, base, null, file)
        } finally {
            tmp.delete()
        }
    }

    /** Delete a user-imported font. Returns false for non-user ids. */
    fun deleteUserFont(id: String): Boolean {
        if (!id.startsWith("user:")) return false
        val f = userFile(id) ?: return false
        val ok = f.delete()
        cache.remove(id)
        refreshUserSpecs(force = true)
        return ok
    }

    private fun userFile(id: String): File? {
        val name = id.removePrefix("user:")
        if (name.isBlank() || name.contains('/') || name.contains('\\')) return null
        val ctx = appContext ?: return null
        return File(File(ctx.filesDir, USER_DIR), name)
    }

    /** Re-scan the user font directory (cheap, called by [specs]). */
    private fun refreshUserSpecs(force: Boolean = false) {
        val ctx = appContext ?: return
        val dir = File(ctx.filesDir, USER_DIR)
        if (!force && dir.isDirectory) {
            // Invalidate when the set of files changed.
            val files = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
            val sig = files.joinToString(",") { it.name }
            val oldSig = userSpecsCache.joinToString(",") { it.file?.name ?: "" }
            if (sig == oldSig) return
        }
        val files = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
        userSpecsCache = files.mapNotNull { f ->
            val base = f.nameWithoutExtension
            if (base.isBlank()) null else FontSpec("user:${f.name}", base, base, null, f)
        }
    }

    private fun uniqueTarget(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val stem = candidate.nameWithoutExtension
        for (i in 2..999) {
            candidate = File(dir, "$stem-$i.ttf")
            if (!candidate.exists()) return candidate
        }
        return File(dir, "${System.currentTimeMillis()}.ttf")
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()

    private fun looksLikeFont(bytes: ByteArray): Boolean {
        // TTF/OTF both start with 0x00 0x01 0x00 0x00 or 'OTTO'; woff starts
        // with 'wOFF'; woff2 with 'wOF2'. Accept the first two (Android's
        // Typeface does not decode woff/woff2).
        return bytes.size >= 4 && (
            (bytes[0] == 0.toByte() && bytes[1] == 1.toByte() && bytes[2] == 0.toByte() && bytes[3] == 0.toByte()) ||
                (bytes[0] == 'O'.code.toByte() && bytes[1] == 'T'.code.toByte() && bytes[2] == 'T'.code.toByte() && bytes[3] == 'O'.code.toByte()) ||
                (bytes[0] == 't'.code.toByte() && bytes[1] == 't'.code.toByte() && bytes[2] == 'c'.code.toByte() && bytes[3] == 'f'.code.toByte())
            )
    }

    /** Keep only sane filename characters (ids and display names). */
    fun sanitizeFileName(raw: String): String =
        raw.map { c ->
            val ok = c.isLetterOrDigit() || c == '.' || c == '_' || c == '-' || c == ' ' ||
                c.code in 0x4E00..0x9FA5
            if (ok) c else '_'
        }.joinToString("")
            .take(60)
}
