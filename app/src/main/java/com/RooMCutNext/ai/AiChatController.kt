package com.RooMCutNext.ai

import com.RooMCutNext.core.model.MediaType
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
 * v1.6.9（整段视频，用户问“我的模型支持视频分析，怎么像电脑上那样
 * 直接把素材给模型看”）：
 *  - 设置切到“整段视频”后，宿主 [videosProvider] 把每个视频片段的
 *    内容窗口压成 480p 小代理（Media3 Transformer），整段 base64 直传
 *    —— 原生视频模型（Qwen-VL / GLM-4V-Plus / MiniMax-VL / 自建网关）
 *    能看到完整运动与节奏，对齐电脑 web/编程 Agent 附文件的体验；
 *  - 视频部件方言可切（input_video=Qwen 系 / video_url=GLM·MiniMax 系）；
 *  - 未被视频覆盖的段（图片/超预算/失败）仍用中间帧补齐；被拒时
 *    自动降级：整段视频 → 关键帧 → 纯文字。
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

/**
 * v1.6.9: 整段视频代理 — 片段内容窗口压成 480p 小视频直传给原生
 * 视频理解模型（对齐电脑 web/编程 Agent 里“附文件”的体验）。
 * 模型能看到完整运动/节奏，而不只是一张中间帧。
 */
