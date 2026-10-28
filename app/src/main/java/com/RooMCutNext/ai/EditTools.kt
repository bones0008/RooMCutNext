package com.RooMCutNext.ai

import com.RooMCutNext.core.model.AudioClip
import com.RooMCutNext.core.model.BLACK_DEFAULT_DURATION_US
import com.RooMCutNext.core.model.BLACK_FRAME_ASSET_ID
import com.RooMCutNext.core.model.BLACK_FRAME_FILE_NAME
import com.RooMCutNext.core.model.Clip
import com.RooMCutNext.core.model.DollyZoomSpec
import com.RooMCutNext.core.model.IMAGE_DEFAULT_DURATION_US
import com.RooMCutNext.core.model.IMAGE_MAX_DURATION_US
import com.RooMCutNext.core.model.MediaAsset
import com.RooMCutNext.core.model.MediaType
import com.RooMCutNext.core.model.Project
import com.RooMCutNext.core.model.TextOverlayItem
import com.RooMCutNext.core.model.TextStyleSpec
import com.RooMCutNext.core.model.TransitionStyle
import com.RooMCutNext.core.model.snapProjectFps
import com.RooMCutNext.core.ops.TimelineOps
import com.RooMCutNext.core.util.DollyZoomMath
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

/**
 * [EditTools.T] 全部工具名（v1.6.8：导入技能校验共享 —— 自定义技能
 * 配方里出现幻觉工具名（如 "fade_out_clip"）在导入时即拦下，
 * 坏配方永远进不了 system prompt。反射收一次，常驻内存。
 */
val TOOL_NAMES: Set<String> =
    EditTools.T::class.java.declaredFields
        .filter { it.type == String::class.java }
        .map { it.get(null) as String }
        .toSet()

/**
 * 技能 steps 中允许出现的非工具 snake_case 标识符（参数名 + 概览 JSON 键白名单）。
 * v1.6.12：新增概览键 source_in_sec / source_out_sec / source_duration_sec，
 * 供「恢复原长」等配方精确引用窗口语义。
 * v1.6.13：新增 asset_id / position（add_clip_from_library 参数）与
 * used_count（素材库概览键），供「素材成片」类配方引用。
 */
