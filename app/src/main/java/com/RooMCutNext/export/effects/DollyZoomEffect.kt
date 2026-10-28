package com.RooMCutNext.export.effects

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.RooMCutNext.core.util.DollyZoomMath

/**
 * The GL side of a clip's 希区柯克变焦 (dolly zoom, v1.6.21) — a time-
 * animated magnification of the clip's frame. The formula authority is
 * [DollyZoomMath] (JVM, unit-tested); this class only transcribes it per
 * frame (the TransitionMath ↔ TransitionShaderProgram layering rule).
 *
 * The effect samples a centered window (full edge s, X/Y equal — the window
 * keeps the SOURCE aspect so no stretching; canvas letterboxing stays the
 * canvas Presentation's job exactly as without dolly):
 *
 *     suv = center + (uv - 0.5) * s
 *
 * s animates linearly 1 → 1/maxScale (push in) or the reverse (pull back);
 * the center is clamped so the window never leaves the frame — sampling is
 * provably in-bounds (the shader's clamp is float-error defense only).
 * Alpha is a constant 1: dolly never touches coverage, so the preview/export
 * alpha-convention split that TransitionEffect carries does NOT apply here —
 * whichever consumer composites the output gets an opaque frame either way.
 *
 * MOUNT ORDER: after clipLookEffects (crop first defines the composition,
 * then dolly magnifies it — mosaic included, so a privacy region scales WITH
 * the face it covers), before the canvas Presentation (source space).
 *
 * Timestamp conventions — same dual-clock contract as TransitionEffect:
 *  - EXPORT / preview BODY (Transformer, CompositionPlayer): frames arrive on
 *    the global/sequence clock AFTER speed is applied → construct with
 *    (durationUs = D [timeline domain], globalStartUs = the clip's TIMELINE
 *    start). Both the head item and the body item of a transitioned clip use
 *    the SAME globalStartUs — the window begins at the clip's start, i.e.
 *    inside the transition head.
 *  - PREVIEW HEAD (plain ExoPlayer + clipping): effect timestamps are
 *    player (source-clock) positions, 0 = the head item's start = the clip's
 *    timeline start → construct with (durationUs = D * speed, globalStartUs
 *    = -1). At the head window's end the two conventions give the SAME
 *    progress (DollyZoomMath.boundaryProgressMatch) — the surface handoff
 *    is seamless.
 *
 * v1.5.7-style SESSION-STABLE PREVIEW VARIANT (sessionStable = true): the
 * head player's chain must stay value-equal across retargets or media3
 * re-registers the input stream and recompiles the GL chain (the "越播越卡"
 * storm). The preview mounts ONE instance whose parameters are @Volatile
 * fields MUTATED in place (updateWindow); [equals] is param-blind between
 * session-stable instances. The instance is mounted UNCONDITIONALLY in the
 * head chain (durationUs = 0 → identity window) so the list length never
 * flips between dolly / non-dolly clips — a length flip would re-register
 * on every alternation, which is exactly the pattern a "half the clips have
 * dolly" project would trigger.
 *
 * DollyZoomMath.progress guards its item-local branch with globalStartUs < 0
 * (TransitionMath.progress does not — it relies on transitions never
 * starting before fadeUs of sequence time). Dolly windows may be up to 8s
 * and start at ANY clip offset, so the unguarded form would misread global
 * timestamps below durationUs as item-local ones.
 */
