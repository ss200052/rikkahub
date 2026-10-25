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
    /** 默认内容，用于展示与「恢复默认」 */
    val defaultContent: String,
    /** 内容是否由运行期数据生成（记忆内容、技能列表等） */
    val dynamic: Boolean = false,
) {
    val isOverridden: Boolean get() = content != defaultContent

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
