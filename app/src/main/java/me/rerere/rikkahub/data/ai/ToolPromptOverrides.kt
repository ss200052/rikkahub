package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

/** 工具描述在覆盖表里的键 */
fun toolDescriptionId(toolName: String): String = "tool.$toolName.description"

/**
 * 工具某个参数说明在覆盖表里的键。
 *
 * [paramPath] 是参数在 schema 里的路径：顶层参数就是参数名（`query`），
 * 嵌套对象用点连接（`x_axis.title`），数组元素用 `[]`（`questions[].id`、`series[].points.x`）。
 * 这样嵌套参数的说明也能被改写，不再是够不着的英文。
 */
fun toolParamDescriptionId(toolName: String, paramPath: String): String =
    "tool.$toolName.param.$paramPath"

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

/** 逐层换掉 schema 里的 description 文案，嵌套对象与数组元素都走到 */
private fun InputSchema.withParamDescriptions(
    toolName: String,
    overrides: Map<String, String>,
): InputSchema = when (this) {
    is InputSchema.Obj -> copy(
        properties = JsonObject(
            properties.mapValues { (paramName, value) ->
                applyParamOverrides(toolName, paramName, value, overrides)
            },
        ),
    )
}

private fun applyParamOverrides(
    toolName: String,
    path: String,
    value: JsonElement,
    overrides: Map<String, String>,
): JsonElement {
    if (value !is JsonObject) return value

    val override = overrides[toolParamDescriptionId(toolName, path)]
    val described = if (override != null) {
        JsonObject(
            value.toMutableMap().apply { put("description", JsonPrimitive(override)) },
        )
    } else {
        value
    }

    return when ((described["type"] as? JsonPrimitive)?.contentOrNull) {
        "object" -> {
            val properties = described["properties"] as? JsonObject ?: return described
            JsonObject(
                described.toMutableMap().apply {
                    put(
                        "properties",
                        JsonObject(
                            properties.mapValues { (key, child) ->
                                applyParamOverrides(toolName, "$path.$key", child, overrides)
                            },
                        ),
                    )
                },
            )
        }

        "array" -> {
            val items = described["items"] as? JsonObject ?: return described
            JsonObject(
                described.toMutableMap().apply {
                    put("items", applyParamOverrides(toolName, "$path[]", items, overrides))
                },
            )
        }

        else -> described
    }
}

/**
 * 列出一个工具 schema 里的全部参数说明，路径与 [toolParamDescriptionId] 的约定一致。
 *
 * 预览界面用它把嵌套参数也摆出来——只列顶层的话，`ask_user` 的 questions[]、
 * `chart_display` 的 series[] 那些说明既看不到也改不了。
 */
internal fun collectParamDescriptions(
    toolName: String,
    schema: InputSchema?,
): List<Triple<String, String, String>> {
    val root = schema as? InputSchema.Obj ?: return emptyList()
    val result = mutableListOf<Triple<String, String, String>>()
    collectParams(toolName, "", root.properties, result)
    return result
}

private fun collectParams(
    toolName: String,
    prefix: String,
    properties: JsonObject,
    out: MutableList<Triple<String, String, String>>,
) {
    properties.forEach { (key, value) ->
        if (value !is JsonObject) return@forEach
        val path = if (prefix.isEmpty()) key else "$prefix.$key"
        val description = (value["description"] as? JsonPrimitive)?.contentOrNull
        if (description != null) {
            out += Triple(toolParamDescriptionId(toolName, path), path, description)
        }
        when ((value["type"] as? JsonPrimitive)?.contentOrNull) {
            "object" -> {
                (value["properties"] as? JsonObject)?.let { collectParams(toolName, path, it, out) }
            }

            "array" -> {
                val items = value["items"] as? JsonObject ?: return@forEach
                val itemPath = "$path[]"
                val itemDescription = (items["description"] as? JsonPrimitive)?.contentOrNull
                if (itemDescription != null) {
                    out += Triple(
                        toolParamDescriptionId(toolName, itemPath),
                        itemPath,
                        itemDescription,
                    )
                }
                (items["properties"] as? JsonObject)?.let {
                    collectParams(toolName, itemPath, it, out)
                }
            }
        }
    }
}
