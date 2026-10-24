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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6.0: agent 会话编排测试 —— 用假传输层脚本化模型回复，验证
 * 工具循环 / 提案生命周期 / 设置门禁 / 有界循环安全网。
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
        projectChanged: (Long) -> Boolean = { false },
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
                    structureVersion = 7L,
                )
            },
            applyDraft = { p, label, seek -> applied.add(Triple(p, label, seek)) },
            settingsStore = MemSettings(settings),
            projectChangedSince = projectChanged,
        )
    }

    // ------------------------------------------------------------------

    @Test
    fun fullFlowExecutesToolsAndBuildsProposal() {
        val transport = FakeTransport(
            mutableListOf(
                LlmReply(null, listOf(splitCall())),
                LlmReply("已分割 c1", emptyList()),
            ),
        )
        val applied = mutableListOf<Triple<Project, String, Long?>>()
        val c = controller(transport, applied = applied)

        c.send("把第一段剪成两半")

        // 两轮请求：第一轮带 tool_calls，第二轮只有文本。
        assertEquals(2, transport.requests.size)
        val entries = c.state.value.entries
        assertTrue(entries[0] is AiChatEntry.User)
        val toolEvent = entries.filterIsInstance<AiChatEntry.ToolEvent>().single()
        assertTrue(toolEvent.lines.single().ok)
        val assistant = entries.filterIsInstance<AiChatEntry.Assistant>().single()
        assertEquals("已分割 c1", assistant.text)

        // 提案就绪：1 项修改。
        val proposal = c.state.value.proposal
        assertNotNull(proposal)
        assertEquals(1, proposal!!.changeCount)
        assertFalse(c.state.value.busy)

        // 应用：单次提交、项目已分割、撤销标签带 "AI"。
        c.applyProposal()
        assertEquals(1, applied.size)
        assertEquals(3, applied.single().first.clips.size)
        assertTrue(applied.single().second.contains("AI"))
        assertNull(c.state.value.proposal)
        assertTrue(c.state.value.entries.last() is AiChatEntry.Note)
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
        // 模型永远只回工具调用 → 8 轮上限必须截停。
        val transport = FakeTransport(
            MutableList(20) { LlmReply(null, listOf(seekCall())) },
        )
        val c = controller(transport)

        c.send("连发工具")

        assertEquals(AiChatController.MAX_ROUNDS, transport.requests.size)
        assertEquals(
            AiChatController.MAX_ROUNDS,
            c.state.value.entries.count { it is AiChatEntry.ToolEvent },
        )
        assertFalse(c.state.value.busy)
        // seek-only：项目未变 → 无提案。
        assertNull(c.state.value.proposal)
    }

    @Test
    fun proposalFlagsStaleWhenTimelineChangedDuringChat() {
        val transport = FakeTransport(
            mutableListOf(
                LlmReply(null, listOf(splitCall())),
                LlmReply("done", emptyList()),
            ),
        )
        val c = controller(transport, projectChanged = { v -> true })

        c.send("split")
        assertTrue(c.state.value.proposal!!.stale)
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
