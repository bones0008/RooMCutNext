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
import com.RooMCutNext.core.util.FadeMath

/**
 * Video crossfade-in (v1.2.1): multiplies the frame's alpha by a 0→1 ramp
 * across the item's first [fadeUs] microseconds, so the incoming clip fades
 * in over the (fully opaque) tail of the previous clip playing underneath.
 *
 * Media3 1.11.1 has no built-in time-varying alpha effect (AlphaScale is
 * constant), so this is a custom [GlEffect] whose shader program is a
 * byte-for-byte re-implementation of the library's own
 * AlphaScaleShaderProgram pattern (verified against the shipped bytecode):
 * the same ES2 transformation vertex shader, the same alpha-scaling fragment
 * shader, and `uAlphaScale` simply recomputed from the frame's presentation
 * timestamp in [drawFrame].
 *
 * Timestamp convention robustness: see [FadeMath] — the ramp window is
 * recognized in BOTH global-timeline and item-local coordinates, so preview
 * (CompositionPlayer, global timestamps) and export (Transformer) render
 * identically.
 */
class FadeInEffect(
    private val fadeUs: Long,
    private val globalStartUs: Long,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        FadeInShaderProgram(useHdr, fadeUs, globalStartUs)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = fadeUs <= 0L
}

/**
 * The GL side of [FadeInEffect]. Full-screen pass-through quad; the only
 * change against a plain copy shader is `gl_FragColor.a *= uAlphaScale`.
 */
internal class FadeInShaderProgram(
    useHdr: Boolean,
    private val fadeUs: Long,
    private val globalStartUs: Long,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity = */ 1) {

    private val glProgram: GlProgram = try {
        GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            val identity = GlUtil.create4x4IdentityMatrix()
            setFloatsUniform("uTransformationMatrix", identity)
            setFloatsUniform("uTexTransformationMatrix", identity)
            setFloatUniform("uAlphaScale", 1f)
        }
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException.from(e)
    }

    override fun configure(width: Int, height: Int): Size = Size(width, height)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnit = */ 0)
            glProgram.setFloatUniform(
                "uAlphaScale",
                FadeMath.fadeInAlpha(presentationTimeUs, fadeUs, globalStartUs),
            )
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first = */ 0, /* count = */ 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException.from(e, presentationTimeUs)
        }
    }

    private companion object {
        // androidx.media3.effect R.raw.vertex_shader_transformation_es2 (ES2,
        // Apache-2.0): maps the normalized-device quad through the 4×4
        // matrices; with identity matrices vTexSamplingCoord is the frame in
        // [0,1]² with v=1 at the TOP of the picture.
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

        // androidx.media3.effect R.raw.fragment_shader_alpha_scale_es2 (ES2,
        // Apache-2.0): samples the input and scales its alpha.
        private const val FRAGMENT_SHADER = """#version 100
precision mediump float;
uniform sampler2D uTexSampler;
uniform float uAlphaScale;
varying vec2 vTexSamplingCoord;
void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  gl_FragColor = vec4(src.rgb, src.a * uAlphaScale);
}
"""
    }
}
