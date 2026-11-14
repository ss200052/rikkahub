package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/** 跨对话跳回某一轮的定位计算 */
class ChatJumpTest {

    private fun nodes(count: Int) = List(count) { UIMessage.user("m$it").toMessageNode() }

    @Test
    fun `locates the node that started the batch`() {
        val list = nodes(5)
        assertEquals(3, jumpTargetIndex(list, list[3].id))
    }

    @Test
    fun `returns null when the target node was deleted`() {
        assertNull(jumpTargetIndex(nodes(3), Uuid.random()))
    }

    @Test
    fun `returns null when no target was requested`() {
        assertNull(jumpTargetIndex(nodes(3), null))
    }

    @Test
    fun `returns null for an empty conversation`() {
        assertNull(jumpTargetIndex(emptyList(), Uuid.random()))
    }
}
