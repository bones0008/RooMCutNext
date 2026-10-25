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
        override suspend fun chatCompletion(
            baseUrl: String,
            apiKey: String,
            model: String,
            messages: List<WireMessage>,
            tools: kotlinx.serialization.json.JsonArray,
        ): LlmReply {
            requests.add(messages)
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
        )
    }

    // ------------------------------------------------------------------

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
}
