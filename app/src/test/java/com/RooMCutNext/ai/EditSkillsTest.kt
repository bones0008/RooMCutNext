package com.RooMCutNext.ai

import com.RooMCutNext.core.model.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6.5 剪辑技能库测试。
 *
 * 核心防线：**配方不得引用幻觉工具** —— steps 里出现的每个
 * snake_case 标识符，要么是 [EditTools] 真实存在的工具名，要么在
 * 参数白名单里。技能库一旦写出现实中不存在的工具调用（例如
 * "fade_out_clip"），模型照做只会拿到满屏 error，此测试第一时间拦下。
 */
class EditSkillsTest {

    /** steps 中允许出现的参数名（非工具名）—— 与生产校验同源。 */
    private val paramWhitelist: Set<String> = SKILL_PARAM_WHITELIST

    /** EditTools 全部工具名（生产校验同源，新增工具自动纳入）。 */
    private val toolNames: Set<String> = TOOL_NAMES

    @Test
    fun skillIdsAreUnique() {
        val ids = EditSkills.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everySkillIsWellFormed() {
        assertTrue(EditSkills.ALL.isNotEmpty())
        EditSkills.ALL.forEach { s ->
            assertTrue("skill ${s.id}: nameZh blank", s.nameZh.isNotBlank())
            assertTrue("skill ${s.id}: nameEn blank", s.nameEn.isNotBlank())
            assertTrue("skill ${s.id}: oneLinerZh blank", s.oneLinerZh.isNotBlank())
            assertTrue("skill ${s.id}: oneLinerEn blank", s.oneLinerEn.isNotBlank())
            assertTrue("skill ${s.id}: needs >=2 steps", s.steps.size >= 2)
        }
    }

    @Test
    fun allCategoriesArePopulated() {
        val byCat = EditSkills.byCategory().toMap()
        // v1.6.8：CUSTOM 无导入技能时不出现（空分组过滤）。
        SkillCategory.entries.filter { it != SkillCategory.CUSTOM }.forEach { c ->
            assertTrue("category ${c.id} empty", byCat[c].orEmpty().isNotEmpty())
        }
        assertEquals(SkillCategory.entries.size - 1, EditSkills.byCategory().size)
        assertEquals(EditSkills.ALL.size, EditSkills.byCategory().sumOf { it.second.size })
    }

    @Test
    fun promptBlockContainsEverySkillName() {
        val block = EditSkills.promptBlock()
        assertTrue(block.startsWith("Skill library"))
        EditSkills.ALL.forEach { s ->
            assertTrue("missing nameZh ${s.nameZh}", block.contains(s.nameZh))
            assertTrue("missing nameEn ${s.nameEn}", block.contains(s.nameEn))
            s.aliasesZh.forEach { a -> assertTrue("missing alias $a", block.contains(a)) }
        }
    }

    @Test
    fun recipeToolsExist() {
        val snake = Regex("[a-z]+(?:_[a-z]+)+")
        EditSkills.ALL.forEach { s ->
            s.steps.forEach { step ->
                snake.findAll(step.lowercase()).map { it.value }.forEach { token ->
                    assertTrue(
                        "skill ${s.id}: step references unknown identifier '$token' " +
                            "(not a tool, not a whitelisted param)",
                        token in toolNames || token in paramWhitelist,
                    )
                }
            }
        }
    }

    @Test
    fun findMatchesByNameAndAlias() {
        assertEquals("jump_cut", EditSkills.find("跳剪")?.id)
        assertEquals("jump_cut", EditSkills.find("快剪")?.id)
        assertEquals("speed_ramp", EditSkills.find("Speed Ramp")?.id)
        assertNotNull(EditSkills.find("背景音乐"))
        assertEquals(null, EditSkills.find(""))
        assertEquals(null, EditSkills.find("不存在的技能xyz"))
    }

    @Test
    fun systemPromptInjectsSkillLibrary() {
        val tools = EditTools(
            fonts = listOf("system" to "System"),
            luts = listOf("builtin:warm" to "Warm"),
            idFactory = { "x" },
        )
        val p = Project(
            id = "p", name = "P", assets = emptyList(),
            clips = emptyList(), texts = emptyList(), audios = emptyList(),
        )
        val prompt = tools.systemPrompt(EditTools.Draft(p), playheadUs = 0L)
        assertTrue(prompt.contains("Skill library"))
        assertTrue(prompt.contains("跳剪"))
        assertTrue(prompt.contains("jump cut"))
    }

    /** v1.6.8：systemPrompt 接受自定义技能并随技能库注入。 */
    @Test
    fun systemPromptInjectsCustomSkills() {
        val tools = EditTools(
            fonts = listOf("system" to "System"),
            luts = listOf("builtin:warm" to "Warm"),
            idFactory = { "x" },
        )
        val p = Project(
            id = "p", name = "P", assets = emptyList(),
            clips = emptyList(), texts = emptyList(), audios = emptyList(),
        )
        val prompt = tools.systemPrompt(
            EditTools.Draft(p), playheadUs = 0L,
            customSkills = listOf(customSkill(nameZh = "专属开场")),
        )
        assertTrue(prompt.contains("专属开场"))
        assertTrue(prompt.contains("自定义 Custom"))
        // 默认参（空自定义）不引入 CUSTOM 分组。
        assertFalse(tools.systemPrompt(EditTools.Draft(p), 0L).contains("自定义 Custom"))
    }

    // ------------------------------------------------------------------
    // v1.6.6 画面感知：content-blind 规则（无帧时先问，不瞎猜）
    // ------------------------------------------------------------------

    @Test
    fun contentBlindRuleInPromptBlock() {
        val block = EditSkills.promptBlock()
        assertTrue(block.contains("CONTENT-BLIND RULE"))
        assertTrue(block.contains("WITHOUT frames you CANNOT"))
        assertTrue(block.contains("ASK ONE short question"))
    }

    /** 需要内容判断的技能：盲态（无帧/无用户提示）时必须含“先问”步骤。 */
    @Test
    fun judgementSkillsAskWhenBlind() {
        val judgementIds = setOf(
            "jump_cut", "speed_ramp", "breathing_room", "black_separator",
            "soft_mix", "hard_beats", "hook_first", "tight_cut",
            "mute_noise", "highlight_caption",
        )
        EditSkills.ALL.filter { it.id in judgementIds }.forEach { s ->
            assertTrue(
                "skill ${s.id}: judgement recipe lacks ask-first fallback",
                s.steps.any { it.contains(Regex("(?i)ask")) },
            )
        }
    }

    // ------------------------------------------------------------------
    // v1.6.8 自定义技能：分组 / 查找 / prompt 注入
    // ------------------------------------------------------------------

    private fun customSkill(id: String = "my_cut", nameZh: String = "我的招式") = EditSkill(
        id = id, nameZh = nameZh, nameEn = "My Move",
        aliasesZh = listOf("招式"), category = SkillCategory.CUSTOM,
        oneLinerZh = "自定义测试技能", oneLinerEn = "A custom test skill",
        steps = listOf("get_draft_overview first.", "seek to 0."),
    )

    @Test
    fun customSkillsAppearInCustomGroupOnlyWhenPresent() {
        // 空自定义：无 CUSTOM 分组（旧行为字节一致）。
        assertFalse(EditSkills.byCategory().any { it.first == SkillCategory.CUSTOM })
        // 有自定义：CUSTOM 分组出现且只含它。
        val byCat = EditSkills.byCategory(listOf(customSkill())).toMap()
        assertEquals(listOf("my_cut"), byCat[SkillCategory.CUSTOM]?.map { it.id })
        assertEquals(
            EditSkills.ALL.size + 1,
            EditSkills.byCategory(listOf(customSkill())).sumOf { it.second.size },
        )
    }

    @Test
    fun promptBlockInjectsCustomSkills() {
        val block = EditSkills.promptBlock(listOf(customSkill(nameZh = "专属开场")))
        assertTrue(block.contains("自定义 Custom"))
        assertTrue(block.contains("专属开场"))
        assertTrue(block.contains("My Move"))
        assertTrue(block.contains("imported by the user"))
        // 空自定义：不该出现导入注记（与旧输出字节一致）。
        assertFalse(EditSkills.promptBlock().contains("imported by the user"))
    }

    @Test
    fun findPrefersCustomOverBuiltin() {
        val sameNameAsBuiltin = EditSkill(
            id = "my_jump", nameZh = "跳剪", nameEn = "my jump",
            category = SkillCategory.CUSTOM,
            oneLinerZh = "用户版", oneLinerEn = "user version",
            steps = listOf("seek to 0."),
        )
        assertEquals("my_jump", EditSkills.find("跳剪", listOf(sameNameAsBuiltin))?.id)
        assertEquals("my_cut", EditSkills.find("我的招式", listOf(customSkill()))?.id)
        assertEquals("my_cut", EditSkills.find("招式", listOf(customSkill()))?.id)
        // 无自定义时回落内置。
        assertEquals("jump_cut", EditSkills.find("跳剪")?.id)
    }
}
