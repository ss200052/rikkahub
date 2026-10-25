package me.rerere.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (model: Model, messages: List<UIMessage>) -> String = { _, _ -> "" },
    /**
     * 这一段系统提示的模板形式，含 `{{标签}}`。
     *
     * 只有「说明文字 + 运行期数据」混排的工具才提供：有了它，整段就能被用户改写，
     * 改写时保留标签即可，实际数据在发请求时填回去。为空表示这段是普通文本，不需要模板。
     */
    val systemPromptTemplate: String? = null,
    /** 模板里各标签当前的值；与 [systemPromptTemplate] 配套 */
    val systemPromptVariables: (() -> Map<String, String>)? = null,
    val needsApproval: (JsonElement) -> Boolean = { false },
    val execute: suspend (JsonElement) -> List<UIMessagePart>
)

@Serializable
sealed class InputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject,
        val required: List<String>? = null,
    ) : InputSchema()
}
