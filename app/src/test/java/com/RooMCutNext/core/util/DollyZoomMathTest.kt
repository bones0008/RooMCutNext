package com.RooMCutNext.core.util

import com.RooMCutNext.core.model.Clip
import com.RooMCutNext.core.model.DollyZoomSpec
import kotlin.math.abs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6.21: dolly-zoom 数学唯一权威的测试。shader 只转录这些公式
 * （DollyZoomShaderProgram 每帧在 CPU 上调用本对象），所以这里的断言
 * 就是预览/导出的行为契约。
 */
class DollyZoomMathTest {

    private val eps = 1e-6f

    // ------------------------------------------------------------------
    // progress — 双钟约定
    // ------------------------------------------------------------------

    @Test
    fun progressItemLocalWindow() {
        // head 约定：globalStartUs = -1，tUs 即窗内偏移。
        assertEquals(0f, DollyZoomMath.progress(0L, 2_000_000L, -1L), eps)
        assertEquals(0.5f, DollyZoomMath.progress(1_000_000L, 2_000_000L, -1L), eps)
        assertEquals(0.75f, DollyZoomMath.progress(1_500_000L, 2_000_000L, -1L), eps)
        // 窗末（含）之后保持终点态。
        assertEquals(1f, DollyZoomMath.progress(2_000_000L, 2_000_000L, -1L), eps)
        assertEquals(1f, DollyZoomMath.progress(9_000_000L, 2_000_000L, -1L), eps)
    }

    @Test
    fun progressGlobalClockWindow() {
        // body/export 约定：globalStartUs = 素材时间线起点。
        assertEquals(0f, DollyZoomMath.progress(3_000_000L, 5_000_000L, 3_000_000L), eps)
        assertEquals(0.2f, DollyZoomMath.progress(4_000_000L, 5_000_000L, 3_000_000L), eps)
        assertEquals(1f, DollyZoomMath.progress(8_000_000L, 5_000_000L, 3_000_000L), eps)
        // 窗前 = 起始态 0，窗后 = 终点态 1。
        assertEquals(0f, DollyZoomMath.progress(1_000_000L, 5_000_000L, 3_000_000L), eps)
        assertEquals(1f, DollyZoomMath.progress(99_000_000L, 5_000_000L, 3_000_000L), eps)
    }

    @Test
    fun progressLongWindowDoesNotMisreadAsItemLocal() {
        // 钉死 DollyZoomEffect 类文档里的"长窗陷阱"：TransitionMath.progress
        // 的 item-local 分支没有 globalStartUs < 0 守卫，靠转场窗 ≤2s 且
        // effectiveTransitions 保证 globalStartUs ≥ fadeUs 才没踩坑。dolly 窗
        // 最长 8s 且可从任意 clipStart 开始 —— globalStart=3s、D=5s 时
        // t=4s（真进度 0.2）若被误读为 item-local 会给出 0.8。
        val p = DollyZoomMath.progress(4_000_000L, 5_000_000L, 3_000_000L)
        assertEquals(0.2f, p, eps)
    }

    @Test
    fun progressZeroDurationIsComplete() {
        assertEquals(1f, DollyZoomMath.progress(0L, 0L, -1L), eps)
        assertEquals(1f, DollyZoomMath.progress(5L, -3L, 100L), eps)
    }

    @Test
    fun progressGlobalStartZeroUsesGlobalBranch() {
        // 第一个素材：clipStart = 0。第一分支（item-local）被 globalStartUs<0
        // 守卫挡下，走全局分支 —— 与 item-local 同式但语义明确。
        assertEquals(0f, DollyZoomMath.progress(0L, 2_000_000L, 0L), eps)
        assertEquals(0.5f, DollyZoomMath.progress(1_000_000L, 2_000_000L, 0L), eps)
    }

    // ------------------------------------------------------------------
    // windowSize — 窗口全边长
    // ------------------------------------------------------------------

