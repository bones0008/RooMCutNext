package com.RooMCutNext.ui.editor

import com.RooMCutNext.core.model.TransitionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.5 — the transition PANEL hides the zoom pair (field report: "转场中
 * 的缩放没有反应，直接删掉这个效果" — the zoom reads as a dead effect on the
 * reported device, and a zoom belongs at a clip's ending, not between two
 * clips).
 *
 * The catalog itself keeps both entries: ordinals are shader branch indices
 * (DO NOT renumber — TransitionStyle's contract), and projects that already
 * saved a zoom transition keep rendering it. These tests pin BOTH halves of
 * that contract so a future refactor cannot silently drop one.
 */
class TransitionUiTest {

    @Test
    fun `panel grid hides the zoom pair`() {
        val flat = TransitionUi.grouped().flatMap { it.second }
        assertFalse(flat.contains(TransitionStyle.ZOOM_IN))
        assertFalse(flat.contains(TransitionStyle.ZOOM_OUT))
    }

    @Test
    fun `panel grid keeps every other style`() {
        val flat = TransitionUi.grouped().flatMap { it.second }
        val expected = TransitionStyle.entries - setOf(TransitionStyle.ZOOM_IN, TransitionStyle.ZOOM_OUT)
        assertEquals(expected.size, flat.size)
        assertTrue(flat.containsAll(expected))
    }

    @Test
    fun `panel grid keeps the group order and fills the EFFECT group`() {
        val groups = TransitionUi.grouped().map { it.first }
        assertEquals(TransitionStyle.Group.entries, groups)
        val effect = TransitionUi.grouped().last { it.first == TransitionStyle.Group.EFFECT }.second
        assertEquals(
            listOf(
                TransitionStyle.SPIN, TransitionStyle.BLUR, TransitionStyle.MOSAIC,
                TransitionStyle.FLASH, TransitionStyle.BLINDS,
            ),
            effect,
        )
    }

    @Test
    fun `catalog ordinals are stable for the shader branch map`() {
        // The GL shader branches on ordinal — the v1.5.5 panel removal must
        // not have touched the catalog order.
        assertEquals(0, TransitionStyle.DISSOLVE.ordinal)
        assertEquals(3, TransitionStyle.SLIDE_LEFT.ordinal)
        assertEquals(7, TransitionStyle.WIPE_LEFT.ordinal)
        assertEquals(14, TransitionStyle.ZOOM_IN.ordinal)
        assertEquals(15, TransitionStyle.ZOOM_OUT.ordinal)
        assertEquals(20, TransitionStyle.BLINDS.ordinal)
        assertEquals(21, TransitionStyle.entries.size)
    }
}
