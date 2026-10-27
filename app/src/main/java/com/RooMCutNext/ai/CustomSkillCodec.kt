package com.RooMCutNext.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * v1.6.8 自定义技能导入 —— JSON 编解码 + 校验。
 *
 * 用户把新剪辑"技巧"写成 JSON（文件或粘贴）导入 App：导入后随
 * system prompt 注入，任何 OpenAI 兼容模型即刻可用 —— 与内置 15 项
 * 技能完全同权（[EditSkills.promptBlock]）。
 *
 * 支持三种输入形状（宽容解析）：
 *  1. 单个技能对象 `{"id": ..., "steps": [...]}`
 *  2. 技能数组 `[{...}, {...}]`
 *  3. 包装对象 `{"skills": [{...}, ...]}`
 *
 * 防线（与内置技能同标准，见 EditSkillsTest）：
 *  - steps 里出现的每个 snake_case 标识符必须是 [TOOL_NAMES]
 *    真实工具或 [SKILL_PARAM_WHITELIST] 参数 —— 幻觉工具名
 *    （如 "fade_out_clip"）在导入时即拦下，不会送进模型；
 *  - id 与内置/已存技能查重（同名导入 = 更新：覆盖写同 id 文件）；
 *  - 字段长度/数量上限，防止巨型 payload 撑爆 prompt。
 *
 * 纯 Kotlin（无 Android 依赖），可单测；文件由
 * [com.RooMCutNext.data.CustomSkillStore] 落盘。
 */
object CustomSkillCodec {

    /** 单次导入 / 存量上限。 */
    const val MAX_SKILLS = 50
    const val MAX_STEPS = 6

    private const val MAX_ID = 40
    private const val MAX_NAME_ZH = 20
    private const val MAX_NAME_EN = 48
    private const val MAX_ONELINER = 100
    private const val MAX_STEP_LEN = 400
    private const val MAX_ALIASES = 8
    private const val MAX_ALIAS_LEN = 20

    /** JSON 文本长度上限（约 64KB，远超正常技能包）。 */
    private const val MAX_TEXT = 64 * 1024

    private val ID_REGEX = Regex("[a-z0-9_-]+")
    private val SNAKE = Regex("[a-z]+(?:_[a-z0-9]+)+")

    sealed interface Result {
        /** 导入成功（skills 非空；warnings 为不影响导入的提示）。 */
        data class Success(val skills: List<EditSkill>, val warnings: List<String>) : Result
        /** 导入失败（面向用户的中文原因，已含具体字段/序号）。 */
        data class Error(val message: String) : Result
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    fun parse(text: String, existing: List<EditSkill> = emptyList()): Result {
        if (text.isBlank()) return Result.Error("内容为空")
        if (text.length > MAX_TEXT) return Result.Error("内容过长（超过 64KB）——请确认导入的是技能文件")
        val trimmed = text.trim()
        // v1.6.20: 双格式 —— 以 { 或 [ 开头按 JSON 解析；否则按
        // SKILL.md（Markdown + frontmatter）解析。手写 Markdown 远比
        // 手写 JSON 友好（业界 Agent Skills 的事实形态），JSON 保留兼容。
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return parseMarkdown(trimmed, existing)
        }
        val root = runCatching { Json.parseToJsonElement(trimmed) }.getOrElse {
            return Result.Error("不是合法的 JSON：${it.message?.take(120) ?: "格式错误"}")
        }
        val objs: List<JsonObject> = when (root) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> {
                val wrapped = (root["skills"] as? JsonArray)
                if (wrapped != null) wrapped.mapNotNull { it as? JsonObject }
                else listOf(root)
            }
            else -> return Result.Error("顶层应为技能对象或数组")
        }
        if (objs.isEmpty()) return Result.Error("没有找到技能对象")
        if (objs.size > MAX_SKILLS) return Result.Error("一次最多导入 $MAX_SKILLS 个技能（当前 ${objs.size} 个）")

        val seen = mutableSetOf<String>()
        val builtInIds = EditSkills.ALL.map { it.id }.toSet()
        val existingIds = existing.map { it.id }.toSet()
        val skills = mutableListOf<EditSkill>()
        val warnings = mutableListOf<String>()

