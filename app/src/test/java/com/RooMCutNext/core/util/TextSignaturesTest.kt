package com.RooMCutNext.core.util

import com.RooMCutNext.core.model.TextOverlayItem
import com.RooMCutNext.core.model.TextStyleSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * v1.3.0 rebuild-signature contract: the preview composition (body AND
 * crossfade head) rebuilds ONLY when a text's structural fields change —
 * identity and visibility windows. Every visual field is rendered live by
 * TimedTextOverlay's per-frame state read and must NEVER churn the player.
 */
class TextSignaturesTest {

    private fun item(
        id: String = "t1",
        start: Long = 0L,
        end: Long = 3_000_000L,
        text: String = "字幕",
        x: Float = 0.5f,
        y: Float = 0.5f,
        size: Float = 0.08f,
        rot: Float = 0f,
        color: Long = 0xFFFFFFFF,
        fontId: String = "system",
    ) = TextOverlayItem(
        id = id, text = text, xRatio = x, yRatio = y, rotationDeg = rot,
        startUs = start, endUs = end,
        style = TextStyleSpec(fontSizeRatio = size, color = color, fontId = fontId),
    )

    @Test
    fun `visual edits keep the signature - no rebuild`() {
        val base = listOf(item())
        val before = TextSignatures.windows(base)

        // Every VISUAL field the gesture layer / panel sliders can change.
        val moved = listOf(item(x = 0.12f, y = 0.88f))
        val resized = listOf(item(size = 0.25f))
        val rotated = listOf(item(rot = -175f))
        val recolored = listOf(item(color = 0xFF00FF00))
        val refonted = listOf(item(fontId = "kai"))
        val retyped = listOf(item(text = "完全不同的文案"))

        assertEquals(before, TextSignatures.windows(moved))
        assertEquals(before, TextSignatures.windows(resized))
        assertEquals(before, TextSignatures.windows(rotated))
        assertEquals(before, TextSignatures.windows(recolored))
        assertEquals(before, TextSignatures.windows(refonted))
        assertEquals(before, TextSignatures.windows(retyped))
    }

    @Test
    fun `window edits change the signature - rebuild`() {
        val before = TextSignatures.windows(listOf(item()))
        assertNotEquals(before, TextSignatures.windows(listOf(item(start = 1L))))
        assertNotEquals(before, TextSignatures.windows(listOf(item(end = 3_000_001L))))
        assertNotEquals(before, TextSignatures.windows(listOf(item(start = 500_000L, end = 2_000_000L))))
    }

    @Test
    fun `identity change rebuilds`() {
        val before = TextSignatures.windows(listOf(item(id = "t1")))
        assertNotEquals(before, TextSignatures.windows(listOf(item(id = "t2"))))
    }

    @Test
    fun `count change rebuilds`() {
        val one = TextSignatures.windows(listOf(item()))
        val two = TextSignatures.windows(listOf(item(), item(id = "t2")))
        assertNotEquals(one, two)
        val empty = TextSignatures.windows(emptyList())
        assertNotEquals(one, empty)
        assertEquals(empty, TextSignatures.windows(emptyList()))
    }

    @Test
    fun `order matters - insertion order is structural`() {
        val a = TextSignatures.windows(listOf(item(id = "a"), item(id = "b")))
        val b = TextSignatures.windows(listOf(item(id = "b"), item(id = "a")))
        assertNotEquals(a, b)
    }

    @Test
    fun `same windows same order are stable across visual edits of many items`() {
        val base = listOf(
            item(id = "a", start = 0L, end = 2_000_000L),
            item(id = "b", start = 1_000_000L, end = 4_000_000L),
        )
        val edited = listOf(
            item(id = "a", start = 0L, end = 2_000_000L, x = 0.05f, y = 0.95f, rot = 179f, size = 0.03f),
            item(id = "b", start = 1_000_000L, end = 4_000_000L, text = "x", color = 1L, fontId = "hei"),
        )
        assertEquals(TextSignatures.windows(base), TextSignatures.windows(edited))
    }
}
