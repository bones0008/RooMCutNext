package com.RooMCutNext.core.model

/**
 * The transition style catalog (v1.5.0) — the SINGLE SOURCE OF TRUTH for
 * every transition type id in the app. Everything keys off these ids:
 *  - [Clip.transitionType] (project JSON),
 *  - the GL shader branch in
 *    [com.RooMCutNext.export.effects.TransitionEffect] (branch index =
 *    [ordinal], kept in lockstep),
 *  - the transition panel grid and the timeline marker (ids -> string res).
 *
 * The catalog covers the transitions a CapCut-style editor uses, grouped the
 * way those editors group them:
 *  - BASIC  : plain alpha composites (dissolve / fade through black / white);
 *  - SLIDE  : the incoming clip travels into the frame (leading edge starts
 *             off-screen), e.g. slide_left = B slides in from the right edge;
 *  - WIPE   : the incoming clip sits in place and a soft reveal front sweeps
 *             over it (directional wipes, iris open/close, clock sweep);
 *  - EFFECT : pixel-level looks (zoom, spin, blur, mosaic, flash, blinds).
 *
 * Backwards compatibility: ids are persisted strings; a build that meets an
 * unknown or missing id falls back to [DISSOLVE] via [fromId] — the exact
 * behavior every version up to v1.4.0 shipped (there was only the crossfade).
 * DO NOT renumber existing entries (ordinal = shader branch); append new
 * styles at the end of their group.
 */
enum class TransitionStyle(val id: String, val group: Group) {

    // ---- BASIC (alpha composites) ----
    DISSOLVE("dissolve", Group.BASIC),
    FADE_BLACK("fade_black", Group.BASIC),
    FADE_WHITE("fade_white", Group.BASIC),

    // ---- SLIDE (the incoming clip moves in) ----
    SLIDE_LEFT("slide_left", Group.SLIDE),
    SLIDE_RIGHT("slide_right", Group.SLIDE),
    SLIDE_UP("slide_up", Group.SLIDE),
    SLIDE_DOWN("slide_down", Group.SLIDE),

    // ---- WIPE (reveal front sweeps; B stays in place) ----
    WIPE_LEFT("wipe_left", Group.WIPE),
    WIPE_RIGHT("wipe_right", Group.WIPE),
    WIPE_UP("wipe_up", Group.WIPE),
    WIPE_DOWN("wipe_down", Group.WIPE),
    IRIS_OPEN("iris_open", Group.WIPE),
    IRIS_CLOSE("iris_close", Group.WIPE),
    CLOCK_WIPE("clock_wipe", Group.WIPE),

    // ---- EFFECT (pixel-level looks) ----
    ZOOM_IN("zoom_in", Group.EFFECT),
    ZOOM_OUT("zoom_out", Group.EFFECT),
    SPIN("spin", Group.EFFECT),
    BLUR("blur", Group.EFFECT),
    MOSAIC("mosaic", Group.EFFECT),
    FLASH("flash", Group.EFFECT),
    BLINDS("blinds", Group.EFFECT);

    /** Panel sections, rendered in this order. */
    enum class Group { BASIC, SLIDE, WIPE, EFFECT }

    companion object {
        /** Old/unknown/missing ids degrade to the classic crossfade. */
        fun fromId(id: String?): TransitionStyle =
            entries.firstOrNull { it.id == id } ?: DISSOLVE
    }
}
