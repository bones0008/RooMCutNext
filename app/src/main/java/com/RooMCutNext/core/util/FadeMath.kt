package com.RooMCutNext.core.util

/**
 * Pure alpha math for the crossfade-in shader program (v1.2.1).
 *
 * The GL pipeline feeds shader programs per-frame presentation timestamps,
 * and the exact convention differs between consumers: CompositionPlayer adds
 * the sequence window offset (GLOBAL timeline timestamps — the behavior the
 * v1.0.8 text-overlay verification established for the primary sequence),
 * while Transformer's per-sequence asset loaders have historically wobbled
 * between item-local and sequence-local conventions.
 *
 * [fadeInAlpha] therefore recognizes BOTH windows and is a no-op (alpha 1)
 * outside them, so the same effect renders identically whichever convention
 * a consumer applies:
 *
 *  - ITEM-LOCAL: the head item's frames arrive as t ∈ [0, fadeUs) — ramp t/fadeUs.
 *  - GLOBAL / SEQUENCE-LOCAL: frames arrive as t ∈ [globalStartUs, globalStartUs+fadeUs)
 *    — ramp (t − globalStartUs)/fadeUs. A sequence starting at 0 makes these
 *    identical, which is why one extra window check covers both.
 *
 * The tiny ambiguity when the two windows overlap (a crossfade on the first
 * two clips, globalStartUs < fadeUs) resolves toward the item-local ramp —
 * a sub-fade-length timing skew on a cosmetic ramp, imperceptible in practice.
 */
object FadeMath {

    /**
     * Alpha of a fading-in head frame at presentation time [tUs].
     *
     * @param tUs            presentation timestamp of the frame, microseconds
     * @param fadeUs         fade duration, microseconds (> 0)
     * @param globalStartUs  the head item's start on the GLOBAL timeline
     */
    fun fadeInAlpha(tUs: Long, fadeUs: Long, globalStartUs: Long): Float {
        if (fadeUs <= 0L) return 1f
        // Item-local window [0, fadeUs).
        if (tUs >= 0L && tUs < fadeUs) {
            return (tUs.toDouble() / fadeUs.toDouble()).toFloat().coerceIn(0f, 1f)
        }
        // Global / sequence-local window [globalStartUs, globalStartUs + fadeUs).
        if (tUs >= globalStartUs && tUs < globalStartUs + fadeUs) {
            return ((tUs - globalStartUs).toDouble() / fadeUs.toDouble()).toFloat().coerceIn(0f, 1f)
        }
        // Outside both windows (frame after the fade completed, or a consumer
        // convention we did not anticipate): fully opaque, never invisible.
        return 1f
    }
}
