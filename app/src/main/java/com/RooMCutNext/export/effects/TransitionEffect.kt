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
import com.RooMCutNext.core.model.TransitionStyle
import com.RooMCutNext.core.util.TransitionMath

/**
 * The GL side of a timeline transition (v1.5.0) — the successor of the
 * v1.2.1 FadeInEffect, which only ramped alpha (the classic dissolve).
 *
 * A transition renders on the HEAD layer of an overlap: the head carries the
 * INCOMING clip B and plays above the body (the outgoing clip A, fully
 * opaque). The composite is `final = head*headAlpha + body*(1-headAlpha)`,
 * so this single shader fully owns the look of the transition through BOTH
 * the head's per-pixel alpha (masks, coverage) and its color (through-black
 * / white, flash, blur, mosaic). The body never moves — every style in
 * [TransitionStyle] is expressible in that single-layer-animation form.
 *
 * ONE shader, ONE source of semantics: every branch below is a literal
 * transcription of the reviewed, unit-tested formulas in
 * [com.RooMCutNext.core.util.TransitionMath] (same pattern as MosaicGeometry
 * vs MosaicEffect — shaders cannot run on the JVM). The `uStyle` uniform is
 * the [TransitionStyle.ordinal]; the branch numbers below and the enum
 * declaration order are kept in LOCKSTEP — never renumber either.
 *
 * WYSIWYG: PREVIEW and EXPORT mount the SAME effect. Timestamp conventions
 * (the reason both consumers work unchanged):
 *  - EXPORT (Transformer): frames arrive on the global/sequence clock after
 *    speed is applied — construct with (fadeUs = T, globalStartUs = the
 *    head's timeline start), exactly like the old FadeInEffect.
 *  - PREVIEW head player (plain ExoPlayer): effect timestamps are PLAYER
 *    (source-clock) positions — construct with (fadeUs = T*speed, the head's
 *    source span, globalStartUs = -1). The item-local window of
 *    [TransitionMath.progress] then lands the ramp on the timeline scale.
 *
 * Outside the transition window the shader is fully opaque (progress = 1),
 * never invisible — the v1.2.1 contract that keeps tail/preroll frames safe.
 *
 * v1.5.1 — OUTPUT ALPHA CONVENTION (the preview-looked-like-dissolve bug):
 * the two consumers composite this shader's output with DIFFERENT alpha
 * conventions, so the effect carries an explicit [premultipliedAlpha] flag:
 *
 *  - EXPORT (Transformer): each sequence's frames land in the compositor,
 *    whose DefaultCompositorGlProgram blends inputs with
 *    glBlendFuncSeparate(srcRGB = GL_SRC_ALPHA, dstRGB =
 *    GL_ONE_MINUS_SRC_ALPHA, ...) — STRAIGHT source alpha (source-verified,
 *    media3 1.11.1). Correct output: vec4(rgb, alpha) → rgb·alpha + (1−alpha)·body.
 *    → premultipliedAlpha = false.
 *
 *  - PREVIEW head player (plain ExoPlayer → PlaybackVideoGraphWrapper →
 *    DefaultVideoFrameProcessor → TextureView's SurfaceTexture): there is NO
 *    compositor — the last effect's output IS the buffer content, and Android
 *    HWUI composites SurfaceTexture buffers PREMULTIPLIED. A straight-alpha
 *    buffer is then read as rgb + (1−alpha)·body: the incoming clip shows at
 *    FULL brightness wherever alpha > 0 — every position-stable style
 *    (dissolve / fade / flash / wipe / iris / clock / zoom / blur / mosaic)
 *    collapses into the same ghosted crossfade; only slide/spin betrayed any
 *    motion. Correct output: vec4(rgb·alpha, alpha). → premultipliedAlpha =
 *    true, set by OverlayPreviewController's mount.
 */
class TransitionEffect(
    styleId: String,
    private val fadeUs: Long,
    private val globalStartUs: Long,
    /** Emit premultiplied alpha for TextureView-style consumers; see the
     * class doc's v1.5.1 section. Export = false (compositor blends straight). */
    private val premultipliedAlpha: Boolean = false,
) : GlEffect {

    private val styleOrdinal: Int = TransitionStyle.fromId(styleId).ordinal

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        TransitionShaderProgram(useHdr, styleOrdinal, fadeUs, globalStartUs, premultipliedAlpha)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = fadeUs <= 0L
}

