package com.RooMCutNext.ai

import com.RooMCutNext.core.model.AudioClip
import com.RooMCutNext.core.model.BLACK_FRAME_ASSET_ID
import com.RooMCutNext.core.model.BLACK_FRAME_FILE_NAME
import com.RooMCutNext.core.model.Clip
import com.RooMCutNext.core.model.IMAGE_MAX_DURATION_US
import com.RooMCutNext.core.model.MediaAsset
import com.RooMCutNext.core.model.MediaType
import com.RooMCutNext.core.model.Project
import com.RooMCutNext.core.model.TextOverlayItem
import com.RooMCutNext.core.model.TextStyleSpec
import com.RooMCutNext.core.model.TransitionStyle
import com.RooMCutNext.core.ops.TimelineOps
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * AI 剪辑（v1.6.0）— 编辑工具集（"skills"）。
 *
 * 复刻 OpenChatCut 的核心模式：外部 agent（LLM）与编辑器本体共享同一套
 * EditorCore 命令。这里每个工具直接落到 [TimelineOps] 纯函数上——与
 * 手动编辑、撤销栈、导出管线完全同构，不存在第二套会漂移的编辑语义。
 *
 * 所有工具操作的都是会话 [Draft]（项目深拷贝 + 操作日志），用户确认后
 * 由 ViewModel 以单条撤销记录提交 —— 等价于 OpenChatCut 的
 * begin_edit_session → draft → review → apply 事务模型。
 *
 * 本类是纯 Kotlin（无 Android 依赖），全部行为可单测。
 */