data class AiClipVideo(
    val clipId: String,
    val assetName: String,
    /** 片段内容窗口起点（秒，仅标注用）。 */
    val startSec: Double,
    /** 实际发送的时长（秒）。 */
    val sentSec: Double,
    /** 内容窗口全长（秒）—— sentSec < windowSec 表示只附了开头一段。 */
    val windowSec: Double,
    /** base64 MP4（无 data: 前缀，已压小）。 */
    val b64Mp4: String,
    val style: VideoPartStyle,
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
    /**
     * v1.6.9 整段视频：宿主在 IO 线程把视频片段压成小代理（可返回空
     * = 无视频可传/全部转码失败，控制器自动退回关键帧）。默认不提供。
     */
    private val videosProvider: suspend (Project) -> List<AiClipVideo> = { emptyList() },
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

        // v1.6.9: 时间线上有没有视频片段（决定要不要走整段直传）。
        val assetsById = ctx.project.assets.associateBy { it.id }
        val videoClipsAvailable = ctx.project.clips.any { c ->
            assetsById[c.assetId]?.type == MediaType.VIDEO
        }
        val wantVideo = settings.wantVideo(videoClipsAvailable)

        // v1.6.6/v1.6.9 画面感知 — 提帧/压视频在后台进行，忙碌行给用户提示。
        _state.update { it.copy(busyTool = if (wantVideo) BUSY_COMPRESSING else BUSY_READING_FRAMES) }
        val videos = if (wantVideo) {
            runCatching { videosProvider(ctx.project) }.getOrDefault(emptyList())
        } else emptyList()
        val frames = runCatching { framesProvider(ctx.project) }.getOrDefault(emptyList())

        // 多轮记忆：只保留历史里的 user / assistant 文本（工具报文巨大且已失效）。
        val history = _state.value.entries.mapNotNull { e ->
            when (e) {
                is AiChatEntry.User -> WireMessage("user", e.text)
                is AiChatEntry.Assistant -> if (e.text.isNotBlank()) WireMessage("assistant", e.text) else null
                else -> null
            }
        }

        // v1.6.9: 视频优先，未被视频覆盖的段（图片片段/超预算/转码失败）
        // 用中间帧补齐 —— 混合在同一条 vision 消息里。wantVideo 命中时
        // 即使是纯文本模型名也发（用户显式选了整段视频档）；否则维持
        // v1.6.6 的 wantFrames 门控（auto 启发式 / on / off）。
        val wantVision = wantVideo || settings.wantFrames(frames.isNotEmpty())
        val videoClipIds = videos.map { it.clipId }.toSet()
        val framesForSend = if (wantVision) frames.filter { it.clipId !in videoClipIds } else emptyList()

        try {
            try {
                runLoop(userText, history, tools, ctx, settings, framesForSend, videos)
            } catch (e: LlmException) {
                val sentVisual = videos.isNotEmpty() || framesForSend.isNotEmpty()
                if (sentVisual && looksLikeVisionRejection(e.message)) {
                    if (videos.isNotEmpty()) {
                        // v1.6.9: 整段视频被拒（方言不对/模型不吃视频）→ 关键帧降级。
                        appendEntry(AiChatEntry.Note(VIDEO_FALLBACK_NOTE))
                        try {
                            runLoop(userText, history, tools, ctx, settings, frames, emptyList())
                        } catch (e2: LlmException) {
                            if (frames.isNotEmpty() && looksLikeVisionRejection(e2.message)) {
                                // v1.6.6: 帧也被拒 → 纯文字。
                                appendEntry(AiChatEntry.Note(VISION_FALLBACK_NOTE))
                                runLoop(userText, history, tools, ctx, settings, emptyList(), emptyList())
                            } else throw e2
                        }
                    } else {
                        // v1.6.6: 帧被拒（纯文本模型收到图）→ 降级纯文字重试一次。
                        appendEntry(AiChatEntry.Note(VISION_FALLBACK_NOTE))
                        runLoop(userText, history, tools, ctx, settings, emptyList(), emptyList())
                    }
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
     * 一轮完整对话循环（含自动应用）。frames/videos 非空时在 system 后
     * 插入混合 vision 消息（视频代理 + 补齐帧）。
     */
    private suspend fun runLoop(
        userText: String,
        history: List<WireMessage>,
        tools: EditTools,
        ctx: AiProjectContext,
        settings: AiSettings,
        frames: List<AiClipFrame>,
        videos: List<AiClipVideo>,
    ) {
        val d = EditTools.Draft(ctx.project)
        val wire = mutableListOf(
            WireMessage("system", tools.systemPrompt(d, ctx.playheadUs, customSkillsProvider())),
        )
        if (frames.isNotEmpty() || videos.isNotEmpty()) wire.add(visionMessage(frames, videos))
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

    /** v1.6.9: 混合 vision 消息 — 视频代理（原生视频模型）+ 补齐帧。 */
    private fun visionMessage(frames: List<AiClipFrame>, videos: List<AiClipVideo>): WireMessage {
        val text = buildString {
            append("Visual context of the current timeline. ")
            if (videos.isNotEmpty()) {
                append("VIDEO parts are attached AFTER any image parts, in timeline order: ")
                append("each is a compressed 480p proxy of that clip's full content window - ")
                append("watch them to judge content, motion, pacing and rhythm precisely. ")
                videos.forEach { v ->
                    append("\n[video ${v.clipId} \"${v.assetName}\" content ")
                    append("${"%.1f".format(v.startSec)}s..${"%.1f".format(v.startSec + v.windowSec)}s")
                    if (v.sentSec + 0.05 < v.windowSec) {
                        append(", only the FIRST ${"%.0f".format(v.sentSec)}s attached")
                    }
                    append("]")
                }
                append("\nThe proxies are lower resolution: find EXACT cut points with your ")
                append("tools (split/trim) and verify with get_draft_overview instead of ")
                append("pixel-peeping.")
            }
            if (frames.isNotEmpty()) {
                if (videos.isNotEmpty()) append("\n")
                append("IMAGE parts follow (one MIDDLE frame per remaining clip, in timeline ")
                append("order) - use them to judge content where no video is attached: ")
                frames.forEach { f ->
                    append("\n[${f.clipId} \"${f.assetName}\" at ${"%.1f".format(f.midSec)}s]")
                }
            }
            append("\nClips with no attachment here are black gaps, audio-only, or extraction failed.")
        }
        return WireMessage(
            role = "user",
            content = text,
            images = frames.map { it.b64Jpeg },
            videos = videos.map { WireVideo(it.b64Mp4, "video/mp4", it.style) },
        )
    }

    /** 错误文本像“模型不收图/视频”吗（降级重试的触发条件，保守匹配）。 */
    private fun looksLikeVisionRejection(msg: String?): Boolean {
        val m = msg ?: return false
        return Regex("(?i)(image|multimodal|modalit|vision|base64|video|unsupported content|content type)")
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
        const val BUSY_COMPRESSING = "正在压缩视频发给模型…"
        const val VISION_FALLBACK_NOTE = "这个模型看不了图，我改用纯文字模式再试了一次"
        const val VIDEO_FALLBACK_NOTE = "这个模型收不了整段视频，我改用关键帧画面再试了一次"
    }
}
