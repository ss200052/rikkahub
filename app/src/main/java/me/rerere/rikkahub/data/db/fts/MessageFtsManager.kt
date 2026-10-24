package me.rerere.rikkahub.data.db.fts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

enum class MessageSearchSort(val orderBy: String) {
    RELEVANCE("rank, update_at DESC"),
    NEWEST_FIRST("update_at DESC, rank"),
    OLDEST_FIRST("update_at ASC, rank"),
}

private const val TAG = "MessageFtsManager"

/** 索引文本上限：正文优先保留，思维链追加在后 */
private const val MAX_FTS_TEXT = 30_000

/** 索引结构版本；改动 [extractFtsText] 的内容时递增，App 启动会自动重建一次 */
const val FTS_SCHEMA_VERSION = 1

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val conversationId = conversation.id.toString()
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
        conversation.messageNodes.forEach { node ->
            node.messages.forEach { message ->
                val text = message.extractFtsText()
                if (text.isNotBlank()) {
                    db.execSQL(
                        "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            text,
                            node.id.toString(),
                            message.id.toString(),
                            conversationId,
                            conversation.title,
                            conversation.updateAt.toEpochMilli().toString(),
                        )
                    )
                }
            }
        }
    }

    suspend fun updateTitle(conversationId: String, title: String) = withContext(Dispatchers.IO) {
        db.execSQL("UPDATE message_fts SET title = ? WHERE conversation_id = ?", arrayOf(title, conversationId))
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts")
    }

    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        // 只搜这些助手的对话，null 表示不限
        assistantIds: Collection<String>? = null,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MessageSearchResult>()
        if (assistantIds != null && assistantIds.isEmpty()) return@withContext results
        val assistantFilter = if (assistantIds != null) {
            """
            AND EXISTS (
                SELECT 1 FROM conversationentity AS conversation
                WHERE conversation.id = message_fts.conversation_id
                  AND conversation.assistant_id IN (${assistantIds.joinToString { "?" }})
            )
            """.trimIndent()
        } else {
            ""
        }
        val cursor = db.query(
            """
            SELECT node_id, message_id, conversation_id, title, update_at,
                   simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
            FROM message_fts
            WHERE text MATCH jieba_query(?)
            $assistantFilter
            ORDER BY ${sort.orderBy}
            LIMIT 50
            """.trimIndent(),
            arrayOf(keyword, *assistantIds.orEmpty().toTypedArray())
        )
        Log.i(TAG, "search: $keyword")
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = it.getString(0),
                        messageId = it.getString(1),
                        conversationId = it.getString(2),
                        title = it.getString(3),
                        updateAt = Instant.ofEpochMilli(it.getLong(4)),
                        snippet = it.getString(5),
                    )
                )
            }
        }
        results
    }
}

/**
 * 参与检索的文本：正文优先，思维链追加在后。
 * 思维链里出现的关键词同样要能搜到，所以两者都进索引；正文放在前面，
 * 长思维链不会把正文挤出长度上限。
 */
private fun UIMessage.extractFtsText(): String = buildString {
    val body = parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    val reasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().joinToString("\n") { it.reasoning }
    if (body.isNotBlank()) append(body)
    if (reasoning.isNotBlank()) {
        if (isNotEmpty()) append('\n')
        append(reasoning)
    }
}.trim().take(MAX_FTS_TEXT)
