package me.rerere.rikkahub.data.batch

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.mapLocalFileUrls
import kotlin.uuid.Uuid

/**
 * 把一条批量结果写进对话。
 *
 * 三种目标的 id 策略：
 * - [CommitTarget.REPLACE_CURRENT]：复用目标位置那条回复的 id，只换内容，分支选中项不变；
 *   目标位置还没有回复节点时新建一条。
 * - [CommitTarget.APPEND_CANDIDATE]：新建 id 追加为候选，**不改变当前选中项**。
 * - [CommitTarget.NEW_BRANCH]：由调用方先 fork 出新对话再写入，这里返回 null。
 *
 * 同一路重复落盘时每次都产生新的 message id，不会互相覆盖其它落盘记录。
 * 目标节点已被删除时返回 null，调用方应降级为新建分支而不是静默丢弃。
 */
internal fun Conversation.commitBatchLane(
    targetNodeId: Uuid,
    laneMessage: UIMessage,
    target: CommitTarget,
    copyFile: (String) -> String,
): Conversation? {
    if (target == CommitTarget.NEW_BRANCH) return null

    val targetIndex = messageNodes.indexOfFirst { it.id == targetNodeId }
    if (targetIndex == -1) return null

    // 紧邻目标用户消息的那条回复；中间隔着压缩检查点时不复用，改为新起一条
    val replyIndex = (targetIndex + 1).takeIf { it in messageNodes.indices }
    val replyNode = replyIndex
        ?.let { messageNodes[it] }
        ?.takeIf { it.role == MessageRole.ASSISTANT }

    return when (target) {
        CommitTarget.REPLACE_CURRENT -> {
            if (replyNode != null) {
                // 复用当前分支那条消息的 id，原地换内容
                val reusedId = replyNode.currentMessage.id
                upsertMessage(replyNode.id, laneMessage.copy(id = reusedId))
            } else {
                val fresh = laneMessage.withCopiedAttachments(copyFile)
                insertReplyAfter(targetIndex, fresh)
            }
        }

        CommitTarget.APPEND_CANDIDATE -> {
            val fresh = laneMessage.copy(id = Uuid.random()).withCopiedAttachments(copyFile)
            if (replyNode != null) {
                val updated = replyNode.copy(messages = replyNode.messages + fresh)
                copy(
                    messageNodes = messageNodes.toMutableList()
                        .also { it[replyIndex!!] = updated },
                )
            } else {
                insertReplyAfter(targetIndex, fresh)
            }
        }

        CommitTarget.NEW_BRANCH -> null
    }
}

/** 在 [index] 之后插入一个新的回复节点 */
private fun Conversation.insertReplyAfter(index: Int, message: UIMessage): Conversation = copy(
    messageNodes = messageNodes.take(index + 1) +
        MessageNode.of(message) +
        messageNodes.drop(index + 1),
)

/** 附件是实际复制一份，两个对话之后各删各的文件，与原分支逻辑保持一致 */
internal fun UIMessage.withCopiedAttachments(copyFile: (String) -> String): UIMessage =
    copy(parts = parts.map { it.mapLocalFileUrls(copyFile) })
