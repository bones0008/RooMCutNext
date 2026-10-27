package com.RooMCutNext.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6.8 自定义技能导入测试。
 *
 * 防线（与内置技能同标准）：
 *  1. steps 引用幻觉工具（如 fade_out_clip）→ 导入即拒，错误消息含
 *     该标识符 —— 坏配方永远进不了 system prompt；
 *  2. id 与内置/已存/同批次查重；
 *  3. 宽容解析（三种形状）+ 字段缺省回退（nameEn←→nameZh 等）；
 *  4. serialize 与 parse 互逆（存储往返不丢字段）；
 *  5. SAMPLE_JSON（“填入示例”）本身必须可导入 —— 用户照着改的
 *     模板坏了就是事故。
 */
class CustomSkillCodecTest {

    private fun minimalJson(vararg extra: Pair<String, String>): String {
        val fields = buildString {
            append("\"id\":\"my_cut\",\"nameZh\":\"我的招式\",\"nameEn\":\"My Move\",")
            append("\"oneLinerZh\":\"一句话\",\"oneLinerEn\":\"One liner\",")
            append("\"steps\":[\"get_draft_overview first.\",\"seek to 0.\"]")
            extra.forEach { append(",\"${it.first}\":${it.second}") }
        }
        return "{$fields}"
    }

    // ------------------------------------------------------------------
    // 三种输入形状
    // ------------------------------------------------------------------

    @Test
    fun parsesSingleObject() {
        val r = CustomSkillCodec.parse(minimalJson())
        assertTrue(r.toString(), r is CustomSkillCodec.Result.Success)
        val s = (r as CustomSkillCodec.Result.Success).skills.single()
        assertEquals("my_cut", s.id)
        assertEquals("我的招式", s.nameZh)
        assertEquals(SkillCategory.CUSTOM, s.category)
        assertEquals(2, s.steps.size)
    }

    @Test
    fun parsesArrayAndWrapped() {
        val arr = "[${minimalJson()},${minimalJson().replace("my_cut", "second_cut")}]"
        assertEquals(2, (CustomSkillCodec.parse(arr) as CustomSkillCodec.Result.Success).skills.size)
        val wrapped = """{"skills":[${minimalJson()}]}"""
        assertEquals(1, (CustomSkillCodec.parse(wrapped) as CustomSkillCodec.Result.Success).skills.size)
    }

    // ------------------------------------------------------------------
    // 缺省回退与 category 映射
    // ------------------------------------------------------------------

    @Test
    fun fieldFallbacks() {
        val json = """{
            "nameEn": "Auto Derived",
            "oneLinerEn": "Only English liner",
            "steps": ["seek to 1."]
        }""".trimIndent()
        val r = CustomSkillCodec.parse(json) as CustomSkillCodec.Result.Success
        val s = r.skills.single()
        assertEquals("auto_derived", s.id) // id 从 nameEn 派生
        assertEquals("Auto Derived", s.nameZh) // nameZh 缺省回退 nameEn
        assertEquals("Only English liner", s.oneLinerZh) // oneLinerZh 回退 En
    }

    @Test
    fun categoryMapping() {
        fun catOf(json: String) =
            (CustomSkillCodec.parse(json) as CustomSkillCodec.Result.Success).skills.single().category
        val base = """"id":"c1","nameZh":"名","oneLinerEn":"x","steps":["seek to 0."]"""
        assertEquals(SkillCategory.STORY, catOf("""{$base,"category":"story"}"""))
        assertEquals(SkillCategory.RHYTHM, catOf("""{$base,"category":" RHYTHM "}"""))
        assertEquals(SkillCategory.CUSTOM, catOf("""{$base,"category":"nonexistent"}"""))
        assertEquals(SkillCategory.CUSTOM, catOf("""{$base}"""))
    }

    // ------------------------------------------------------------------
    // 拒绝路径（错误消息面向用户，含定位信息）
    // ------------------------------------------------------------------

    private fun errorMessage(json: String): String =
        (CustomSkillCodec.parse(json) as CustomSkillCodec.Result.Error).message

    @Test
    fun rejectsInvalidJson() {
        // v1.6.20 双格式路由：以 { 开头但坏了的仍是 JSON 错误；
        // 纯文本不再报"JSON 不合法"而是按 Markdown 给出缺名指引。
        assertTrue(errorMessage("{\"broken\":").contains("JSON"))
        assertTrue(errorMessage("not json at all").contains("技能名"))
        assertTrue(CustomSkillCodec.parse("   ") is CustomSkillCodec.Result.Error)
    }

    @Test
    fun rejectsMissingNamesAndSteps() {
        assertTrue(errorMessage("""{"id":"x1","steps":["seek to 0."]}""").contains("nameZh"))
        assertTrue(
            errorMessage("""{"nameZh":"名","id":"x2","oneLinerEn":"x"}""").contains("steps"),
        )
        assertTrue(
            errorMessage("""{"nameZh":"名","id":"x3","oneLinerEn":"x","steps":[""]}""").contains("steps"),
        )
    }

    @Test
    fun rejectsBadId() {
        val bad = errorMessage("""{"id":"Bad ID!","nameZh":"名","steps":["seek to 0."]}""")
        assertTrue(bad.contains("id"))
        // 纯中文名且无 id：派生失败要提示补 id，而不是报空 id。
        val derived = errorMessage("""{"nameZh":"纯中文","steps":["seek to 0."]}""")
        assertTrue(derived.contains("id"))
    }

    @Test
    fun rejectsHallucinatedTool() {
        val json = """
            {"id":"bad_tool","nameZh":"坏配方","oneLinerEn":"x",
             "steps":["call fade_out_clip on the last clip."]}
        """.trimIndent()
        val msg = errorMessage(json)
        assertTrue("message should name the unknown tool: $msg", msg.contains("fade_out_clip"))
    }

