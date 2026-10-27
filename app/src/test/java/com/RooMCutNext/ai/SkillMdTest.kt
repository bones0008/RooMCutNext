package com.RooMCutNext.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6.20 SKILL.md（Markdown + frontmatter）解析测试。
 *
 * 设计要点（对照用户诉求"技能文件不该逼人写 JSON"）：
 *  - 业界 Agent Skills 形态 = Markdown，frontmatter 只放元数据；
 *  - 解析宽松友好：frontmatter 可省略、# 标题可当名字、列表或段落皆可成步骤；
 *  - 与 JSON 路径同一防线：防幻觉工具、内置重名拒绝、长度上限。
 */
class SkillMdTest {

    private val full = """
        ---
        name: 卡点视频
        name_en: beat sync
        aliases: 卡点, 踩点、beat；节奏切换
        category: rhythm
        one_liner: 画面踩着音乐节拍切换
        one_liner_en: Cut on the beat.
        ---

        拿到音乐后：

        1. get_draft_overview 查看时间线
        2. trim_clip 按节拍对齐
        3. seek 到开头预览
    """.trimIndent()

    @Test
    fun `parses full frontmatter and numbered steps`() {
        val p = SkillMd.parse(full)
        val s = p.skill
        assertNotNull("should parse", s)
        assertNull(p.error)
        s!!
        assertEquals("卡点视频", s.nameZh)
        assertEquals("beat sync", s.nameEn)
        assertEquals(listOf("卡点", "踩点", "beat", "节奏切换"), s.aliasesZh)
        assertEquals(SkillCategory.RHYTHM, s.category)
        assertEquals("画面踩着音乐节拍切换", s.oneLinerZh)
        assertEquals("Cut on the beat.", s.oneLinerEn)
        assertEquals(3, s.steps.size)
        assertTrue(s.steps[0].startsWith("get_draft_overview"))
        assertTrue(s.steps[2].startsWith("seek"))
    }

    @Test
    fun `frontmatter optional - heading becomes the name`() {
        val md = """
            # 静音彩蛋
            一个安静的小技能

            - set_clip_volume mute=true
            - 报告结果
        """.trimIndent()
        val s = SkillMd.parse(md).skill
        assertNotNull(s)
        s!!
        assertEquals("静音彩蛋", s.nameZh)
        assertEquals(SkillCategory.CUSTOM, s.category) // 无 category → 自定义
        assertEquals(2, s.steps.size)
        // # 标题行不混进步骤；标题下第一行成为 one_liner 兜底。
        assertEquals("一个安静的小技能", s.oneLinerZh)
        assertEquals("set_clip_volume mute=true", s.steps[0])
    }

    @Test
    fun `dash bullets and paragraphs both become steps`() {
        val s1 = SkillMd.parse(
            "---\nname: 甲\n---\n\n- 第一招\n- 第二招"
        ).skill!!
        assertEquals(listOf("第一招", "第二招"), s1.steps)

        val para = SkillMd.parse(
            "---\nname: 乙\n---\n\n先做这步。\n\n再做那步。"
        ).skill!!
        assertEquals(listOf("先做这步。", "再做那步。"), para.steps)

        // 没有名字来源（无 frontmatter / 无标题）→ 报错而不是猜。
        assertEquals(SkillMd.Error.MISSING_NAME, SkillMd.parse("只有正文，没有名字。").error)
    }

    @Test
    fun `errors - missing name, empty steps, unclosed frontmatter`() {
        assertEquals(SkillMd.Error.MISSING_NAME, SkillMd.parse("只是普通文本，没有名字。").error)
        assertEquals(SkillMd.Error.EMPTY_STEPS, SkillMd.parse("---\nname: 空\n---\n").error)
        assertEquals(SkillMd.Error.BAD_FRONT_MATTER, SkillMd.parse("---\nname: 没闭合\n").error)
        assertEquals(SkillMd.Error.EMPTY_STEPS, SkillMd.parse("   ").error)
    }

