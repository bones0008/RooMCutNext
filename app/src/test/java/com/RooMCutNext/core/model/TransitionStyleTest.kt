package com.RooMCutNext.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.0 — the transition style registry (single source of truth for every
 * transition type id in the app: model JSON, GL shader branch, UI panels
 * and the timeline marker all key off these ids).
 */
class TransitionStyleTest {

    @Test
    fun `ids are globally unique`() {
        val ids = TransitionStyle.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `unknown and missing ids fall back to dissolve`() {
        // Old project JSON has no transitionType field at all -> default.
        // Hand-edited / future JSON may carry an id this build does not know.
        // Both must deserialize to the classic crossfade, never crash.
        assertEquals(TransitionStyle.DISSOLVE, TransitionStyle.fromId(null))
        assertEquals(TransitionStyle.DISSOLVE, TransitionStyle.fromId(""))
        assertEquals(TransitionStyle.DISSOLVE, TransitionStyle.fromId("glitch_2077"))
    }

    @Test
    fun `every style round-trips through its id`() {
        TransitionStyle.entries.forEach { s ->
            assertEquals(s, TransitionStyle.fromId(s.id))
        }
    }

    @Test
    fun `clip default transition type is dissolve for old projects`() {
        // Old JSON (schema with no transitionType) -> dissolve = the exact
        // behavior every version up to v1.4.0 shipped. New JSON stores ids.
        val c = Clip(id = "c", assetId = "a", sourceInUs = 0L, sourceOutUs = 10L)
        assertEquals(TransitionStyle.DISSOLVE, TransitionStyle.fromId(c.transitionType))
        assertEquals("dissolve", c.transitionType)
    }

    @Test
    fun `all four groups are populated`() {
        // The UI renders one section per group; an empty group would render
        // a dangling section header.
        TransitionStyle.Group.entries.forEach { g ->
            assertTrue(
                "group $g has no styles",
                TransitionStyle.entries.any { it.group == g },
            )
        }
    }

    @Test
    fun `the catalog carries the common editing transitions`() {
        // The 20 styles the user asked for ("常用转场都做进去"), grouped the
        // way CapCut-style editors group them.
        val expected = listOf(
            "dissolve", "fade_black", "fade_white",
            "slide_left", "slide_right", "slide_up", "slide_down",
            "wipe_left", "wipe_right", "wipe_up", "wipe_down",
            "iris_open", "iris_close", "clock_wipe",
            "zoom_in", "zoom_out", "spin", "blur", "mosaic", "flash", "blinds",
        )
        assertEquals(expected, TransitionStyle.entries.map { it.id })
    }
}
