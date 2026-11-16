package me.rerere.ai.ui

/**
 * 把相邻的同类型 part 合回一段：正文并正文、思考并思考。
 *
 * 两种情况会让同一条消息里出现多段同类 part：
 *
 * 1. 续写：新的流会另起 part（Handler 不知道上一段对应哪个 id），于是消息里出现
 *    「前半截正文 / 后半截正文」两段。后果不只是渲染——编辑消息时只取最后一段正文，
 *    前半截根本看不见。
 * 2. 中断正好发生在代码块里：前半截围栏没闭合，Markdown 会把后面整段当成代码。
 *
 * 带签名 / 加密 metadata 的思考**不合并**：内容变了签名就对不上，回传时会被服务端拒绝。
 * 没有可合并的相邻 part 时原样返回，不做多余分配。
 */
fun List<UIMessagePart>.mergeAdjacentSameType(): List<UIMessagePart> {
    var needsMerge = false
    for (i in 1 until size) {
        val prev = this[i - 1]
        val next = this[i]
        if (
            (prev is UIMessagePart.Text && next is UIMessagePart.Text) ||
            (prev is UIMessagePart.Reasoning && next is UIMessagePart.Reasoning &&
                prev.metadata.isNullOrEmpty() && next.metadata.isNullOrEmpty())
        ) {
            needsMerge = true
            break
        }
    }
    if (!needsMerge) return this

    val merged = ArrayList<UIMessagePart>(size)
    forEach { part ->
        val last = merged.lastOrNull()
        when {
            part is UIMessagePart.Text && last is UIMessagePart.Text ->
                merged[merged.lastIndex] = last.copy(text = last.text + part.text)

            part is UIMessagePart.Reasoning && last is UIMessagePart.Reasoning &&
                last.metadata.isNullOrEmpty() && part.metadata.isNullOrEmpty() ->
                merged[merged.lastIndex] = last.copy(
                    reasoning = last.reasoning + part.reasoning,
                    finishedAt = part.finishedAt ?: last.finishedAt,
                )

            else -> merged.add(part)
        }
    }
    return merged
}
