package com.RooMCutNext.core.util

import com.RooMCutNext.core.model.DOLLY_MAX_SCALE
import com.RooMCutNext.core.model.DOLLY_MIN_SCALE

/**
 * v1.6.21 — 希区柯克变焦（dolly zoom）的数学唯一权威（JVM 可测；
 * GL 侧 [com.RooMCutNext.export.effects.DollyZoomEffect] 只做逐帧转录 —
 * 同 TransitionMath↔TransitionShaderProgram 的分层铁律）。
 *
 * 模型：输出帧 = 采样窗内容拉满整帧。窗中心 c、全边长 s（UV 域，X/Y 等边 —
 * 窗必须保持源的宽高比，否则内容被拉伸；画幅 letterbox 由链尾的 canvas
 * Presentation 统一负责，与无 dolly 时完全一致）：
 *
 *     suv = c + (uv - 0.5) * s        （per-pixel 重映射）
 *
 * s 随线性进度 p 从 1 走向 1/maxScale（推近）或反向（拉远）。线性 = 匀速
 * 胀缩，眩晕感最稳；缓动留给未来选项。窗全程被钳制在画内（[clampedCenter]），
 * 所以采样永不越界（shader 的 clamp 纯为浮点防御）。
 *
 * 时间约定与 [TransitionMath.progress] 完全同构（双钟）：
 *  - EXPORT / 预览 body（Transformer / CompositionPlayer）：presentationTimeUs
 *    是速度已应用的全局（时间线）钟 → (durationUs = D, globalStartUs = 素材
 *    时间线起点)。
 *  - 预览 head（普通 ExoPlayer + clipping）：presentationTimeUs 是 item-local
 *    源钟（0 = head item 起点 = 素材时间线起点）→ (durationUs = D * speed,
 *    globalStartUs = -1)。
 *  两个约定在窗口边界处给出同一 p —— head 在转场窗末的 p 与 body 在窗首的
 *  p 严格相等，跨表面无缝（单测钉死）。
 */
object DollyZoomMath {

    // ------------------------------------------------------------------
    // 进度（双钟约定 — 逐式对齐 TransitionMath.progress）
    // ------------------------------------------------------------------

    /**
     * 效果进度 0..1。`durationUs <= 0` = 无效果 → 1（完成态；调用方
     * [DollyZoomEffect] 对无效果直接短路成恒等窗，不走本函数）。
     * `globalStartUs < 0` = item-local（presentationTimeUs 即窗内偏移）。
     */
    fun progress(tUs: Long, durationUs: Long, globalStartUs: Long): Float {
        if (durationUs <= 0L) return 1f
        if (tUs >= 0L && tUs < durationUs && globalStartUs < 0L) {
            return (tUs.toDouble() / durationUs.toDouble()).toFloat().coerceIn(0f, 1f)
        }
        if (globalStartUs >= 0L && tUs >= globalStartUs && tUs < globalStartUs + durationUs) {
            return ((tUs - globalStartUs).toDouble() / durationUs.toDouble()).toFloat()
                .coerceIn(0f, 1f)
        }
        // 窗外：窗前 = 0（起始边），窗后 = 1（终点边保持）。
        return if (globalStartUs >= 0L && tUs < globalStartUs) 0f else 1f
    }

    // ------------------------------------------------------------------
    // 采样窗几何
    // ------------------------------------------------------------------

    /**
     * 采样窗全边长（UV 域，X/Y 等边），随进度 p 线性变化：
     * 推近 1 → 1/maxScale；拉远 1/maxScale → 1。maxScale 先钳进合法域。
     */
    fun windowSize(p: Float, maxScale: Float, zoomIn: Boolean): Float {
        val clamped = maxScale.coerceIn(DOLLY_MIN_SCALE, DOLLY_MAX_SCALE)
        val end = 1f / clamped
        val pc = p.coerceIn(0f, 1f)
        return if (zoomIn) (1f - pc) + pc * end else end + pc * (1f - end)
    }

    /**
     * 窗中心钳制：窗（全边长 s）必须留在 [0,1] 内 → center ∈ [s/2, 1-s/2]。
     * center 本身也先钳进 [0,1]（模型字段可能有脏值）。s ≤ 1 恒成立
     * （windowSize 上界 = 1），故区间不反转；防御式 min/max 仍保留。
     */
    fun clampedCenter(center: Float, halfSize: Float): Float {
        val c = center.coerceIn(0f, 1f)
        val lo = minOf(halfSize, 0.5f)
        val hi = maxOf(1f - halfSize, 0.5f)
        return c.coerceIn(lo, hi)
    }

    /**
     * 采样坐标（per-pixel 重映射，shader 镜像）。测试与语义文档用；
     * GPU 上就是 `uCenter + (uv - 0.5) * uS` 一行。
     */
    fun sampleUv(uv: Float, center: Float, size: Float): Float =
        clampedCenter(center, 0.5f * size) + (uv - 0.5f) * size

    // ------------------------------------------------------------------
    // 参数规整（UI / AI 入口共用）
    // ------------------------------------------------------------------

    /** durationUs 钳进 [0.3s, 8s]。 */
    fun clampDurationUs(us: Long): Long =
        us.coerceIn(com.RooMCutNext.core.model.DOLLY_MIN_DURATION_US,
            com.RooMCutNext.core.model.DOLLY_MAX_DURATION_US)

    /** maxScale 钳进 [1.2, 4]。 */
    fun clampMaxScale(scale: Float): Float =
        scale.coerceIn(DOLLY_MIN_SCALE, DOLLY_MAX_SCALE)

    /**
     * head/body 双约定的边界一致性（WYSIWYG 的接缝证明）：转场窗末
     * （head 的 T*speed 源钟位置）与窗首（body 的全局时间线位置 T）必须
     * 得到同一个 p —— 跨表面切换无跳变。effect 挂载参数由调用方按约定
     * 传入，这里只钉死公式等价性。
     */
    fun boundaryProgressMatch(
        transitionTimelineUs: Long,
        speed: Float,
        dollyDurationUs: Long,
    ): Boolean {
        if (dollyDurationUs <= 0L) return true
        val headP = progress((transitionTimelineUs * speed).toLong(), (dollyDurationUs * speed).toLong(), -1L)
        val bodyP = progress(transitionTimelineUs, dollyDurationUs, 0L)
        return headP == bodyP
    }
}
