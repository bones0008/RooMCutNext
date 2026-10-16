package com.RooMCutNext.export.effects

import com.RooMCutNext.core.model.TransitionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.7: the SESSION-STABLE variant's equality contract.
 *
 * media3's DefaultVideoFrameProcessor compares effect LISTS by equals to
 * decide whether an input-stream switch must release + recompile the shader
 * chain, and PlaybackVideoGraphWrapper's InputVideoSink re-registers on every
 * UNEQUAL setVideoEffects call. The session-stable preview instance must
 * therefore stay list-equal across window mutations — while EXPORT instances
 * keep the identity semantics the Transformer pipeline has always seen.
 * These tests pin both halves of that contract (pure JVM: equality and
 * updateWindow only; the GL side is exercised on-device).
 */
class TransitionEffectTest {

    private fun stable(
        styleId: String = "clock_wipe",
        fadeUs: Long = 500_000L,
        globalStartUs: Long = -1L,
    ) = TransitionEffect(styleId, fadeUs, globalStartUs, premultipliedAlpha = true, sessionStable = true)

    private fun exportLike(
        styleId: String = "clock_wipe",
        fadeUs: Long = 500_000L,
        globalStartUs: Long = 1_000_000L,
    ) = TransitionEffect(styleId, fadeUs, globalStartUs, premultipliedAlpha = false)

    @Test
    fun `session-stable instances stay equal across different window parameters`() {
        // The core of the churn fix: the sink's list comparison must not see
        // a "new" chain when the controller re-points the stable instance at
        // another window (different style, duration, timeline anchor).
        val a = stable(styleId = "clock_wipe", fadeUs = 500_000L)
        val b = stable(styleId = "flash", fadeUs = 1_200_000L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `stable list equality survives an in-place updateWindow`() {
        // The controller's actual retarget shape: ONE instance, mutated in
        // place — the list containing it must compare equal to itself before
        // and after the mutation.
        val effect = stable(styleId = "dissolve", fadeUs = 400_000L)
        val before = listOf<Any>(effect)
        effect.updateWindow(styleId = "iris_open", fadeUs = 900_000L, globalStartUs = 7_000_000L)
        val after = listOf<Any>(effect)
        assertEquals(before, after)
    }

    @Test
    fun `updateWindow re-points the readable window parameters`() {
        val effect = stable(styleId = "dissolve", fadeUs = 400_000L)
        effect.updateWindow(
            styleId = "slide_left",
            fadeUs = 900_000L,
            globalStartUs = 7_000_000L,
        )
        assertEquals(TransitionStyle.fromId("slide_left").ordinal, effect.styleOrdinal)
        assertEquals(900_000L, effect.fadeUs)
        assertEquals(7_000_000L, effect.globalStartUs)
    }

    @Test
    fun `stable instances with different alpha conventions are unequal`() {
        // premultipliedAlpha distinguishes the PREVIEW (TextureView) from the
        // EXPORT (compositor) consumer; swapping conventions mid-session
        // would change every rendered pixel of the transition.
        assertNotEquals(
            stable(),
            TransitionEffect("clock_wipe", 500_000L, -1L, premultipliedAlpha = false, sessionStable = true),
        )
    }

    @Test
    fun `export instances keep identity semantics (v1 5 6 behavior unchanged)`() {
        // The Transformer pipeline never sees sessionStable=true; two export
        // effects with identical parameters must remain UNequal (identity),
        // exactly as every previous version behaved.
        assertNotEquals(exportLike(), exportLike())
        val self = exportLike()
        assertEquals(self, self) // only self-equal
        assertNotEquals(exportLike(), exportLike(styleId = "flash"))
    }

    @Test
    fun `stable and export instances never compare equal`() {
        // Even with identical parameters, the two variants must not mix: the
        // stable variant exists solely as the preview's mutable slot.
        assertNotEquals(stable(), TransitionEffect("clock_wipe", 500_000L, -1L, premultipliedAlpha = true))
    }

    @Test
    fun `isNoOp follows the current fadeUs`() {
        val effect = stable(fadeUs = 1L)
        assertTrue(!effect.isNoOp(1920, 1080))
        effect.updateWindow(styleId = "clock_wipe", fadeUs = 0L, globalStartUs = -1L)
        assertTrue(effect.isNoOp(1920, 1080))
    }
}
