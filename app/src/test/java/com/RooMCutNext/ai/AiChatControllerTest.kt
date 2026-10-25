package com.RooMCutNext.ai

import com.RooMCutNext.core.model.Clip
import com.RooMCutNext.core.model.MediaAsset
import com.RooMCutNext.core.model.MediaType
import com.RooMCutNext.core.model.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6.0: agent 会话编排测试 —— 用假传输层脚本化模型回复，验证
 * 工具循环 / 自动应用（v1.6.4 起不再有提案确认）/ 设置门禁 /
 * 有界循环安全网 / 通俗动作文案。
 */
class AiChatControllerTest {

    private class FakeTransport(val script: MutableList<LlmReply>) : LlmTransport {
        val requests = mutableListOf<List<WireMessage>>()
        var failWith: LlmException? = null
        /** v1.6.6: 只失败一次（降级重试测试用），首次抛出后自动清除。 */
        var failFirstWith: LlmException? = null
        /** v1.6.9: 依次抛出的错误队列（两级降级测试：视频拒→帧拒→纯文字）。 */
        val failQueue = ArrayDeque<LlmException>()
        override suspend fun chatCompletion(
            baseUrl: String,
            apiKey: String,
            model: String,
            messages: List<WireMessage>,
            tools: kotlinx.serialization.json.JsonArray,
        ): LlmReply {
            requests.add(messages)
            failQueue.removeFirstOrNull()?.let { throw it }
            failFirstWith?.let { val e = it; failFirstWith = null; throw e }
            failWith?.let { throw it }
            return if (script.isEmpty()) LlmReply(null, emptyList()) else script.removeAt(0)
        }
    }

    private class MemSettings(var settings: AiSettings) : AiSettingsStore {
        override fun load(): AiSettings = settings
        override fun save(settings: AiSettings) { this.settings = settings }
    }

    private fun asset(id: String) = MediaAsset(
        id = id, fileName = "$id.mp4", displayName = "Asset $id", type = MediaType.VIDEO,
        durationUs = 10_000_000L, width = 1920, height = 1080, fps = 30f, hasAudio = true,
    )

    private fun twoClipProject() = Project(
        id = "p", name = "P",
        assets = listOf(asset("a1"), asset("a2")),
        clips = listOf(Clip(id = "c1", assetId = "a1", sourceInUs = 0, sourceOutUs = 10_000_000L),
            Clip(id = "c2", assetId = "a2", sourceInUs = 0, sourceOutUs = 10_000_000L)),
    )

    private fun call(id: String, name: String, args: JsonObject) = LlmToolCall(id, name, args)

    private fun splitCall() = call(
        "1", "split_clip",
        buildJsonObject { put("clip_id", "c1"); put("timeline_sec", 5.0) },
    )

    private fun seekCall() = call("1", "seek", buildJsonObject { put("sec", 2.0) })

