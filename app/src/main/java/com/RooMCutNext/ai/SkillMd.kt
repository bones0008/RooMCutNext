package com.RooMCutNext.ai

/**
 * v1.6.20 自定义技能 —— SKILL.md（Markdown + YAML frontmatter）解析。
 *
 * 业界 Agent Skills 的事实标准（Claude Code / Cursor 等）是 Markdown：
 * frontmatter 放结构化元数据，正文是写给 LLM 看的自然语言配方。
 * RooMCutNext 的技能本质就是"喂给模型的提示词配方"，因此自定义技能
 * 导入采用同一形态，而不是让用户手写 JSON：
 *
 * ```
 * ---
 * name: 卡点视频
 * name_en: beat sync
 * aliases: 卡点, 踩点, beat
 * category: rhythm        # rhythm/transition/story/audio/text，缺省 custom
 * one_liner: 画面踩着音乐节拍切换
 * ---
 *
 * 拿到音乐后：
 *
 * 1. get_draft_overview 查看时间线素材
 * 2. 按节拍 trim_clip 每段
 * 3. seek 到开头预览
 * ```
 *
 * 解析规则（宽松友好，宁可导入也别把用户挡在门外）：
 *  - frontmatter 可整块省略；省略时正文首个 `#` 标题作为技能名；
 *  - 正文里的列表项（`-`/`*`/`+`/`1.`…）按序成为 steps；没有列表时
 *    非空段落成为 steps；
 *  - one_liner 缺省取正文第一行非标题非列表文字；
 *  - 别名分隔符支持中英文逗号 / 顿号 / 分号 / 空白；
 *  - category 按枚举 id / 中文名 / 英文名匹配（不区分大小写），
 *    认不出来归入 [SkillCategory.CUSTOM]；
 *  - 防护上限：名字 24 字符、别名 8 个、步骤 12 条（超出丢弃）、
 *    单步 400 字符（超出截断）。
 *
 * 与 [CustomSkillCodec]（JSON 路径）同一防线标准：
 *  - steps 里每个 snake_case 标识符必须是 [TOOL_NAMES] 真实工具或
 *    [SKILL_PARAM_WHITELIST] 参数（幻觉工具在导入时拦下）；
 *  - 名字/别名与内置技能冲突即拒绝（点名时无法区分）；
 *  - 同名重导入 = 同 id 覆盖更新（id 由名字哈希派生，稳定）。
 *
 * 本文件纯 Kotlin（无 Android 依赖），全部行为可单测；入口在
 * [CustomSkillCodec.parse] 的格式自动识别（JSON 或 Markdown）。
 */
object SkillMd {

    /** 上限（导入护栏：防止 system prompt 被巨型技能撑爆）。 */
    const val MAX_NAME_CHARS = 24
    const val MAX_ALIASES = 8
    const val MAX_STEPS = 12
    const val MAX_STEP_CHARS = 400
    const val MAX_BODY_CHARS = 4000

    /**
     * 导入失败原因（机器可读；[CustomSkillCodec] 的 Markdown 路径
     * 转换成面向用户的中文错误文案，与 JSON 路径同风格）。
     */
    enum class Error { MISSING_NAME, EMPTY_STEPS, BAD_FRONT_MATTER, CLASH_BUILTIN, HALLUCINATED_TOOL }

    /** 解析结果：skill 与 error 恰好一个非空；detail 携带冲突名/幻觉工具名。 */
    data class Parse(val skill: EditSkill?, val error: Error?, val detail: String? = null)

    /** 列表项：`- xxx` / `* xxx` / `+ xxx` / `1. xxx` / `12) xxx`。 */
    private val BULLET = Regex("""^\s{0,8}(?:[-*+]|\d{1,2}[.)])\s+(.+)$""")

    /** frontmatter 键（Claude 风格英文键；description 是 one_liner 的别名）。 */
    private val KEYS = setOf("name", "name_en", "aliases", "category", "one_liner", "one_liner_en", "description")

    /** stable id：同名（忽略大小写与首尾空白）重导入 = 同一技能 = 覆盖更新。 */
    fun idFor(name: String): String =
        "user_" + (name.trim().lowercase().hashCode().toLong() and 0xffffffffL)

    fun parse(markdownRaw: String): Parse {
        val text = markdownRaw.replace("\r\n", "\n").replace("\r", "\n").trim()
        if (text.isEmpty()) return Parse(null, Error.EMPTY_STEPS)

        // ---- 1. frontmatter（可省略，须成对闭合）----
        var meta = LinkedHashMap<String, String>()
        var body = text
        if (text.startsWith("---")) {
            val lines = text.split("\n")
            val close = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (close < 0) return Parse(null, Error.BAD_FRONT_MATTER)
            lines.subList(1, close + 1).forEach { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val k = line.substring(0, idx).trim().lowercase()
                    val v = line.substring(idx + 1).trim()
                    if (k in KEYS && v.isNotEmpty()) meta[k] = v
                }
            }
            body = lines.subList(close + 2, lines.size).joinToString("\n").trim()
        }
        if (body.length > MAX_BODY_CHARS) body = body.take(MAX_BODY_CHARS)

