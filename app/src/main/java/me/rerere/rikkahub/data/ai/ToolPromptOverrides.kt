package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

/** 工具描述在覆盖表里的键 */
fun toolDescriptionId(toolName: String): String = "tool.$toolName.description"

/** 工具某个参数说明在覆盖表里的键 */
fun toolParamDescriptionId(toolName: String, paramName: String): String =
    "tool.$toolName.param.$paramName"

/**
 * 把用户对工具文案的改写套到工具定义上。
 *
 * 工具名不参与改写：模型调用工具要靠名字匹配，改了就用不了。
 * 只有描述和参数说明可以被替换，没配改写的保持原样（英文基线）。
 */
internal fun Tool.withPromptOverrides(overrides: Map<String, String>): Tool {
    if (overrides.isEmpty()) return this

    val descOverride = overrides[toolDescriptionId(name)]
    val paramPrefix = "tool.$name.param."
    val paramOverrides = overrides.filterKeys { it.startsWith(paramPrefix) }

    if (descOverride == null && paramOverrides.isEmpty()) return this

    return copy(
        description = descOverride ?: description,
        parameters = {
            val schema = parameters()
            if (schema == null || paramOverrides.isEmpty()) {
                schema
            } else {
                schema.withParamDescriptions(name, paramOverrides)
            }
        },
    )
}

/** 逐个参数换掉 schema 里的 description 文案 */
private fun InputSchema.withParamDescriptions(
    toolName: String,
    overrides: Map<String, String>,
): InputSchema = when (this) {
    is InputSchema.Obj -> copy(
        properties = JsonObject(
            properties.mapValues { (paramName, value) ->
                val override = overrides[toolParamDescriptionId(toolName, paramName)]
                if (override != null && value is JsonObject) {
                    JsonObject(value.toMutableMap().apply {
                        put("description", JsonPrimitive(override))
                    })
                } else {
                    value
                }
            },
        ),
    )
}