    private fun controller(
        transport: FakeTransport,
        settings: AiSettings = AiSettings(apiKey = "k"),
        applied: MutableList<Triple<Project, String, Long?>> = mutableListOf(),
        frames: List<AiClipFrame> = emptyList(),
        customSkills: List<EditSkill> = emptyList(),
        videos: List<AiClipVideo> = emptyList(),
        videoProviderCalls: MutableList<Int> = mutableListOf(),
    ): AiChatController {
        val ctxProject = twoClipProject()
        return AiChatController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            transport = transport,
            contextProvider = {
                AiProjectContext(
                    project = ctxProject,
                    playheadUs = 0L,
                    fonts = listOf("system" to "System"),
                    luts = listOf("builtin:warm" to "Warm"),
                )
            },
            applyDraft = { p, label, seek -> applied.add(Triple(p, label, seek)) },
            settingsStore = MemSettings(settings),
            framesProvider = { frames },
            customSkillsProvider = { customSkills },
            videosProvider = {
                videoProviderCalls.add(1)
                videos
            },
        )
    }

    // ------------------------------------------------------------------

    /**
     * v1.6.7 回归：创建控制器即装载已保存配置 —— 此前面板首次打开时
     * state.settings 是空默认值（只在 send/openSettings 才读盘），导致
     * "请先配置模型"引导条对已配置用户每次都出现（真机反馈"每次都要
     * 确认一遍配置"）。编辑器 VM 退出即销毁、进编辑器即重建控制器，
     * 因此 init 读盘 = 每次进编辑器都拿到最新配置。
     */
    @Test
    fun initLoadsSavedSettingsSoConfiguredUsersSeeReadyState() {
        val transport = FakeTransport(mutableListOf())
        val saved = AiSettings(apiKey = "sk-abc", model = "glm-4.6")
        val c = controller(transport, settings = saved)

        assertTrue(c.state.value.settings.ready)
        assertEquals("sk-abc", c.state.value.settings.apiKey)
        assertEquals("glm-4.6", c.state.value.settings.effectiveModel)
    }

    /** v1.6.7 回归：未配置时 init 保持未就绪状态（引导条只该给真没配置的人看）。 */
    @Test
    fun initKeepsUnconfiguredDefaultsSoOnboardingBarStillShows() {
        val transport = FakeTransport(mutableListOf())
        val c = controller(transport, settings = AiSettings())

        assertFalse(c.state.value.settings.ready)
        assertEquals("", c.state.value.settings.apiKey)
    }

    /**
     * v1.6.8 回归：自定义技能随 system prompt 注入 —— 导入的新招式
     * 下一句话即可被模型按名调用（provider 每次 send 现取，无需重建
     * 控制器）。
     */
    @Test
    fun customSkillsInjectedIntoSystemPrompt() {
        val transport = FakeTransport(mutableListOf())
        val mySkill = EditSkill(
            id = "my_opener", nameZh = "专属开场", nameEn = "my opener",
            aliasesZh = listOf("开场钩子"), category = SkillCategory.CUSTOM,
            oneLinerZh = "自定义开场招式", oneLinerEn = "A custom opener",
            steps = listOf("get_draft_overview first.", "seek to 0."),
        )
        val c = controller(transport, customSkills = listOf(mySkill))

        c.send("专属开场")

        assertEquals(1, transport.requests.size)
        val system = transport.requests.single().first().content.orEmpty()
        assertTrue("system prompt should carry the custom skill", system.contains("专属开场"))
        assertTrue(system.contains("my opener"))
        assertTrue(system.contains("imported by the user"))
    }

    @Test
    fun fullFlowExecutesToolsAndAutoApplies() {
        val transport = FakeTransport(
            mutableListOf(
                LlmReply(null, listOf(splitCall())),
                LlmReply("剪好了", emptyList()),
            ),
        )
        val applied = mutableListOf<Triple<Project, String, Long?>>()
        val c = controller(transport, applied = applied)

        c.send("把第一段剪成两半")

        // 两轮请求：第一轮带 tool_calls，第二轮只有文本。
        assertEquals(2, transport.requests.size)
        val entries = c.state.value.entries
        assertTrue(entries[0] is AiChatEntry.User)
        val assistant = entries.filterIsInstance<AiChatEntry.Assistant>().single()
        assertEquals("剪好了", assistant.text)

        // v1.6.4: 自动应用 —— 单次提交、项目已分割、撤销标签带 "AI"，
        // 无需用户确认。
        assertEquals(1, applied.size)
        assertEquals(3, applied.single().first.clips.size)
        assertTrue(applied.single().second.contains("AI"))
        assertFalse(c.state.value.busy)
        // 末尾是“已改好”提示。
        val note = entries.last() as AiChatEntry.Note
        assertFalse(note.isError)
        assertTrue(note.text.contains("已改好"))
    }

    @Test
    fun toolEventsUseFriendlyLabelsAndHideInternalCalls() {
        val transport = FakeTransport(
            mutableListOf(
                // 先读概览（内部操作，不得上屏），再分割，最后收尾。
                LlmReply(null, listOf(
                    call("0", "get_draft_overview", buildJsonObject { }),
                    splitCall(),
                )),
                LlmReply("好了", emptyList()),
            ),
        )
        val c = controller(transport)

        c.send("剪一刀")

        val toolEvent = c.state.value.entries.filterIsInstance<AiChatEntry.ToolEvent>().single()
        // 只有 split 上屏；get_draft_overview 被滤掉。
        assertEquals(1, toolEvent.lines.size)
        assertTrue(toolEvent.lines.single().ok)
        // 通俗文案：不含工具名/参数名，含位置秒数。
        val label = toolEvent.lines.single().label
        assertFalse(label.contains("split_clip"))
        assertFalse(label.contains("clip_id"))
        assertTrue(label.contains("5"))
    }

    @Test
    fun missingSettingsGatesRequest() {
        val transport = FakeTransport(mutableListOf())
        val c = controller(transport, settings = AiSettings(apiKey = ""))

        c.send("帮我把节奏加快")

        assertEquals(0, transport.requests.size)
        assertTrue(c.state.value.settingsOpen)
        assertTrue(c.state.value.entries.last() is AiChatEntry.Note)
        assertFalse(c.state.value.busy)
    }

    @Test
    fun transportErrorSurfacesAsErrorNote() {
        val transport = FakeTransport(mutableListOf())
        transport.failWith = LlmException("HTTP 401: invalid key")
        val c = controller(transport)

        c.send("做个转场")

        assertEquals(1, transport.requests.size)
        val note = c.state.value.entries.last() as AiChatEntry.Note
        assertTrue(note.isError)
        assertTrue(note.text.contains("HTTP 401"))
        assertFalse(c.state.value.busy)
    }

    @Test
    fun boundedLoopStopsAtRoundLimit() {
        // 模型永远只回分割调用 → 8 轮上限必须截停，且已做的修改自动应用。
        val transport = FakeTransport(
            MutableList(20) { LlmReply(null, listOf(splitCall())) },
        )
        val applied = mutableListOf<Triple<Project, String, Long?>>()
        val c = controller(transport, applied = applied)

        c.send("连发工具")

        assertEquals(AiChatController.MAX_ROUNDS, transport.requests.size)
        assertEquals(
            AiChatController.MAX_ROUNDS,
            c.state.value.entries.count { it is AiChatEntry.ToolEvent },
        )
        assertFalse(c.state.value.busy)
        // 截停后自动应用了已完成的修改（一次提交）。
        assertEquals(1, applied.size)
    }

    @Test
    fun noChangesMeansNoApply() {
        // seek-only：项目未变 → 不应用。
        val transport = FakeTransport(
            mutableListOf(
                LlmReply(null, listOf(seekCall())),
                LlmReply("看了一眼", emptyList()),
            ),
        )
        val applied = mutableListOf<Triple<Project, String, Long?>>()
        val c = controller(transport, applied = applied)

        c.send("看一眼")

        assertEquals(0, applied.size)
        // seek 是内部操作：消息流里不应出现工具事件行。
        assertEquals(0, c.state.value.entries.count { it is AiChatEntry.ToolEvent })
    }

    @Test
    fun toolArgumentsCarriedOnWireAsToolRole() {
        val transport = FakeTransport(
            mutableListOf(
                LlmReply(null, listOf(splitCall())),
                LlmReply("ok", emptyList()),
            ),
        )
        val c = controller(transport)

        c.send("split")

        // 第二轮请求的倒数第二条 = assistant(tool_calls)，最后一条 = tool 结果。
        val second = transport.requests[1]
        val toolResult = second.last()
        assertEquals("tool", toolResult.role)
        assertEquals("1", toolResult.toolCallId)
        assertEquals("split_clip", toolResult.name)
        assertTrue(toolResult.content!!.contains("\"ok\":true"))
        // system prompt 含项目概览。
        assertTrue((transport.requests[0].first() as WireMessage).content!!.contains("\"c1\""))
    }

    // ------------------------------------------------------------------
    // v1.6.6 画面感知（多模态模型看帧判断内容；纯文本模型退回先问模式）
    // ------------------------------------------------------------------

    private fun oneFrame() = listOf(
        AiClipFrame(clipId = "c1", assetName = "Asset a1", midSec = 5.0, b64Jpeg = "QUJD"),
    )

    @Test
    fun visionFramesSentForMultimodalModel() {
        val transport = FakeTransport(mutableListOf(LlmReply("看到了", emptyList())))
        val c = controller(
            transport,
            settings = AiSettings(apiKey = "k", model = "gpt-4o"),
            frames = oneFrame(),
        )

        c.send("高潮前置")

        // auto 模式 + 多模态模型名 → system 后紧跟一条带图的 user 消息。
        assertEquals(1, transport.requests.size)
        val first = transport.requests[0]
        assertEquals("system", first[0].role)
        val vision = first[1]
        assertEquals("user", vision.role)
        assertEquals(1, vision.images.size)
        assertEquals("QUJD", vision.images.single())
        assertTrue(vision.content!!.contains("c1"))
        assertTrue(vision.content!!.contains("Asset a1"))
    }

    @Test
    fun textModelSkipsFramesInAutoMode() {
        val transport = FakeTransport(mutableListOf(LlmReply("没图", emptyList())))
        val c = controller(
            transport,
            settings = AiSettings(apiKey = "k", model = "deepseek-chat"),
            frames = oneFrame(),
        )

        c.send("高潮前置")

        // 纯文本模型：有帧也不发（启发式未命中）。
        val first = transport.requests[0]
        assertTrue(first.none { it.images.isNotEmpty() })
        assertEquals("user", first[1].role) // system 后直接是用户消息
    }

    @Test
    fun visionOnForcesFramesEvenForTextModelName() {
        val transport = FakeTransport(mutableListOf(LlmReply("好", emptyList())))
        val c = controller(
            transport,
            settings = AiSettings(apiKey = "k", model = "deepseek-chat", visionMode = AiSettings.VISION_ON),
            frames = oneFrame(),
        )

        c.send("看看")

        // 始终发送：无视启发式（用户知道自己接的是什么网关）。
        assertTrue(transport.requests[0].any { it.images.isNotEmpty() })
    }

    @Test
    fun visionOffNeverSendsFrames() {
        val transport = FakeTransport(mutableListOf(LlmReply("好", emptyList())))
        val c = controller(
            transport,
            settings = AiSettings(apiKey = "k", model = "gpt-4o", visionMode = AiSettings.VISION_OFF),
            frames = oneFrame(),
        )

        c.send("看看")

        assertTrue(transport.requests[0].none { it.images.isNotEmpty() })
    }

    @Test
    fun visionRejectionFallsBackToTextOnce() {
        // 第一次请求被拒（模型不收图）→ 降级纯文字重试 → 成功。
        val transport = FakeTransport(mutableListOf(LlmReply("纯文字模式完成了", emptyList())))
        transport.failFirstWith = LlmException("HTTP 400: image content is not supported by this model")
        val c = controller(
            transport,
            settings = AiSettings(apiKey = "k", model = "gpt-4o"),
            frames = oneFrame(),
        )

        c.send("高潮前置")

        // 两次请求：第一次带图失败，第二次纯文字成功。
        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests[0].any { it.images.isNotEmpty() })
        assertTrue(transport.requests[1].none { it.images.isNotEmpty() })
        // 降级提示出现在消息流里。
        val notes = c.state.value.entries.filterIsInstance<AiChatEntry.Note>().map { it.text }
        assertTrue(notes.any { it.contains("看不了图") })
        assertTrue(c.state.value.entries.filterIsInstance<AiChatEntry.Assistant>().single().text.contains("纯文字"))
    }

    @Test
    fun nonVisionErrorDoesNotTriggerFallback() {
        // 与图无关的错误（如 401）→ 不降级，直接报错。
        val transport = FakeTransport(mutableListOf())
        transport.failWith = LlmException("HTTP 401: invalid key")
        val c = controller(
            transport,
            settings = AiSettings(apiKey = "k", model = "gpt-4o"),
            frames = oneFrame(),
        )

        c.send("做点什么")

        assertEquals(1, transport.requests.size)
        val note = c.state.value.entries.last() as AiChatEntry.Note
        assertTrue(note.isError)
        assertTrue(note.text.contains("HTTP 401"))
    }

    // ------------------------------------------------------------------
    // v1.6.9 整段视频（原生视频模型看整段素材；被拒自动逐级降级）
    // ------------------------------------------------------------------

    private fun oneVideo(clipId: String = "c1") = AiClipVideo(
        clipId = clipId,
        assetName = "Asset a1",
        startSec = 0.0,
        sentSec = 10.0,
        windowSec = 10.0,
        b64Mp4 = "QUJD",
        style = VideoPartStyle.INPUT_VIDEO,
    )

    private fun twoFrames() = listOf(
        AiClipFrame(clipId = "c1", assetName = "Asset a1", midSec = 5.0, b64Jpeg = "QUJD"),
        AiClipFrame(clipId = "c2", assetName = "Asset a2", midSec = 5.0, b64Jpeg = "WFla"),
    )

    @Test
    fun videoSentForVideoNativeModelInAutoMode() {
        val transport = FakeTransport(mutableListOf(LlmReply("看完了", emptyList())))
        val calls = mutableListOf<Int>()
        val c = controller(
            transport,
            settings = AiSettings(
                apiKey = "k",
                model = "qwen3-vl-plus",
                visionStyle = AiSettings.VISION_STYLE_VIDEO,
            ),
            frames = oneFrame(),
            videos = listOf(oneVideo()),
            videoProviderCalls = calls,
        )

        c.send("帮我看看整段素材")

        // auto + 原生视频模型名 + 整段视频档 → videosProvider 被调用。
        assertEquals(1, calls.size)
        assertEquals(1, transport.requests.size)
        val vision = transport.requests[0][1]
        assertEquals("user", vision.role)
        assertEquals(1, vision.videos.size)
        assertEquals("QUJD", vision.videos.single().b64Mp4)
        assertEquals(VideoPartStyle.INPUT_VIDEO, vision.videos.single().style)
        // c1 已被视频覆盖 → 不再重复发帧。
        assertTrue(vision.images.isEmpty())
        assertTrue(vision.content!!.contains("video c1"))
    }

    @Test
    fun mixedVideoAndFramesInOneVisionMessage() {
        val transport = FakeTransport(mutableListOf(LlmReply("都看到了", emptyList())))
        val c = controller(
            transport,
            settings = AiSettings(
                apiKey = "k",
                model = "qwen3-vl-plus",
                visionStyle = AiSettings.VISION_STYLE_VIDEO,
            ),
            frames = twoFrames(),
            videos = listOf(oneVideo("c1")),
        )

        c.send("看素材")

        val vision = transport.requests[0][1]
        // 一条消息里：c1 整段视频 + c2 中间帧。
        assertEquals(1, vision.videos.size)
        assertEquals(1, vision.images.size)
        assertEquals("WFla", vision.images.single())
        assertTrue(vision.content!!.contains("c2"))
    }

    @Test
    fun videoStyleFramesNeverCallsVideosProvider() {
        val transport = FakeTransport(mutableListOf(LlmReply("好", emptyList())))
        val calls = mutableListOf<Int>()
        val c = controller(
            transport,
            settings = AiSettings(apiKey = "k", model = "qwen3-vl-plus"), // 默认关键帧档
            frames = oneFrame(),
            videos = listOf(oneVideo()),
            videoProviderCalls = calls,
        )

        c.send("看看")

        // 关键帧档：模型再像视频模型也不走整段直传。
        assertTrue(calls.isEmpty())
        val vision = transport.requests[0][1]
        assertTrue(vision.videos.isEmpty())
        assertEquals(1, vision.images.size)
    }

    @Test
    fun nonVideoNativeAutoWithVideoStyleFallsBackToFrames() {
        val transport = FakeTransport(mutableListOf(LlmReply("帧看完了", emptyList())))
        val calls = mutableListOf<Int>()
        val c = controller(
            transport,
            settings = AiSettings(
                apiKey = "k",
                model = "gpt-4o", // 多模态但非原生视频
                visionStyle = AiSettings.VISION_STYLE_VIDEO,
            ),
            frames = oneFrame(),
            videos = listOf(oneVideo()),
            videoProviderCalls = calls,
        )

        c.send("看看")

        // auto 启发式挡住整段视频 → 回落 v1.6.6 帧路径。
        assertTrue(calls.isEmpty())
        val vision = transport.requests[0][1]
        assertTrue(vision.videos.isEmpty())
        assertEquals(1, vision.images.size)
    }

    @Test
    fun videoRejectedFallsBackToFrames() {
        // 第一次整段视频被拒 → 关键帧重试成功。
        val transport = FakeTransport(mutableListOf(LlmReply("关键帧看完了", emptyList())))
        transport.failFirstWith = LlmException("HTTP 400: input_video is not supported for this model")
        val c = controller(
            transport,
            settings = AiSettings(
                apiKey = "k",
                model = "qwen3-vl-plus",
                visionStyle = AiSettings.VISION_STYLE_VIDEO,
            ),
            frames = oneFrame(),
            videos = listOf(oneVideo()),
        )

        c.send("看看")

        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests[0][1].videos.isNotEmpty())
        assertTrue(transport.requests[1][1].videos.isEmpty())
        // 降级后用全量帧（video 覆盖的 c1 也回到帧）。
        assertEquals(1, transport.requests[1][1].images.size)
        val notes = c.state.value.entries.filterIsInstance<AiChatEntry.Note>().map { it.text }
        assertTrue(notes.any { it.contains("收不了整段视频") })
    }

    @Test
    fun videoAndFramesBothRejectedFallsBackToText() {
        // 两级降级：视频拒 → 帧拒 → 纯文字成功。
        val transport = FakeTransport(mutableListOf(LlmReply("纯文字完成了", emptyList())))
        transport.failQueue.addAll(
            listOf(
                LlmException("HTTP 400: input_video not supported"),
                LlmException("HTTP 400: image content type is not supported"),
            ),
        )
        val c = controller(
            transport,
            settings = AiSettings(
                apiKey = "k",
                model = "qwen3-vl-plus",
                visionStyle = AiSettings.VISION_STYLE_VIDEO,
            ),
            frames = oneFrame(),
            videos = listOf(oneVideo()),
        )

        c.send("看看")

        assertEquals(3, transport.requests.size)
        assertTrue(transport.requests[0][1].videos.isNotEmpty())
        assertTrue(transport.requests[1][1].images.isNotEmpty())
        assertTrue(
            transport.requests[2].none { it.images.isNotEmpty() || it.videos.isNotEmpty() },
        )
        val notes = c.state.value.entries.filterIsInstance<AiChatEntry.Note>().map { it.text }
        assertTrue(notes.any { it.contains("收不了整段视频") })
        assertTrue(notes.any { it.contains("看不了图") })
    }
}
