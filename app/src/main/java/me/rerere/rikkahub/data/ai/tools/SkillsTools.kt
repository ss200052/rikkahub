package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.PromptRuntime
import me.rerere.rikkahub.data.ai.PromptSegments
import me.rerere.rikkahub.data.skills.SkillFrontmatterParser
import me.rerere.rikkahub.data.skills.SkillMetadata
import me.rerere.rikkahub.data.skills.SkillPaths

// 与 Agent Skills 规范的 description 上限一致
private const val MAX_SKILL_DESCRIPTION_LENGTH = 1024

fun createSkillTools(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
): List<Tool> {
    val available = allSkills.filter { it.name in enabledSkills }
    if (available.isEmpty()) return emptyList()

    // 技能清单是运行期数据：收进 {{skills_block}}，说明文字保持可改写
    val skillsVariables: () -> Map<String, String> = {
        mapOf(
            "skills_block" to buildString {
                appendLine("<available_skills>")
                available.forEach { skill ->
                    appendLine("  <skill>")
                    // 技能可能来自第三方导入，转义并限长，防止 name/description 闭合标签注入任意系统提示
                    appendLine("    <name>${skill.name.escapeXml()}</name>")
                    appendLine(
                        "    <description>${skill.description.take(
                            MAX_SKILL_DESCRIPTION_LENGTH
                        ).escapeXml()}</description>"
                    )
                    appendLine("  </skill>")
                }
                append("</available_skills>")
            },
        )
    }

    return listOf(
        Tool(
            name = "use_skill",
            description = """
                Load and apply a skill to get specialized instructions or capabilities.
                Call this tool when the user's request matches one of the available skills.
            """.trimIndent(),
            systemPrompt = { _, _ -> "" },
            systemPromptTemplate = PromptSegments.SKILLS_SKELETON,
            systemPromptVariables = skillsVariables,
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "The name of the skill to use")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional relative path to a file inside the skill directory. Omit to read the default SKILL.md instructions. Only use paths extracted from Markdown links in the SKILL.md content. Do NOT guess or infer paths."
                            )
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = {
                val name = it.jsonObject["name"]?.jsonPrimitive?.content
                    ?: error(PromptRuntime.text("common.nameRequired"))
                // 模型可能照抄系统提示中转义后的名称，两种形式都接受
                val skill = available.firstOrNull { skill -> skill.name == name || skill.name.escapeXml() == name }
                    ?: error(
                        PromptRuntime.text(
                            "skill.notAvailable",
                            name,
                            available.joinToString { it.name },
                        ),
                    )
                val path = it.jsonObject["path"]?.jsonPrimitive?.content
                val content = if (path.isNullOrBlank()) {
                    require(skill.skillFile.exists()) { PromptRuntime.text("skill.notFound", name) }
                    SkillFrontmatterParser.extractBody(skill.skillFile.readText())
                } else {
                    val target = SkillPaths.resolveSkillFile(skill.skillDir, path)
                        ?: error(PromptRuntime.text("skill.pathOutside", path))
                    require(target.exists()) { PromptRuntime.text("skill.fileNotFound", path, name) }
                    target.readText()
                }
                listOf(UIMessagePart.Text(content))
            }
        )
    )
}

private fun String.escapeXml(): String = buildString(length) {
    for (c in this@escapeXml) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(c)
        }
    }
}
