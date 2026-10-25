package me.rerere.rikkahub.ui.pages.search

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.db.fts.SearchContentScope
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import kotlin.uuid.Uuid

private const val SORT_ORDER_PREF_KEY = "search_page_sort_order"

enum class MessageSearchScope {
    CURRENT_ASSISTANT,
    ALL_ASSISTANTS,
}

private data class SearchRequest(
    val query: String,
    val sort: MessageSearchSort,
    val scope: MessageSearchScope,
    val contentScope: SearchContentScope,
    val assistantId: Uuid?,
    val debounce: Boolean,
)

/** 点击某条结果后弹出的详情：完整思维链与正文，关键词高亮用 */
data class SearchResultDetail(
    val result: MessageSearchResult,
    val reasoning: String,
    val body: String,
)

class SearchVM(
    private val context: Application,
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val searchRequests = Channel<SearchRequest>(Channel.CONFLATED)
    private var currentAssistantId: Uuid? = null

    var searchQuery by mutableStateOf("")
        private set
    var searchScope by mutableStateOf(MessageSearchScope.CURRENT_ASSISTANT)
        private set

    /** 检索哪些内容：正文 / 思维链 / 两者 */
    var contentScope by mutableStateOf(SearchContentScope.TEXT_AND_REASONING)
        private set

    /** 点击结果后加载出来的详情，非空时显示弹窗 */
    var selectedDetail by mutableStateOf<SearchResultDetail?>(null)
        private set
    var sortOrder by mutableStateOf(
        runCatching {
            MessageSearchSort.valueOf(
                context.readStringPreference(SORT_ORDER_PREF_KEY, MessageSearchSort.RELEVANCE.name)!!
            )
        }.getOrDefault(MessageSearchSort.RELEVANCE)
    )
        private set
    var results by mutableStateOf<List<MessageSearchResult>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var isRebuilding by mutableStateOf(false)
        private set
    var rebuildProgress by mutableStateOf(0 to 0)
        private set

    init {
        viewModelScope.launch {
            searchRequests.receiveAsFlow().collectLatest { request -> performSearch(request) }
        }
        viewModelScope.launch {
            settingsStore.settingsFlow
                .map { it.getCurrentAssistant().id }
                .distinctUntilChanged()
                .collect { assistantId ->
                    currentAssistantId = assistantId
                    if (searchScope == MessageSearchScope.CURRENT_ASSISTANT) {
                        search()
                    }
                }
        }
    }

    fun onQueryChange(query: String) {
        searchQuery = query
        requestSearch(debounce = true)
    }

    fun onScopeChange(scope: MessageSearchScope) {
        if (searchScope == scope) return
        searchScope = scope
        search()
    }

    fun onSortChange(sort: MessageSearchSort) {
        if (sortOrder == sort) return
        sortOrder = sort
        context.writeStringPreference(SORT_ORDER_PREF_KEY, sort.name)
        search()
    }

    fun onContentScopeChange(scope: SearchContentScope) {
        if (contentScope == scope) return
        contentScope = scope
        search()
    }

    /** 点开某条结果：读取完整内容用于就地展示，不跳转 */
    fun openDetail(result: MessageSearchResult) {
        viewModelScope.launch {
            val conversation = conversationRepo.getConversationById(Uuid.parse(result.conversationId))
                ?: return@launch
            val message = conversation.messageNodes
                .firstOrNull { it.id.toString() == result.nodeId }
                ?.messages
                ?.firstOrNull { it.id.toString() == result.messageId }
                ?: return@launch
            selectedDetail = SearchResultDetail(
                result = result,
                reasoning = message.parts
                    .filterIsInstance<me.rerere.ai.ui.UIMessagePart.Reasoning>()
                    .joinToString("\n\n") { it.reasoning },
                body = message.parts
                    .filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text },
            )
        }
    }

    fun dismissDetail() {
        selectedDetail = null
    }

    fun search() {
        requestSearch()
    }

    private fun requestSearch(debounce: Boolean = false) {
        val assistantId = when (searchScope) {
            MessageSearchScope.CURRENT_ASSISTANT -> currentAssistantId
            MessageSearchScope.ALL_ASSISTANTS -> null
        }
        searchRequests.trySend(
            SearchRequest(
                query = searchQuery,
                sort = sortOrder,
                scope = searchScope,
                contentScope = contentScope,
                assistantId = assistantId,
                debounce = debounce,
            )
        )
    }

    fun rebuildIndex() {
        viewModelScope.launch {
            isRebuilding = true
            rebuildProgress = 0 to 0
            try {
                conversationRepo.rebuildAllIndexes { current, total ->
                    rebuildProgress = current to total
                }
            } finally {
                isRebuilding = false
            }
        }
    }

    private suspend fun performSearch(request: SearchRequest) {
        results = emptyList()
        if (request.query.isBlank() ||
            (request.scope == MessageSearchScope.CURRENT_ASSISTANT && request.assistantId == null)
        ) {
            return
        }
        isLoading = true
        try {
            if (request.debounce) delay(300L)
            results = conversationRepo.searchMessages(
                keyword = request.query,
                sort = request.sort,
                assistantId = request.assistantId,
                scope = request.contentScope,
            )
        } finally {
            isLoading = false
        }
    }
}
