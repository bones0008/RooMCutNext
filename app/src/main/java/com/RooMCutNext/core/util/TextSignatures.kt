package com.RooMCutNext.core.util

import com.RooMCutNext.core.model.TextOverlayItem

/**
 * Rebuild-signature for the text layer (v1.3.0).
 *
 * Since v1.3.0 the preview's TimedTextOverlay reads the LIVE item state
 * every frame (liveTransform hook), so VISUAL fields (position / size /
 * rotation / color / font / content) need no composition rebuild at all —
 * exactly like PiP geometry before it (see PlaybackController.refresh).
 * Only the fields baked into the composition structure still gate a
 * rebuild:
 *   - identity (id) and windows (startUs/endUs) — they pick which items
 *     carry which TimedTextOverlay and its visibility window;
 *   - the list itself (count/order) — insertion/deletion/trim-order.
 *
 * Both rebuild gates use this one function (PlaybackController.refresh and
 * OverlayPreviewController.headEffectsSignature), keeping the body and the
 * crossfade head in agreement.
 */
object TextSignatures {

    /** Structure-only hash of a text list: windows + identity, never visuals. */
    fun windows(texts: List<TextOverlayItem>): Int =
        texts.fold(1) { acc, t ->
            31 * acc + listOf(t.id, t.startUs, t.endUs).hashCode()
        }
}
