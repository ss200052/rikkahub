package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid

/**
 * 进入对话后要滚动到的位置。
 *
 * 返回 null 表示不做定位：可能是没带目标节点，也可能是那个节点已经不在了
 * （对话被删过消息），此时保持原有行为，不要去猜一个位置。
 */
internal fun jumpTargetIndex(nodes: List<MessageNode>, nodeId: Uuid?): Int? {
    if (nodeId == null) return null
    return nodes.indexOfFirst { it.id == nodeId }.takeIf { it >= 0 }
}
