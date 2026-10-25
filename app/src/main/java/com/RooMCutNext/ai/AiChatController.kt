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
 * v1.6.6（画面感知，用户问“看不到画面怎么高潮前置/整片精简”）：
 *  - 宿主 [framesProvider] 在 IO 线程为每段提取中间帧小图（base64 JPEG），
 *    随对话以 OpenAI 兼容 vision 格式发给模型 —— 多模态模型
 *    （gpt-4o / GLM-4V / Kimi-vision …）由此真正“看到”时间线内容；
 *  - 纯文本模型（auto 启发式未命中或显式关闭）不发帧，技能配方
 *    要求内容判断时先问用户，不瞎猜（见 [EditSkills.promptBlock]）；
 *  - 意外被拒（模型不支持图）→ 自动降级纯文字重试一次。
 *
 * v1.6.4（直接做 + 说人话）维持不变：轮次结束草稿自动应用（单条撤销
 * 记录）、工具调用以通俗动作描述上屏。
 *
 * 对话循环：用户一句话 → system prompt（含项目概览 + 技能库）→
 * [可选 vision 帧消息] → 模型 tool_calls → [EditTools] 在 DRAFT 上执行 →
 * 结果回传模型 → 循环，直到模型给出总结。
 */

/** v1.6.6: 单帧画面 — 模型可据此判断内容（哪段精彩/场景切换/停顿）。 */
data class AiClipFrame(
    /** 所属片段 id（与概览 JSON 里一致，供模型对应）。 */
    val clipId: String,
    val assetName: String,
    /** 帧取自片段内容窗口中间位置（秒，仅展示用）。 */
    val midSec: Double,
    /** base64 JPEG（无 data: 前缀）。 */
    val b64Jpeg: String,
)

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
    /**
     * v1.6.6 画面感知：宿主在 IO 线程提取当前项目的代表帧（可返回空
     * = 无画面可用，例如全黑场/提取失败）。默认不提供（测试兼容）。
     */
    private val framesProvider: suspend (Project) -> List<AiClipFrame> = { emptyList() },
    /**
     * v1.6.8 自定义技能：宿主提供已导入技能（每轮 send 时现取 ——
     * 导入后无需重建控制器，下一句话即生效）。默认空（测试兼容）。
     */
    private val customSkillsProvider: () -> List<EditSkill> = { emptyList() },
) {

    private val _state = MutableStateFlow(AiChatUiState())
    val state: StateFlow<AiChatUiState> = _state.asStateFlow()

    init {
        // v1.6.7: 创建时即装载已保存配置。此前 state.settings 一直是空默认
        // 值、只在 send/openSettings 时才读盘 —— 面板每次打开都按"未配置"
        // 显示引导条，即使 key 早已存好（真机反馈"每次都要确认一遍配置"）。
        // 编辑器 VM 退出编辑即销毁（AppRoot EditorStoreOwner），因此每次
        // 进编辑器都会重建本控制器 = 每次都读到最新保存的配置。
        _state.update { it.copy(settings = settingsStore.load()) }
    }

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

        // v1.6.6: 画面感知 — 提帧在后台进行，忙碌行给用户提示。
        _state.update { it.copy(busyTool = BUSY_READING_FRAMES) }
        val frames = runCatching { framesProvider(ctx.project) }.getOrDefault(emptyList())

        // 多轮记忆：只保留历史里的 user / assistant 文本（工具报文巨大且已失效）。
        val history = _state.value.entries.mapNotNull { e ->
            when (e) {
                is AiChatEntry.User -> WireMessage("user", e.text)
                is AiChatEntry.Assistant -> if (e.text.isNotBlank()) WireMessage("assistant", e.text) else null
                else -> null
            }
        }

        val wantVision = settings.wantFrames(frames.isNotEmpty())

        try {
            try {
                runLoop(userText, history, tools, ctx, settings, if (wantVision) frames else emptyList())
            } catch (e: LlmException) {
                // v1.6.6: 意外被拒（纯文本模型收到图）→ 降级纯文字重试一次。
                // 仅当带了帧才可能走这里；重建草稿保证零残留。
                if (wantVision && looksLikeVisionRejection(e.message)) {
                    appendEntry(AiChatEntry.Note(VISION_FALLBACK_NOTE))
                    runLoop(userText, history, tools, ctx, settings, emptyList())
                } else {
                    throw e
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

    /**
     * 一轮完整对话循环（含自动应用）。frames 非空时在 system 后插入
     * vision 帧消息（每段中间画面 → base64 JPEG）。
     */
    private suspend fun runLoop(
        userText: String,
        history: List<WireMessage>,
        tools: EditTools,
        ctx: AiProjectContext,
        settings: AiSettings,
        frames: List<AiClipFrame>,
    ) {
        val d = EditTools.Draft(ctx.project)
        val wire = mutableListOf(
            WireMessage("system", tools.systemPrompt(d, ctx.playheadUs, customSkillsProvider())),
        )
        if (frames.isNotEmpty()) wire.add(visionMessage(frames))
        wire.addAll(history)
        wire.add(WireMessage("user", userText))

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
    }

    /** v1.6.6: 帧消息 — 每段中间画面，按时间线顺序，标注 clip id。 */
    private fun visionMessage(frames: List<AiClipFrame>): WireMessage {
        val text = buildString {
            append("Frames of the current timeline - one per clip, taken from the MIDDLE of ")
            append("each clip's content, in timeline order. Clip id and time are given ")
            append("before each frame. Use them to judge CONTENT (which clip is the ")
            append("highlight, scene changes, dead pauses, visual quality); each is a ")
            append("single midpoint frame, so find precise boundaries with your tools ")
            append("(split/trim) and verify with get_draft_overview. If a clip has no ")
            append("frame here, it is a black gap or its frame could not be extracted.")
            frames.forEach { f ->
                append("\n[${f.clipId} \"${f.assetName}\" at ${"%.1f".format(f.midSec)}s]")
            }
        }
        return WireMessage("user", text, images = frames.map { it.b64Jpeg })
    }

    /** 错误文本像“模型不收图”吗（降级重试的触发条件，保守匹配）。 */
    private fun looksLikeVisionRejection(msg: String?): Boolean {
        val m = msg ?: return false
        return Regex("(?i)(image|multimodal|modalit|vision|base64|unsupported content|content type)")
            .containsMatchIn(m)
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
        const val BUSY_READING_FRAMES = "正在读取画面…"
        const val VISION_FALLBACK_NOTE = "这个模型看不了图，我改用纯文字模式再试了一次"
    }
}
