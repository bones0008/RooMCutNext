package com.RooMCutNext.ai

/**
 * AI 剪辑（v1.6.5）— 剪辑技能库。
 *
 * 复刻 OpenChatCut 的 skill 模块思路：剪辑"技巧"不需要训练进模型，
 * 而是写成结构化配方（名字 + 适用场景 + 具体步骤），随 system prompt
 * 注入。模型照配方用现有 18 个编辑工具组合执行 —— 对用户而言就是
 * "AI 学会了这一招"。换任何 OpenAI 兼容模型（智谱 / DeepSeek / Kimi /
 * GPT / 本地网关）都即刻生效，零额外成本。
 *
 * 每个技能的 steps 只允许引用 [EditTools] 真实存在的工具名与参数取向
 * （单测 EditSkillsTest.recipeToolsExist 强校验，防止配方里出现幻觉
 * 工具）。所有技能都保证可用现有工具落地 —— 做不到的（关键帧渐变、
 * J-cut 预听等）一律不收录。
 *
 * 本文件纯 Kotlin（无 Android 依赖），可单测；UI 侧用 nameZh/oneLinerZh
 * 显示（英语环境取 nameEn/oneLinerEn），prompt 侧双语全量注入。
 */

/** 技能分类（UI 分组标题用）。CUSTOM 仅用于用户导入的技能。 */
enum class SkillCategory(val id: String, val zh: String, val en: String) {
    RHYTHM("rhythm", "节奏", "Rhythm"),
    TRANSITION("transition", "转场", "Transition"),
    STORY("story", "叙事", "Story"),
    AUDIO("audio", "声音", "Audio"),
    TEXT("text", "字幕", "Text"),
    /** v1.6.8：用户导入技能的默认分组（内置技能永不使用）。 */
    CUSTOM("custom", "自定义", "Custom"),
}

/**
 * 一项剪辑技能。steps 是写给模型的英文配方（工具名 + 参数取向），
 * 模型须结合当前草稿状态灵活套用，而非机械照抄数字。
 */
data class EditSkill(
    val id: String,
    val nameZh: String,
    val nameEn: String,
    /** 中文口语别名 —— 用户不一定说标准名（"快剪一下" = 跳剪）。 */
    val aliasesZh: List<String> = emptyList(),
    val category: SkillCategory,
    /** UI 一句话说明（什么时候用）。 */
    val oneLinerZh: String,
    val oneLinerEn: String,
    /** 给模型的执行配方（英文，3-5 步）。 */
    val steps: List<String>,
) {
    /** prompt 里的匹配名：中文名 + 英文名 + 中文别名。 */
    val promptNames: String
        get() = (listOf(nameZh, nameEn) + aliasesZh).joinToString(" / ")
}

object EditSkills {

