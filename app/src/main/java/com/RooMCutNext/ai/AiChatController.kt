package com.RooMCutNext.ai

import com.RooMCutNext.core.model.Project
import com.RooMCutNext.core.util.IdGen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * AI 剪辑（v1.6.0）— 会话编排器（OpenChatCut 模式的安卓复刻）。
 *
 * 对话循环：用户一句话 → system prompt（含项目概览）→ 模型 tool_calls →
 * [EditTools] 在 DRAFT 上执行 → 结果回传模型 → 循环，直到模型给出总结。
 * 结束后草稿以「提案」形式呈现，用户点应用才真正写入项目——并且以
 * 单条撤销记录提交（SnapshotHistory），整组 AI 修改一步可撤销。
 *
 * 有界循环是硬安全网：最多 [MAX_ROUNDS] 轮、[MAX_TOTAL_CALLS] 次工具调用，
 * 防止模型陷入自我循环烧 token。
 */

/** 每轮对话开始时由宿主（EditorViewModel）提供的现场快照。 */
data class AiProjectContext(
    val project: Project,
    val playheadUs: Long,
    /** (id, 显示名)，来自 FontRegistry。 */
    val fonts: List<Pair<String, String>>,
    /** (id, 显示名)，来自 LutRegistry。 */
    val luts: List<Pair<String, String>>,
    /** 结构版本号，用于检测对话期间的手动编辑。 */
    val structureVersion: Long,
)

// ---------------------------------------------------------------------
// UI 状态
// ---------------------------------------------------------------------

sealed interface AiChatEntry {
    data class User(val text: String) : AiChatEntry
    data class Assistant(val text: String) : AiChatEntry
    /** 一轮模型工具调用（成组显示）。 */
    data class ToolEvent(val lines: List<ToolEventLine>) : AiChatEntry
    /** 系统性提示：已应用/已放弃/错误/上限说明。 */
    data class Note(val text: String, val isError: Boolean = false) : AiChatEntry
}

data class ToolEventLine(val label: String, val ok: Boolean)

data class AiProposalUi(
    val changeCount: Int,
    val journal: List<String>,
    val summary: String,
    val seekSec: Double?,
    val baseVersion: Long,
    /** 对话期间用户手动改过时间线（应用会覆盖这些改动）。 */
    val stale: Boolean,
)

data class AiChatUiState(
    val entries: List<AiChatEntry> = emptyList(),
    val busy: Boolean = false,
    val busyTool: String? = null,
    val proposal: AiProposalUi? = null,
    val settings: AiSettings = AiSettings(),
    val settingsOpen: Boolean = false,
)

// ---------------------------------------------------------------------
// 控制器
// ---------------------------------------------------------------------

