package com.RooMCutNext.ai

import com.RooMCutNext.core.model.AudioClip
import com.RooMCutNext.core.model.Clip
import com.RooMCutNext.core.model.MediaAsset
import com.RooMCutNext.core.model.MediaType
import com.RooMCutNext.core.model.Project
import com.RooMCutNext.core.model.TextOverlayItem
import kotlin.math.abs
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6.0: AI 编辑工具执行器测试 —— 每个 agent 工具都必须严格落到
 * TimelineOps 语义上（与手动编辑同构），错误信息可指导模型自纠。
 */
class EditToolsTest {

    private fun asset(
        id: String,
        durUs: Long = 10_000_000L,
        type: MediaType = MediaType.VIDEO,
        fps: Float = 30f,
    ) = MediaAsset(
        id = id, fileName = "$id.mp4", displayName = "Asset $id", type = type,
        durationUs = durUs, width = 1920, height = 1080, fps = fps, hasAudio = true,
    )

    private fun clip(id: String, assetId: String, inUs: Long = 0L, outUs: Long = 10_000_000L) =
        Clip(id = id, assetId = assetId, sourceInUs = inUs, sourceOutUs = outUs)

    private fun project(
        clips: List<Clip>,
        assets: List<MediaAsset>,
        texts: List<TextOverlayItem> = emptyList(),
        audios: List<AudioClip> = emptyList(),
    ) = Project(id = "p", name = "P", assets = assets, clips = clips, texts = texts, audios = audios)

