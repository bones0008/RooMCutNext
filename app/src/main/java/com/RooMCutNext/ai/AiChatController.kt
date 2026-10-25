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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * AI 剪辑（v1.6.0）— 会话编排器（OpenChatCut 模式的安卓复刻）。
 *
 * v1.6.4（真机反馈“返回信息太高级、为什么要确认”）：
 *  - **直接做**：一轮结束后草稿立即自动应用（仍以单条撤销记录提交，
 *    不满意点撤销一键还原）——「提案卡 + 应用/放弃」整层确认流程删除；
 *  - **说人话**：工具调用不再以 `split_clip(clip_id=c1 …)` 等技术形态
 *    出现在对话里，改为通俗动作描述（“在 5 秒处剪了一刀”）；内部性
 *    操作（get_draft_overview / seek）不再出现在消息流里；失败的调用
 *    附一句短的失败原因。
 *
 * 对话循环：用户一句话 → system prompt（含项目概览）→ 模型 tool_calls →
 * [EditTools] 在 DRAFT 上执行 → 结果回传模型 → 循环，直到模型给出总结。
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
)

// ---------------------------------------------------------------------
// UI 状态
// ---------------------------------------------------------------------

sealed interface AiChatEntry {
    data class User(val text: String) : AiChatEntry
    data class Assistant(val text: String) : AiChatEntry
    /** 一轮模型工具调用（成组显示，v1.6.4 起为通俗动作描述）。 */
    data class ToolEvent(val lines: List<ToolEventLine>) : AiChatEntry
    /** 系统性提示：已应用/错误/上限说明。 */
    data class Note(val text: String, val isError: Boolean = false) : AiChatEntry
}

data class ToolEventLine(val label: String, val ok: Boolean)

data class AiChatUiState(
    val entries: List<AiChatEntry> = emptyList(),
    val busy: Boolean = false,
    val busyTool: String? = null,
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
) {

    private val _state = MutableStateFlow(AiChatUiState())
    val state: StateFlow<AiChatUiState> = _state.asStateFlow()

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
        _state.update { it.copy(busy = true, busyTool = null) }

        scope.launch { runTurn(text, settings) }
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
        val tools = EditTools(fonts = ctx.fonts, luts = ctx.luts, idFactory = { IdGen.new("ai") })
        val d = EditTools.Draft(ctx.project)

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
                    break@loop
                }
                wire.add(WireMessage("assistant", reply.content, toolCalls = reply.toolCalls))
                val lines = mutableListOf<ToolEventLine>()
                for (call in reply.toolCalls.take(MAX_CALLS_PER_ROUND)) {
                    totalCalls++
                    val result = runCatching { tools.execute(d, call.name, call.arguments) }
                        .getOrElse { e ->
                            EditTools.ToolResult(
                                kotlinx.serialization.json.buildJsonObject {
                                    put("ok", false)
                                    put("error", e.javaClass.simpleName + ": " + (e.message?.take(80) ?: ""))
                                },
                                false,
                            )
                        }
                    // v1.6.4: 通俗动作描述；内部操作（读概览/seek）不上屏。
                    val friendly = friendlyAction(call)
                    if (friendly != null) {
                        val label = if (result.ok) friendly
                        else "$friendly：${errorBrief(result.json)}"
                        lines.add(ToolEventLine(label = label, ok = result.ok))
                    }
                    _state.update { it.copy(busyTool = friendly) }
                    wire.add(
                        WireMessage(
                            role = "tool",
                            content = result.json.toString(),
                            toolCallId = call.id,
                            name = call.name,
                        ),
                    )
                }
                if (lines.isNotEmpty()) appendEntry(AiChatEntry.ToolEvent(lines))
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

            // v1.6.4: 直接做 —— 轮次结束即自动应用（单条撤销记录）。
            if (d.changed) {
                applyDraft(d.project, "AI 剪辑（${d.changeCount} 项操作）", d.seekUs)
                appendEntry(AiChatEntry.Note(appliedNote(d.changeCount)))
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

    // -------------------------------------------------------------
    // v1.6.4: 工具调用 → 通俗动作描述
    // -------------------------------------------------------------

    /** null = 内部操作（读概览 / seek），不上屏。 */
    private fun friendlyAction(call: LlmToolCall): String? {
        val args = call.arguments
        val sec = { key: String ->
            args[key]?.let { (it as? JsonPrimitive)?.doubleOrNull }?.let { v ->
                if (v == v.toLong().toDouble()) "${v.toLong()}" else "%.1f".format(v)
            }
        }
        return when (call.name) {
            EditTools.T.OVERVIEW, EditTools.T.SEEK -> null
            EditTools.T.SPLIT -> "在 ${sec("timeline_sec") ?: ""}秒处剪了一刀"
            EditTools.T.DELETE_CLIP -> "删掉了一段素材"
            EditTools.T.TRIM_CLIP -> "修剪了片段长度"
            EditTools.T.MOVE_CLIP -> "调整了片段顺序"
            EditTools.T.SPEED -> {
                val s = args["speed"]?.let { (it as? JsonPrimitive)?.doubleOrNull }
                if (s != null) "速度调到 ${"%.2f".format(s).trimEnd('0').trimEnd('.')} 倍" else "调整了速度"
            }
            EditTools.T.VOLUME -> {
                val m = args["mute"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                if (m == "true") "把片段静音了" else "调整了片段音量"
            }
            EditTools.T.LOOK -> {
                val has = args["lut_id"]?.let { (it as? JsonPrimitive)?.contentOrNull } != null
                if (has) "给片段换了滤镜" else "去掉了片段滤镜"
            }
            EditTools.T.TRANSITION -> "加了一个转场"
            EditTools.T.REMOVE_TRANSITION -> "去掉了转场"
            EditTools.T.ADD_TEXT -> "加了一条字幕"
            EditTools.T.UPDATE_TEXT -> "修改了字幕"
            EditTools.T.DELETE_TEXT -> "删掉了字幕"
            EditTools.T.AUDIO_VOLUME -> {
                val m = args["mute"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                if (m == "true") "把音乐静音了" else "调整了音乐音量"
            }
            EditTools.T.AUDIO_TRIM -> "修剪了音乐"
            EditTools.T.DELETE_AUDIO -> "删掉了音乐"
            EditTools.T.ADD_BLACK -> {
                val s = args["duration_sec"]?.let { (it as? JsonPrimitive)?.doubleOrNull }
                if (s != null) "加了 ${"%.1f".format(s).trimEnd('0').trimEnd('.')} 秒黑场停顿" else "加了一段黑场停顿"
            }
            else -> "做了操作：${call.name}"
        }
    }

    private fun errorBrief(json: JsonObject): String {
        val err = (json["error"] as? JsonPrimitive)?.contentOrNull
            ?: (json["error"] as? JsonPrimitive)?.toString()
        return (err ?: "没成功").take(40)
    }

    private fun appliedNote(changeCount: Int): String =
        "✅ 已改好，共 $changeCount 处（点撤销可一键还原）"

    companion object {
        const val MAX_ROUNDS = 8
        const val MAX_CALLS_PER_ROUND = 5
        const val MAX_TOTAL_CALLS = 24
        const val MAX_ENTRIES = 60

        const val NEED_CONFIG_HINT = "请先在设置中配置模型 API Key（密钥只保存在本机）"
        const val ROUND_LIMIT_NOTE = "这一轮做的修改有点多，先到这儿；不满意点撤销，想继续就再说一句"
    }
}
