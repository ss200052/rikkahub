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
 * 系统提示词的一个块。
 *
 * [defaultContent] 是当前实际会注入的内容；[content] 是可被用户覆盖后的值。
 * 两者相同表示没有覆盖。动态块（记忆、技能列表）每次组装都会重新生成，
 * 一旦被覆盖就不再跟随数据变化。
 */
data class SystemPromptBlock(
    val id: String,
    val title: String,
    /** 实际会注入的内容：有覆盖时是覆盖值，否则是默认值 */
    val content: String,
    /** 默认内容，用于展示与「恢复跟随」 */
    val defaultContent: String,
    /** 内容是否由运行期数据生成（记忆内容、技能列表等） */
    val dynamic: Boolean = false,
    /** 是否存在用户改写。不能靠比较内容判断：改写值可能恰好等于某次默认值 */
    val hasOverride: Boolean = false,
    /** 这一块在请求体的哪个位置 */
    val group: PromptSegmentGroup = PromptSegmentGroup.SYSTEM,
    /** 工具名这类契约字段不可编辑 */
    val editable: Boolean = true,
) {
    val isOverridden: Boolean get() = hasOverride

    /** 预览时能直接显示吗：空内容也要占位，避免用户以为漏了一块 */
    val displayText: String get() = content
}

/** 用户对某个块的改写 */
@Serializable
data class SystemPromptOverride(
    val blockId: String,
    val content: String,
)

/** 一整套系统提示词方案，可整体保存与切换 */
@Serializable
data class SystemPromptPreset(
    val id: String,
    val name: String,
    val overrides: List<SystemPromptOverride> = emptyList(),
)