    val ALL: List<EditSkill> = listOf(
        // ------------------------------------------------ 节奏
        EditSkill(
            id = "jump_cut", nameZh = "跳剪", nameEn = "jump cut",
            aliasesZh = listOf("快剪", "跳切"),
            category = SkillCategory.RHYTHM,
            oneLinerZh = "删掉镜头里的停顿和废话，节奏立刻变紧凑",
            oneLinerEn = "Cut dead air and filler inside clips to tighten pacing.",
            steps = listOf(
                "Call get_draft_overview and locate clips that feel long or contain pauses/filler. "+
                    "WITH frames judge visually; WITHOUT frames and no user hint, ASK where the dead parts are instead of guessing.",
                "For each dead segment INSIDE a clip, split_clip at its start and end points.",
                "delete_clip the dead pieces - neighbors ripple closed automatically.",
                "Optionally finish with one seek to the first edit so the user sees the change.",
            ),
        ),
        EditSkill(
            id = "speed_ramp", nameZh = "快慢对比", nameEn = "speed ramp",
            aliasesZh = listOf("变速", "慢动作"),
            category = SkillCategory.RHYTHM,
            oneLinerZh = "关键动作放慢、前后铺垫加速，重点瞬间更有冲击",
            oneLinerEn = "Slow down the key action, speed up the lead-in/out around it.",
            steps = listOf(
                "get_draft_overview and find the key action clip - WITH frames pick it visually; "+
                    "WITHOUT frames, use the user's wording if they named the moment, else ASK which moment to slow down.",
                "split_clip just before and just after the key moment.",
                "set_clip_speed around 0.5 on the key moment, 1.5-2.0 on the lead-in and tail.",
                "get_draft_overview again to verify the new total duration, then seek to the ramp.",
            ),
        ),
        EditSkill(
            id = "slow_ending", nameZh = "结尾慢放", nameEn = "slow ending",
            aliasesZh = listOf("结尾慢动作"),
            category = SkillCategory.RHYTHM,
            oneLinerZh = "最后一段放慢收尾，留出余味",
            oneLinerEn = "Slow the last clip for an emotional, lingering ending.",
            steps = listOf(
                "get_draft_overview and take the last clip's id.",
                "set_clip_speed 0.5-0.75 on that clip.",
                "If music exists, trim_audio so it still ends with the video.",
            ),
        ),
        EditSkill(
            id = "breathing_room", nameZh = "留白停顿", nameEn = "breathing room",
            aliasesZh = listOf("停顿", "留白"),
            category = SkillCategory.RHYTHM,
            oneLinerZh = "在情绪点前后插一秒黑场，让观众喘口气",
            oneLinerEn = "Insert a short black pause before/after an emotional beat.",
            steps = listOf(
                "get_draft_overview and pick the beat the user described; if they did not say "+
                    "and no frames are provided, ASK which moment needs breathing room.",
                "add_black_clip with anchor_clip_id = the beat clip, position before/after, duration_sec 0.5-1.0.",
                "Optionally set_transition fade INTO the black clip for a fade-to-black feel.",
            ),
        ),
        // ------------------------------------------------ 转场
        EditSkill(
            id = "black_separator", nameZh = "黑场分隔", nameEn = "black separator",
            aliasesZh = listOf("黑屏分隔", "分段黑场"),
            category = SkillCategory.TRANSITION,
            oneLinerZh = "章节之间垫一小段黑屏，换场干净不突兀",
            oneLinerEn = "A short black gap between chapters for clean scene breaks.",
            steps = listOf(
                "get_draft_overview and identify the chapter/topic boundaries - WITH frames from "+
                    "visual context shifts; WITHOUT frames or a user hint, ASK where the chapters split.",
                "At each boundary add_black_clip with duration_sec 0.3-0.8 (anchor = clip before the boundary, position after).",
                "Optionally set_transition fade into the black clip at the strongest boundary only.",
            ),
        ),
        EditSkill(
            id = "soft_mix", nameZh = "柔和叠化", nameEn = "soft mix",
            aliasesZh = listOf("叠化", "淡入淡出"),
            category = SkillCategory.TRANSITION,
            oneLinerZh = "场景切换处加叠化，过渡丝滑不生硬",
            oneLinerEn = "Dissolve transitions at scene changes for smooth flow.",
            steps = listOf(
                "get_draft_overview and find scene-change boundaries (content or location jumps) - "+
                    "WITH frames spot them visually; WITHOUT frames rely on names or the user, and ASK if unclear.",
                "At each boundary set_transition on the RIGHT clip with a dissolve/fade style, duration_sec 0.5-0.8.",
                "Do NOT put transitions everywhere - only real scene changes; keep hard cuts inside a scene.",
            ),
        ),
        EditSkill(
            id = "hard_beats", nameZh = "硬切连击", nameEn = "hard beats",
            aliasesZh = listOf("连续快切", "快切"),
            category = SkillCategory.TRANSITION,
            oneLinerZh = "每段掐到半秒一秒，纯硬切连打，冲击力拉满",
            oneLinerEn = "Punchy sub-second clips back to back, hard cuts only.",
            steps = listOf(
                "get_draft_overview and delete_clip the weakest material first - judge from "+
                    "frames or the user's words; when blind, ASK which clips to drop.",
                "trim_clip each remaining clip to 0.5-1.5s keeping only its best frame range.",
                "remove_transition on every clip so all cuts are hard.",
                "seek to the start when done.",
            ),
        ),
        // ------------------------------------------------ 叙事
        EditSkill(
            id = "trim_fat", nameZh = "掐头去尾", nameEn = "trim fat",
            aliasesZh = listOf("去废话", "删头尾"),
            category = SkillCategory.STORY,
            oneLinerZh = "每段前后各删掉几秒晃动和废话，直入主题",
            oneLinerEn = "Trim the wobble/filler at each clip's head and tail.",
            steps = listOf(
                "get_draft_overview for fresh ids and durations.",
                "For every clip trim_clip: move new_start_sec in by 0.3-1s and new_end_sec in by 0.3-1s.",
                "Keep the first clip's head if it is an intentional opening shot.",
            ),
        ),
        EditSkill(
            id = "hook_first", nameZh = "高潮前置", nameEn = "hook first",
            aliasesZh = listOf("精彩前置", "钩子开头"),
            category = SkillCategory.STORY,
            oneLinerZh = "把最炸的一段挪到开头当钩子，前三秒留住人",
            oneLinerEn = "Move the strongest moment to the front as a hook.",
            steps = listOf(
                "get_draft_overview and pick the most striking clip - WITH frames choose visually; "+
                    "WITHOUT frames, if the user did not name it, ASK which clip is the highlight.",
                "move_clip that clip to to_index 0.",
                "Optionally add_text a 1.5s hook title over the new opening.",
                "seek to 0 so the user immediately sees the new opening.",
            ),
        ),
        EditSkill(
            id = "tight_cut", nameZh = "整片精简", nameEn = "tight cut",
            aliasesZh = listOf("压缩时长", "精简全片"),
            category = SkillCategory.STORY,
            oneLinerZh = "围绕主线删掉次要段落，把整片压到目标时长",
            oneLinerEn = "Cut secondary segments to hit a target duration.",
            steps = listOf(
                "get_draft_overview and ask/decide the target duration if the user did not give one.",
                "delete_clip segments that repeat or drift from the main thread - identified from "+
                    "frames or the user; when blind, ASK which segments matter most before deleting.",
                "trim_clip what remains; keep the story spine: opening - action - payoff.",
                "get_draft_overview to check the new total, then report the duration in plain words.",
            ),
        ),
        // ------------------------------------------------ 声音
        EditSkill(
            id = "music_bed", nameZh = "音乐压低当背景", nameEn = "music bed",
            aliasesZh = listOf("背景音乐", "音乐当背景"),
            category = SkillCategory.AUDIO,
            oneLinerZh = "有说话时把音乐压到三成，人声清楚又不冷场",
            oneLinerEn = "Duck the music under speech so voices stay clear.",
            steps = listOf(
                "get_draft_overview and find the audio (music) clips.",
                "set_audio_volume around 0.3 on music tracks.",
                "Leave video-clip voices at their original volume - do not mute them.",
            ),
        ),
        EditSkill(
            id = "mute_noise", nameZh = "静音杂音", nameEn = "mute noise",
            aliasesZh = listOf("去杂音", "消杂音"),
            category = SkillCategory.AUDIO,
            oneLinerZh = "把有风声、摩擦声的片段整体静音",
            oneLinerEn = "Mute clips with wind/handling noise.",
            steps = listOf(
                "get_draft_overview; ask the user WHICH clips sound noisy if it is not obvious.",
                "set_clip_volume mute=true on each noisy clip.",
                "Report which segments are now silent in plain words.",
            ),
        ),
        EditSkill(
            id = "music_ending", nameZh = "音乐收尾", nameEn = "music ending",
            aliasesZh = listOf("音乐对齐结尾"),
            category = SkillCategory.AUDIO,
            oneLinerZh = "把音乐裁到正好和画面一起结束，不拖不截",
            oneLinerEn = "Trim music so it ends with the video.",
            steps = listOf(
                "get_draft_overview; compare total video duration with each audio clip's end.",
                "trim_audio each music clip: set start_sec unchanged and duration_sec so it ends at the video's last frame.",
                "If music ends too early, it is fine - do not invent audio you cannot add.",
            ),
        ),
        // ------------------------------------------------ 字幕
        EditSkill(
            id = "title_card", nameZh = "片名标题", nameEn = "title card",
            aliasesZh = listOf("片头标题", "标题"),
            category = SkillCategory.TEXT,
            oneLinerZh = "开头压一行大字标题，视频立刻有作品感",
            oneLinerEn = "A big title over the opening frames.",
            steps = listOf(
                "get_draft_overview for the timeline start and an available font id.",
                "add_text: the title (from the user or project name), start_sec 0, duration_sec 1.5-2.5, font_size_ratio 0.10-0.14, x_ratio 0.5, y_ratio 0.45, color_hex #FFFFFF.",
                "seek to 0 so the user sees the title.",
            ),
        ),
        EditSkill(
            id = "highlight_caption", nameZh = "金句字幕", nameEn = "highlight caption",
            aliasesZh = listOf("金句", "高光字幕"),
            category = SkillCategory.TEXT,
            oneLinerZh = "在最精彩的一句上打字幕，情绪跟着字走",
            oneLinerEn = "Caption the strongest line/moment.",
            steps = listOf(
                "get_draft_overview and find the highlight moment (or ask the user which line).",
                "add_text over that time range: short text, font_size_ratio 0.07-0.09, x_ratio 0.5, y_ratio 0.78, color_hex #FFFFFF.",
                "Duration equals the moment; do not leave captions hanging after it ends.",
            ),
        ),
    )