        // ---- 2. 技能名：frontmatter name > 正文首个 # 标题 > 报错 ----
        var nameZh = meta["name"]?.trim().orEmpty()
        val bodyLines = body.split("\n")
        if (nameZh.isEmpty()) {
            val headingIdx = bodyLines.indexOfFirst { it.trimStart().startsWith("#") }
            if (headingIdx >= 0) {
                nameZh = bodyLines[headingIdx].trimStart().replace(Regex("""^#{1,6}\s+"""), "").trim()
                body = bodyLines.filterIndexed { i, _ -> i != headingIdx }.joinToString("\n").trim()
            }
        }
        if (nameZh.isEmpty()) return Parse(null, Error.MISSING_NAME)
        nameZh = nameZh.take(MAX_NAME_CHARS).trim()
        if (nameZh.isEmpty()) return Parse(null, Error.MISSING_NAME)

        // ---- 3. steps：列表项优先，否则非空段落 ----
        val refreshed = body.split("\n")
        val bullets = refreshed.mapNotNull { BULLET.find(it)?.groupValues?.get(1)?.trim() }
            .filter { it.isNotEmpty() }
        val paragraphs = body.split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.replace("\n", " ") }
        val rawSteps = if (bullets.isNotEmpty()) bullets else paragraphs
        if (rawSteps.isEmpty()) return Parse(null, Error.EMPTY_STEPS)
        val steps = rawSteps.take(MAX_STEPS).map { it.take(MAX_STEP_CHARS).trim() }
            .filter { it.isNotEmpty() }
        if (steps.isEmpty()) return Parse(null, Error.EMPTY_STEPS)

        // ---- 4. one_liner：frontmatter > 正文首行非标题非列表 ----
        val oneLinerZh = meta["one_liner"] ?: meta["description"]
            ?: refreshed.firstOrNull {
                it.isNotBlank() && !it.trimStart().startsWith("#") && !BULLET.matches(it)
            }?.trim()
            ?: steps.first()
        val oneLinerEn = meta["one_liner_en"]?.takeIf { it.isNotBlank() } ?: oneLinerZh

        // ---- 5. 别名 / 分类 ----
        val aliases = (meta["aliases"] ?: "")
            .split(',', '，', '、', ';', '；', ' ', '\t', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_ALIASES)
        val category = mapCategory(meta["category"])
        val nameEn = meta["name_en"]?.takeIf { it.isNotBlank() } ?: nameZh

        // ---- 6. 防线一：名字/别名与内置技能冲突即拒绝（点名无法区分）----
        val clash = EditSkills.find(nameZh)?.nameZh
            ?: EditSkills.find(nameEn)?.nameZh
            ?: aliases.mapNotNull { EditSkills.find(it)?.nameZh }.firstOrNull()
        if (clash != null) return Parse(null, Error.CLASH_BUILTIN, clash)

        // ---- 7. 防线二：幻觉工具（与 JSON 路径同标准）----
        val snake = Regex("[a-z]+(?:_[a-z0-9]+)+")
        steps.forEach { step ->
            snake.findAll(step.lowercase()).map { it.value }.forEach { token ->
                if (token !in TOOL_NAMES && token !in SKILL_PARAM_WHITELIST) {
                    return Parse(null, Error.HALLUCINATED_TOOL, token)
                }
            }
        }

        // ---- 8. 组装 ----
        return Parse(
            EditSkill(
                id = idFor(nameZh),
                nameZh = nameZh,
                nameEn = nameEn.take(MAX_NAME_CHARS),
                aliasesZh = aliases,
                category = category,
                oneLinerZh = oneLinerZh.take(60),
                oneLinerEn = oneLinerEn.take(60),
                steps = steps,
            ),
            null,
        )
    }

    /** category 字段 → 枚举：id / 中文名 / 英文名（忽略大小写），认不出归 CUSTOM。 */
    private fun mapCategory(raw: String?): SkillCategory {
        val q = raw?.trim()?.lowercase() ?: return SkillCategory.CUSTOM
        return SkillCategory.entries.firstOrNull {
            it.id == q || it.zh.lowercase() == q || it.en.lowercase() == q
        } ?: SkillCategory.CUSTOM
    }

    /** 导入对话框「填入示例」用 —— 全部引用真实工具，可直接导入。 */
    val SAMPLE_MD: String = """
        ---
        name: 卡点视频
        name_en: beat sync
        aliases: 卡点, 踩点
        category: rhythm
        one_liner: 画面踩着音乐节拍切换
        ---

        1. get_draft_overview 找到音乐与各段画面
        2. WITH frames 按画面动作挑节拍点；WITHOUT frames 先问用户哪几段是重点
        3. trim_clip 各段到节拍附近，动作落在拍子上
        4. seek 到开头预览效果
    """.trimIndent()
}
