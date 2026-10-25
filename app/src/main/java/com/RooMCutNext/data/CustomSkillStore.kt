package com.RooMCutNext.data

import android.content.Context
import com.RooMCutNext.ai.CustomSkillCodec
import com.RooMCutNext.ai.EditSkill
import com.RooMCutNext.ai.EditSkills
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * v1.6.8 自定义技能存储 —— 每技能一个 JSON 文件（filesDir/custom_skills/
 * &lt;id&gt;.json）。
 *
 * 选文件而非 SharedPreferences 单串：删除单个技能 = 删一个文件（原子、
 * 无读改写竞争）；覆盖更新 = 同名文件整体重写；损坏文件跳过不拖垮整库
 * （手动改坏一个文件最多丢那一个技能）。
 *
 * id 在 [CustomSkillCodec] 已被规约为 [a-z0-9_-]{2,40}，天然是安全
 * 文件名。导入时的总量/查重校验在 codec + ViewModel 层完成。
 */
class CustomSkillStore(context: Context) {

    private val dir: File = File(context.filesDir, "custom_skills")

    /** 当前全部自定义技能（按名称排序，UI 稳定顺序；损坏文件静默跳过）。 */
    fun list(): List<EditSkill> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f -> runCatching { parseSkill(f.readText()) }.getOrNull() }
            .sortedBy { it.nameZh }
    }

    /** 写入/覆盖一个技能（同 id 文件被整体重写）。 */
    fun save(skill: EditSkill) {
        dir.mkdirs()
        File(dir, "${skill.id}.json").writeText(CustomSkillCodec.serialize(skill))
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}

/** 与 [CustomSkillCodec.serialize] 对称的单技能解析（存储层读回用）。 */
private fun parseSkill(text: String): EditSkill? {
    val obj = Json.parseToJsonElement(text).jsonObject
    val id = obj.str("id") ?: return null
    val nameZh = obj.str("nameZh") ?: return null
    val nameEn = obj.str("nameEn") ?: return null
    val steps = (obj["steps"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()
    if (id.isBlank() || steps.isEmpty()) return null
    return EditSkill(
        id = id.trim(),
        nameZh = nameZh,
        nameEn = nameEn,
        aliasesZh = (obj["aliasesZh"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty(),
        category = EditSkills.categoryById(obj.str("category")),
        oneLinerZh = obj.str("oneLinerZh") ?: "",
        oneLinerEn = obj.str("oneLinerEn") ?: "",
        steps = steps,
    )
}

private fun kotlinx.serialization.json.JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