    @Test
    fun windowSizePushInEndpoints() {
        assertEquals(1f, DollyZoomMath.windowSize(0f, 2f, zoomIn = true), eps)
        assertEquals(0.5f, DollyZoomMath.windowSize(1f, 2f, zoomIn = true), eps)
        // 线性中点。
        assertEquals(0.75f, DollyZoomMath.windowSize(0.5f, 2f, zoomIn = true), eps)
    }

    @Test
    fun windowSizePullBackMirrorsPushIn() {
        assertEquals(0.5f, DollyZoomMath.windowSize(0f, 2f, zoomIn = false), eps)
        assertEquals(1f, DollyZoomMath.windowSize(1f, 2f, zoomIn = false), eps)
        assertEquals(0.75f, DollyZoomMath.windowSize(0.5f, 2f, zoomIn = false), eps)
    }

    @Test
    fun windowSizeClampsMaxScaleIntoDomain() {
        // 0.5 低于下限 → 按 1.2 算；99 高于上限 → 按 4 算。
        assertEquals(1f / 1.2f, DollyZoomMath.windowSize(1f, 0.5f, zoomIn = true), 1e-4f)
        assertEquals(0.25f, DollyZoomMath.windowSize(1f, 99f, zoomIn = true), eps)
    }

    @Test
    fun windowSizeClampsProgress() {
        assertEquals(1f, DollyZoomMath.windowSize(-0.5f, 2f, zoomIn = true), eps)
        assertEquals(0.5f, DollyZoomMath.windowSize(1.5f, 2f, zoomIn = true), eps)
    }

    // ------------------------------------------------------------------
    // clampedCenter — 窗不出画
    // ------------------------------------------------------------------

    @Test
    fun clampedCenterKeepsFreeCenterWhenWindowFits() {
        // s=0.5 → half=0.25：中心 0.3/0.7 自由。
        assertEquals(0.3f, DollyZoomMath.clampedCenter(0.3f, 0.25f), eps)
        assertEquals(0.7f, DollyZoomMath.clampedCenter(0.7f, 0.25f), eps)
    }

    @Test
    fun clampedCenterPullsEdgeCenterInside() {
        // s=0.5 → half=0.25：中心 0.9 越界 → 钳到 0.75；0.05 → 0.25。
        assertEquals(0.75f, DollyZoomMath.clampedCenter(0.9f, 0.25f), eps)
        assertEquals(0.25f, DollyZoomMath.clampedCenter(0.05f, 0.25f), eps)
    }

    @Test
    fun clampedCenterFullWindowPinsToHalf() {
        // s=1 → half=0.5：合法域收缩成单点 0.5（全幅窗的中心必是画面中心）。
        assertEquals(0.5f, DollyZoomMath.clampedCenter(0.8f, 0.5f), eps)
        assertEquals(0.5f, DollyZoomMath.clampedCenter(0.2f, 0.5f), eps)
    }

    @Test
    fun clampedCenterSanitizesDirtyCenter() {
        // 模型脏值（>1 / <0）先钳进 [0,1] 再进窗口域。
        assertEquals(0.75f, DollyZoomMath.clampedCenter(5f, 0.25f), eps)
        assertEquals(0.25f, DollyZoomMath.clampedCenter(-5f, 0.25f), eps)
    }

    // ------------------------------------------------------------------
    // sampleUv — per-pixel 重映射（shader 镜像）
    // ------------------------------------------------------------------

    @Test
    fun sampleUvIdentityAtFullWindow() {
        // 无效果（s=1，c=0.5）逐字节恒等 —— always-mounted 预览实例的
        // 空转安全性就靠它。
        for (uv in listOf(0f, 0.17f, 0.5f, 0.83f, 1f)) {
            assertEquals(uv, DollyZoomMath.sampleUv(uv, 0.5f, 1f), eps)
        }
    }

