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
import com.RooMCutNext.core.model.MosaicRegion
import com.RooMCutNext.core.util.MosaicGeometry

/**
 * Rectangular pixelation (v1.2.2): pixelates one [MosaicRegion] of the frame —
 * the classic face / logo / plate blur, CapCut-style. One instance = one GL
 * pass; a clip with several regions chains several effects.
 *
 * Media3 1.11.1 has no pixelation effect, so this is a custom [GlEffect]
 * following the library's own AlphaScaleShaderProgram pattern (bytecode-
 * verified): same ES2 transformation vertex shader, a fragment shader that
 * quantizes the sampling UV inside the region rect, uniform values prepared
 * by the pure [MosaicGeometry] (JVM unit-tested).
 *
 * Region coordinates are normalized to the CLIP's own frame in the intuitive
 * convention (y down); see [MosaicRegion] and [MosaicGeometry.glRegion] for
 * the GL y-flip.
 */
class MosaicEffect(private val region: MosaicRegion) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        MosaicShaderProgram(useHdr, region)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = region.w <= 0f || region.h <= 0f
}

/**
 * The GL side of [MosaicEffect]. Full-screen pass-through outside the region;
 * inside it, the sampling coordinate is snapped to the center of the block it
 * falls into (floor + half-block), which reads as chunky pixelation.
 */
internal class MosaicShaderProgram(
    useHdr: Boolean,
    private val region: MosaicRegion,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity = */ 1) {

    private var inputWidth = 0
    private var inputHeight = 0

    private val glProgram: GlProgram = try {
        GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            val identity = GlUtil.create4x4IdentityMatrix()
            setFloatsUniform("uTransformationMatrix", identity)
            setFloatsUniform("uTexTransformationMatrix", identity)
            // Region rect is size-independent; uBlocks is first set in
            // configure(), once the input frame size is known.
            setFloatsUniform("uRegion", MosaicGeometry.glRegion(region))
        }
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException.from(e)
    }

    override fun configure(width: Int, height: Int): Size {
        inputWidth = width
        inputHeight = height
        // Block counts depend on the input frame size (square pixels); the
        // region rect itself is size-independent (normalized). configure() is
        // re-invoked whenever the input format changes, so refresh here.
        try {
            glProgram.use()
            glProgram.setFloatsUniform("uRegion", MosaicGeometry.glRegion(region))
            glProgram.setFloatsUniform("uBlocks", MosaicGeometry.blocks(region, width, height))
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException.from(e)
        }
        return Size(width, height)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnit = */ 0)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first = */ 0, /* count = */ 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException.from(e, presentationTimeUs)
        }
    }

    private companion object {
        // androidx.media3.effect R.raw.vertex_shader_transformation_es2 (ES2,
        // Apache-2.0) — identical to the fade program's vertex stage.
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

        // ES2 fragment shader: quantize the sampling coordinate inside the
        // region rect (xMin, yMinGL, xMax, yMaxGL) into a uBlocks.x × uBlocks.y
        // grid; outside the region the frame passes through untouched.
        private const val FRAGMENT_SHADER = """#version 100
precision mediump float;
uniform sampler2D uTexSampler;
uniform vec4 uRegion;
uniform vec2 uBlocks;
varying vec2 vTexSamplingCoord;
void main() {
  vec2 uv = vTexSamplingCoord;
  if (uv.x >= uRegion.x && uv.x <= uRegion.z &&
      uv.y >= uRegion.y && uv.y <= uRegion.w) {
    vec2 size = uRegion.zw - uRegion.xy;
    vec2 local = (uv - uRegion.xy) / size;
    vec2 quant = (floor(local * uBlocks) + vec2(0.5)) / uBlocks;
    uv = uRegion.xy + quant * size;
  }
  gl_FragColor = texture2D(uTexSampler, uv);
}
"""
    }
}