    @Test
    fun `category maps by id, zh label and en label, unknown falls to CUSTOM`() {
        fun cat(raw: String?) = SkillMd.parse(
            "---\nname: 某技能\ncategory: ${raw ?: ""}\n---\n\n- 走一步"
        ).skill!!.category
        assertEquals(SkillCategory.AUDIO, cat("audio"))
        assertEquals(SkillCategory.AUDIO, cat("声音"))
        assertEquals(SkillCategory.TRANSITION, cat("Transition"))
        assertEquals(SkillCategory.STORY, cat("story"))
        assertEquals(SkillCategory.TEXT, cat("字幕"))
        assertEquals(SkillCategory.CUSTOM, cat("nonexistent"))
        assertEquals(SkillCategory.CUSTOM, cat(null))
    }

    @Test
    fun `caps - steps truncated to 12 and each step to 400 chars`() {
        val many = (1..20).joinToString("\n") { "- 第 $it 步 " + "x".repeat(50) }
        val s = SkillMd.parse("---\nname: 上限测试\n---\n\n$many").skill!!
        assertEquals(SkillMd.MAX_STEPS, s.steps.size)
        assertTrue(s.steps.all { it.length <= SkillMd.MAX_STEP_CHARS })
        assertTrue(s.steps[11].startsWith("第 12 步"))

        val longName = "超".repeat(40)
        val n = SkillMd.parse("---\nname: $longName\n---\n\n- 步").skill!!
        assertEquals(SkillMd.MAX_NAME_CHARS, n.nameZh.length)
    }

    @Test
    fun `stable id - same name maps to same id, case and spaces ignored`() {
        assertEquals(SkillMd.idFor("卡点视频"), SkillMd.idFor(" 卡点视频 "))
        assertEquals(SkillMd.idFor("Beat"), SkillMd.idFor("beat"))
        assertTrue(SkillMd.idFor("A") != SkillMd.idFor("B"))
        val s = SkillMd.parse(full).skill!!
        assertTrue(s.id.startsWith("user_"))
    }

    @Test
    fun `crlf input parses identically`() {
        assertEquals(SkillMd.parse(full).skill, SkillMd.parse(full.replace("\n", "\r\n")).skill)
    }

    @Test
    fun `extra frontmatter keys are ignored, description aliases one_liner`() {
        val md = "---\nname: 宽容解析\nunknown_key: whatever\ndescription: 描述别名\n---\n\n- 步骤一"
        val s = SkillMd.parse(md).skill!!
        assertEquals("宽容解析", s.nameZh)
        assertEquals("描述别名", s.oneLinerZh) // description 是 one_liner 的别名键
        assertEquals(1, s.steps.size)
    }

    // ------------------------------------------------------------------
    // 防线：与 JSON 路径同一标准
    // ------------------------------------------------------------------

    @Test
    fun `hallucinated tool is rejected with the offending token`() {
        val md = "---\nname: 幻觉检查\n---\n\n- call fade_out_clip on the first clip"
        val p = SkillMd.parse(md)
        assertNull(p.skill)
        assertEquals(SkillMd.Error.HALLUCINATED_TOOL, p.error)
        assertEquals("fade_out_clip", p.detail)
    }

    @Test
    fun `builtin name clash is rejected with the clashing name`() {
        val md = "---\nname: 跳剪\n---\n\n- get_draft_overview 后 split_clip"
        val p = SkillMd.parse(md)
        assertNull(p.skill)
        assertEquals(SkillMd.Error.CLASH_BUILTIN, p.error)
        assertEquals("跳剪", p.detail)

        // 别名冲突同样拒绝。
        val aliasMd = "---\nname: 新招式\naliases: 快剪\n---\n\n- get_draft_overview 后 seek"
        assertEquals(SkillMd.Error.CLASH_BUILTIN, SkillMd.parse(aliasMd).error)
    }

    @Test
    fun `sample md is importable`() {
        // 对话框「填入示例」的模板坏了就是事故 —— 必须能直接过解析。
        val p = SkillMd.parse(SkillMd.SAMPLE_MD)
        assertNull("sample must parse: ${p.error} ${p.detail}", p.error)
        val s = p.skill!!
        assertEquals("卡点视频", s.nameZh)
        assertTrue(s.steps.isNotEmpty())
    }
}
