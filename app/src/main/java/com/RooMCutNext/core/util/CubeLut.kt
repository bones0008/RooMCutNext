package com.RooMCutNext.core.util

/**
 * Parser and in-memory representation of the Adobe `.cube` 3D LUT format.
 *
 * PURE KOTLIN (no android.* deps) so it is unit-testable on the JVM.
 *
 * Format reference (as emitted by Premiere/Resolve and thousands of free LUTs):
 * ```
 * TITLE "Some Look"
 * LUT_3D_SIZE 33
 * DOMAIN_MIN 0.0 0.0 0.0
 * DOMAIN_MAX 1.0 1.0 1.0
 * r g b          <- 33^3 lines, RED varies fastest (line index = r + N*g + N^2*b)
 * ```
 *
 * `LUT_1D_SIZE` files are NOT 3D LUTs and are rejected.
 *
 * The parsed grid is stored in the SAME r-fastest order as the file
 * ([rgb], `rgb[i*3 + j]`, i = r + N*g + N^2*b), which is ALSO the order
 * `LutRegistry.toBitmap` consumes. DOMAIN_MIN/MAX are parsed but ignored
 * (all real-world files use 0..1; non-0..1 domains are vanishingly rare and
 * ignoring them degrades gracefully).
 */
class CubeLut private constructor(
    /** Grid edge length N (2..129). */
    val size: Int,
    /** Flat RGB triples, N^3 entries, r-fastest file order. */
    val rgb: FloatArray,
    /** Optional title from the file, without quotes. */
    val title: String?,
) {
    companion object {
        /** Grid sizes above this are rejected (130^3 > 2M entries is absurd). */
        const val MAX_SIZE = 129

        /**
         * Parse `.cube` text. Whitespace-tolerant, comment (`#`) and
         * blank-line tolerant, accepts CRLF.
         *
         * @throws IllegalArgumentException on structural errors (no
         *   LUT_3D_SIZE, bad size, non-numeric or out-of-range samples,
         *   wrong sample count).
         */
        fun parse(text: String): CubeLut {
            var size = -1
            var title: String? = null
            val samples = ArrayList<Float>(16 * 1024)

            text.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                when {
                    line.startsWith("LUT_3D_SIZE", ignoreCase = true) -> {
                        val v = line.substringAfter(' ').trim().toIntOrNull()
                            ?: throw bad("LUT_3D_SIZE is not an integer: \"$line\"")
                        if (v < 2 || v > MAX_SIZE) throw bad("LUT_3D_SIZE $v out of range 2..$MAX_SIZE")
                        if (size != -1 && size != v) throw bad("conflicting LUT_3D_SIZE values")
                        size = v
                    }
                    line.startsWith("LUT_1D_SIZE", ignoreCase = true) ->
                        throw bad("1D LUTs are not supported (this is a 3D-LUT feature)")
                    line.startsWith("TITLE", ignoreCase = true) -> {
                        title = line.substringAfter('"', "").substringBefore('"').ifBlank { null }
                            ?: line.removePrefix("TITLE").trim().removeSurrounding("\"").ifBlank { null }
                    }
                    line.startsWith("DOMAIN_", ignoreCase = true) ||
                        line.startsWith("LUT_3D_INPUT", ignoreCase = true) -> Unit // ignored
                    else -> {
                        // Data line: three floats.
                        val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
                        if (parts.size < 3) throw bad("malformed data line: \"$line\"")
                        for (i in 0 until 3) {
                            val f = parts[i].toFloatOrNull()
                                ?: throw bad("non-numeric sample \"${parts[i]}\"")
                            if (!f.isFinite()) throw bad("non-finite sample \"$f\"")
                            samples.add(f.coerceIn(0f, 1f))
                        }
                    }
                }
            }

            if (size < 0) throw bad("missing LUT_3D_SIZE header")
            val expected = size * size * size * 3
            if (samples.size != expected) {
                throw bad("expected ${expected} floats for size $size, got ${samples.size}")
            }
            return CubeLut(size, samples.toFloatArray(), title)
        }

        private fun bad(msg: String): IllegalArgumentException =
            IllegalArgumentException("Bad .cube file: $msg")
    }

    /** Sample the LUT at integer grid coordinates (r, g, b in 0..size-1). */
    fun grid(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val i = (r + size * (g + size * b)) * 3
        return Triple(rgb[i], rgb[i + 1], rgb[i + 2])
    }

    /**
     * 8 anchor colors pushed through the LUT — thumbnail swatches for the UI.
     * Returns ARGB ints. Handles the identity case implicitly.
     */
    fun previewSwatches(): IntArray {
        val anchors = arrayOf(
            floatArrayOf(0.13f, 0.13f, 0.13f),   // near-black
            floatArrayOf(0.35f, 0.28f, 0.22f),   // dark warm (shadow skin)
            floatArrayOf(0.5f, 0.5f, 0.5f),      // mid gray
            floatArrayOf(0.72f, 0.55f, 0.42f),   // skin tone
            floatArrayOf(0.30f, 0.45f, 0.70f),   // sky blue
            floatArrayOf(0.35f, 0.60f, 0.35f),   // foliage green
            floatArrayOf(0.85f, 0.85f, 0.85f),   // light gray
            floatArrayOf(0.97f, 0.97f, 0.97f),   // near-white
        )
        return IntArray(anchors.size) { idx ->
            val (r, g, b) = anchors[idx]
            val (or_, og, ob) = sample(r, g, b)
            (0xFF shl 24) or
                ((or_ * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                ((og * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                (ob * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
    }

    /** Trilinear sample at arbitrary RGB in 0..1. */
    fun sample(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val n = size - 1
        val rf = (r.coerceIn(0f, 1f) * n)
        val gf = (g.coerceIn(0f, 1f) * n)
        val bf = (b.coerceIn(0f, 1f) * n)
        val r0 = rf.toInt().coerceAtMost(n - 1)
        val g0 = gf.toInt().coerceAtMost(n - 1)
        val b0 = bf.toInt().coerceAtMost(n - 1)
        val r1 = r0 + 1
        val g1 = g0 + 1
        val b1 = b0 + 1
        val tr = rf - r0
        val tg = gf - g0
        val tb = bf - b0

        fun lerp(a: Float, b2: Float, t: Float) = a + (b2 - a) * t

        fun at(rr: Int, gg: Int, bb: Int): Triple<Float, Float, Float> = grid(rr, gg, bb)

        val c000 = at(r0, g0, b0); val c100 = at(r1, g0, b0)
        val c010 = at(r0, g1, b0); val c110 = at(r1, g1, b0)
        val c001 = at(r0, g0, b1); val c101 = at(r1, g0, b1)
        val c011 = at(r0, g1, b1); val c111 = at(r1, g1, b1)

        fun mixTrio(x: Triple<Float, Float, Float>, y: Triple<Float, Float, Float>, t: Float) =
            Triple(lerp(x.first, y.first, t), lerp(x.second, y.second, t), lerp(x.third, y.third, t))

        val c00 = mixTrio(c000, c100, tr)
        val c10 = mixTrio(c010, c110, tr)
        val c01 = mixTrio(c001, c101, tr)
        val c11 = mixTrio(c011, c111, tr)
        val c0 = mixTrio(c00, c10, tg)
        val c1 = mixTrio(c01, c11, tg)
        return mixTrio(c0, c1, tb)
    }

    override fun equals(other: Any?): Boolean = this === other ||
        (other is CubeLut && other.size == size && other.rgb.contentEquals(rgb))

    override fun hashCode(): Int = 31 * size + rgb.contentHashCode()
}
