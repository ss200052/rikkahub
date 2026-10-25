package me.rerere.rikkahub.data.ai

import kotlinx.serialization.Serializable

/** 系统提示词里各个块的固定标识，顺序即实际注入顺序 */
object SystemPromptBlockId {
    /** 助手/对话自己的系统提示词 */
    const val BASE = "base"

    /** 记忆系统注入的一整块 */
    const val MEMORY = "memory"

    /** 单个工具自带的提示词，后面接工具名 */
    const val TOOL_PREFIX = "tool:"

    /** 工作区提醒（单独一条 system 消息） */
    const val WORKSPACE = "workspace"
}

/** 片段在请求体里的位置分组 */
enum class PromptSegmentGroup(val label: String) {
    /** 系统提示词里的内容 */
    SYSTEM("系统提示词"),

    /** tools 参数里的工具描述与参数说明 */
    TOOLS("工具描述"),

    /** 消息列表里的合成内容（时间提醒等） */
    MESSAGES("合成消息"),
}

/**
 * 请求体里的一个可查看、可改写的单元。
 *
 * 两种形态并存：
 * - 纯文本文段（助手提示词、工具描述这类）：整段就是一个可编辑字符串，[parts] 为空；
 * - 分部件段（时间提醒、工作区提醒、技能清单、记忆）：由文本片段与不可编辑的数据块交替组成，
 *   [parts] 给出结构，[slotTexts] 给出每个文本片段当前的内容。
 *
 * [content] 始终是「按当前数据拼好的成品」，只用来展示与核对，不用于编辑。
 */
data class SystemPromptBlock(
    val id: String,
    val title: String,
    /** 实际会注入的内容：按当前方案与当前数据拼好的成品 */
    val content: String,
    /** 默认方案（英文）下拼出来的成品 */
    val defaultContent: String,
    /** 内容是否由运行期数据生成 */
    val dynamic: Boolean = false,
    /** 是否存在用户改写 */
    val hasOverride: Boolean = false,
    /** 这一块在请求体的哪个位置 */
    val group: PromptSegmentGroup = PromptSegmentGroup.SYSTEM,
    /** 工具名这类契约字段不可编辑 */
    val editable: Boolean = true,
    /** 分部件段的部件结构；纯文本段为空 */
    val parts: List<PromptGroup> = emptyList(),
    /** 每个文本片段当前生效的文字（已叠加方案与用户改写） */
    val slotTexts: Map<String, String> = emptyMap(),
) {
    val isOverridden: Boolean get() = hasOverride

    /** 这一块是不是按部件排布的 */
    val isTokenized: Boolean get() = parts.isNotEmpty()

    /** 分部件段里所有文本片段的标识，按出现顺序 */
    val slots: List<String> get() = PromptComposer.slots(parts)

    /** 分部件段里所有数据块的标识，按出现顺序 */
    val tokens: List<String> get() = PromptComposer.tokens(parts)

    /** 预览时能直接显示吗：空内容也要占位，避免用户以为漏了一块 */
    val displayText: String get() = content
}

/**
 * 用户对某个块的改写。
 *
 * 纯文本段用 [content]；分部件段用 [slots]，键是文本片段的标识。
 * 两者都为空表示没有改写，这一块跟随默认方案。
 */
@Serializable
data class SystemPromptOverride(
    val blockId: String,
    val content: String = "",
    val slots: Map<String, String> = emptyMap(),
) {
    /** 这条改写是否有实际内容 */
    val isMeaningful: Boolean get() = content.isNotBlank() || slots.values.any { it.isNotBlank() }
}

/** 一整套系统提示词方案，可整体保存与切换 */
@Serializable
data class SystemPromptPreset(
    val id: String,
    val name: String,
    val overrides: List<SystemPromptOverride> = emptyList(),
)