        objs.forEachIndexed { idx, o ->
            when (val r = parseOne(o, idx, builtInIds, seen)) {
                is OneOk -> {
                    skills.add(r.skill)
                    seen.add(r.skill.id)
                    if (r.skill.id in existingIds) {
                        warnings.add("「${r.skill.nameZh}」覆盖了已有的同 id 技能")
                    }
                }
                is OneError -> return r.toResult()
            }
        }
        if (skills.isEmpty()) {
            return Result.Error(warnings.firstOrNull() ?: "没有可导入的技能")
        }
        return Result.Success(skills, warnings)
    }

    private sealed interface One
    private data class OneOk(val skill: EditSkill) : One
    private class OneError(val message: String) : One {
        fun toResult() = Result.Error(message)
    }

    /**
     * v1.6.20 SKILL.md（Markdown）路径：[SkillMd] 负责解析与防护，
     * 这里只做结果转换 + 与 JSON 路径一致的覆盖提示语义。
     */
    private fun parseMarkdown(text: String, existing: List<EditSkill>): Result {
        val p = SkillMd.parse(text)
        val skill = p.skill ?: return Result.Error(
            when (p.error) {
                SkillMd.Error.MISSING_NAME ->
                    "缺少技能名：在文件开头写 name: 技能名（或正文第一行用 # 标题）"
                SkillMd.Error.EMPTY_STEPS ->
                    "没有步骤：正文需要列表（如「- xxx」或「1. xxx」）或分段落写"
                SkillMd.Error.BAD_FRONT_MATTER ->
                    "文件开头的 --- 没有成对闭合，请补上第二个 ---"
                SkillMd.Error.CLASH_BUILTIN ->
                    "名字或别名「${p.detail ?: ""}」与内置技能冲突，请换个名字"
                SkillMd.Error.HALLUCINATED_TOOL ->
                    "步骤引用了不存在的工具 \"${p.detail ?: ""}\"。" +
                        "可用工具：split_clip、trim_clip、delete_clip、move_clip、" +
                        "set_clip_speed、set_clip_volume、set_transition、add_black_clip、" +
                        "add_text、update_text、delete_text、set_audio_volume、trim_audio、" +
                        "delete_audio、seek、get_draft_overview 等"
                null -> "无法解析"
            }
        )
        val warnings = if (skill.id in existing.map { it.id }) {
            listOf("「${skill.nameZh}」覆盖了已有的同名技能")
        } else emptyList()
        return Result.Success(listOf(skill), warnings)
    }

    private fun parseOne(
        o: JsonObject,
        idx: Int,
        builtInIds: Set<String>,
        seenInBatch: Set<String>,
    ): One {
        val at = "第 ${idx + 1} 个技能"
        val nameZh = o.str("nameZh")?.trim().orEmpty()
        val nameEn = o.str("nameEn")?.trim().orEmpty()
        if (nameZh.isEmpty() && nameEn.isEmpty()) return OneError("$at：缺少 nameZh 或 nameEn")

        // id：可选 —— 缺省由 nameEn（否则 nameZh）派生。
        val rawId = o.str("id")?.trim().orEmpty()
        val id = if (rawId.isNotEmpty()) rawId.lowercase() else deriveId(nameEn.ifEmpty { nameZh })
        if (id.length !in 2..MAX_ID || !ID_REGEX.matches(id)) {
            return if (rawId.isEmpty()) {
                OneError("$at：缺少 id，且无法从名称派生（名称需含字母），请补上 id 字段")
            } else {
                OneError("$at：id 只能含小写字母/数字/下划线/横线（2-$MAX_ID 位），当前 \"$rawId\"")
            }
        }
        if (id in builtInIds) return OneError("$at：id \"$id\" 与内置技能重名，请换一个 id")
        if (id in seenInBatch) return OneError("$at：id \"$id\" 在本次导入中重复")

        if (nameZh.length > MAX_NAME_ZH) return OneError("$at：nameZh 超过 $MAX_NAME_ZH 字")
        if (nameEn.length > MAX_NAME_EN) return OneError("$at：nameEn 超过 $MAX_NAME_EN 字符")

        val oneZh = o.str("oneLinerZh")?.trim().orEmpty()
        val oneEn = o.str("oneLinerEn")?.trim().orEmpty()
        if (oneZh.isEmpty() && oneEn.isEmpty()) return OneError("$at（$id）：缺少 oneLinerZh 或 oneLinerEn")

        val aliases = (o["aliasesZh"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()
        if (aliases.size > MAX_ALIASES) return OneError("$at（$id）：别名最多 $MAX_ALIASES 个")
        if (aliases.any { it.length > MAX_ALIAS_LEN }) return OneError("$at（$id）：单个别名超过 $MAX_ALIAS_LEN 字")

        val steps = (o["steps"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (steps.isEmpty()) return OneError("$at（$id）：steps 至少要 1 步")
        if (steps.size > MAX_STEPS) return OneError("$at（$id）：steps 最多 $MAX_STEPS 步（当前 ${steps.size}）")
        if (steps.any { it.length > MAX_STEP_LEN }) return OneError("$at（$id）：单步超过 $MAX_STEP_LEN 字符")

        // 防幻觉工具：steps 里的 snake_case 标识符必须是真实工具/白名单参数。
        steps.forEach { step ->
            SNAKE.findAll(step.lowercase()).map { it.value }.forEach { token ->
                if (token !in TOOL_NAMES && token !in SKILL_PARAM_WHITELIST) {
                    return OneError(
                        "$at（$id）：步骤引用了不存在的工具 \"$token\"。" +
                            "可用工具：split_clip、trim_clip、restore_clip、delete_clip、move_clip、" +
                            "set_clip_speed、set_clip_volume、set_transition、add_black_clip、" +
                            "add_text、update_text、delete_text、set_audio_volume、trim_audio、" +
                            "delete_audio、seek、get_draft_overview 等",
                    )
                }
            }
        }

        val skill = EditSkill(
            id = id,
            nameZh = nameZh.ifEmpty { nameEn },
            nameEn = nameEn.ifEmpty { nameZh },
            aliasesZh = aliases,
            category = EditSkills.categoryById(o.str("category")),
            oneLinerZh = oneZh.ifEmpty { oneEn },
            oneLinerEn = oneEn.ifEmpty { oneZh },
            steps = steps,
        )
        return OneOk(skill)
    }

    /** nameEn/nameZh → 合法 id（小写、非法字符转 _、压缩连续 _）。 */
    private fun deriveId(from: String): String =
        from.lowercase().replace(Regex("[^a-z0-9_]+"), "_")
            .replace(Regex("_+"), "_").trim('_').take(MAX_ID)

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    // ------------------------------------------------------------------
    // 序列化（导出/回写用，与解析同字段）
    // ------------------------------------------------------------------

    fun serialize(skill: EditSkill): String = buildJsonObject {
        put("id", skill.id)
        put("nameZh", skill.nameZh)
        put("nameEn", skill.nameEn)
        if (skill.aliasesZh.isNotEmpty()) {
            put("aliasesZh", kotlinx.serialization.json.buildJsonArray {
                skill.aliasesZh.forEach { add(JsonPrimitive(it)) }
            })
        }
        put("category", skill.category.id)
        put("oneLinerZh", skill.oneLinerZh)
        put("oneLinerEn", skill.oneLinerEn)
        put("steps", kotlinx.serialization.json.buildJsonArray {
            skill.steps.forEach { add(JsonPrimitive(it)) }
        })
    }.toString()

    // ------------------------------------------------------------------
    // 示例（导入对话框“填入示例”用 —— 全部引用真实工具，可直接导入）
    // ------------------------------------------------------------------

    val SAMPLE_JSON: String = """
        {
          "skills": [
            {
              "id": "vlog_opener",
              "nameZh": "Vlog开场钩子",
              "nameEn": "vlog opener",
              "aliasesZh": ["开场钩子", "黄金三秒"],
              "category": "story",
              "oneLinerZh": "把最抓人的一段挪到开头，前三秒就留住观众",
              "oneLinerEn": "Lead with the most striking moment to hook viewers in three seconds.",
              "steps": [
                "get_draft_overview; WITH frames pick the most striking clip visually, WITHOUT frames ASK the user which clip hooks best.",
                "move_clip that clip to to_index 0.",
                "trim_clip it to 2-3 seconds keeping the strongest part.",
                "add_text a short hook caption at start_sec 0, duration_sec 2, y_ratio 0.80, color_hex #FFFFFF."
              ]
            }
          ]
        }
    """.trimIndent()
}
