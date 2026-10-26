package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切片必须无损：拼回去要和原文逐字相同，否则保存时会悄悄吃掉内容。
 */
class ReasoningSliceTest {
    @Test
    fun `短文本不切片`() {
        val text = "很短的一段思考"
        assertEquals(listOf(text), sliceReasoning(text))
    }

    @Test
    fun `长度正好等于上限时不切片`() {
        val text = "a".repeat(REASONING_SLICE_SIZE)
        assertEquals(listOf(text), sliceReasoning(text))
    }

    @Test
    fun `长文本切片后拼回来与原文一致`() {
        val text = (1..4000).joinToString("") { "第${it}句思考内容。" }
        val slices = sliceReasoning(text)

        assertTrue("应当切成多片", slices.size > 1)
        assertEquals(text, slices.joinToString(""))
        slices.forEach { slice ->
            assertTrue("单片长度超出上限: ${slice.length}", slice.length <= REASONING_SLICE_SIZE)
            assertTrue("出现了空片", slice.isNotEmpty())
        }
    }

    @Test
    fun `优先在换行处断开`() {
        val head = "a".repeat(4800)
        val text = head + "\n" + "b".repeat(4000)
        val slices = sliceReasoning(text)

        assertEquals(text, slices.joinToString(""))
        // 第一片应当停在换行处，而不是把换行劈到第二片开头
        assertEquals(head + "\n", slices.first())
    }

    @Test
    fun `没有断点的超长单行也能切`() {
        val text = "x".repeat(REASONING_SLICE_SIZE * 2 + 123)
        val slices = sliceReasoning(text)

        assertEquals(text, slices.joinToString(""))
        assertEquals(3, slices.size)
        assertTrue(slices.all { it.length <= REASONING_SLICE_SIZE })
    }

    @Test
    fun `上限非法时退回单片的保守行为`() {
        val text = "abc"
        assertEquals(listOf(text), sliceReasoning(text, size = 0))
        assertEquals(listOf(text), sliceReasoning(text, size = -1))
    }

    @Test
    fun `空文本返回一个空片`() {
        assertEquals(listOf(""), sliceReasoning(""))
    }
}