class DollyZoomEffect(
    durationUs: Long,
    globalStartUs: Long,
    zoomIn: Boolean,
    maxScale: Float,
    centerX: Float,
    centerY: Float,
    /** v1.6.21: session-stable preview variant — see the class doc. */
    internal val sessionStable: Boolean = false,
) : GlEffect {

    /** Per-frame-read parameters (@Volatile — mutated by the main thread at
     * retarget, read by the GL thread per draw; a mutation always precedes
     * the new item's first frame, same ordering argument as TransitionEffect). */
    @Volatile internal var durationUs: Long = durationUs
        private set

    @Volatile internal var globalStartUs: Long = globalStartUs
        private set

    @Volatile internal var zoomIn: Boolean = zoomIn
        private set

    @Volatile internal var maxScale: Float = maxScale
        private set

    @Volatile internal var centerX: Float = centerX
        private set

    @Volatile internal var centerY: Float = centerY
        private set

    /** Re-point the stable preview instance at a new clip's spec (null =
     * identity: durationUs 0 short-circuits to the full-frame window). */
    fun updateWindow(
        durationUs: Long,
        globalStartUs: Long,
        zoomIn: Boolean,
        maxScale: Float,
        centerX: Float,
        centerY: Float,
    ) {
        this.durationUs = durationUs
        this.globalStartUs = globalStartUs
        this.zoomIn = zoomIn
        this.maxScale = maxScale
        this.centerX = centerX
        this.centerY = centerY
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        DollyZoomShaderProgram(this)

    /**
     * Export instances (durationUs > 0 by construction — the model clamps
     * the spec) are never no-ops. SESSION-STABLE instances must report false
     * EVEN WHILE idle: media3 consults isNoOp when building the chain, and an
     * idle-but-present instance that reported true would never get its
     * program built — the later updateWindow to a real window could not
     * revive it. (TransitionEffect gets this for free because a transition
     * window is never 0 at first mount; dolly clips may be.)
     */
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        !sessionStable && durationUs <= 0L

    /** v1.5.7 pattern: session-stable instances are param-blind equal. */
    override fun equals(other: Any?): Boolean = when {
        other !is DollyZoomEffect -> false
        sessionStable && other.sessionStable -> true
        else -> this === other
    }

    override fun hashCode(): Int =
        if (sessionStable) 0x0D0117 else System.identityHashCode(this)
}

/**
 * One full-screen pass: computes the window on the CPU per frame (the math
 * lives in [DollyZoomMath] — single source of truth, no GLSL twin to keep in
 * lockstep) and hands the shader two uniforms (uS, uCenter). The per-pixel
 * remap is a single line of GLSL; identity (s=1, center=.5) is bit-equal to
 * a no-op pass.
 */
internal class DollyZoomShaderProgram(
    private val effect: DollyZoomEffect,
) : BaseGlShaderProgram(/* useHdr = */ false, /* texturePoolCapacity = */ 1) {

    private val glProgram: GlProgram = try {
        GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            val identity = GlUtil.create4x4IdentityMatrix()
            setFloatsUniform("uTransformationMatrix", identity)
            setFloatsUniform("uTexTransformationMatrix", identity)
            setFloatUniform("uS", 1f)
            setFloatsUniform("uCenter", floatArrayOf(0.5f, 0.5f))
        }
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException.from(e)
    }

    // BaseGlShaderProgram declares configure() abstract; the window math is
    // aspect-free (X/Y equal edges), so the output size is the input size.
    override fun configure(width: Int, height: Int): Size = Size(width, height)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            // Per-frame reads of the (possibly session-stable) effect's
            // CURRENT parameters — the whole window math on the CPU.
            val durUs = effect.durationUs
            val size = if (durUs <= 0L) {
                // Idle session-stable instance (clip has no dolly): identity.
                1f
            } else {
                val p = DollyZoomMath.progress(presentationTimeUs, durUs, effect.globalStartUs)
                DollyZoomMath.windowSize(p, effect.maxScale, effect.zoomIn)
            }
            val cx = DollyZoomMath.clampedCenter(effect.centerX, 0.5f * size)
            val cy = DollyZoomMath.clampedCenter(effect.centerY, 0.5f * size)
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnit = */ 0)
            glProgram.setFloatUniform("uS", size)
            glProgram.setFloatsUniform("uCenter", floatArrayOf(cx, cy))
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first = */ 0, /* count = */ 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException.from(e, presentationTimeUs)
        }
    }

    private companion object {
        // androidx.media3.effect R.raw.vertex_shader_transformation_es2 (ES2,
        // Apache-2.0) — the same vertex stage TransitionShaderProgram uses:
        // identity matrices → vTexSamplingCoord is the frame in [0,1]^2 with
        // v = 1 at the TOP of the picture.
        private const val VERTEX_SHADER = """#version 100
attribute vec4 aFramePosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = uTransformationMatrix * aFramePosition;
  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5,
                              aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
}
"""

        // The window remap. uS = full window edge (X/Y equal — source aspect
        // preserved); uCenter = clamped window center. clamp() on the sampled
        // UV is pure float-error defense — DollyZoomMath.clampedCenter keeps
        // the window inside [0,1] by construction. Alpha is always 1.
        private const val FRAGMENT_SHADER = """#version 100
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform float uS;
uniform vec2 uCenter;
varying vec2 vTexSamplingCoord;
void main() {
  vec2 suv = uCenter + (vTexSamplingCoord - vec2(0.5)) * uS;
  gl_FragColor = vec4(texture2D(uTexSampler, clamp(suv, 0.0, 1.0)).rgb, 1.0);
}
"""
    }
}