class AiChatController(
    private val scope: CoroutineScope,
    private val transport: LlmTransport,
    private val contextProvider: () -> AiProjectContext,
    /** (draft, undoLabel, seekUs) — 宿主负责以单条撤销记录提交。 */
    private val applyDraft: (Project, String, Long?) -> Unit,
    private val settingsStore: AiSettingsStore,
    private val projectChangedSince: (Long) -> Boolean,
) {

    private val _state = MutableStateFlow(AiChatUiState())
    val state: StateFlow<AiChatUiState> = _state.asStateFlow()

    private var draft: EditTools.Draft? = null
    private var lastAssistantText: String = ""

    // -------------------------------------------------------------
    // 公共 API（面板调用）
    // -------------------------------------------------------------

    fun send(textRaw: String) {
        val text = textRaw.trim()
        if (text.isEmpty() || _state.value.busy) return
        val settings = settingsStore.load()
        if (!settings.ready) {
            appendEntry(AiChatEntry.Note(NEED_CONFIG_HINT, isError = true))
            _state.update { it.copy(settingsOpen = true, settings = settings) }
            return
        }
        _state.update { it.copy(settings = settings, settingsOpen = false) }
        appendEntry(AiChatEntry.User(text))
        _state.update { it.copy(busy = true, busyTool = null, proposal = null) }

        scope.launch { runTurn(text, settings) }
    }

    fun applyProposal() {
        val d = draft ?: return
        if (!d.changed) {
            discardProposal()
            return
        }
        val label = "AI 剪辑（${d.changeCount} 项操作）"
        applyDraft(d.project, label, d.seekUs)
        draft = null
        _state.update { it.copy(proposal = null) }
        appendEntry(AiChatEntry.Note(APPLIED_NOTE))
    }

    fun discardProposal() {
        if (draft == null && _state.value.proposal == null) return
        draft = null
        _state.update { it.copy(proposal = null) }
        appendEntry(AiChatEntry.Note(DISCARDED_NOTE))
    }

    fun openSettings(open: Boolean) {
        _state.update { it.copy(settingsOpen = open, settings = settingsStore.load()) }
    }

    fun updateSettings(settings: AiSettings) {
        settingsStore.save(settings)
        _state.update { it.copy(settings = settings, settingsOpen = false) }
    }

    // -------------------------------------------------------------
    // 对话主循环
    // -------------------------------------------------------------

    private suspend fun runTurn(userText: String, settings: AiSettings) {
        val ctx = contextProvider()
        val baseVersion = ctx.structureVersion
        val tools = EditTools(fonts = ctx.fonts, luts = ctx.luts, idFactory = { IdGen.new("ai") })
        val d = EditTools.Draft(ctx.project)
        draft = d

        // 多轮记忆：只保留历史里的 user / assistant 文本（工具报文巨大且已失效）。
        val history = _state.value.entries.mapNotNull { e ->
            when (e) {
                is AiChatEntry.User -> WireMessage("user", e.text)
                is AiChatEntry.Assistant -> if (e.text.isNotBlank()) WireMessage("assistant", e.text) else null
                else -> null
            }
        }

        val wire = mutableListOf(WireMessage("system", tools.systemPrompt(d, ctx.playheadUs)))
        wire.addAll(history)
        wire.add(WireMessage("user", userText))

        try {
            var rounds = 0
            var totalCalls = 0
            var hitRoundLimit = false
            loop@ while (rounds < MAX_ROUNDS) {
                rounds++
                val reply = transport.chatCompletion(
                    baseUrl = settings.effectiveBaseUrl,
                    apiKey = settings.apiKey,
                    model = settings.effectiveModel,
                    messages = wire.toList(),
                    tools = tools.toolSchemas(),
                )
                if (reply.toolCalls.isEmpty()) {
                    val summaryText = reply.content ?: ""
                    if (summaryText.isNotBlank()) {
                        appendEntry(AiChatEntry.Assistant(summaryText))
                    }
                    lastAssistantText = summaryText
                    break@loop
                }
                wire.add(WireMessage("assistant", reply.content, toolCalls = reply.toolCalls))
                val lines = mutableListOf<ToolEventLine>()
                for (call in reply.toolCalls.take(MAX_CALLS_PER_ROUND)) {
                    totalCalls++
                    val result = runCatching { tools.execute(d, call.name, call.arguments) }
                        .getOrElse { e ->
                            EditTools.ToolResult(
                                buildJsonObject {
                                    put("ok", false)
                                    put("error", e.javaClass.simpleName + ": " + (e.message?.take(80) ?: ""))
                                },
                                false,
                            )
                        }
                    lines.add(ToolEventLine(label = callLabel(call), ok = result.ok))
                    _state.update { it.copy(busyTool = call.name) }
                    wire.add(
                        WireMessage(
                            role = "tool",
                            content = result.json.toString(),
                            toolCallId = call.id,
                            name = call.name,
                        ),
                    )
                }
                appendEntry(AiChatEntry.ToolEvent(lines))
                if (totalCalls >= MAX_TOTAL_CALLS) {
                    wire.add(
                        WireMessage(
                            "user",
                            "[system] Tool call budget reached ($MAX_TOTAL_CALLS). " +
                                "Stop calling tools now and summarize what you changed so far.",
                        ),
                    )
                }
                if (rounds == MAX_ROUNDS) hitRoundLimit = true
            }
            if (hitRoundLimit) appendEntry(AiChatEntry.Note(ROUND_LIMIT_NOTE))

            if (d.changed) {
                val stale = projectChangedSince(baseVersion)
                _state.update {
                    it.copy(
                        proposal = AiProposalUi(
                            changeCount = d.changeCount,
                            journal = d.journal.toList(),
                            summary = lastAssistantText,
                            seekSec = d.seekUs?.let { u -> (u / 1000.0) / 1000.0 },
                            baseVersion = baseVersion,
                            stale = stale,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            val message = when (e) {
                is LlmException -> e.message ?: "请求失败"
                else -> e.javaClass.simpleName + ": " + (e.message?.take(120) ?: "")
            }
            appendEntry(AiChatEntry.Note("✗ $message", isError = true))
        } finally {
            _state.update { it.copy(busy = false, busyTool = null) }
        }
    }

    private fun appendEntry(entry: AiChatEntry) {
        _state.update { it.copy(entries = (it.entries + entry).takeLast(MAX_ENTRIES)) }
    }

    private fun callLabel(call: LlmToolCall): String {
        val args = call.arguments.entries.take(3).joinToString(" ") { (k, v) ->
            val shown = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
            "$k=${shown.take(18)}"
        }
        return if (args.isEmpty()) call.name else "${call.name}($args)"
    }

    companion object {
        const val MAX_ROUNDS = 8
        const val MAX_CALLS_PER_ROUND = 5
        const val MAX_TOTAL_CALLS = 24
        const val MAX_ENTRIES = 60

        const val NEED_CONFIG_HINT = "请先在设置中配置模型 API Key（密钥只保存在本机）"
        const val APPLIED_NOTE = "已应用 AI 修改（可在时间线撤销）"
        const val DISCARDED_NOTE = "已放弃 AI 草稿"
        const val ROUND_LIMIT_NOTE = "已达到本轮回合上限，可发送新消息继续"
    }
}