val SKILL_PARAM_WHITELIST: Set<String> = setOf(
    "anchor_clip_id", "duration_sec", "start_sec", "new_start_sec", "new_end_sec",
    "to_index", "font_size_ratio", "x_ratio", "y_ratio", "color_hex",
    "source_in_sec", "source_out_sec", "source_duration_sec",
    "asset_id", "position", "used_count",
    // v1.6.21: dolly-zoom 工具参数（set_clip_dolly_zoom）。
    "max_scale", "direction", "center_x", "center_y",
)

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

    class Draft(val base: Project, val playheadUs: Long = 0L) {
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
                "values are clamped to the source media bounds. To give the clip back its " +
                "full original length, prefer restore_clip instead of computing the edges.",
            mapOf("clip_id" to "string", "new_start_sec" to "number", "new_end_sec" to "number"),
            listOf("clip_id"),
        ))
        add(schema(
            T.RESTORE_CLIP,
            "Restore a clip to its ORIGINAL full length: reopen the trim window to the " +
                "whole source media (source_in_sec 0 → source_duration_sec). Speed, volume, " +
                "looks and other edits stay untouched. Image clips return to their default " +
                "display duration (3s; black frames 2s). Safe to call on an already-full " +
                "clip (returns already_full=true, changes nothing).",
            mapOf("clip_id" to "string"),
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
            T.ADD_FROM_LIBRARY,
            "Add a clip from the project's media LIBRARY onto the timeline. The library " +
                "array in the overview lists every imported asset (used_count 0 = not on the " +
                "timeline yet) - pick asset_id from there. Optional source_in_sec/source_out_sec " +
                "use only a SEGMENT of the asset (seconds within the source; default = whole " +
                "asset, images default to 3s display). position: \"before\" or \"after\" the " +
                "anchor clip (default after); with no anchor_clip_id, appends at the end. " +
                "Audio assets join the independent audio track at the playhead.",
            mapOf(
                "asset_id" to "string", "anchor_clip_id" to "string", "position" to "string",
                "source_in_sec" to "number", "source_out_sec" to "number",
            ),
            listOf("asset_id"),
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
        add(schema(
            T.DOLLY,
            "Hitchcock dolly-zoom on a clip: time-animated magnification of the frame over " +
                "duration_sec from the clip's start. direction in = push in (background closes " +
                "on the subject - the Vertigo look); out = reverse settle. max_scale (1.2..4) is " +
                "the final magnification; center_x/center_y (0..1) pick the FIXED point - put " +
                "them ON the subject. BEST on 4K footage shot while actually zooming (that " +
                "combination is the real dolly-zoom); on ordinary footage it reads as a smooth " +
                "Ken-Burns push. All params optional - omitted ones keep the clip's current " +
                "values (defaults on a fresh set: 2s, 2x, in, center).",
            mapOf(
                "clip_id" to "string",
                "duration_sec" to "number",
                "max_scale" to "number",
                "direction" to "string",
                "center_x" to "number",
                "center_y" to "number",
            ),
            listOf("clip_id"),
        ))
        add(schema(
            T.DOLLY_REMOVE,
            "Remove the dolly-zoom effect from a clip (no-op if absent).",
            mapOf("clip_id" to "string"),
            listOf("clip_id"),
        ))
    }

    internal object T {
        const val OVERVIEW = "get_draft_overview"
        const val SPLIT = "split_clip"
        const val DELETE_CLIP = "delete_clip"
        const val TRIM_CLIP = "trim_clip"
        const val RESTORE_CLIP = "restore_clip"
        const val MOVE_CLIP = "move_clip"
        const val SPEED = "set_clip_speed"
        const val VOLUME = "set_clip_volume"
        const val LOOK = "set_clip_look"
        const val TRANSITION = "set_transition"
        const val REMOVE_TRANSITION = "remove_transition"
        const val ADD_BLACK = "add_black_clip"
        const val ADD_FROM_LIBRARY = "add_clip_from_library"
        const val ADD_TEXT = "add_text"
        const val UPDATE_TEXT = "update_text"
        const val DELETE_TEXT = "delete_text"
        const val AUDIO_VOLUME = "set_audio_volume"
        const val AUDIO_TRIM = "trim_audio"
        const val DELETE_AUDIO = "delete_audio"
        const val SEEK = "seek"
        const val DOLLY = "set_clip_dolly_zoom"
        const val DOLLY_REMOVE = "remove_clip_dolly_zoom"
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
            T.DOLLY -> {
                val clipId = args.str("clip_id") ?: return errJson("缺少参数 clip_id")
                val found = requireClip(p, clipId) ?: return errJson(
                    "clip_id 无效。可用: ${clipIdList(p)}",
                )
                val (_, clip) = found
                val current = clip.dollyZoom
                val durationSec = args.num("duration_sec")
                val scale = args.num("max_scale")
                val dirRaw = args.str("direction")?.lowercase()
                val zoomIn: Boolean = when (dirRaw) {
                    null, "" -> current?.zoomIn ?: true
                    "in", "push_in", "zoom_in" -> true
                    "out", "pull_out", "zoom_out" -> false
                    else -> return errJson(
                        "direction 无效（in=推近 / out=拉远还原），收到 \"$dirRaw\"",
                    )
                }
                val cx = args.num("center_x")?.coerceIn(0.0, 1.0)
                val cy = args.num("center_y")?.coerceIn(0.0, 1.0)
                val spec = DollyZoomSpec(
                    durationUs = DollyZoomMath.clampDurationUs(
                        durationSec?.let { (it * 1e6).roundToLong() }
                            ?: current?.durationUs ?: 2_000_000L,
                    ),
                    zoomIn = zoomIn,
                    maxScale = DollyZoomMath.clampMaxScale(
                        (scale ?: current?.maxScale?.toDouble() ?: 2.0).toFloat(),
                    ),
                    centerX = (cx ?: current?.centerX?.toDouble() ?: 0.5).toFloat(),
                    centerY = (cy ?: current?.centerY?.toDouble() ?: 0.5).toFloat(),
                )
                val newClips = TimelineOps.update(p.clips, clip.id) {
                    it.copy(dollyZoom = spec)
                }
                draft.project = p.copy(clips = newClips)
                bump(
                    draft,
                    "set_clip_dolly_zoom: $clipId ${usToSec(spec.durationUs)}s " +
                        "${spec.maxScale}x ${if (spec.zoomIn) "推近" else "拉远"}",
                )
                okJson(
                    "duration_sec" to usToSec(spec.durationUs),
                    "max_scale" to spec.maxScale.toDouble(),
                    "direction" to if (spec.zoomIn) "in" else "out",
                    "center_x" to spec.centerX.toDouble(),
                    "center_y" to spec.centerY.toDouble(),
                )
            }
            T.DOLLY_REMOVE -> {
                val clipId = args.str("clip_id") ?: return errJson("缺少参数 clip_id")
                val found = requireClip(p, clipId) ?: return errJson(
                    "clip_id 无效。可用: ${clipIdList(p)}",
                )
                val (_, clip) = found
                val had = clip.dollyZoom != null
                val newClips = TimelineOps.update(p.clips, clip.id) {
                    it.copy(dollyZoom = null)
                }
                draft.project = p.copy(clips = newClips)
                bump(draft, "remove_clip_dolly_zoom: $clipId")
                okJson("removed" to had)
            }
            else -> clipTools(draft, name, args) ?: textTools(draft, name, args)
                ?: audioTools(draft, name, args) ?: errJson(
                    "未知工具 \"$name\"。可用: " +
                        listOf(T.OVERVIEW, T.SPLIT, T.DELETE_CLIP, T.TRIM_CLIP, T.RESTORE_CLIP,
                            T.MOVE_CLIP, T.SPEED, T.VOLUME, T.LOOK, T.TRANSITION, T.REMOVE_TRANSITION,
                            T.ADD_BLACK, T.ADD_FROM_LIBRARY, T.ADD_TEXT, T.UPDATE_TEXT, T.DELETE_TEXT,
                            T.AUDIO_VOLUME, T.AUDIO_TRIM, T.DELETE_AUDIO, T.SEEK,
                            T.DOLLY, T.DOLLY_REMOVE).joinToString(", "),
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

    /** 素材库 id 摘要（排除内置黑场）：id(名称)，供模型选 asset_id。 */
    private fun libraryIdList(p: Project): String {
        val lib = p.assets.filter { it.id != BLACK_FRAME_ASSET_ID }
        return lib.take(8).joinToString(", ") { "${it.id}(${it.displayName.take(12)})" } +
            if (lib.size > 8) " …" else ""
    }

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
            T.ADD_FROM_LIBRARY -> {
                // v1.6.13 素材库：AI 自己从库里挑素材上时间线 —— 导入即入库
                // （Project.assets），本工具把库里的资产变成时间线内容。
                val assetId = args.str("asset_id")
                    ?: return errJson("缺少参数 asset_id")
                val asset = p.assets.firstOrNull { it.id == assetId && it.id != BLACK_FRAME_ASSET_ID }
                    ?: return errJson(
                        "asset_id \"$assetId\" 无效。素材库可用: ${libraryIdList(p)}",
                    )
                val posRaw = args.str("position") ?: "after"
                val afterPos = when (posRaw.lowercase()) {
                    "before", "前" -> false
                    "after", "后" -> true
                    else -> return errJson("position 只能是 \"before\" 或 \"after\"")
                }
                // 锚点参数名兼容（同 add_black_clip）：anchor_clip_id 或 clip_id。
                val anchorId = args.str("anchor_clip_id") ?: clipId
                val anchorIdx = if (anchorId != null) {
                    requireClip(p, anchorId)?.first
                        ?: return errJson("anchor_clip_id 无效。可用: ${clipIdList(p)}")
                } else -1

                if (asset.isAudio) {
                    // 音频资产 → 独立音频轨，从 playhead 起（与手动添加一致）。
                    val newId = idFactory()
                    val audio = AudioClip(
                        id = newId,
                        assetId = asset.id,
                        startUs = draft.playheadUs.coerceAtLeast(0L),
                        sourceInUs = 0L,
                        sourceOutUs = asset.durationUs,
                    )
                    draft.project = p.copy(audios = TimelineOps.insertAudio(p.audios, audio))
                    bump(draft, "add_clip_from_library: audio \"${asset.displayName}\"")
                    okJson(
                        "audio_id" to newId,
                        "start_sec" to usToSec(audio.startUs),
                        "duration_sec" to usToSec(audio.durationUs),
                    )
                } else {
                    // 视频/图片 → 时间线 clip。可选窗口：视频取源内一段，
                    // 图片 source_out_sec 即展示时长（0.1..60s，默认 3s）。
                    val newId = idFactory()
                    val (inUs, outUs) = if (asset.isImage) {
                        val displaySec = args.num("source_out_sec") ?: 3.0
                        0L to (displaySec * 1e6).roundToLong()
                            .coerceIn(MIN_SPAN, IMAGE_MAX_DURATION_US)
                    } else {
                        val inReq = secToUs(args.num("source_in_sec")) ?: 0L
                        val outReq = secToUs(args.num("source_out_sec"))
                            ?: (asset.durationUs).coerceAtLeast(MIN_SPAN)
                        val inClamped = inReq.coerceIn(0L, (asset.durationUs - MIN_SPAN).coerceAtLeast(0L))
                        inClamped to outReq.coerceIn(inClamped + MIN_SPAN, asset.durationUs)
                    }
                    val clip = Clip(id = newId, assetId = asset.id, sourceInUs = inUs, sourceOutUs = outUs)
                    val insertAt = if (anchorIdx < 0) p.clips.size
                    else if (afterPos) anchorIdx + 1 else anchorIdx
                    val newClips = TimelineOps.insert(p.clips, clip, insertAt)
                    // 空时间线上第一个视觉片段决定画布/帧率（同手动导入）。
                    val np = if (p.clips.isEmpty()) {
                        if (asset.isImage) p.copy(width = asset.width, height = asset.height)
                        else p.copy(
                            fps = snapProjectFps(asset.fps),
                            width = asset.width,
                            height = asset.height,
                        )
                    } else p
                    draft.project = np.copy(clips = newClips)
                    bump(
                        draft,
                        "add_clip_from_library: \"${asset.displayName}\" " +
                            "${usToSec(inUs)}s-${usToSec(outUs)}s @${insertAt}",
                    )
                    okJson(
                        "new_clip_id" to newId,
                        "index" to insertAt,
                        "duration_sec" to usToSec(clip.durationUs),
                        "source_in_sec" to usToSec(inUs),
                        "source_out_sec" to usToSec(outUs),
                    )
                }
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
            T.RESTORE_CLIP -> {
                // v1.6.11：恢复原始长度 —— 修剪是非破坏性窗口，源文件完整
                // 存在项目媒体目录里；把窗口重开到整条源即可无损还原。
                // 速度/音量/滤镜等其它属性一概不动（只还原窗口）。
                val found = requireClip(p, clipId) ?: return errJson(
                    "clip_id 无效。可用: ${clipIdList(p)}",
                )
                val (idx, clip) = found
                val asset = p.assetById(clip.assetId)
                    ?: return errJson("素材元数据缺失（${clip.assetId}），无法恢复")
                if (!asset.isImage && asset.durationUs <= 0L) {
                    return errJson("该素材时长未知（元数据损坏），无法恢复")
                }
                if (!asset.isImage && clip.sourceInUs == 0L && clip.sourceOutUs == asset.durationUs) {
                    // 已经是全长：诚实告知，不动草稿（幂等无副作用）。
                    return okJson(
                        "duration_sec" to usToSec(clip.durationUs),
                        "already_full" to true,
                        "source_duration_sec" to usToSec(asset.durationUs),
                    )
                }
                val fullOutUs = when {
                    asset.isBlackFrame -> BLACK_DEFAULT_DURATION_US
                    asset.isImage -> IMAGE_DEFAULT_DURATION_US
                    else -> asset.durationUs
                }
                val restored = clip.copy(sourceInUs = 0L, sourceOutUs = fullOutUs)
                val newClips = p.clips.toMutableList().also { it[idx] = restored }
                draft.project = p.copy(clips = newClips)
                val newSt = TimelineOps.starts(newClips)
                bump(
                    draft,
                    "restore_clip: ${clip.id} → dur ${usToSec(restored.durationUs)}s",
                )
                okJson(
                    "timeline_start_sec" to usToSec(newSt[idx]),
                    "duration_sec" to usToSec(restored.durationUs),
                    "source_duration_sec" to usToSec(
                        if (asset.isImage) fullOutUs else asset.durationUs,
                    ),
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
                        // v1.6.11：暴露原始素材信息 —— AI 必须能看出「被修建过」
                        // 以及原始全长是多少，才能正确执行「恢复原始长度」。
                        // 图片没有“原始时长”概念（默认展示 3s、可拉 0.1..60s），
                        // 不报告窗口字段，避免误导模型。
                        if (clipAsset != null && !clipAsset.isImage && clipAsset.durationUs > 0) {
                            put("source_duration_sec", usToSec(clipAsset.durationUs))
                            put("source_in_sec", usToSec(c.sourceInUs))
                            put("source_out_sec", usToSec(c.sourceOutUs))
                            if (c.sourceInUs > 0 || c.sourceOutUs < clipAsset.durationUs) {
                                put("trimmed", true)
                            }
                        }
                        put("speed", c.speed.toDouble())
                        put("volume", c.volume.toDouble())
                        if (c.muted) put("muted", true)
                        if (c.transitionInUs > 0) {
                            put("transition_in_sec", usToSec(c.transitionInUs))
                            put("transition_type", c.transitionType)
                        }
                        // v1.6.21: dolly-zoom 状态 —— agent 能看出哪些素材已有
                        // 希区柯克变焦及参数，避免重复设置/盲目覆盖。
                        c.dollyZoom?.let { dz ->
                            put("dolly_zoom", buildJsonObject {
                                put("duration_sec", usToSec(dz.durationUs))
                                put("max_scale", dz.maxScale.toDouble())
                                put("direction", if (dz.zoomIn) "in" else "out")
                                put("center_x", dz.centerX.toDouble())
                                put("center_y", dz.centerY.toDouble())
                            })
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
            // v1.6.13 素材库：全部已导入素材（含未上时间线的），AI 由此
            // 自主选素材（asset_id）并用 add_clip_from_library 上时间线。
            // used_count = clips+audios+pips 引用次数，0 = 还在库里没用。
            put("library", buildJsonArray {
                val libAssets = p.assets.filter { it.id != BLACK_FRAME_ASSET_ID }
                libAssets.take(MAX_LIBRARY_IN_OVERVIEW).forEach { a ->
                    val uses = p.clips.count { it.assetId == a.id } +
                        p.audios.count { it.assetId == a.id } +
                        p.pips.count { it.assetId == a.id }
                    add(buildJsonObject {
                        put("id", a.id)
                        put("name", a.displayName.take(24))
                        put("type", a.type.name.lowercase())
                        if (!a.isImage && a.durationUs > 0) {
                            put("duration_sec", usToSec(a.durationUs))
                        }
                        put("size", "${a.width}x${a.height}")
                        if (a.fps > 0f) put("fps", a.fps.toInt())
                        if (!a.isImage && !a.hasAudio) put("no_audio", true)
                        put("used_count", uses)
                    })
                }
                if (libAssets.size > MAX_LIBRARY_IN_OVERVIEW) {
                    add(JsonPrimitive("…+${libAssets.size - MAX_LIBRARY_IN_OVERVIEW} assets"))
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

    /** 每轮对话的 system prompt（含最新项目概览 + 技能库）。v1.6.8：自定义技能一并注入。 */
    fun systemPrompt(
        draft: Draft,
        playheadUs: Long,
        customSkills: List<EditSkill> = emptyList(),
    ): String {
        val overview = overviewJson(draft, playheadUs).toString()
        return buildString {
            append(
                "You are the AI editing assistant of RooMCutNext, a mobile video editor. " +
                    "You edit the user's REAL project through tool calls: split, trim, restore " +
                    "full length, delete, reorder, speed, volume, LUT looks, transitions, black " +
                    "gaps (add_black_clip), text overlays and adding clips from the media " +
                    "library (add_clip_from_library). Deleting a clip RIPPLES the " +
                    "timeline closed - add a black gap clip when the user wants a pause or " +
                    "breathing room. Edits apply immediately as ONE undoable step - the user " +
                    "can revert everything with a single tap, so just do the work directly.\n\n" +
                    "Rules:\n" +
                    "1. Times are seconds (float). Use ids EXACTLY as given in the overview.\n" +
                    "2. Call get_draft_overview when you need fresh state after edits.\n" +
                    "3. Prefer several small precise tool calls over one huge guess; chain them freely.\n" +
                    "4. When done, reply in the user's language with ONE or TWO short, plain, friendly " +
                    "sentences describing what changed - like a video-editor buddy talking to a friend. " +
                    "NO bullet lists, NO markdown, NO tool ids, NO technical terms or parameter names.\n" +
                    "5. Media ALREADY imported lives in the project library (the \"library\" array " +
                    "below) - add any of it to the timeline with add_clip_from_library. Only " +
                    "importing NEW media from the device, generating audio/video, keyframes or " +
                    "PiP geometry are beyond your tools; say so plainly when asked.\n" +
                    "6. Reply in the user's language (Chinese in, Chinese out; English in, English out).\n\n" +
                    // v1.6.5: 技能库 —— 命名剪辑技巧配方，用户可点名调用。
                    "7. The skill library below names editing techniques you know how to execute. " +
                    "When the user names one (or describes it), follow its recipe; when the user asks " +
                    "\"what can you do\", mention a few skill names in the user's language.\n\n" +
                    // v1.6.11: 原始素材可见性 —— 修复「AI 称素材已是完整长度」幻觉。
                    "8. Every video clip carries source_in_sec/source_out_sec (the source window it " +
                    "currently uses) and source_duration_sec (the ORIGINAL full length); a clip " +
                    "marked \"trimmed\" lost part of it. Trimming is non-destructive and the FULL " +
                    "source file is always kept in the project, so restoring a clip to its " +
                    "original length is ALWAYS possible via restore_clip - NEVER claim it is " +
                    "already full or impossible: check the trimmed flag and the window instead.\n\n" +
                    // v1.6.13: 素材库 —— AI 自主选素材（用户反馈：让 AI 分析素材库、
                    // 自己判断用什么素材上时间线，比只看时间线更精确）。
                    "9. The library array lists EVERY imported asset with used_count (0 = not on " +
                    "the timeline yet). When the user asks you to pick/arrange their footage " +
                    "(\"帮我选素材\", \"用素材库剪一条\"), study the library - names, durations, " +
                    "and attached visual frames of unused assets - choose the right assets and " +
                    "place them with add_clip_from_library (optional source_in_sec/source_out_sec " +
                    "to use just the best segment). Never invent asset ids: use the listed ones.\n\n" +
                    EditSkills.promptBlock(customSkills) +
                    "\nCurrent draft overview (JSON):\n",
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
        const val MAX_LIBRARY_IN_OVERVIEW = 40
    }
}
