package com.RooMCutNext.ai

import com.RooMCutNext.core.model.Project
import org.junit.Assert.assertEquals
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

    /** steps 中允许出现的参数名（非工具名）。 */
    private val paramWhitelist = setOf(
        "anchor_clip_id", "duration_sec", "start_sec", "new_start_sec", "new_end_sec",
        "to_index", "font_size_ratio", "x_ratio", "y_ratio", "color_hex",
    )

    /** EditTools.T 的全部工具名（反射收集，新增工具自动纳入校验）。 */
    private val toolNames: Set<String> =
        EditTools.T::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.get(null) as String }
            .toSet()

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
        SkillCategory.entries.forEach { c ->
            assertTrue("category ${c.id} empty", byCat[c].orEmpty().isNotEmpty())
        }
        assertEquals(SkillCategory.entries.size, EditSkills.byCategory().size)
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
}