    @Test
    fun sampleUvHalfWindowZoomsCenter() {
        // s=0.5、c=0.5：uv∈[0,1] → [0.25, 0.75]（中心区域拉满）。
        assertEquals(0.25f, DollyZoomMath.sampleUv(0f, 0.5f, 0.5f), eps)
        assertEquals(0.5f, DollyZoomMath.sampleUv(0.5f, 0.5f, 0.5f), eps)
        assertEquals(0.75f, DollyZoomMath.sampleUv(1f, 0.5f, 0.5f), eps)
    }

    @Test
    fun sampleUvOffCenterWindow() {
        // s=0.5、c=0.75（已钳）：[0.5, 1.0] —— 窗口靠右。
        assertEquals(0.5f, DollyZoomMath.sampleUv(0f, 0.75f, 0.5f), eps)
        assertEquals(1f, DollyZoomMath.sampleUv(1f, 0.75f, 0.5f), eps)
    }

    // ------------------------------------------------------------------
    // 域钳制
    // ------------------------------------------------------------------

    @Test
    fun clampDurationAndScale() {
        assertEquals(300_000L, DollyZoomMath.clampDurationUs(1L))
        assertEquals(8_000_000L, DollyZoomMath.clampDurationUs(99_000_000L))
        assertEquals(2_000_000L, DollyZoomMath.clampDurationUs(2_000_000L))
        assertEquals(1.2f, DollyZoomMath.clampMaxScale(0.5f), eps)
        assertEquals(4f, DollyZoomMath.clampMaxScale(99f), eps)
        assertEquals(2f, DollyZoomMath.clampMaxScale(2f), eps)
    }

    // ------------------------------------------------------------------
    // 双钟边界一致性（head→body 表面交接无缝的证明）
    // ------------------------------------------------------------------

    @Test
    fun boundaryProgressMatchesAcrossConventions() {
        // 转场 0.5s、speed 1.5x、dolly 2s：head 在窗末（源钟 0.75s）的
        // 进度必须等于 body 在全局 0.5s 的进度。
        assertTrue(DollyZoomMath.boundaryProgressMatch(500_000L, 1.5f, 2_000_000L))
        assertTrue(DollyZoomMath.boundaryProgressMatch(500_000L, 1f, 2_000_000L))
        assertTrue(DollyZoomMath.boundaryProgressMatch(1_200_000L, 0.25f, 8_000_000L))
        // 全程采样一致（进度相同 → 窗口边长相同 → 两表面画面相同）。
        val t = 300_000L
        val speed = 1.5f
        val d = 2_000_000L
        val headP = DollyZoomMath.progress((t * speed).toLong(), (d * speed).toLong(), -1L)
        val bodyP = DollyZoomMath.progress(t, d, 0L)
        assertTrue(abs(headP - bodyP) < 1e-4f)
    }

    // ------------------------------------------------------------------
    // 序列化兼容（旧项目 JSON 无字段 → null）
    // ------------------------------------------------------------------

    @Test
    fun oldProjectJsonDecodesWithoutDolly() {
        val legacy = """
            {"id":"c1","assetId":"a1","sourceInUs":0,"sourceOutUs":10000000}
        """.trimIndent()
        val clip = Json.decodeFromString<Clip>(legacy)
        assertNull(clip.dollyZoom)
    }

    @Test
    fun dollySpecRoundTrip() {
        val spec = DollyZoomSpec(
            durationUs = 3_500_000L, zoomIn = false, maxScale = 2.5f,
            centerX = 0.31f, centerY = 0.72f,
        )
        val clip = Clip(id = "c1", assetId = "a1", sourceInUs = 0L,
            sourceOutUs = 10_000_000L, dollyZoom = spec)
        val text = Json.encodeToString<Clip>(clip)
        val back = Json.decodeFromString<Clip>(text)
        assertNotNull(back.dollyZoom)
        assertEquals(spec, back.dollyZoom)
    }
}