/**
 * The GL side of [TransitionEffect]: one full-screen pass over the head
 * texture. The vertex stage is the library's own ES2 transformation shader
 * (identity matrices -> vTexSamplingCoord is the frame in [0,1]^2 with
 * v = 1 at the TOP of the picture); the fragment stage picks the branch for
 * [TransitionStyle.ordinal] and applies the TransitionMath formula pair
 * (sample-UV transform + alpha/color) for that style. The final write honors
 * the consumer's alpha convention: straight (compositor blending) or
 * premultiplied (TextureView) — see [TransitionEffect]'s v1.5.1 section.
 */
internal class TransitionShaderProgram(
    useHdr: Boolean,
    private val styleOrdinal: Int,
    private val fadeUs: Long,
    private val globalStartUs: Long,
    premultipliedAlpha: Boolean,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity = */ 1) {

    private val premultInt = if (premultipliedAlpha) 1 else 0

    private val glProgram: GlProgram = try {
        GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            val identity = GlUtil.create4x4IdentityMatrix()
            setFloatsUniform("uTransformationMatrix", identity)
            setFloatsUniform("uTexTransformationMatrix", identity)
            setIntUniform("uStyle", styleOrdinal)
            setFloatUniform("uProgress", 1f)
            setFloatUniform("uAspect", 1f)
            setIntUniform("uPremult", premultInt)
        }
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException.from(e)
    }

    /** Frame aspect (width/height) — iris/clock branches aspect-correct x. */
    private var aspect = 1f

    override fun configure(width: Int, height: Int): Size {
        aspect = if (height > 0) width.toFloat() / height else 1f
        return Size(width, height)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnit = */ 0)
            glProgram.setFloatUniform(
                "uProgress",
                TransitionMath.progress(presentationTimeUs, fadeUs, globalStartUs),
            )
            glProgram.setFloatUniform("uAspect", aspect)
            glProgram.setIntUniform("uStyle", styleOrdinal)
            glProgram.setIntUniform("uPremult", premultInt)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first = */ 0, /* count = */ 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException.from(e, presentationTimeUs)
        }
    }

    private companion object {
        // androidx.media3.effect R.raw.vertex_shader_transformation_es2 (ES2,
        // Apache-2.0): maps the normalized-device quad through the 4x4
        // matrices; with identity matrices vTexSamplingCoord is the frame in
        // [0,1]^2 with v=1 at the TOP of the picture.
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

        // The transition catalog. uStyle = TransitionStyle.ordinal:
        //  0 dissolve, 1 fade_black, 2 fade_white,
        //  3 slide_left, 4 slide_right, 5 slide_up, 6 slide_down,
        //  7 wipe_left, 8 wipe_right, 9 wipe_up, 10 wipe_down,
        //  11 iris_open, 12 iris_close, 13 clock_wipe,
        //  14 zoom_in, 15 zoom_out, 16 spin, 17 blur, 18 mosaic,
        //  19 flash, 20 blinds.
        // Every formula is a literal transcription of core/util/TransitionMath.kt
        // (see the tests there for hand-calculated expectations).
        private const val FRAGMENT_SHADER = """#version 100
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform float uProgress;
uniform int uStyle;
uniform float uAspect;
uniform int uPremult;
varying vec2 vTexSamplingCoord;

// TransitionMath.softLe / softGe — soft edges (EDGE_UV = 0.008).
float softLe(float v, float edge, float e) {
  return clamp((edge + e - v) / (2.0 * e), 0.0, 1.0);
}
float softGe(float v, float edge, float e) {
  return clamp((v - edge + e) / (2.0 * e), 0.0, 1.0);
}

void main() {
  float p = clamp(uProgress, 0.0, 1.0);
  vec2 uv = vTexSamplingCoord;
  float alpha = 1.0;
  vec3 rgb = vec3(0.0);
  int S = uStyle;

  if (S == 0) {
    // dissolve: plain coverage ramp.
    alpha = p;
    rgb = texture2D(uTexSampler, uv).rgb;
  } else if (S == 1 || S == 2) {
    // fade_black / fade_white — TransitionMath.throughColorAlpha/TexMix:
    // the head takes the frame over with the solid color by midpoints,
    // then B appears inside it.
    float cov = clamp(2.0 * p, 0.0, 1.0);
    float tmx = clamp(2.0 * p - 1.0, 0.0, 1.0);
    alpha = cov;
    vec3 base = (S == 1) ? vec3(0.0) : vec3(1.0);
    rgb = mix(base, texture2D(uTexSampler, uv).rgb, tmx);
  } else if (S >= 3 && S <= 6) {
    // slide — TransitionMath.slideUv/slideMask: B travels INTO the frame,
    // leading edge off-screen at p=0, flush at p=1.
    vec2 off = vec2(0.0);
    if (S == 3) { off = vec2(p - 1.0, 0.0); }
    else if (S == 4) { off = vec2(1.0 - p, 0.0); }
    else if (S == 5) { off = vec2(0.0, 1.0 - p); }
    else { off = vec2(0.0, p - 1.0); }
    float m = 1.0;
    if (S == 3) { m = softGe(uv.x, 1.0 - p, 0.008); }
    else if (S == 4) { m = softLe(uv.x, p, 0.008); }
    else if (S == 5) { m = softLe(uv.y, p, 0.008); }
    else { m = softGe(uv.y, 1.0 - p, 0.008); }
    alpha = m;
    rgb = texture2D(uTexSampler, clamp(uv + off, 0.001, 0.999)).rgb;
  } else if (S >= 7 && S <= 10) {
    // wipe — TransitionMath.wipeMask: B sits in place; the soft reveal
    // front sweeps (same masks as slide, no UV offset).
    float m = 1.0;
    if (S == 7) { m = softGe(uv.x, 1.0 - p, 0.008); }
    else if (S == 8) { m = softLe(uv.x, p, 0.008); }
    else if (S == 9) { m = softLe(uv.y, p, 0.008); }
    else { m = softGe(uv.y, 1.0 - p, 0.008); }
    alpha = m;
    rgb = texture2D(uTexSampler, uv).rgb;
  } else if (S == 11) {
    // iris_open — TransitionMath.irisOpenRadius: reveal circle from center.
    float R = 0.5 * sqrt(uAspect * uAspect + 1.0);
    float d = length(vec2((uv.x - 0.5) * uAspect, uv.y - 0.5));
    alpha = softLe(d, p * R, 0.016);
    rgb = texture2D(uTexSampler, uv).rgb;
  } else if (S == 12) {
    // iris_close — TransitionMath.irisCloseRadius/irisShowsUnderneath:
    // A pinches through black (head black outside a shrinking circle),
    // then B opens inside a growing one.
    float R = 0.5 * sqrt(uAspect * uAspect + 1.0);
    float d = length(vec2((uv.x - 0.5) * uAspect, uv.y - 0.5));
    float r = abs(2.0 * p - 1.0) * R;
    float inC = softLe(d, r, 0.016);
    if (p < 0.5) {
      alpha = 1.0 - inC;
      rgb = vec3(0.0);
    } else {
      alpha = 1.0;
      rgb = texture2D(uTexSampler, uv).rgb * inC;
    }
  } else if (S == 13) {
    // clock_wipe — TransitionMath.clockAngle: clockwise sweep from 12.
    float sx = (uv.x - 0.5) * uAspect;
    float sy = uv.y - 0.5;
    float t = atan(sx, sy) / 6.2831853;
    t = t - floor(t);
    alpha = softLe(t, p, 0.008);
    rgb = texture2D(uTexSampler, uv).rgb;
  } else if (S == 14 || S == 15) {
    // zoom_in / zoom_out — TransitionMath.zoomScale (v1.5.3: zoom_out
    // enters at 2.4x magnification and settles quadratically; the v1.5.2
    // 1.8x linear settle read as "no effect" in the field).
    float sc = (S == 14) ? (0.25 + 0.75 * p)
                         : (1.0 + 1.4 * (1.0 - p) * (1.0 - p));
    vec2 suv = vec2(0.5) + (uv - vec2(0.5)) / sc;
    rgb = texture2D(uTexSampler, clamp(suv, 0.001, 0.999)).rgb;
    if (S == 14) {
      // zoom_in: B grows from a quarter-size window; outside it A shows.
      float bx = clamp((0.5 - abs(suv.x - 0.5)) / 0.008, 0.0, 1.0);
      float by = clamp((0.5 - abs(suv.y - 0.5)) / 0.008, 0.0, 1.0);
      alpha = min(bx, by) * mix(0.6, 1.0, p);
    } else {
      // zoom_out — TransitionMath.zoomOutRevealAlpha (v1.5.3: linear
      // coverage across the WHOLE window; the v1.5.2 3p curve hid the
      // settle behind an already-opaque B).
      alpha = p;
    }
  } else if (S == 16) {
    // spin — TransitionMath.spinAngleRad/spinScale: unwinds 240 degrees
    // while growing from 0.4x to full size.
    float sc = 0.4 + 0.6 * p;
    float ang = (1.0 - p) * 4.1887902;
    float ca = cos(ang);
    float sa = sin(ang);
    vec2 c = (uv - vec2(0.5)) / sc;
    vec2 suv = vec2(0.5) + vec2(c.x * ca - c.y * sa, c.x * sa + c.y * ca);
    rgb = texture2D(uTexSampler, clamp(suv, 0.001, 0.999)).rgb;
    float bx = clamp((0.5 - abs(suv.x - 0.5)) / 0.008, 0.0, 1.0);
    float by = clamp((0.5 - abs(suv.y - 0.5)) / 0.008, 0.0, 1.0);
    alpha = min(bx, by) * mix(0.6, 1.0, p);
  } else if (S == 17) {
    // blur — TransitionMath.blurRadiusUv: 9-tap box blur that sharpens to
    // a crisp image while the coverage ramps.
    float r = 0.05 * (1.0 - p);
    vec2 uvL = clamp(uv + vec2(-r, 0.0), 0.001, 0.999);
    vec2 uvR = clamp(uv + vec2(r, 0.0), 0.001, 0.999);
    vec2 uvD = clamp(uv + vec2(0.0, -r), 0.001, 0.999);
    vec2 uvU = clamp(uv + vec2(0.0, r), 0.001, 0.999);
    vec2 uvLD = clamp(uv + vec2(-r, -r), 0.001, 0.999);
    vec2 uvRD = clamp(uv + vec2(r, -r), 0.001, 0.999);
    vec2 uvLU = clamp(uv + vec2(-r, r), 0.001, 0.999);
    vec2 uvRU = clamp(uv + vec2(r, r), 0.001, 0.999);
    vec3 acc = texture2D(uTexSampler, uv).rgb
        + texture2D(uTexSampler, uvL).rgb + texture2D(uTexSampler, uvR).rgb
        + texture2D(uTexSampler, uvD).rgb + texture2D(uTexSampler, uvU).rgb
        + texture2D(uTexSampler, uvLD).rgb + texture2D(uTexSampler, uvRD).rgb
        + texture2D(uTexSampler, uvLU).rgb + texture2D(uTexSampler, uvRU).rgb;
    alpha = p;
    rgb = acc / 9.0;
  } else if (S == 18) {
    // mosaic — TransitionMath.mosaicBlocks/mosaicClearMix: B materializes
    // as a coarse pixel grid that refines into the sharp image. v1.5.3: the
    // density follows 24*40^(p^3) — the v1.5.2 linear ramp was near-sharp
    // by mid-window and the style previewed as plain dissolve.
    float blocks = 24.0 * pow(40.0, p * p * p);
    vec2 q = (floor(uv * blocks) + vec2(0.5)) / blocks;
    vec2 suv = mix(q, uv, clamp((p - 0.92) / 0.08, 0.0, 1.0));
    alpha = p;
    rgb = texture2D(uTexSampler, clamp(suv, 0.001, 0.999)).rgb;
  } else if (S == 19) {
    // flash — TransitionMath.flashBoost: white pulse peaking at midpoint.
    alpha = p;
    float d = 1.0 - abs(2.0 * p - 1.0);
    rgb = texture2D(uTexSampler, uv).rgb + vec3(d * d * 0.8);
  } else {
    // blinds — TransitionMath.blindsBandAlpha: 8 horizontal bands light up
    // top to bottom (uv.y = 1 is the TOP of the picture).
    float band = floor((1.0 - uv.y) * 8.0);
    alpha = clamp(p * 9.0 - band, 0.0, 1.0);
    rgb = texture2D(uTexSampler, uv).rgb;
  }

  // Endpoint hygiene (v1.5.0): a soft edge's transition band naturally
  // lingers at the window ends — a half-transparent sliver of clip A at
  // p=1 would stay visible through the whole post-window TAIL, and the
  // leading-edge sliver at p=0 through the whole PREROLL (the head renders
  // its first frame at source position 0 and parks there). Pin both ends
  // to the exact endpoint state instead.
  if (p <= 0.0) {
    alpha = 0.0;
  } else if (p >= 1.0) {
    alpha = 1.0;
    rgb = texture2D(uTexSampler, uv).rgb;
  }

  // v1.5.1 — consumer alpha convention (see TransitionEffect's class doc):
  // uPremult=1 emits rgb*alpha for TextureView-style PREMULTIPLIED
  // compositing (preview head); uPremult=0 keeps straight alpha for the
  // export compositor's GL_SRC_ALPHA blending. TransitionMath.premultiply
  // is the reviewed Kotlin source of this line.
  if (uPremult == 1) {
    gl_FragColor = vec4(rgb * alpha, alpha);
  } else {
    gl_FragColor = vec4(rgb, alpha);
  }
}
"""
    }
}