    @Test
    fun rejectsDuplicates() {
        // 与内置 id 撞。
        assertTrue(errorMessage(minimalJson().replace("my_cut", "jump_cut")).contains("内置"))
        // 同批次重复。
        val dup = "[${minimalJson()},${minimalJson()}]"
        assertTrue(errorMessage(dup).contains("重复"))
    }

    @Test
    fun sameIdAsExistingIsOverwriteWithWarning() {
        val existing = listOf(
            EditSkill(
                id = "my_cut", nameZh = "旧版", nameEn = "old",
                category = SkillCategory.CUSTOM,
                oneLinerZh = "旧", oneLinerEn = "old",
                steps = listOf("seek to 0."),
            ),
        )
        val r = CustomSkillCodec.parse(minimalJson(), existing = existing) as CustomSkillCodec.Result.Success
        assertEquals(1, r.skills.size)
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings.single().contains("覆盖"))
    }

    @Test
    fun enforcesCountAndLengthLimits() {
        val many = (1..51).joinToString(",") { i ->
            """{"id":"s$i","nameZh":"名$i","steps":["seek to 0."]}"""
        }
        assertTrue(CustomSkillCodec.parse("[$many]") is CustomSkillCodec.Result.Error)

        val tooManySteps = (1..7).joinToString(",") { "\"step $it\"" }
        val r = CustomSkillCodec.parse(
            """{"id":"big","nameZh":"名","oneLinerEn":"x","steps":[$tooManySteps]}""",
        )
        assertTrue(r is CustomSkillCodec.Result.Error)
        assertTrue((r as CustomSkillCodec.Result.Error).message.contains("steps"))
    }

    // ------------------------------------------------------------------
    // 序列化往返 + 示例
    // ------------------------------------------------------------------

    @Test
    fun serializeRoundTrip() {
        val original = (CustomSkillCodec.parse(minimalJson()) as CustomSkillCodec.Result.Success).skills.single()
        val again = (CustomSkillCodec.parse(CustomSkillCodec.serialize(original)) as CustomSkillCodec.Result.Success).skills.single()
        assertEquals(original, again)
    }

    @Test
    fun sampleJsonParsesCleanly() {
        val r = CustomSkillCodec.parse(CustomSkillCodec.SAMPLE_JSON)
        assertTrue("SAMPLE_JSON must import cleanly: $r", r is CustomSkillCodec.Result.Success)
        val s = (r as CustomSkillCodec.Result.Success).skills.single()
        assertEquals("vlog_opener", s.id)
        assertEquals(SkillCategory.STORY, s.category)
        assertTrue(s.aliasesZh.isNotEmpty())
    }

    // ------------------------------------------------------------------
    // v1.6.20 SKILL.md（Markdown）路径 —— 入口仍是 parse，格式自动识别
    // ------------------------------------------------------------------

    @Test
    fun markdownInputRoutesToSkillMdParser() {
        val md = """
            ---
            name: 卡点视频
            name_en: beat sync
            category: rhythm
            ---

            1. get_draft_overview 查看时间线
            2. trim_clip 按节拍对齐
            3. seek 到开头预览
        """.trimIndent()
        val r = CustomSkillCodec.parse(md)
        assertTrue(r.toString(), r is CustomSkillCodec.Result.Success)
        val s = (r as CustomSkillCodec.Result.Success).skills.single()
        assertEquals("卡点视频", s.nameZh)
        assertEquals(SkillCategory.RHYTHM, s.category)
        assertTrue(s.id.startsWith("user_"))
        assertTrue(s.steps.size == 3)
    }

    @Test
    fun sampleMdParsesCleanlyThroughCodec() {
        val r = CustomSkillCodec.parse(SkillMd.SAMPLE_MD)
        assertTrue("SAMPLE_MD must import cleanly: $r", r is CustomSkillCodec.Result.Success)
        assertEquals(1, (r as CustomSkillCodec.Result.Success).skills.size)
    }

    @Test
    fun jsonPathStillWorksUnchanged() {
        // 双格式不能伤及存量：JSON 单对象/数组/包装三形状照旧。
        assertTrue(CustomSkillCodec.parse(minimalJson()) is CustomSkillCodec.Result.Success)
        val arr = "[${minimalJson()},${minimalJson().replace("my_cut", "second_cut")}]"
        assertEquals(2, (CustomSkillCodec.parse(arr) as CustomSkillCodec.Result.Success).skills.size)
    }

    @Test
    fun markdownErrorsSurfaceInChinese() {
        val badTool = "---\nname: 坏工具\n---\n\n- call fade_out_clip on it"
        val r1 = CustomSkillCodec.parse(badTool) as CustomSkillCodec.Result.Error
        assertTrue(r1.message.contains("fade_out_clip"))

        val clash = "---\nname: 跳剪\n---\n\n- get_draft_overview 后 seek"
        val r2 = CustomSkillCodec.parse(clash) as CustomSkillCodec.Result.Error
        assertTrue(r2.message.contains("跳剪"))

        val noName = "只有正文没有名字的普通 markdown。"
        val r3 = CustomSkillCodec.parse(noName) as CustomSkillCodec.Result.Error
        assertTrue(r3.message.contains("技能名"))
    }

    @Test
    fun markdownReimportSameNameWarnsOverwrite() {
        val md = "---\nname: 循环导入\n---\n\n- get_draft_overview 后 seek"
        val first = CustomSkillCodec.parse(md) as CustomSkillCodec.Result.Success
        val existing = first.skills
        val second = CustomSkillCodec.parse(md, existing = existing) as CustomSkillCodec.Result.Success
        assertTrue(second.warnings.single().contains("覆盖"))
    }
}