class EditTools(
    /** 可用字体 (id, 显示名)。 */
    private val fonts: List<Pair<String, String>>,
    /** 可用 LUT (id, 显示名)，id 形如 "builtin:warm"。 */
    private val luts: List<Pair<String, String>>,
    private val idFactory: () -> String,
) {

    /** 转场样式 id（v1.5.5 已从面板移除的 zoom_in/zoom_out 不对 agent 开放）。 */
    val styles: List<String> = TransitionStyle.entries
        .filter { it != TransitionStyle.ZOOM_IN && it != TransitionStyle.ZOOM_OUT }
        .map { it.id }

    class Draft(val base: Project) {
        var project: Project = base
        val journal = mutableListOf<String>()
        var seekUs: Long? = null
        var changeCount = 0
        val changed: Boolean get() = project != base
    }

    data class ToolResult(val json: JsonObject, val ok: Boolean)

    // ------------------------------------------------------------------
    // 时间换算（agent 侧全部用秒，核心域全部是微秒）
    // ------------------------------------------------------------------

    private fun secToUs(sec: Double?): Long? = sec?.let { (it * 1_000_000.0).roundToLong() }
    private fun usToSec(us: Long): Double = (us / 1000.0).roundToInt() / 1000.0

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun okJson(vararg pairs: Pair<String, Any?>): ToolResult {
        val obj = buildJsonObject {
            put("ok", true)
            pairs.forEach { (k, v) ->
                when (v) {
                    null -> {}
                    is String -> put(k, v)
                    is Number -> put(k, v)
                    is Boolean -> put(k, v)
                }
            }
        }
        return ToolResult(obj, true)
    }

    private fun errJson(message: String): ToolResult =
        ToolResult(buildJsonObject { put("ok", false); put("error", message) }, false)

    // ------------------------------------------------------------------
    // 工具 schema（OpenAI function calling JSON Schema 子集）
    // ------------------------------------------------------------------

    private fun schema(
        name: String,
        description: String,
        properties: Map<String, String>,
        required: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    properties.forEach { (k, desc) -> put(k, buildJsonObject { put("type", desc) }) }
                })
                if (required.isNotEmpty()) {
                    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
                }
            })
        })
    }

    /** 完整工具清单（发给模型的 tools 数组）。 */
    fun toolSchemas(): JsonArray = buildJsonArray {
        val styleList = styles.joinToString(" | ")
        add(schema(
            T.OVERVIEW,
            "Get the CURRENT draft timeline state as JSON (clips, texts, audios, pips with ids, " +
                "positions in seconds, durations). Call this after edits when you need fresh state.",
            emptyMap(),
        ))
        add(schema(
            T.SPLIT,
            "Split one video clip into two at a timeline position. timeline_sec must be INSIDE the clip. " +
                "Returns the new right clip's id.",
            mapOf("clip_id" to "string", "timeline_sec" to "number"),
            listOf("clip_id", "timeline_sec"),
        ))
        add(schema(
            T.DELETE_CLIP,
            "Delete a video clip from the timeline (its neighbors close the hole).",
            mapOf("clip_id" to "string"),
            listOf("clip_id"),
        ))
        add(schema(
            T.TRIM_CLIP,
            "Trim a clip's content window (head/tail). new_start_sec/new_end_sec are the " +
                "desired timeline positions of the clip's start/end edges; omit the edge you " +
                "don't move. NOTE: the timeline is a gapless film strip - trimming the head " +
                "shortens the clip and shifts later clips earlier (no gap is created); " +
                "values are clamped to the source media bounds.",
            mapOf("clip_id" to "string", "new_start_sec" to "number", "new_end_sec" to "number"),
            listOf("clip_id"),
        ))
        add(schema(
            T.MOVE_CLIP,
            "Reorder a clip to a new index on the video track (0-based).",
            mapOf("clip_id" to "string", "to_index" to "integer"),
            listOf("clip_id", "to_index"),
        ))
        add(schema(
            T.SPEED,
            "Set playback speed of a clip (0.25 to 4.0). Duration changes accordingly.",
            mapOf("clip_id" to "string", "speed" to "number"),
            listOf("clip_id", "speed"),
        ))
        add(schema(
            T.VOLUME,
            "Set audio volume (0.0 to 2.0, 1.0 = original) and/or mute flag of a video clip.",
            mapOf("clip_id" to "string", "volume" to "number", "mute" to "boolean"),
            listOf("clip_id"),
        ))
        add(schema(
            T.LOOK,
            "Apply a LUT color look to a clip. lut_id must be one of the available LUT ids " +
                "(${luts.joinToString(" | ") { it.first }}), or null/empty to clear. " +
                "strength: 0.0-1.0 (default 1.0).",
            mapOf("clip_id" to "string", "lut_id" to "string", "strength" to "number"),
            listOf("clip_id"),
        ))
        add(schema(
            T.TRANSITION,
            "Set the transition INTO a clip (crossfade with the previous clip). clip_id is the " +
                "RIGHT clip of the pair (index > 0). style_id one of: $styleList. " +
                "duration_sec: 0.1-2.0, default 0.4 when starting from none.",
            mapOf("clip_id" to "string", "style_id" to "string", "duration_sec" to "number"),
            listOf("clip_id", "style_id"),
        ))
        add(schema(
            T.REMOVE_TRANSITION,
            "Remove the transition into a clip (hard cut).",
            mapOf("clip_id" to "string"),
            listOf("clip_id"),
        ))
        add(schema(
            T.ADD_BLACK,
            "Insert a black gap clip (solid black frames) - the explicit way to add a pause; " +
                "deleting clips RIPPLES the timeline closed, so pauses must be inserted on purpose. " +
                "position: \"before\" or \"after\" the anchor clip (default after); with no " +
                "anchor_clip_id, appends at the end. duration_sec: 0.1-60 (default 2.0), " +
                "stretchable afterwards like any clip. Tip: a dissolve transition into a black " +
                "clip is a professional fade-to-black.",
            mapOf("anchor_clip_id" to "string", "position" to "string", "duration_sec" to "number"),
            emptyList(),
        ))
        add(schema(
            T.ADD_TEXT,
            "Add a text overlay (title/caption). font_id one of: ${fonts.joinToString(" | ") { it.first }}. " +
                "color_hex like \"#FFFFFF\". x_ratio/y_ratio: 0-1 canvas position. " +
                "font_size_ratio: 0.02-0.5 of canvas height (default 0.08).",
            mapOf(
                "text" to "string", "start_sec" to "number", "duration_sec" to "number",
                "font_id" to "string", "color_hex" to "string", "font_size_ratio" to "number",
                "x_ratio" to "number", "y_ratio" to "number",
            ),
            listOf("text", "start_sec", "duration_sec"),
        ))
        add(schema(
            T.UPDATE_TEXT,
            "Partially update an existing text overlay (only given fields change).",
            mapOf(
                "text_id" to "string", "text" to "string", "start_sec" to "number",
                "duration_sec" to "number", "color_hex" to "string", "font_size_ratio" to "number",
                "x_ratio" to "number", "y_ratio" to "number", "font_id" to "string",
            ),
            listOf("text_id"),
        ))
        add(schema(
            T.DELETE_TEXT,
            "Delete a text overlay.",
            mapOf("text_id" to "string"),
            listOf("text_id"),
        ))
        add(schema(
            T.AUDIO_VOLUME,
            "Set volume (0.0-2.0) and/or mute of an audio clip (music/voiceover).",
            mapOf("audio_id" to "string", "volume" to "number", "mute" to "boolean"),
            listOf("audio_id"),
        ))
        add(schema(
            T.AUDIO_TRIM,
            "Move / shorten an audio clip on the timeline. start_sec is the new timeline start; " +
                "duration_sec the new length (clamped to the source audio bounds).",
            mapOf("audio_id" to "string", "start_sec" to "number", "duration_sec" to "number"),
            listOf("audio_id"),
        ))
        add(schema(
            T.DELETE_AUDIO,
            "Delete an audio clip.",
            mapOf("audio_id" to "string"),
            listOf("audio_id"),
        ))
        add(schema(
            T.SEEK,
            "Move the playhead (preview position) after the edits are applied, so the user " +
                "lands where the change is visible.",
            mapOf("sec" to "number"),
            listOf("sec"),
        ))
    }

    private object T {
        const val OVERVIEW = "get_draft_overview"
        const val SPLIT = "split_clip"
        const val DELETE_CLIP = "delete_clip"
        const val TRIM_CLIP = "trim_clip"
        const val MOVE_CLIP = "move_clip"
        const val SPEED = "set_clip_speed"
        const val VOLUME = "set_clip_volume"
        const val LOOK = "set_clip_look"
        const val TRANSITION = "set_transition"
        const val REMOVE_TRANSITION = "remove_transition"
        const val ADD_BLACK = "add_black_clip"
        const val ADD_TEXT = "add_text"
        const val UPDATE_TEXT = "update_text"
        const val DELETE_TEXT = "delete_text"
        const val AUDIO_VOLUME = "set_audio_volume"
        const val AUDIO_TRIM = "trim_audio"
        const val DELETE_AUDIO = "delete_audio"
        const val SEEK = "seek"
    }

    // ------------------------------------------------------------------
    // 执行器
    // ------------------------------------------------------------------

    fun execute(draft: Draft, name: String, args: JsonObject): ToolResult {
        val p = draft.project
        return when (name) {
            T.OVERVIEW -> ToolResult(overviewJson(draft), true)
            T.SEEK -> {
                val sec = args.num("sec")
                    ?: return errJson("缺少参数 sec")
                if (sec < 0) return errJson("sec 不能为负数")
                draft.seekUs = (sec * 1_000_000.0).roundToLong()
                draft.journal.add("seek → ${usToSec(draft.seekUs!!)}s")
                okJson("sec" to usToSec(draft.seekUs!!))
            }
            else -> clipTools(draft, name, args) ?: textTools(draft, name, args)
                ?: audioTools(draft, name, args) ?: errJson(
                    "未知工具 \"$name\"。可用: " +
                        listOf(T.OVERVIEW, T.SPLIT, T.DELETE_CLIP, T.TRIM_CLIP, T.MOVE_CLIP,
                            T.SPEED, T.VOLUME, T.LOOK, T.TRANSITION, T.REMOVE_TRANSITION,
                            T.ADD_BLACK, T.ADD_TEXT, T.UPDATE_TEXT, T.DELETE_TEXT, T.AUDIO_VOLUME,
                            T.AUDIO_TRIM, T.DELETE_AUDIO, T.SEEK).joinToString(", "),
                )
        }
    }

    private fun requireClip(p: Project, clipId: String?): Pair<Int, Clip>? {
        if (clipId == null) return null
        val idx = p.clips.indexOfFirst { it.id == clipId }
        if (idx < 0) {
            return null
        }
        return idx to p.clips[idx]
    }

    private fun clipIdList(p: Project): String =
        p.clips.take(8).joinToString(", ") { it.id } + if (p.clips.size > 8) " …" else ""

    /**
     * v1.6.1: 草稿会话内的黑场资产模板（纯 Kotlin，无文件系统依赖）。
     * 提交时 ViewModel 会用真实落盘资产（本地化名称/真实字节数）治愈替换
     * ——见 EditorViewModel.aiApplyDraftInternal。
     */
    private fun blackFrameAsset() = MediaAsset(
        id = BLACK_FRAME_ASSET_ID,
        fileName = BLACK_FRAME_FILE_NAME,
        displayName = "黑场",
        type = MediaType.IMAGE,
        durationUs = 0L,
        width = 1920,
        height = 1080,
        fps = 0f,
        hasAudio = false,
    )

    private fun clipTools(draft: Draft, name: String, args: JsonObject): ToolResult? {
        val p = draft.project
        val clipId = args.str("clip_id")
        return when (name) {
            T.SPLIT -> {
                val found = requireClip(p, clipId) ?: return errJson(
                    "clip_id 无效。可用: ${clipIdList(p)}",
                )
                val (idx, clip) = found
                val timelineUs = secToUs(args.num("timeline_sec"))
                    ?: return errJson("缺少参数 timeline_sec")
                val st = TimelineOps.starts(p.clips)
                val local = timelineUs - st[idx]
                if (local <= 0 || local >= clip.durationUs) {
                    return errJson(
                        "timeline_sec ${usToSec(timelineUs)} 不在片段 ${clip.id} 内 " +
                            "(${usToSec(st[idx])}s - ${usToSec(st[idx] + clip.durationUs)}s)",
                    )
                }
                val newId = idFactory()
                val result = TimelineOps.splitAtTimeline(p.clips, { id -> p.assetById(id) }, timelineUs, newId)
                    ?: return errJson(
                        "分割点距片段边缘过近（最少保留 ${usToSec(MIN_SPAN)}s）",
                    )
                draft.project = p.copy(clips = result.clips)
                bump(draft, "split_clip: ${clip.id} @ ${usToSec(timelineUs)}s → +${result.rightId}")
                okJson("new_clip_id" to result.rightId)
            }
            T.DELETE_CLIP -> {
                if (clipId == null || p.clips.none { it.id == clipId }) {
                    return errJson("clip_id 无效。可用: ${clipIdList(p)}")
                }
                val newClips = TimelineOps.deleteById(p.clips, clipId)
                draft.project = p.copy(clips = newClips)
                bump(draft, "delete_clip: $clipId")
                okJson("clips_remaining" to newClips.size)
            }
            T.ADD_BLACK -> {
                val durSec = args.num("duration_sec") ?: 2.0
                if (durSec <= 0.0) return errJson("duration_sec 必须为正数")
                val durUs = (durSec * 1_000_000.0).roundToLong()
                    .coerceIn(MIN_SPAN, IMAGE_MAX_DURATION_US)
                val posRaw = args.str("position") ?: "after"
                val afterPos = when (posRaw.lowercase()) {
                    "before", "前" -> false
                    "after", "后" -> true
                    else -> return errJson("position 只能是 \"before\" 或 \"after\"")
                }
                // 锚点参数名兼容：schema 声明 anchor_clip_id，但其它工具都是
                // clip_id —— 模型两种叫法都接受，避免无谓的工具调用失败。
                val anchorId = args.str("anchor_clip_id") ?: clipId
                val anchorIdx = if (anchorId != null) {
                    requireClip(p, anchorId)?.first
                        ?: return errJson("clip_id 无效。可用: ${clipIdList(p)}")
                } else -1
                val newId = idFactory()
                val withAsset = if (p.assets.any { it.id == BLACK_FRAME_ASSET_ID }) {
                    p
                } else {
                    p.copy(assets = p.assets + blackFrameAsset())
                }
                val newClips = TimelineOps.insertBlackGap(
                    withAsset.clips,
                    anchorIdx,
                    after = afterPos,
                    durationUs = durUs,
                    newId = newId,
                )
                draft.project = withAsset.copy(clips = newClips)
                bump(
                    draft,
                    "add_black_clip: ${usToSec(durUs)}s " +
                        (if (anchorIdx >= 0) "${if (afterPos) "after" else "before"} $anchorId" else "at end"),
                )
                okJson("new_clip_id" to newId, "duration_sec" to usToSec(durUs))
            }
            T.TRIM_CLIP -> {
                val found = requireClip(p, clipId) ?: return errJson(
                    "clip_id 无效。可用: ${clipIdList(p)}",
                )
                val (idx, clip) = found
                val newStart = args.num("new_start_sec")
                val newEnd = args.num("new_end_sec")
                if (newStart == null && newEnd == null) {
                    return errJson("至少提供 new_start_sec 或 new_end_sec 之一")
                }
                val st = TimelineOps.starts(p.clips)
                val curStartUs = st[idx]
                val requestedIn = if (newStart != null) {
                    clip.sourceInUs + ((newStart * 1e6 - curStartUs) * clip.speed).roundToLong()
                } else clip.sourceInUs
                val requestedOut = if (newEnd != null) {
                    clip.sourceInUs + ((newEnd * 1e6 - curStartUs) * clip.speed).roundToLong()
                } else clip.sourceOutUs
                val asset = p.assetById(clip.assetId)
                val newClips = TimelineOps.trim(p.clips, idx, asset, requestedIn, requestedOut)
                val updated = newClips[idx]
                draft.project = p.copy(clips = newClips)
                val newSt = TimelineOps.starts(newClips)
                // 无缝胶片条模型：掐头缩短时长、后续片段左移，片段本身仍锚在其起点。
                bump(
                    draft,
                    "trim_clip: ${clip.id} → dur ${usToSec(updated.durationUs)}s",
                )
                okJson(
                    "timeline_start_sec" to usToSec(newSt[idx]),
                    "duration_sec" to usToSec(updated.durationUs),
                )
            }
            T.MOVE_CLIP -> {
                val found = requireClip(p, clipId) ?: return errJson(
                    "clip_id 无效。可用: ${clipIdList(p)}",
                )
                val (from, _) = found
                val to = args.num("to_index")?.roundToInt()
                    ?: return errJson("缺少参数 to_index")
                if (to < 0 || to >= p.clips.size) {
                    return errJson("to_index $to 超出范围 0-${p.clips.size - 1}")
                }
                val newClips = TimelineOps.move(p.clips, from, to)
                draft.project = p.copy(clips = newClips)
                bump(draft, "move_clip: $clipId → #$to")
                okJson("index" to to)
            }
            T.SPEED -> {
                val found = requireClip(p, clipId) ?: return errJson(
                    "clip_id 无效。可用: ${clipIdList(p)}",
                )
                val (_, clip) = found
                val speed = args.num("speed") ?: return errJson("缺少参数 speed")
                val clamped = speed.coerceIn(SPEED_MIN, SPEED_MAX)
                val newClips = TimelineOps.update(p.clips, clip.id) { it.copy(speed = clamped.toFloat()) }
                draft.project = p.copy(clips = newClips)
                bump(draft, "set_clip_speed: $clipId → ${"%.2f".format(clamped)}x")
                okJson("speed" to clamped.toDouble())
            }
            T.VOLUME -> {
                val found = requireClip(p, clipId) ?: return errJson(
                    "clip_id 无效。可用: ${clipIdList(p)}",
                )
                val (_, clip) = found
                val volume = args.num("volume")?.coerceIn(0.0, 2.0)
                val mute = args.bool("mute")
                if (volume == null && mute == null) {
                    return errJson("至少提供 volume 或 mute 之一")
                }
                val newClips = TimelineOps.update(p.clips, clip.id) {
                    it.copy(
                        volume = volume?.toFloat() ?: it.volume,
                        muted = mute ?: it.muted,
                    )
                }
                draft.project = p.copy(clips = newClips)
                bump(
                    draft,
                    "set_clip_volume: $clipId → vol=${"%.2f".format(volume ?: clip.volume.toDouble())}" +
                        (mute?.let { " mute=$it" } ?: ""),
                )
                okJson(
                    "volume" to (volume ?: clip.volume.toDouble()),
                    "muted" to (mute ?: clip.muted),
                )
            }
            T.LOOK -> {
                val found = requireClip(p, clipId) ?: return errJson(
                    "clip_id 无效。可用: ${clipIdList(p)}",
                )
                val (_, clip) = found
                val lutRaw = args.str("lut_id")
                val strength = args.num("strength")?.coerceIn(0.0, 1.0)
                val lutId: String? = if (lutRaw.isNullOrBlank() || lutRaw == "null") {
                    null
                } else {
                    if (luts.none { it.first == lutRaw }) {
                        return errJson(
                            "lut_id \"$lutRaw\" 不存在。可用: " +
                                luts.take(8).joinToString(", ") { it.first },
                        )
                    }
                    lutRaw
                }
                val newClips = TimelineOps.update(p.clips, clip.id) {
                    it.copy(adjust = it.adjust.copy(
                        lutId = lutId,
                        lutStrength = strength?.toFloat() ?: if (lutId == null) it.adjust.lutStrength else 1f,
                    ))
                }
                draft.project = p.copy(clips = newClips)
                bump(draft, "set_clip_look: $clipId → ${lutId ?: "无"}")
                okJson("lut_id" to (lutId ?: ""))
            }
            T.TRANSITION -> {
                val found = requireClip(p, clipId) ?: return errJson(
                    "clip_id 无效。可用: ${clipIdList(p)}",
                )
                val (idx, clip) = found
                if (idx == 0) {
                    return errJson("第一个片段没有前置片段，无法设置转场（请选择 index > 0 的片段）")
                }
                val styleId = args.str("style_id") ?: return errJson("缺少参数 style_id")
                if (styles.none { it == styleId }) {
                    return errJson(
                        "style_id \"$styleId\" 无效。可用: ${styles.joinToString(", ")}",
                    )
                }
                val durationSec = args.num("duration_sec")
                val requestedUs = when {
                    durationSec != null -> (durationSec * 1e6).roundToLong()
                    clip.transitionInUs > 0 -> clip.transitionInUs
                    else -> DEFAULT_TRANSITION_US
                }
                var newClips = TimelineOps.setTransitionType(p.clips, idx, styleId)
                newClips = TimelineOps.setTransitionIn(newClips, idx, requestedUs)
                val effective = newClips[idx].transitionInUs
                draft.project = p.copy(clips = newClips)
                bump(
                    draft,
                    "set_transition: ${clip.id} ← $styleId ${usToSec(effective)}s",
                )
                okJson("duration_sec" to usToSec(effective), "style_id" to styleId)
            }
            T.REMOVE_TRANSITION -> {
                val found = requireClip(p, clipId) ?: return errJson(
                    "clip_id 无效。可用: ${clipIdList(p)}",
                )
                val (idx, clip) = found
                val newClips = TimelineOps.setTransitionIn(p.clips, idx, 0L)
                draft.project = p.copy(clips = newClips)
                bump(draft, "remove_transition: ${clip.id}")
                okJson()
            }
            else -> null
        }
    }

    private fun textTools(draft: Draft, name: String, args: JsonObject): ToolResult? {
        val p = draft.project
        return when (name) {
            T.ADD_TEXT -> {
                val text = args.str("text") ?: return errJson("缺少参数 text")
                if (text.length > MAX_TEXT_LEN) {
                    return errJson("文字过长（最多 $MAX_TEXT_LEN 字）")
                }
                val startUs = secToUs(args.num("start_sec")) ?: return errJson("缺少参数 start_sec")
                if (startUs < 0) return errJson("start_sec 不能为负数")
                val durationUs = secToUs(args.num("duration_sec")) ?: return errJson("缺少参数 duration_sec")
                if (durationUs < MIN_TEXT_DURATION_US) {
                    return errJson("duration_sec 至少 ${usToSec(MIN_TEXT_DURATION_US)}s")
                }
                if (p.texts.size >= MAX_TEXTS) {
                    return errJson("文字层数量已达上限（$MAX_TEXTS）")
                }
                val fontRaw = args.str("font_id")
                val fontId = if (fontRaw == null || fonts.none { it.first == fontRaw }) {
                    if (fontRaw != null && fonts.isNotEmpty()) {
                        return errJson(
                            "font_id \"$fontRaw\" 不存在。可用: ${fonts.joinToString(", ") { it.first }}",
                        )
                    }
                    TextStyleSpec.FONT_SYSTEM
                } else fontRaw
                val color = args.str("color_hex")?.let {
                    parseColorHex(it) ?: return errJson("color_hex 格式应为 \"#RRGGBB\"")
                }
                val sizeRatio = args.num("font_size_ratio")?.coerceIn(0.02, 0.5)
                val xRatio = args.num("x_ratio")?.coerceIn(0.0, 1.0)
                val yRatio = args.num("y_ratio")?.coerceIn(0.0, 1.0)
                val item = TextOverlayItem(
                    id = idFactory(),
                    text = text,
                    startUs = startUs,
                    endUs = startUs + durationUs,
                    xRatio = xRatio?.toFloat() ?: 0.5f,
                    yRatio = yRatio?.toFloat() ?: 0.5f,
                    style = TextStyleSpec(
                        fontSizeRatio = sizeRatio?.toFloat() ?: 0.08f,
                        color = color ?: 0xFFFFFFFF,
                        fontId = fontId,
                    ),
                )
                draft.project = p.copy(texts = TimelineOps.insertText(p.texts, item))
                bump(
                    draft,
                    "add_text: \"${text.take(16)}\" ${usToSec(startUs)}-${usToSec(startUs + durationUs)}s",
                )
                okJson("text_id" to item.id)
            }
            T.UPDATE_TEXT -> {
                val textId = args.str("text_id") ?: return errJson("缺少参数 text_id")
                val existing = p.texts.firstOrNull { it.id == textId }
                    ?: return errJson(
                        "text_id 无效。可用: " +
                            p.texts.take(8).joinToString(", ") { it.id },
                    )
                var item = existing
                args.str("text")?.let {
                    if (it.length > MAX_TEXT_LEN) return errJson("文字过长（最多 $MAX_TEXT_LEN 字）")
                    item = item.copy(text = it)
                }
                val startSec = args.num("start_sec")
                val durationSec = args.num("duration_sec")
                if (startSec != null || durationSec != null) {
                    val startUs = startSec?.let {
                        (it * 1e6).roundToLong().coerceAtLeast(0L)
                    } ?: item.startUs
                    val durUs = durationSec?.let {
                        (it * 1e6).roundToLong().coerceAtLeast(MIN_TEXT_DURATION_US)
                    } ?: item.durationUs
                    item = item.copy(startUs = startUs, endUs = startUs + durUs)
                }
                args.str("color_hex")?.let {
                    val c = parseColorHex(it) ?: return errJson("color_hex 格式应为 \"#RRGGBB\"")
                    item = item.copy(style = item.style.copy(color = c))
                }
                args.num("font_size_ratio")?.let {
                    item = item.copy(style = item.style.copy(fontSizeRatio = it.coerceIn(0.02, 0.5).toFloat()))
                }
                args.num("x_ratio")?.let { item = item.copy(xRatio = it.coerceIn(0.0, 1.0).toFloat()) }
                args.num("y_ratio")?.let { item = item.copy(yRatio = it.coerceIn(0.0, 1.0).toFloat()) }
                args.str("font_id")?.let { fid ->
                    if (fonts.none { it.first == fid }) {
                        return errJson("font_id \"$fid\" 不存在")
                    }
                    item = item.copy(style = item.style.copy(fontId = fid))
                }
                if (item == existing) return errJson("没有任何字段需要更新")
                draft.project = p.copy(texts = p.texts.map { if (it.id == textId) item else it })
                bump(draft, "update_text: $textId")
                okJson()
            }
            T.DELETE_TEXT -> {
                val textId = args.str("text_id") ?: return errJson("缺少参数 text_id")
                if (p.texts.none { it.id == textId }) {
                    return errJson("text_id 无效")
                }
                draft.project = p.copy(texts = p.texts.filter { it.id != textId })
                bump(draft, "delete_text: $textId")
                okJson()
            }
            else -> null
        }
    }

    private fun audioTools(draft: Draft, name: String, args: JsonObject): ToolResult? {
        val p = draft.project
        return when (name) {
            T.AUDIO_VOLUME -> {
                val audioId = args.str("audio_id") ?: return errJson("缺少参数 audio_id")
                val existing = p.audios.firstOrNull { it.id == audioId }
                    ?: return errJson(
                        "audio_id 无效。可用: " + p.audios.take(8).joinToString(", ") { it.id },
                    )
                val volume = args.num("volume")?.coerceIn(0.0, 2.0)
                val mute = args.bool("mute")
                if (volume == null && mute == null) {
                    return errJson("至少提供 volume 或 mute 之一")
                }
                draft.project = p.copy(audios = p.audios.map {
                    if (it.id != audioId) it else it.copy(
                        volume = volume?.toFloat() ?: it.volume,
                        muted = mute ?: it.muted,
                    )
                })
                bump(
                    draft,
                    "set_audio_volume: $audioId → vol=${"%.2f".format(volume ?: existing.volume.toDouble())}" +
                        (mute?.let { " mute=$it" } ?: ""),
                )
                okJson()
            }
            T.AUDIO_TRIM -> {
                val audioId = args.str("audio_id") ?: return errJson("缺少参数 audio_id")
                val existing = p.audios.firstOrNull { it.id == audioId }
                    ?: return errJson(
                        "audio_id 无效。可用: " + p.audios.take(8).joinToString(", ") { it.id },
                    )
                val startSec = args.num("start_sec")
                val durationSec = args.num("duration_sec")
                if (startSec == null && durationSec == null) {
                    return errJson("至少提供 start_sec 或 duration_sec 之一")
                }
                val startUs = startSec?.let { (it * 1e6).roundToLong().coerceAtLeast(0L) }
                    ?: existing.startUs
                var durUs = durationSec?.let { (it * 1e6).roundToLong() } ?: existing.durationUs
                if (durUs < MIN_AUDIO_DURATION_US) durUs = MIN_AUDIO_DURATION_US
                val asset = p.assetById(existing.assetId)
                if (asset != null && asset.durationUs > 0) {
                    durUs = durUs.coerceAtMost(asset.durationUs)
                }
                val updated = existing.copy(
                    startUs = startUs,
                    sourceOutUs = existing.sourceInUs + durUs,
                )
                draft.project = p.copy(audios = p.audios.map { if (it.id == audioId) updated else it })
                bump(
                    draft,
                    "trim_audio: $audioId → start ${usToSec(startUs)}s " +
                        "dur ${usToSec(updated.durationUs)}s",
                )
                okJson("start_sec" to usToSec(startUs), "duration_sec" to usToSec(updated.durationUs))
            }
            T.DELETE_AUDIO -> {
                val audioId = args.str("audio_id") ?: return errJson("缺少参数 audio_id")
                if (p.audios.none { it.id == audioId }) {
                    return errJson("audio_id 无效")
                }
                draft.project = p.copy(audios = p.audios.filter { it.id != audioId })
                bump(draft, "delete_audio: $audioId")
                okJson()
            }
            else -> null
        }
    }

    private fun bump(draft: Draft, journalLine: String) {
        draft.journal.add(journalLine)
        draft.changeCount++
    }

    private fun parseColorHex(s: String): Long? {
        val h = s.removePrefix("#").removePrefix("0x")
        if (h.length != 6 && h.length != 8) return null
        val raw = h.toLongOrNull(16) ?: return null
        return if (h.length == 6) (0xFF000000L or raw) else raw
    }

    // ------------------------------------------------------------------
    // 项目概览（system prompt 内嵌 + get_draft_overview 工具复用）
    // ------------------------------------------------------------------

    fun overviewJson(draft: Draft, playheadUs: Long = 0L): JsonObject {
        val p = draft.project
        val st = TimelineOps.starts(p.clips)
        return buildJsonObject {
            put("fps", p.fps)
            put("canvas", "${p.width}x${p.height}")
            put("duration_sec", usToSec(p.totalDurationUs))
            put("playhead_sec", usToSec(playheadUs))
            put("clips", buildJsonArray {
                p.clips.take(MAX_CLIPS_IN_OVERVIEW).forEachIndexed { i, c ->
                    val clipAsset = p.assetById(c.assetId)
                    add(buildJsonObject {
                        put("id", c.id)
                        put("index", i)
                        put("name", clipAsset?.displayName?.take(24) ?: c.assetId)
                        if (clipAsset?.isBlackFrame == true) put("black", true)
                        put("start_sec", usToSec(st[i]))
                        put("duration_sec", usToSec(c.durationUs))
                        put("speed", c.speed.toDouble())
                        put("volume", c.volume.toDouble())
                        if (c.muted) put("muted", true)
                        if (c.transitionInUs > 0) {
                            put("transition_in_sec", usToSec(c.transitionInUs))
                            put("transition_type", c.transitionType)
                        }
                        c.adjust.lutId?.let { put("lut", it) }
                    })
                }
                if (p.clips.size > MAX_CLIPS_IN_OVERVIEW) add(JsonPrimitive("…+${p.clips.size - MAX_CLIPS_IN_OVERVIEW} clips"))
            })
            put("texts", buildJsonArray {
                p.texts.take(MAX_TEXTS_IN_OVERVIEW).forEach {
                    add(buildJsonObject {
                        put("id", it.id)
                        put("text", it.text.take(24))
                        put("start_sec", usToSec(it.startUs))
                        put("duration_sec", usToSec(it.durationUs))
                    })
                }
            })
            put("audios", buildJsonArray {
                p.audios.take(MAX_TEXTS_IN_OVERVIEW).forEach {
                    add(buildJsonObject {
                        put("id", it.id)
                        put("name", p.assetById(it.assetId)?.displayName?.take(24) ?: it.assetId)
                        put("start_sec", usToSec(it.startUs))
                        put("duration_sec", usToSec(it.durationUs))
                        put("volume", it.volume.toDouble())
                    })
                }
            })
            put("pips", buildJsonArray {
                p.pips.take(MAX_PIPS_IN_OVERVIEW).forEach {
                    add(buildJsonObject {
                        put("id", it.id)
                        put("name", p.assetById(it.assetId)?.displayName?.take(24) ?: it.assetId)
                        put("start_sec", usToSec(it.startUs))
                        put("duration_sec", usToSec(it.durationUs))
                    })
                }
            })
            put("available_luts", buildJsonArray {
                luts.forEach { add(buildJsonObject { put("id", it.first); put("name", it.second) }) }
            })
            put("available_transition_styles", buildJsonArray {
                styles.forEach { add(JsonPrimitive(it)) }
            })
            put("available_fonts", buildJsonArray {
                fonts.forEach { add(JsonPrimitive(it.first)) }
            })
        }
    }

    /** 每轮对话的 system prompt（含最新项目概览）。 */
    fun systemPrompt(draft: Draft, playheadUs: Long): String {
        val overview = overviewJson(draft, playheadUs).toString()
        return buildString {
            append(
                "You are the AI editing assistant of RooMCutNext, a mobile video editor. " +
                    "You edit the user's REAL project through tool calls: split, trim, delete, reorder, " +
                    "speed, volume, LUT looks, transitions, black gaps (add_black_clip) and text " +
                    "overlays. Deleting a clip RIPPLES the timeline closed - add a black gap clip " +
                    "when the user wants a pause or breathing room. All edits go to a DRAFT " +
                    "the user reviews and applies as ONE undoable step - nothing is destructive.\n\n" +
                    "Rules:\n" +
                    "1. Times are seconds (float). Use ids EXACTLY as given in the overview.\n" +
                    "2. Call get_draft_overview when you need fresh state after edits.\n" +
                    "3. Prefer several small precise tool calls over one huge guess; chain them freely.\n" +
                    "4. When done, reply with a SHORT summary of what changed (2-5 bullets).\n" +
                    "5. If the request needs a capability you have no tool for (importing new media, " +
                    "generating audio/video, keyframes, PiP geometry), say so plainly.\n" +
                    "6. Reply in the user's language (Chinese in, Chinese out; English in, English out).\n\n" +
                    "Current draft overview (JSON):\n",
            )
            append(overview)
        }
    }

    private companion object {
        const val MIN_SPAN = 250_000L
        const val SPEED_MIN = 0.25
        const val SPEED_MAX = 4.0
        const val DEFAULT_TRANSITION_US = 400_000L
        const val MAX_TEXT_LEN = 200
        const val MAX_TEXTS = 30
        const val MIN_TEXT_DURATION_US = 200_000L
        const val MIN_AUDIO_DURATION_US = 100_000L
        const val MAX_CLIPS_IN_OVERVIEW = 80
        const val MAX_TEXTS_IN_OVERVIEW = 40
        const val MAX_PIPS_IN_OVERVIEW = 20
    }
}