    /** category id → 分类（导入 JSON 用；未知/缺失归 CUSTOM）。 */
    fun categoryById(id: String?): SkillCategory =
        SkillCategory.entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: SkillCategory.CUSTOM

    /**
     * 分组视图：内置 + 自定义（v1.6.8）。空分组不返回 —— CUSTOM 在
     * 无导入技能时不出现在 UI/prompt，与旧行为字节一致。
     */
    fun byCategory(custom: List<EditSkill> = emptyList()): List<Pair<SkillCategory, List<EditSkill>>> =
        SkillCategory.entries.map { c ->
            c to (ALL.filter { it.category == c } + custom.filter { it.category == c })
        }.filter { it.second.isNotEmpty() }

    /**
     * 按名字/别名找技能（UI 或控制器侧可用）。
     * v1.6.8：自定义优先 —— 同名时用户导入的技能覆盖内置（用户表达意愿
     * 优先于出厂默认），id 仍保证不撞（导入时查重拦截）。
     */
    fun find(query: String, custom: List<EditSkill> = emptyList()): EditSkill? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val match: (EditSkill) -> Boolean = { s ->
            q.equals(s.nameZh, true) || q.equals(s.nameEn, true) ||
                s.aliasesZh.any { q.contains(it, true) }
        }
        return custom.firstOrNull(match) ?: ALL.firstOrNull(match)
    }

    /**
     * 注入 system prompt 的紧凑技能块（英文指令 + 双语匹配名）。
     * 全量 15 技能约 1k tokens，相对概览 JSON 可忽略。
     *
     * v1.6.6 content-blind 规则（用户问“看不到画面怎么高潮前置”）：
     * 对话里可能带、也可能不带每段一帧的 vision 消息。带帧 → 用眼睛
     * 判断内容；不带帧 → 只能靠名字/时长/用户描述，需要内容判断时
     * 先问一句，禁止默默瞎猜。
     */
    fun promptBlock(custom: List<EditSkill> = emptyList()): String = buildString {
        append("Skill library - named techniques the user may ask for by name ")
        append("(match the names/aliases below in Chinese OR English). When a request ")
        append("matches a skill, follow its recipe with your tools, adapting the numbers ")
        append("to the actual draft; when it only partially matches, take the relevant ")
        append("steps and stay honest about the rest.\n\n")
        append("CONTENT-BLIND RULE: this conversation may or may not include one ")
        append("frame per clip (a vision user message right after the system prompt). ")
        append("WITH frames, judge content from them - which clip is the highlight, ")
        append("where scenes change, where a pause drags. WITHOUT frames you CANNOT ")
        append("see the footage at all: rely on names, durations and the user's words; ")
        append("when a choice needs content judgment (highlight clip, dead segments, ")
        append("chapter boundaries), ASK ONE short question in the user's language ")
        append("instead of guessing silently.\n")
        byCategory(custom).forEach { (cat, skills) ->
            append("\n[${cat.zh} ${cat.en}]\n")
            skills.forEach { s ->
                append("- ${s.promptNames}: ${s.oneLinerEn}\n")
                s.steps.forEachIndexed { i, step -> append("  $i. $step\n") }
            }
        }
        if (custom.isNotEmpty()) {
            append("\nThe [${SkillCategory.CUSTOM.zh} ${SkillCategory.CUSTOM.en}] ")
            append("entries above were imported by the user - treat them exactly like ")
            append("the built-in skills.\n")
        }
    }
}
