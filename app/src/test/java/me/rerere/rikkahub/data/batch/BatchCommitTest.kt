package me.rerere.rikkahub.data.batch

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class BatchCommitTest {

    private val copyFile: (String) -> String = { it }

    /** 最小对话：一条用户消息 + 一条回复 */
    private fun conversationWithReply(): Conversation {
        val user = UIMessage.user("问题").toMessageNode()
        val reply = UIMessage.assistant("原回复").toMessageNode()
        return Conversation(assistantId = Uuid.random(), messageNodes = listOf(user, reply))
    }

    /**
     * 回归用例：批次属于 A 对话，用户在 B 对话里打开工作台并落盘。
     *
     * 修之前落盘用的是「当前页面」的对话，于是目标节点在 B 里根本不存在，
     * 这里返回 null，调用方再降级到新建分支也同样找不到节点，最后静默什么都不写。
     * 现在落盘一律按批次自己的 conversationId 走，本用例保证「用错对话必然拿不到结果」这件事可被观测。
     */
    @Test
    fun `commit into the wrong conversation finds no target node`() {
        val owner = conversationWithReply()
        val other = conversationWithReply()
        val targetInOwner = owner.messageNodes.first().id

        val result = other.commitBatchLane(
            targetNodeId = targetInOwner,
            laneMessage = UIMessage.assistant("新回复"),
            target = CommitTarget.REPLACE_CURRENT,
            copyFile = copyFile,
        )

        assertNull(result)
    }

    /** 同一个 targetNodeId 在所属对话里必须能落盘，两者对照说明差异只来自选错了对话 */
    @Test
    fun `commit into the owner conversation succeeds`() {
        val owner = conversationWithReply()
        val targetInOwner = owner.messageNodes.first().id

        val result = owner.commitBatchLane(
            targetNodeId = targetInOwner,
            laneMessage = UIMessage.assistant("新回复"),
            target = CommitTarget.REPLACE_CURRENT,
            copyFile = copyFile,
        )

        assertEquals("新回复", result!!.messageNodes[1].currentMessage.toText())
    }

    @Test
    fun `replace current reuses the reply id so the branch selection is untouched`() {
        val conversation = conversationWithReply()
        val previousReplyId = conversation.messageNodes[1].currentMessage.id

        val result = conversation.commitBatchLane(
            targetNodeId = conversation.messageNodes.first().id,
            laneMessage = UIMessage.assistant("新回复"),
            target = CommitTarget.REPLACE_CURRENT,
            copyFile = copyFile,
        )!!

        assertEquals(2, result.messageNodes.size)
        val reply = result.messageNodes[1]
        assertEquals(previousReplyId, reply.currentMessage.id)
        assertEquals("新回复", reply.currentMessage.toText())
    }

    @Test
    fun `replace current inserts a reply node when there is none yet`() {
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(UIMessage.user("问题").toMessageNode()),
        )

        val result = conversation.commitBatchLane(
            targetNodeId = conversation.messageNodes.first().id,
            laneMessage = UIMessage.assistant("新回复"),
            target = CommitTarget.REPLACE_CURRENT,
            copyFile = copyFile,
        )!!

        assertEquals(2, result.messageNodes.size)
        assertEquals("新回复", result.messageNodes[1].currentMessage.toText())
    }

    @Test
    fun `append candidate adds a candidate without changing the selection`() {
        val conversation = conversationWithReply()
        val previousReplyId = conversation.messageNodes[1].currentMessage.id

        val result = conversation.commitBatchLane(
            targetNodeId = conversation.messageNodes.first().id,
            laneMessage = UIMessage.assistant("新回复"),
            target = CommitTarget.APPEND_CANDIDATE,
            copyFile = copyFile,
        )!!

        val reply = result.messageNodes[1]
        assertEquals(2, reply.messages.size)
        // 选中项不变，仍停在原来那条
        assertEquals(0, reply.selectIndex)
        assertEquals(previousReplyId, reply.currentMessage.id)
        assertNotEquals(previousReplyId, reply.messages[1].id)
        assertEquals("新回复", reply.messages[1].toText())
    }

    @Test
    fun `new branch target is handled by the caller and returns null here`() {
        val conversation = conversationWithReply()

        val result = conversation.commitBatchLane(
            targetNodeId = conversation.messageNodes.first().id,
            laneMessage = UIMessage.assistant("新回复"),
            target = CommitTarget.NEW_BRANCH,
            copyFile = copyFile,
        )

        assertNull(result)
    }
}
