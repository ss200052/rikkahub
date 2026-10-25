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

/** 关键词在哪些内容里参与检索 */
enum class SearchContentScope {
    /** 只搜正文 */
    TEXT_ONLY,

    /** 只搜思维链 */
    REASONING_ONLY,

    /** 两者都搜 */
    TEXT_AND_REASONING,
}

private const val TAG = "MessageFtsManager"

/** 单列索引文本上限 */
private const val MAX_FTS_TEXT = 30_000

/**
 * 索引结构版本。
 * 2：正文与思维链拆成两列，支持按范围检索。改动列结构时必须递增，App 启动会重建一次。
 */
const val FTS_SCHEMA_VERSION = 2

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    /**
     * 按 [FTS_SCHEMA_VERSION] 的结构重建索引表。
     * 旧版本是单列 text，列数不同无法原地升级，只能整表重建。
     */
    suspend fun recreateSchema() = withContext(Dispatchers.IO) {
        db.execSQL("DROP TABLE IF EXISTS message_fts")
        db.execSQL(
            """
            CREATE VIRTUAL TABLE message_fts USING fts5(
                text,
                reasoning,
                node_id UNINDEXED,
                message_id UNINDEXED,
                conversation_id UNINDEXED,
                title UNINDEXED,
                update_at UNINDEXED,
                tokenize = 'simple'
            )
            """.trimIndent()
        )
    }

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val conversationId = conversation.id.toString()
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
        conversation.messageNodes.forEach { node ->
            node.messages.forEach { message ->
                val text = message.extractBodyText()
                val reasoning = message.extractReasoningText()
                if (text.isNotBlank() || reasoning.isNotBlank()) {
                    db.execSQL(
                        "INSERT INTO message_fts(text, reasoning, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            text,
                            reasoning,
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
        scope: SearchContentScope = SearchContentScope.TEXT_AND_REASONING,
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
        // 列级 MATCH 只在该列里找；不限列时整行都参与
        val matchTarget = when (scope) {
            SearchContentScope.TEXT_ONLY -> "text"
            SearchContentScope.REASONING_ONLY -> "reasoning"
            SearchContentScope.TEXT_AND_REASONING -> "message_fts"
        }
        // snippet 的列号跟着范围走；-1 表示跨列取最匹配的一段
        val snippetColumn = when (scope) {
            SearchContentScope.TEXT_ONLY -> 0
            SearchContentScope.REASONING_ONLY -> 1
            SearchContentScope.TEXT_AND_REASONING -> -1
        }
        val cursor = db.query(
            """
            SELECT node_id, message_id, conversation_id, title, update_at,
                   simple_snippet(message_fts, $snippetColumn, '[', ']', '...', 30) AS snippet
            FROM message_fts
            WHERE $matchTarget MATCH jieba_query(?)
            $assistantFilter
            ORDER BY ${sort.orderBy}
            LIMIT 50
            """.trimIndent(),
            arrayOf(keyword, *assistantIds.orEmpty().toTypedArray())
        )
        Log.i(TAG, "search: $keyword scope=$scope")
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

/** 参与检索的正文 */
private fun UIMessage.extractBodyText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(MAX_FTS_TEXT)

/** 参与检索的思维链 */
private fun UIMessage.extractReasoningText(): String =
    parts.filterIsInstance<UIMessagePart.Reasoning>()
        .joinToString("\n") { it.reasoning }
        .take(MAX_FTS_TEXT)
