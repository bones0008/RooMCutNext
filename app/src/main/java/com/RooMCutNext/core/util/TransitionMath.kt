package com.RooMCutNext.core.util

import com.RooMCutNext.core.model.TransitionStyle
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The pure math behind every transition GLSL branch (v1.5.0).
 *
 * The fragment shader in [com.RooMCutNext.export.effects.TransitionEffect] is
 * a LITERAL TRANSCRIPTION of the formulas below (same pattern as
 * [MosaicGeometry] vs MosaicEffect: the Kotlin side is the reviewed,
 * unit-tested source of truth; the GLSL side inlines the same arithmetic
 * because shaders cannot be executed on the JVM). Any change here MUST be
 * mirrored in the shader and vice versa — the comments in both files point
 * at each other.
 *
 * SCREEN-UV convention (identical in Kotlin and GLSL; v=1 at the TOP of the
 * picture, the media3 texture convention — see FadeInEffect's vertex shader):
 *  - x ∈ [0,1]: left → right
 *  - y ∈ [0,1]: bottom → TOP
 *  - aspect = frame width / height
 *
 * Composite model (unchanged since v1.2.1): the frame is
 * `final = head*headAlpha + body*(1-headAlpha)` where the body shows the
 * outgoing clip A and the head carries the incoming clip B. Every style
 * below is expressible in that single-layer-animation form: the head shader
 * owns BOTH the head's color and its per-pixel alpha, so the body never has
 * to move.
 */
object TransitionMath {

    /** Soft edge width (in UV units) for wipe/slide/iris/clock masks. */
    const val EDGE_UV = 0.008f

    /** Iris/clock corner radius factor carrier; see [irisOpenRadius]. */
    private fun cornerRadius(aspect: Float): Float =
        0.5f * sqrt(aspect * aspect + 1f)

    // ------------------------------------------------------------------
    // Time — the two timestamp conventions (v1.2.1 FadeMath semantics,
    // generalized from "alpha" to "transition progress").
    // ------------------------------------------------------------------

    /**
     * Transition progress 0..1 at presentation time [tUs].
     *
     * Recognizes BOTH windows so the same effect renders identically in the
     * export pipeline (global / sequence-local clocks) and on preview players
     * (item-local source clocks — there the caller passes fadeUs = T*speed,
     * the head's SOURCE span, so the ramp lands on the timeline scale):
     *  - ITEM-LOCAL: t ∈ [0, fadeUs)        → t / fadeUs
     *  - GLOBAL:     t ∈ [start, start+fade) → (t − start) / fadeUs
     *  - outside both (frame after the window, or an unanticipated
     *    convention): 1 — the transition is over, never invisible.
     */
    fun progress(tUs: Long, fadeUs: Long, globalStartUs: Long): Float {
        if (fadeUs <= 0L) return 1f
        if (tUs >= 0L && tUs < fadeUs) {
            return (tUs.toDouble() / fadeUs.toDouble()).toFloat().coerceIn(0f, 1f)
        }
        if (tUs >= globalStartUs && tUs < globalStartUs + fadeUs) {
            return ((tUs - globalStartUs).toDouble() / fadeUs.toDouble()).toFloat().coerceIn(0f, 1f)
        }
        return 1f
    }

    // ------------------------------------------------------------------
    // slide — B travels into the frame; "sample B at uv + offset".
    // SLIDE_LEFT: B's LEFT edge leads, entering from the RIGHT screen edge
    // (the content moves leftward). Axis mapping for the others accordingly.
    // ------------------------------------------------------------------

    /** B's visibility at screen (x,y) — soft-edged 0..1. */
    fun slideMask(style: TransitionStyle, x: Float, y: Float, p: Float): Float {
        val e = EDGE_UV
        return when (style) {
            TransitionStyle.SLIDE_LEFT -> softGe(x, 1f - p, e)   // visible x ≥ 1-p
            TransitionStyle.SLIDE_RIGHT -> softLe(x, p, e)       // visible x ≤ p
            TransitionStyle.SLIDE_UP -> softLe(y, p, e)           // visible y ≤ p (bottom)
            TransitionStyle.SLIDE_DOWN -> softGe(y, 1f - p, e)    // visible y ≥ 1-p (top)
            else -> 1f
        }
    }

    /** The UV B is sampled from at screen (x,y) — [u, v]. */
    fun slideUv(style: TransitionStyle, x: Float, y: Float, p: Float): FloatArray {
        return when (style) {
            TransitionStyle.SLIDE_LEFT -> floatArrayOf(x + p - 1f, y)
            TransitionStyle.SLIDE_RIGHT -> floatArrayOf(x + 1f - p, y)
            TransitionStyle.SLIDE_UP -> floatArrayOf(x, y + 1f - p)
            TransitionStyle.SLIDE_DOWN -> floatArrayOf(x, y + p - 1f)
            else -> floatArrayOf(x, y)
        }
    }

    // ------------------------------------------------------------------
    // wipe — B stays in place; the soft reveal front sweeps across it.
    // WIPE_RIGHT: the front travels rightward, revealing from the LEFT edge.
    // ------------------------------------------------------------------

    /** B's visibility at screen (x,y) — soft-edged 0..1. */
    fun wipeMask(style: TransitionStyle, x: Float, y: Float, p: Float): Float {
        val e = EDGE_UV
        return when (style) {
            TransitionStyle.WIPE_RIGHT -> softLe(x, p, e)        // revealed x ≤ p
            TransitionStyle.WIPE_LEFT -> softGe(x, 1f - p, e)    // revealed x ≥ 1-p
            TransitionStyle.WIPE_UP -> softLe(y, p, e)            // revealed y ≤ p (bottom)
            TransitionStyle.WIPE_DOWN -> softGe(y, 1f - p, e)     // revealed y ≥ 1-p (top)
            else -> 1f
        }
    }

    // ------------------------------------------------------------------
    // iris — open grows a reveal circle from the center; close pinches A
    // through black first (head black OUTSIDE a shrinking circle), then
    // opens B inside a growing one.
    // ------------------------------------------------------------------

    /** Reveal radius for iris_open: p * R (R = corner distance). */
    fun irisOpenRadius(p: Float, aspect: Float): Float = p * cornerRadius(aspect)

    /** Circle radius for iris_close: |2p−1| * R — zero at the midpoint. */
    fun irisCloseRadius(p: Float, aspect: Float): Float = abs(2f * p - 1f) * cornerRadius(aspect)

    /**
     * iris_close phase flag: true while p < 0.5 — INSIDE the circle the
     * head is transparent (the outgoing clip A shows); outside it the head
     * is solid black. False afterwards: B inside, black outside.
     */
    fun irisShowsUnderneath(p: Float): Boolean = p < 0.5f

    // ------------------------------------------------------------------
    // clock wipe — clockwise sweep starting at 12 o'clock.
    // ------------------------------------------------------------------

    /**
     * Clock position of screen (x,y) as a 0..1 fraction of the full circle:
     * 0 = 12 o'clock, 0.25 = 3, 0.5 = 6, 0.75 = 9. The x axis is aspect-
     * corrected so the sweep reads circular on non-square frames.
     */
    fun clockAngle(x: Float, y: Float, aspect: Float): Float {
        val sx = (x - 0.5f) * aspect
        val sy = y - 0.5f
        val theta = atan2(sx, sy) // 0 at 12 (sy>0), +π/2 at 3, ±π at 6, −π/2 at 9
        var t = (theta / (2f * Math.PI.toFloat()))
        if (t < 0f) t += 1f
        return t.coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------
    // zoom / spin
    // ------------------------------------------------------------------

    /** Sampling scale: suv = 0.5 + (uv−0.5)/scale. */
    fun zoomScale(style: TransitionStyle, p: Float): Float = when (style) {
        TransitionStyle.ZOOM_IN -> 0.25f + 0.75f * p
        TransitionStyle.ZOOM_OUT -> 1f + 0.8f * (1f - p)
        else -> 1f
    }

    /** zoom_out settles in fast: fully visible by 30% progress. */
    fun zoomOutRevealAlpha(p: Float): Float = (3f * p).coerceIn(0f, 1f)

    /** spin's remaining rotation, radians (240° at p=0 → 0 at p=1). */
    fun spinAngleRad(p: Float): Float = (1f - p) * (240f * Math.PI.toFloat() / 180f)

    /** spin's sampling scale (0.4 → 1): B grows into place while unwinding. */
    fun spinScale(p: Float): Float = 0.4f + 0.6f * p

    // ------------------------------------------------------------------
    // blur / mosaic / flash / blinds / through-color
    // ------------------------------------------------------------------

    /** blur: 9-tap box blur radius in UV units (0.05 → 0, linear). */
    fun blurRadiusUv(p: Float): Float = 0.05f * (1f - p)

    /** mosaic: quantization grid density along one axis (24 → 960). */
    fun mosaicBlocks(p: Float): Float = 24f + (960f - 24f) * p

    /** mosaic: cross-fades the last-residual quantization away (p>0.92). */
    fun mosaicClearMix(p: Float): Float = ((p - 0.92f) / 0.08f).coerceIn(0f, 1f)

    /** flash: additive white pulse, peaking 0.8 at the midpoint. */
    fun flashBoost(p: Float): Float {
        val d = 1f - abs(2f * p - 1f)
        return d * d * 0.8f
    }

    /**
     * blinds: alpha of band [band] (0-based, top to bottom) — the bands
     * light up one after another as p runs 0→1.
     */
    fun blindsBandAlpha(band: Int, p: Float, bands: Int): Float =
        (p * (bands + 1f) - band).coerceIn(0f, 1f)

    /**
     * fade_black / fade_white ("through color"): the head takes the whole
     * frame over by the midpoint, then B appears inside the solid color.
     * [throughColorAlpha] is the head's coverage alpha; [throughColorTexMix]
     * is B's share inside the head color after the midpoint.
     */
    fun throughColorAlpha(p: Float): Float = (2f * p).coerceIn(0f, 1f)

    fun throughColorTexMix(p: Float): Float = (2f * p - 1f).coerceIn(0f, 1f)

    // ------------------------------------------------------------------
    // shared soft-edge helpers (the GLSL smoothstep equivalents)
    // ------------------------------------------------------------------

    /** 0..1 ramp that is 1 below [edge] and 0 above [edge]+[e]. */
    private fun softLe(v: Float, edge: Float, e: Float): Float =
        ((edge + e - v) / (2f * e)).coerceIn(0f, 1f)

    /** 0..1 ramp that is 0 below [edge]−[e] and 1 above [edge]. */
    private fun softGe(v: Float, edge: Float, e: Float): Float =
        ((v - edge + e) / (2f * e)).coerceIn(0f, 1f)
}
