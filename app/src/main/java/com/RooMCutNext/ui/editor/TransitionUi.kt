package com.RooMCutNext.ui.editor

import androidx.annotation.StringRes
import com.RooMCutNext.R
import com.RooMCutNext.core.model.TransitionStyle

/**
 * v1.5.0 — the UI string mapping for the transition catalog (single source:
 * the panel grid, the group headers and the timeline marker all resolve
 * their labels here, never inline).
 *
 * Full names go on the panel chips; short names go on the timeline marker
 * pill (canvas-drawn, tight width); group titles head the panel sections.
 */
object TransitionUi {

    /** Panel chip label for a style. */
    @StringRes
    fun nameRes(style: TransitionStyle): Int = when (style) {
        TransitionStyle.DISSOLVE -> R.string.transition_name_dissolve
        TransitionStyle.FADE_BLACK -> R.string.transition_name_fade_black
        TransitionStyle.FADE_WHITE -> R.string.transition_name_fade_white
        TransitionStyle.SLIDE_LEFT -> R.string.transition_name_slide_left
        TransitionStyle.SLIDE_RIGHT -> R.string.transition_name_slide_right
        TransitionStyle.SLIDE_UP -> R.string.transition_name_slide_up
        TransitionStyle.SLIDE_DOWN -> R.string.transition_name_slide_down
        TransitionStyle.WIPE_LEFT -> R.string.transition_name_wipe_left
        TransitionStyle.WIPE_RIGHT -> R.string.transition_name_wipe_right
        TransitionStyle.WIPE_UP -> R.string.transition_name_wipe_up
        TransitionStyle.WIPE_DOWN -> R.string.transition_name_wipe_down
        TransitionStyle.IRIS_OPEN -> R.string.transition_name_iris_open
        TransitionStyle.IRIS_CLOSE -> R.string.transition_name_iris_close
        TransitionStyle.CLOCK_WIPE -> R.string.transition_name_clock_wipe
        TransitionStyle.ZOOM_IN -> R.string.transition_name_zoom_in
        TransitionStyle.ZOOM_OUT -> R.string.transition_name_zoom_out
        TransitionStyle.SPIN -> R.string.transition_name_spin
        TransitionStyle.BLUR -> R.string.transition_name_blur
        TransitionStyle.MOSAIC -> R.string.transition_name_mosaic
        TransitionStyle.FLASH -> R.string.transition_name_flash
        TransitionStyle.BLINDS -> R.string.transition_name_blinds
    }

    /** Timeline marker pill label (short form, canvas-drawn). */
    @StringRes
    fun shortRes(styleId: String): Int = when (TransitionStyle.fromId(styleId)) {
        TransitionStyle.DISSOLVE -> R.string.transition_short_dissolve
        TransitionStyle.FADE_BLACK -> R.string.transition_short_fade_black
        TransitionStyle.FADE_WHITE -> R.string.transition_short_fade_white
        TransitionStyle.SLIDE_LEFT -> R.string.transition_short_slide_left
        TransitionStyle.SLIDE_RIGHT -> R.string.transition_short_slide_right
        TransitionStyle.SLIDE_UP -> R.string.transition_short_slide_up
        TransitionStyle.SLIDE_DOWN -> R.string.transition_short_slide_down
        TransitionStyle.WIPE_LEFT -> R.string.transition_short_wipe_left
        TransitionStyle.WIPE_RIGHT -> R.string.transition_short_wipe_right
        TransitionStyle.WIPE_UP -> R.string.transition_short_wipe_up
        TransitionStyle.WIPE_DOWN -> R.string.transition_short_wipe_down
        TransitionStyle.IRIS_OPEN -> R.string.transition_short_iris_open
        TransitionStyle.IRIS_CLOSE -> R.string.transition_short_iris_close
        TransitionStyle.CLOCK_WIPE -> R.string.transition_short_clock_wipe
        TransitionStyle.ZOOM_IN -> R.string.transition_short_zoom_in
        TransitionStyle.ZOOM_OUT -> R.string.transition_short_zoom_out
        TransitionStyle.SPIN -> R.string.transition_short_spin
        TransitionStyle.BLUR -> R.string.transition_short_blur
        TransitionStyle.MOSAIC -> R.string.transition_short_mosaic
        TransitionStyle.FLASH -> R.string.transition_short_flash
        TransitionStyle.BLINDS -> R.string.transition_short_blinds
    }

    /** Panel section header for a group. */
    @StringRes
    fun groupTitleRes(group: TransitionStyle.Group): Int = when (group) {
        TransitionStyle.Group.BASIC -> R.string.transition_group_basic
        TransitionStyle.Group.SLIDE -> R.string.transition_group_slide
        TransitionStyle.Group.WIPE -> R.string.transition_group_wipe
        TransitionStyle.Group.EFFECT -> R.string.transition_group_effect
    }

    /**
     * The catalog grouped for the panel, in display order.
     *
     * v1.5.5: ZOOM_IN / ZOOM_OUT are HIDDEN from the panel (field report:
     * "转场中的缩放没有反应…直接删掉这个效果" — the zoom pair reads as a
     * dead effect on the reported device, and a zoom belongs at a clip's
     * ending, not between two clips). The enum entries stay: ordinals are
     * the shader branch indices (DO NOT renumber — see TransitionStyle),
     * and projects that already saved a zoom transition keep rendering it.
     */
    fun grouped(): List<Pair<TransitionStyle.Group, List<TransitionStyle>>> =
        TransitionStyle.Group.entries.map { g ->
            g to TransitionStyle.entries.filter { it.group == g && it !in PanelHidden }
        }

    /** v1.5.5: styles removed from the panel grid (kept in the catalog). */
    private val PanelHidden =
        setOf(TransitionStyle.ZOOM_IN, TransitionStyle.ZOOM_OUT)
}