    private val tools = EditTools(
        fonts = listOf("system" to "系统", "font_kai" to "楷体"),
        luts = listOf("builtin:warm" to "暖阳", "builtin:mono" to "黑白"),
        idFactory = { "ai-x" },
    )

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                null -> put(k, JsonNull)
                is String -> put(k, v)
                is Boolean -> put(k, v)
                is Number -> put(k, v.toDouble())
            }
        }
    }

    private fun JsonObject.flag(): Boolean = this["ok"]?.jsonPrimitive?.doubleOrNull == 1.0 ||
        this["ok"]?.jsonPrimitive?.contentOrNull == "true"

    private fun JsonObject.num(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

    // ------------------------------------------------------------------

    @Test
    fun overviewListsClipsTextsAndPlayhead() {
        val p = project(
            clips = listOf(clip("c1", "a1"), clip("c2", "a2")),
            assets = listOf(asset("a1"), asset("a2")),
            texts = listOf(TextOverlayItem(id = "t1", text = "标题", startUs = 1_000_000L, endUs = 4_000_000L)),
        )
        val d = EditTools.Draft(p)
        val json = tools.overviewJson(d, playheadUs = 2_500_000L)
        assertEquals(2, json["clips"]!!.jsonArray.size)
        assertEquals(1, json["texts"]!!.jsonArray.size)
        assertEquals(2.5, json["playhead_sec"]!!.jsonPrimitive.doubleOrNull!!, 1e-9)
        assertEquals(20.0, json["duration_sec"]!!.jsonPrimitive.doubleOrNull!!, 1e-9)
        val luts = json["available_luts"]!!.jsonArray
        assertTrue(luts.toString().contains("builtin:warm"))
    }

    @Test
    fun splitAtMiddleProducesTwoClips() {
        val p = project(listOf(clip("c1", "a1")), listOf(asset("a1")))
        val d = EditTools.Draft(p)
        val r = tools.execute(d, "split_clip", args("clip_id" to "c1", "timeline_sec" to 5.0))
        assertTrue(r.json.toString(), r.ok)
        assertEquals(2, d.project.clips.size)
        assertEquals("ai-x", d.project.clips[1].id)
        // 帧网格对齐：两半源跨度之和严格等于原跨度（snap-once 不变量）。
        val left = d.project.clips[0].sourceSpanUs
        val right = d.project.clips[1].sourceSpanUs
        assertEquals(10_000_000L, left + right)
        assertEquals(1, d.changeCount)
        assertTrue(d.journal.single().startsWith("split_clip"))
    }

    @Test
    fun splitOutsideClipIsError() {
        val p = project(listOf(clip("c1", "a1")), listOf(asset("a1")))
        val d = EditTools.Draft(p)
        val r = tools.execute(d, "split_clip", args("clip_id" to "c1", "timeline_sec" to 15.0))
        assertFalse(r.ok)
        assertTrue(r.json.toString().contains("不在片段"))
        assertEquals(1, d.project.clips.size)
        assertEquals(0, d.changeCount)
    }

    @Test
    fun trimBothEdgesTimelineDomain() {
        val p = project(listOf(clip("c1", "a1")), listOf(asset("a1")))
        val d = EditTools.Draft(p)
        val r = tools.execute(
            d, "trim_clip",
            args("clip_id" to "c1", "new_start_sec" to 1.0, "new_end_sec" to 8.0),
        )
        assertTrue(r.json.toString(), r.ok)
        val start = r.json.num("timeline_start_sec")!!
        val dur = r.json.num("duration_sec")!!
        // 无缝胶片条：掐头 1s 后片段仍锚在 0s，时长 10→7s（含帧对齐容差）。
        assertTrue("start=$start", abs(start - 0.0) < 0.002)
        assertTrue("dur=$dur", abs(dur - 7.0) < 0.004)
    }

    @Test
    fun deleteClipRemovesAndKeepsOthers() {
        val p = project(
            listOf(clip("c1", "a1"), clip("c2", "a2"), clip("c3", "a1")),
            listOf(asset("a1"), asset("a2")),
        )
        val d = EditTools.Draft(p)
        val r = tools.execute(d, "delete_clip", args("clip_id" to "c2"))
        assertTrue(r.ok)
        assertEquals(listOf("c1", "c3"), d.project.clips.map { it.id })
    }

    @Test
    fun setTransitionAppliesStyleAndDuration() {
        val p = project(listOf(clip("c1", "a1"), clip("c2", "a2")), listOf(asset("a1"), asset("a2")))
        val d = EditTools.Draft(p)
        val r = tools.execute(
            d, "set_transition",
            args("clip_id" to "c2", "style_id" to "slide_left", "duration_sec" to 0.6),
        )
        assertTrue(r.json.toString(), r.ok)
        val c2 = d.project.clips[1]
        assertEquals("slide_left", c2.transitionType)
        assertEquals(600_000L, c2.transitionInUs)
    }

    @Test
    fun setTransitionRejectsUnknownStyleAndFirstClip() {
        val p = project(listOf(clip("c1", "a1"), clip("c2", "a2")), listOf(asset("a1"), asset("a2")))
        val d = EditTools.Draft(p)
        assertFalse(tools.execute(d, "set_transition", args("clip_id" to "c2", "style_id" to "turbo_fade")).ok)
        assertFalse(tools.execute(d, "set_transition", args("clip_id" to "c1", "style_id" to "dissolve")).ok)
    }

    @Test
    fun removeTransitionZeroesDuration() {
        val p = project(
            listOf(clip("c1", "a1"), clip("c2", "a2").copy(transitionInUs = 500_000L)),
            listOf(asset("a1"), asset("a2")),
        )
        val d = EditTools.Draft(p)
        assertTrue(tools.execute(d, "remove_transition", args("clip_id" to "c2")).ok)
        assertEquals(0L, d.project.clips[1].transitionInUs)
    }

    @Test
    fun addTextCreatesOverlayWithDefaults() {
        val p = project(listOf(clip("c1", "a1")), listOf(asset("a1")))
        val d = EditTools.Draft(p)
        val r = tools.execute(
            d, "add_text",
            args(
                "text" to "开场标题", "start_sec" to 1.0, "duration_sec" to 3.0,
                "color_hex" to "#FFCC00", "font_id" to "font_kai",
                "font_size_ratio" to 0.1, "x_ratio" to 0.25, "y_ratio" to 0.75,
            ),
        )
        assertTrue(r.json.toString(), r.ok)
        val t = d.project.texts.single()
        assertEquals("ai-x", t.id)
        assertEquals(1_000_000L, t.startUs)
        assertEquals(4_000_000L, t.endUs)
        assertEquals(0xFFFFCC00L, t.style.color)
        assertEquals("font_kai", t.style.fontId)
        assertEquals(0.1f, t.style.fontSizeRatio)
        assertEquals(0.25f, t.xRatio)
        assertEquals(0.75f, t.yRatio)
    }

    @Test
    fun updateTextMovesWindow() {
        val p = project(
            listOf(clip("c1", "a1")), listOf(asset("a1")),
            texts = listOf(TextOverlayItem(id = "t1", text = "旧", startUs = 1_000_000L, endUs = 4_000_000L)),
        )
        val d = EditTools.Draft(p)
        val r = tools.execute(
            d, "update_text",
            args("text_id" to "t1", "start_sec" to 0.5, "duration_sec" to 1.0, "text" to "新"),
        )
        assertTrue(r.json.toString(), r.ok)
        val t = d.project.texts.single()
        assertEquals("新", t.text)
        assertEquals(500_000L, t.startUs)
        assertEquals(1_500_000L, t.endUs)
    }

    @Test
    fun deleteText() {
        val p = project(
            listOf(clip("c1", "a1")), listOf(asset("a1")),
            texts = listOf(TextOverlayItem(id = "t1", text = "A"), TextOverlayItem(id = "t2", text = "B")),
        )
        val d = EditTools.Draft(p)
        assertTrue(tools.execute(d, "delete_text", args("text_id" to "t1")).ok)
        assertEquals(listOf("t2"), d.project.texts.map { it.id })
    }

    @Test
    fun speedClampsToBounds() {
        val p = project(listOf(clip("c1", "a1")), listOf(asset("a1")))
        val d = EditTools.Draft(p)
        tools.execute(d, "set_clip_speed", args("clip_id" to "c1", "speed" to 10.0))
        assertEquals(4.0f, d.project.clips[0].speed)
        tools.execute(d, "set_clip_speed", args("clip_id" to "c1", "speed" to 0.05))
        assertEquals(0.25f, d.project.clips[0].speed)
    }

    @Test
    fun lookRejectsUnknownLutAndClears() {
        val p = project(
            listOf(clip("c1", "a1").copy(sourceInUs = 0, sourceOutUs = 10_000_000L)),
            listOf(asset("a1")),
        )
        val d = EditTools.Draft(p)
        assertFalse(tools.execute(d, "set_clip_look", args("clip_id" to "c1", "lut_id" to "nope")).ok)
        assertTrue(tools.execute(d, "set_clip_look", args("clip_id" to "c1", "lut_id" to "builtin:warm", "strength" to 0.5)).ok)
        assertEquals("builtin:warm", d.project.clips[0].adjust.lutId)
        assertEquals(0.5f, d.project.clips[0].adjust.lutStrength)
        assertTrue(tools.execute(d, "set_clip_look", args("clip_id" to "c1", "lut_id" to null)).ok)
        assertNull(d.project.clips[0].adjust.lutId)
    }

    @Test
    fun seekRecordsTargetWithoutStructuralChange() {
        val p = project(listOf(clip("c1", "a1")), listOf(asset("a1")))
        val d = EditTools.Draft(p)
        val r = tools.execute(d, "seek", args("sec" to 3.5))
        assertTrue(r.ok)
        assertEquals(3_500_000L, d.seekUs)
        assertFalse(d.changed)
    }

    @Test
    fun volumeClampAndMute() {
        val p = project(listOf(clip("c1", "a1")), listOf(asset("a1")))
        val d = EditTools.Draft(p)
        tools.execute(d, "set_clip_volume", args("clip_id" to "c1", "volume" to 5.0))
        assertEquals(2.0f, d.project.clips[0].volume)
        tools.execute(d, "set_clip_volume", args("clip_id" to "c1", "mute" to true))
        assertTrue(d.project.clips[0].muted)
    }

    @Test
    fun moveClipReorders() {
        val p = project(
            listOf(clip("c1", "a1"), clip("c2", "a2"), clip("c3", "a1")),
            listOf(asset("a1"), asset("a2")),
        )
        val d = EditTools.Draft(p)
        assertTrue(tools.execute(d, "move_clip", args("clip_id" to "c3", "to_index" to 0)).ok)
        assertEquals(listOf("c3", "c1", "c2"), d.project.clips.map { it.id })
    }

    @Test
    fun trimAudioClampsToAsset() {
        val a = asset("a3", durUs = 30_000_000L, type = MediaType.AUDIO)
        val p = project(
            emptyList(), listOf(a),
            audios = listOf(AudioClip(id = "x1", assetId = "a3", sourceInUs = 0, sourceOutUs = 30_000_000L)),
        )
        val d = EditTools.Draft(p)
        val r = tools.execute(
            d, "trim_audio",
            args("audio_id" to "x1", "start_sec" to 2.0, "duration_sec" to 60.0),
        )
        assertTrue(r.json.toString(), r.ok)
        val audio = d.project.audios.single()
        assertEquals(2_000_000L, audio.startUs)
        assertEquals(30_000_000L, audio.durationUs)
    }

    @Test
    fun unknownToolReturnsError() {
        val p = project(emptyList(), emptyList())
        val d = EditTools.Draft(p)
        val r = tools.execute(d, "make_coffee", args())
        assertFalse(r.ok)
        assertTrue(r.json.toString().contains("未知工具"))
    }

    // ------------------------------------------------------------------
    // v1.6.1: add_black_clip（黑场）
    // ------------------------------------------------------------------

    @Test
    fun addBlackClipAfterAnchorHugsIt() {
        val p = project(listOf(clip("c1", "a1"), clip("c2", "a2")), listOf(asset("a1"), asset("a2")))
        val d = EditTools.Draft(p)
        val r = tools.execute(
            d, "add_black_clip",
            args("anchor_clip_id" to "c1", "position" to "after", "duration_sec" to 1.5),
        )
        assertTrue(r.json.toString(), r.ok)
        assertEquals("ai-x", r.json["new_clip_id"]?.jsonPrimitive?.contentOrNull)
        // 黑场紧贴 c1 之后，c2 右移；时长 1.5s。
        assertEquals(3, d.project.clips.size)
        assertEquals("ai-x", d.project.clips[1].id)
        assertEquals(1_500_000L, d.project.clips[1].durationUs)
        val st = com.RooMCutNext.core.ops.TimelineOps.starts(d.project.clips)
        assertEquals(10_000_000L, st[1])
        assertEquals(11_500_000L, st[2])
        // 草稿资产表里自动补上黑场资产（稳定 id，提交时 ViewModel 落盘治愈）。
        assertTrue(d.project.assets.any { it.id == com.RooMCutNext.core.model.BLACK_FRAME_ASSET_ID })
        assertTrue(d.journal.single().startsWith("add_black_clip"))
    }

    @Test
    fun addBlackClipBeforeAnchorAndDefaultDuration() {
        val p = project(listOf(clip("c1", "a1")), listOf(asset("a1")))
        val d = EditTools.Draft(p)
        // 省略 position 默认 after；这里显式 before + 省略时长（默认 2s）。
        val r = tools.execute(d, "add_black_clip", args("anchor_clip_id" to "c1", "position" to "before"))
        assertTrue(r.json.toString(), r.ok)
        assertEquals(2, d.project.clips.size)
        assertEquals(2_000_000L, d.project.clips[0].durationUs)
        assertEquals(com.RooMCutNext.core.model.BLACK_FRAME_ASSET_ID, d.project.clips[0].assetId)
    }

    @Test
    fun addBlackClipWithoutAnchorAppendsAtEnd() {
        val p = project(listOf(clip("c1", "a1")), listOf(asset("a1")))
        val d = EditTools.Draft(p)
        val r = tools.execute(d, "add_black_clip", args("duration_sec" to 3.0))
        assertTrue(r.json.toString(), r.ok)
        assertEquals(2, d.project.clips.size)
        assertEquals(3_000_000L, d.project.clips[1].durationUs)
        // 空时间线：黑场单独成片（文字压黑场工作流）。
        val d2 = EditTools.Draft(project(emptyList(), emptyList()))
        val r2 = tools.execute(d2, "add_black_clip", args("duration_sec" to 2.0))
        assertTrue(r2.json.toString(), r2.ok)
        assertEquals(1, d2.project.clips.size)
    }

    @Test
    fun addBlackClipClampsDurationAndValidatesPosition() {
        val p = project(listOf(clip("c1", "a1")), listOf(asset("a1")))
        val d = EditTools.Draft(p)
        // 时长越界被钳入 0.1..60s；无上限报错。
        val r = tools.execute(d, "add_black_clip", args("duration_sec" to 99.0, "position" to "after"))
        assertTrue(r.json.toString(), r.ok)
        assertEquals(60_000_000L, d.project.clips[1].durationUs)
        val rBadPos = tools.execute(d, "add_black_clip", args("position" to "middle"))
        assertFalse(rBadPos.json.toString(), rBadPos.ok)
        // 锚 id 无效 → 可执行错误信息（列出可用 id）。
        val rBadAnchor = tools.execute(d, "add_black_clip", args("anchor_clip_id" to "nope"))
        assertFalse(rBadAnchor.json.toString(), rBadAnchor.ok)
        assertTrue(rBadAnchor.json.toString().contains("c1"))
    }

    @Test
    fun deleteClipNowRipplesClosed() {
        // v1.6.1: delete_clip 与手动删除同构 —— 后续素材收拢贴合，不留洞。
        val p = project(
            listOf(clip("c1", "a1", outUs = 2_000_000L), clip("c2", "a2"), clip("c3", "a3")),
            listOf(asset("a1"), asset("a2"), asset("a3")),
        )
        val d = EditTools.Draft(p)
        val r = tools.execute(d, "delete_clip", args("clip_id" to "c2"))
        assertTrue(r.json.toString(), r.ok)
        val st = com.RooMCutNext.core.ops.TimelineOps.starts(d.project.clips)
        assertEquals(2_000_000L, st[1]) // c3 直接贴上 c1
        assertEquals(0L, d.project.clips.sumOf { it.gapBeforeUs })
    }

    @Test
    fun overviewMarksBlackClips() {
        val blackAsset = com.RooMCutNext.core.model.MediaAsset(
            id = com.RooMCutNext.core.model.BLACK_FRAME_ASSET_ID,
            fileName = com.RooMCutNext.core.model.BLACK_FRAME_FILE_NAME,
            displayName = "黑场", type = MediaType.IMAGE,
            durationUs = 0L, width = 1920, height = 1080, fps = 0f, hasAudio = false,
        )
        val p = project(
            clips = listOf(clip("c1", "a1"), Clip(id = "b1", assetId = com.RooMCutNext.core.model.BLACK_FRAME_ASSET_ID, sourceInUs = 0L, sourceOutUs = 2_000_000L)),
            assets = listOf(asset("a1"), blackAsset),
        )
        val d = EditTools.Draft(p)
        val json = tools.overviewJson(d)
        val clipsJson = json["clips"]!!.jsonArray.toString()
        assertTrue(clipsJson.contains("\"black\":true"))
        // system prompt 同步告知 agent 黑场能力。
        assertTrue(tools.systemPrompt(d, 0L).contains("add_black_clip"))
    }

    @Test
    fun systemPromptContainsOverview() {
        val p = project(listOf(clip("c1", "a1")), listOf(asset("a1")))
        val d = EditTools.Draft(p)
        val prompt = tools.systemPrompt(d, playheadUs = 0L)
        assertTrue(prompt.contains("get_draft_overview"))
        assertTrue(prompt.contains("\"c1\""))
        assertTrue(prompt.contains("RooMCutNext"))
    }

    @Test
    fun toolSchemasExposeAllTools() {
        val names = tools.toolSchemas().toString()
        listOf(
            "get_draft_overview", "split_clip", "delete_clip", "trim_clip", "restore_clip",
            "move_clip", "set_clip_speed", "set_clip_volume", "set_clip_look", "set_transition",
            "remove_transition", "add_black_clip", "add_clip_from_library", "add_text", "update_text",
            "delete_text", "set_audio_volume", "trim_audio", "delete_audio", "seek",
        ).forEach { assertTrue("missing $names", names.contains("\"$it\"")) }
        // zoom 转场 v1.5.5 已移除，不应对 agent 开放。
        assertFalse(names.contains("\"zoom_in\""))
        assertFalse(names.contains("\"zoom_out\""))
    }

    // ------------------------------------------------------------------
    // v1.6.12: 原始素材可见性 + restore_clip（用户真机反馈：修建后让 AI
    // 恢复原始长度，AI 却说“素材现在就是完整长度”——概览里根本没有
    // 原始时长/窗口信息，模型只能瞎猜。修剪本是非破坏性窗口。）
    // ------------------------------------------------------------------

    @Test
    fun overviewShowsSourceWindowAndTrimmedFlag() {
        val p = project(
            listOf(
                clip("c1", "a1", inUs = 2_000_000L, outUs = 6_000_000L), // 修建过
                clip("c2", "a2"),                                        // 全长 0..10s
                clip("c3", "aimg", outUs = 8_000_000L),                  // 图片
            ),
            listOf(asset("a1"), asset("a2"), asset("aimg", durUs = 0L, type = MediaType.IMAGE)),
        )
        val d = EditTools.Draft(p)
        val clips = tools.overviewJson(d)["clips"]!!.jsonArray
        val c1 = clips[0].toString()
        assertTrue(c1, c1.contains("\"source_duration_sec\":10.0"))
        assertTrue(c1, c1.contains("\"source_in_sec\":2.0"))
        assertTrue(c1, c1.contains("\"source_out_sec\":6.0"))
        assertTrue(c1, c1.contains("\"trimmed\":true"))
        val c2 = clips[1].toString()
        assertTrue(c2, c2.contains("\"source_duration_sec\":10.0"))
        assertFalse(c2, c2.contains("\"trimmed\""))
        // 图片没有“原始时长”概念，不报告窗口字段。
        val c3 = clips[2].toString()
        assertFalse(c3, c3.contains("source_duration_sec"))
    }

    @Test
    fun restoreClipReopensFullSourceWindow() {
        val p = project(
            listOf(clip("c1", "a1", inUs = 2_000_000L, outUs = 6_000_000L).copy(speed = 2f, volume = 0.5f)),
            listOf(asset("a1")),
        )
        val d = EditTools.Draft(p)
        val r = tools.execute(d, "restore_clip", args("clip_id" to "c1"))
        assertTrue(r.json.toString(), r.ok)
        val c = d.project.clips.single()
        assertEquals(0L, c.sourceInUs)
        assertEquals(10_000_000L, c.sourceOutUs)
        // 只还原窗口：速度/音量等其它属性一概不动。
        assertEquals(2f, c.speed)
        assertEquals(0.5f, c.volume)
        assertEquals(5_000_000L, c.durationUs) // 10s 源 @2x = 5s 时间线
        assertEquals(10.0, r.json.num("source_duration_sec")!!, 1e-9)
        assertEquals(5.0, r.json.num("duration_sec")!!, 1e-9)
        assertTrue(d.journal.single().startsWith("restore_clip"))
        assertEquals(1, d.changeCount)
    }

    @Test
    fun restoreClipAlreadyFullIsHonestNoOp() {
        val p = project(listOf(clip("c1", "a1")), listOf(asset("a1")))
        val d = EditTools.Draft(p)
        val r = tools.execute(d, "restore_clip", args("clip_id" to "c1"))
        assertTrue(r.json.toString(), r.ok)
        assertEquals("true", r.json["already_full"]?.jsonPrimitive?.contentOrNull)
        assertEquals(10.0, r.json.num("source_duration_sec")!!, 1e-9)
        assertEquals(0, d.changeCount)
        assertFalse(d.changed)
    }

    @Test
    fun restoreClipImageResetsToDefaultDuration() {
        val p = project(
            listOf(clip("c1", "aimg", outUs = 10_000_000L)),
            listOf(asset("aimg", durUs = 0L, type = MediaType.IMAGE)),
        )
        val d = EditTools.Draft(p)
        val r = tools.execute(d, "restore_clip", args("clip_id" to "c1"))
        assertTrue(r.json.toString(), r.ok)
        assertEquals(0L, d.project.clips[0].sourceInUs)
        assertEquals(3_000_000L, d.project.clips[0].sourceOutUs) // 图片默认展示 3s
        assertEquals(3.0, r.json.num("duration_sec")!!, 1e-9)
    }

    @Test
    fun restoreClipInvalidIdIsError() {
        val p = project(listOf(clip("c1", "a1")), listOf(asset("a1")))
        val d = EditTools.Draft(p)
        val r = tools.execute(d, "restore_clip", args("clip_id" to "nope"))
        assertFalse(r.ok)
        assertTrue(r.json.toString().contains("clip_id 无效"))
        assertEquals(0, d.changeCount)
    }

    @Test
    fun systemPromptExplainsSourceSemantics() {
        val p = project(listOf(clip("c1", "a1")), listOf(asset("a1")))
        val d = EditTools.Draft(p)
        val prompt = tools.systemPrompt(d, 0L)
        // 规则 8：原始素材可见性 + “禁止谎称无法恢复”。
        assertTrue(prompt.contains("restore_clip"))
        assertTrue(prompt.contains("source_duration_sec"))
        assertTrue(prompt.contains("NEVER claim"))
        // 概览内嵌：全长片段报告原始时长；概览 JSON 段里不出现 trimmed 标记
        // （规则 8 文本里带引号的 "trimmed" 字样在概览段之前，不算）。
        assertTrue(prompt.contains("\"source_duration_sec\":10.0"))
        val overviewPart = prompt.substringAfter("Current draft overview (JSON):\n")
        assertFalse(overviewPart.contains("\"trimmed\""))
    }

    // ------------------------------------------------------------------
    // v1.6.13: 素材库 —— AI 看得见库里全部素材并可自主上时间线
    //（用户反馈：素材应先进素材库，AI 分析库内容自己挑素材更精确）
    // ------------------------------------------------------------------

    @Test
    fun overviewListsLibraryWithUsedCount() {
        val p = project(
            clips = listOf(clip("c1", "a1"), clip("c2", "a1")),
            assets = listOf(asset("a1"), asset("a2"), asset("aimg", durUs = 0L, type = MediaType.IMAGE)),
            audios = listOf(AudioClip(id = "aud1", assetId = "a2", startUs = 0L, sourceInUs = 0L, sourceOutUs = 5_000_000L)),
        )
        val d = EditTools.Draft(p)
        val library = tools.overviewJson(d)["library"]!!.jsonArray
        assertEquals(3, library.size)
        val a1 = library[0].toString()
        assertTrue(a1, a1.contains("\"id\":\"a1\""))
        assertTrue(a1, a1.contains("\"used_count\":2"))
        assertTrue(a1, a1.contains("\"duration_sec\":10.0"))
        assertTrue(a1, a1.contains("\"type\":\"video\""))
        // 未上时间线的资产 used_count = 0（音频引用也计数）。
        val a2 = library[1].toString()
        assertTrue(a2, a2.contains("\"used_count\":1"))
        val aimg = library[2].toString()
        assertTrue(aimg, aimg.contains("\"type\":\"image\""))
        assertFalse(aimg.contains("duration_sec"))
        // system prompt 讲清 library 语义与挑素材流程。
        val prompt = tools.systemPrompt(d, 0L)
        assertTrue(prompt.contains("add_clip_from_library"))
        assertTrue(prompt.contains("library array"))
    }

    @Test
    fun overviewLibraryHidesBlackFrameToolAsset() {
        val blackAsset = MediaAsset(
            id = com.RooMCutNext.core.model.BLACK_FRAME_ASSET_ID,
            fileName = com.RooMCutNext.core.model.BLACK_FRAME_FILE_NAME,
            displayName = "黑场", type = MediaType.IMAGE,
            durationUs = 0L, width = 1920, height = 1080, fps = 0f, hasAudio = false,
        )
        val p = project(listOf(clip("c1", "a1")), listOf(asset("a1"), blackAsset))
        val d = EditTools.Draft(p)
        val library = tools.overviewJson(d)["library"]!!.jsonArray
        assertEquals(1, library.size)
        assertFalse(library.toString().contains(com.RooMCutNext.core.model.BLACK_FRAME_ASSET_ID))
    }

    @Test
    fun addClipFromLibraryAppendsAtEnd() {
        val p = project(
            listOf(clip("c1", "a1")),
            listOf(asset("a1"), asset("a2")),
        )
        val d = EditTools.Draft(p)
        val r = tools.execute(d, "add_clip_from_library", args("asset_id" to "a2"))
        assertTrue(r.json.toString(), r.ok)
        assertEquals(2, d.project.clips.size)
        val added = d.project.clips[1]
        assertEquals("a2", added.assetId)
        assertEquals(0L, added.sourceInUs)
        assertEquals(10_000_000L, added.sourceOutUs)
        assertEquals(1.0, r.json.num("index")!!, 1e-9)
        assertEquals(10.0, r.json.num("duration_sec")!!, 1e-9)
        assertTrue(d.journal.single().startsWith("add_clip_from_library"))
        assertEquals(1, d.changeCount)
    }

    @Test
    fun addClipFromLibraryBeforeAnchor() {
        val p = project(
            listOf(clip("c1", "a1"), clip("c2", "a1")),
            listOf(asset("a1"), asset("a2")),
        )
        val d = EditTools.Draft(p)
        val r = tools.execute(
            d, "add_clip_from_library",
            args("asset_id" to "a2", "anchor_clip_id" to "c2", "position" to "before"),
        )
        assertTrue(r.json.toString(), r.ok)
        // 插在 c2 之前：[c1, 新(a2), c2]，新片段在 index 1。
        assertEquals(1.0, r.json.num("index")!!, 1e-9)
        assertEquals("c1", d.project.clips[0].id)
        assertEquals("a2", d.project.clips[1].assetId)
        assertEquals("c2", d.project.clips[2].id)
    }

    @Test
    fun addClipFromLibraryUsesSourceSegmentAndCanvas() {
        val a = MediaAsset(
            id = "a1", fileName = "a1.mp4", displayName = "Asset a1", type = MediaType.VIDEO,
            durationUs = 10_000_000L, width = 1080, height = 1920, fps = 24f, hasAudio = true,
        )
        val p = project(emptyList(), listOf(a))
        val d = EditTools.Draft(p)
        val r = tools.execute(
            d, "add_clip_from_library",
            args("asset_id" to "a1", "source_in_sec" to 2.0, "source_out_sec" to 5.0),
        )
        assertTrue(r.json.toString(), r.ok)
        val c = d.project.clips.single()
        assertEquals(2_000_000L, c.sourceInUs)
        assertEquals(5_000_000L, c.sourceOutUs)
        // 空时间线首片段初始化画布/帧率（同手动导入语义）。
        assertEquals(1080, d.project.width)
        assertEquals(1920, d.project.height)
        assertEquals(24, d.project.fps)
    }

    @Test
    fun addClipFromLibraryClampsWindowToSource() {
        val p = project(emptyList(), listOf(asset("a1")))
        val d = EditTools.Draft(p)
        val r = tools.execute(
            d, "add_clip_from_library",
            args("asset_id" to "a1", "source_in_sec" to -3.0, "source_out_sec" to 99.0),
        )
        assertTrue(r.json.toString(), r.ok)
        val c = d.project.clips.single()
        assertEquals(0L, c.sourceInUs)
        assertEquals(10_000_000L, c.sourceOutUs)
    }

    @Test
    fun addClipFromLibraryAudioAtPlayhead() {
        val p = project(
            listOf(clip("c1", "a1")),
            listOf(asset("a1"), asset("am", type = MediaType.AUDIO)),
        )
        val d = EditTools.Draft(p, playheadUs = 4_000_000L)
        val r = tools.execute(d, "add_clip_from_library", args("asset_id" to "am"))
        assertTrue(r.json.toString(), r.ok)
        assertEquals(4.0, r.json.num("start_sec")!!, 1e-9)
        assertEquals(10.0, r.json.num("duration_sec")!!, 1e-9)
        val aud = d.project.audios.single()
        assertEquals(4_000_000L, aud.startUs)
        assertEquals(0L, aud.sourceInUs)
        assertEquals(10_000_000L, aud.sourceOutUs)
        // 音频资产不动视频轨。
        assertEquals(1, d.project.clips.size)
    }

    @Test
    fun addClipFromLibraryImageDisplayDuration() {
        val p = project(emptyList(), listOf(asset("aimg", durUs = 0L, type = MediaType.IMAGE)))
        val d = EditTools.Draft(p)
        val r = tools.execute(d, "add_clip_from_library", args("asset_id" to "aimg"))
        assertTrue(r.json.toString(), r.ok)
        assertEquals(3_000_000L, d.project.clips.single().sourceOutUs) // 图片默认展示 3s
        // 指定展示时长直接生效。
        val d2 = EditTools.Draft(p)
        val r2 = tools.execute(d2, "add_clip_from_library", args("asset_id" to "aimg", "source_out_sec" to 5.0))
        assertTrue(r2.json.toString(), r2.ok)
        assertEquals(5_000_000L, d2.project.clips.single().sourceOutUs)
    }

    @Test
    fun addClipFromLibraryInvalidAssetListsLibrary() {
        val p = project(listOf(clip("c1", "a1")), listOf(asset("a1")))
        val d = EditTools.Draft(p)
        val r = tools.execute(d, "add_clip_from_library", args("asset_id" to "nope"))
        assertFalse(r.ok)
        // 错误信息列出可用 asset，模型可自纠。
        assertTrue(r.json.toString().contains("a1"))
        assertEquals(0, d.changeCount)
    }

    @Test
    fun addClipFromLibraryRejectsBlackFrameAsset() {
        val blackAsset = MediaAsset(
            id = com.RooMCutNext.core.model.BLACK_FRAME_ASSET_ID,
            fileName = com.RooMCutNext.core.model.BLACK_FRAME_FILE_NAME,
            displayName = "黑场", type = MediaType.IMAGE,
            durationUs = 0L, width = 1920, height = 1080, fps = 0f, hasAudio = false,
        )
        val p = project(emptyList(), listOf(asset("a1"), blackAsset))
        val d = EditTools.Draft(p)
        val r = tools.execute(
            d, "add_clip_from_library",
            args("asset_id" to com.RooMCutNext.core.model.BLACK_FRAME_ASSET_ID),
        )
        assertFalse(r.ok)
        assertEquals(0, d.changeCount)
    }

    // ------------------------------------------------------------------
    // v1.6.21: dolly-zoom 工具
    // ------------------------------------------------------------------

    @Test
    fun dollySetFullParamsAppliesClampedSpec() {
        val p = project(listOf(clip("c1", "a1")), listOf(asset("a1")))
        val d = EditTools.Draft(p)
        val r = tools.execute(
            d, "set_clip_dolly_zoom",
            args(
                "clip_id" to "c1", "duration_sec" to 4.0, "max_scale" to 3.0,
                "direction" to "out", "center_x" to 0.25, "center_y" to 0.75,
            ),
        )
        assertTrue(r.ok)
        val spec = d.project.clips[0].dollyZoom
        assertNotNull(spec)
        assertEquals(4_000_000L, spec!!.durationUs)
        assertEquals(false, spec.zoomIn)
        assertEquals(3.0f, spec.maxScale, 1e-6f)
        assertEquals(0.25f, spec.centerX, 1e-6f)
        assertEquals(0.75f, spec.centerY, 1e-6f)
        assertEquals(1, d.changeCount)
    }

    @Test
    fun dollySetDefaultsAndDomainClamps() {
        val p = project(listOf(clip("c1", "a1")), listOf(asset("a1")))
        val d = EditTools.Draft(p)
        // 仅 clip_id：默认 2s / 2x / 推近 / 中心。
        var r = tools.execute(d, "set_clip_dolly_zoom", args("clip_id" to "c1"))
        assertTrue(r.ok)
        var spec = d.project.clips[0].dollyZoom!!
        assertEquals(2_000_000L, spec.durationUs)
        assertEquals(true, spec.zoomIn)
        assertEquals(2.0f, spec.maxScale, 1e-6f)
        // 域钳制：99s → 8s；0.5x → 1.2x；center 越界 → 0/1。
        r = tools.execute(
            d, "set_clip_dolly_zoom",
            args(
                "clip_id" to "c1", "duration_sec" to 99.0, "max_scale" to 0.5,
                "center_x" to -1.0, "center_y" to 2.0,
            ),
        )
        assertTrue(r.ok)
        spec = d.project.clips[0].dollyZoom!!
        assertEquals(8_000_000L, spec.durationUs)
        assertEquals(1.2f, spec.maxScale, 1e-6f)
        assertEquals(0f, spec.centerX, 1e-6f)
        assertEquals(1f, spec.centerY, 1e-6f)
    }

    @Test
    fun dollySetOmittedParamsKeepCurrentValues() {
        val p = project(listOf(clip("c1", "a1")), listOf(asset("a1")))
        val d = EditTools.Draft(p)
        tools.execute(
            d, "set_clip_dolly_zoom",
            args("clip_id" to "c1", "direction" to "out", "max_scale" to 3.0, "center_x" to 0.3),
        )
        // 只改时长：方向/强度/中心保留。
        tools.execute(d, "set_clip_dolly_zoom", args("clip_id" to "c1", "duration_sec" to 5.0))
        val spec = d.project.clips[0].dollyZoom!!
        assertEquals(5_000_000L, spec.durationUs)
        assertEquals(false, spec.zoomIn)
        assertEquals(3.0f, spec.maxScale, 1e-6f)
        assertEquals(0.3f, spec.centerX, 1e-6f)
    }

    @Test
    fun dollySetRejectsBadDirectionAndClip() {
        val p = project(listOf(clip("c1", "a1")), listOf(asset("a1")))
        var d = EditTools.Draft(p)
        var r = tools.execute(
            d, "set_clip_dolly_zoom", args("clip_id" to "c1", "direction" to "sideways"),
        )
        assertFalse(r.ok)
        assertEquals(0, d.changeCount)
        d = EditTools.Draft(p)
        r = tools.execute(d, "set_clip_dolly_zoom", args("clip_id" to "nope"))
        assertFalse(r.ok)
        assertEquals(0, d.changeCount)
    }

    @Test
    fun dollyRemoveClearsAndReports() {
        val p = project(listOf(clip("c1", "a1")), listOf(asset("a1")))
        val d = EditTools.Draft(p)
        tools.execute(d, "set_clip_dolly_zoom", args("clip_id" to "c1"))
        val r = tools.execute(d, "remove_clip_dolly_zoom", args("clip_id" to "c1"))
        assertTrue(r.ok)
        assertNull(d.project.clips[0].dollyZoom)
        // 已无效果的再删：幂等成功，removed=false。
        val r2 = tools.execute(d, "remove_clip_dolly_zoom", args("clip_id" to "c1"))
        assertTrue(r2.ok)
    }
}
