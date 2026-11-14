package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.CustomPromptScheme
import me.rerere.rikkahub.data.ai.PromptSchemes
import me.rerere.rikkahub.data.ai.SystemPromptBlock
import me.rerere.rikkahub.data.ai.SystemPromptOverride
import me.rerere.rikkahub.data.ai.SystemPromptPreset
import me.rerere.rikkahub.data.batch.BatchKeywordPreset
import me.rerere.rikkahub.data.batch.BatchRegenerationConfig
import me.rerere.rikkahub.data.batch.BatchSession
import me.rerere.rikkahub.data.batch.CommitTarget
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.service.BackgroundShellManager
import me.rerere.rikkahub.service.BackgroundShellTask
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.SessionActivity
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.web.ApiException
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val analytics: FirebaseAnalytics,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
    private val providerManager: me.rerere.ai.provider.ProviderManager,
    private val backgroundShellManager: BackgroundShellManager,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()

    val voiceSession = VoiceSessionController(viewModelScope, context::getString) {
        chatService.enqueueVoiceMessage(_conversationId, it)
    }

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val sessionActivity: StateFlow<SessionActivity?> =
        chatService
            .getActivityFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // 批量任务开跑就记账，批次清空则复位
        viewModelScope.launch {
            chatService.batchSession.collect { session ->
                when {
                    session == null -> stopSpendTracking(reset = true)
                    !session.finished -> startSpendTracking()
                    else -> stopSpendTracking(reset = false)
                }
            }
        }

        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
        }

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference("lastConversationId", _conversationId.toString())
    }

    override fun onCleared() {
        voiceSession.stop()
        super.onCleared()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 流式输出时会话每个 chunk 都在变，助手和模型只取决于下面这几个字段
    private val configuredConversation = conversation.distinctUntilChanged { old, new ->
        old.assistantId == new.assistantId && old.config == new.config &&
            old.modeInjectionIds == new.modeInjectionIds && old.lorebookIds == new.lorebookIds
    }

    // 会话视角下的助手：会话开始后，模型、思考级别、搜索等以会话上固定的配置为准
    val assistant: StateFlow<Assistant> = combine(settings, configuredConversation) { settings, conversation ->
        settings.getAssistantOf(conversation)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, settings.value.getAssistantOf(conversation.value))

    // 当前模型
    val currentChatModel: StateFlow<Model?> = combine(settings, configuredConversation) { settings, conversation ->
        settings.getChatModelOf(conversation)
    }.stateIn(viewModelScope, SharingStarted.Lazily, settings.value.getChatModelOf(conversation.value))

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    val messageQueue = chatService.getInboxFlow(_conversationId)

    fun removeQueuedMessage(id: Uuid) = chatService.removeInboxItem(_conversationId, id)

    fun beginEditQueuedMessage(id: Uuid) = chatService.beginEditQueuedMessage(_conversationId, id)

    fun finishEditQueuedMessage(id: Uuid, parts: List<UIMessagePart>?) =
        chatService.finishEditQueuedMessage(_conversationId, id, parts)

    fun resumeMessageQueue() = chatService.resumeInbox(_conversationId)

    // 这个会话启动的后台命令，按启动时间排序
    val backgroundTasks: StateFlow<List<BackgroundShellTask>> = backgroundShellManager.tasks
        .map { tasks -> tasks.values.filter { it.conversationId == _conversationId }.sortedBy { it.startedAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun stopBackgroundTask(id: String) = backgroundShellManager.stop(id)

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(fn: (Settings) -> Settings): Job {
        return viewModelScope.launch {
            var oldSettings: Settings? = null
            var newSettings: Settings? = null
            settingsStore.update { old ->
                fn(old).also { new ->
                    oldSettings = old
                    newSettings = new
                }
            }
            // 检查用户头像是否有变化，如果有则删除旧头像
            checkUserAvatarDelete(oldSettings ?: return@launch, newSettings ?: return@launch)
        }
    }

    // 检查用户头像删除
    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    // 聊天页对助手的修改：新会话直接改助手，会话开始后只改当前会话
    fun updateAssistant(assistant: Assistant) {
        viewModelScope.launch {
            chatService.updateChatAssistant(_conversationId) { assistant }
        }
    }

    // 新会话开始前归入抽屉里选中的文件夹
    suspend fun updateDraftFolder(folderId: Uuid?) {
        chatService.updateDraftConversationFolder(_conversationId, folderId)
    }

    // 设置聊天模型
    fun setChatModel(model: Model) {
        viewModelScope.launch {
            chatService.updateChatAssistant(_conversationId) { it.copy(chatModelId = model.id) }
        }
    }

    // 切换搜索方式
    fun updateSearch(enableWebSearch: Boolean, builtInSearch: Boolean) {
        viewModelScope.launch {
            chatService.updateChatSearch(_conversationId, enableWebSearch, builtInSearch)
        }
    }

    // Update checker
    val updateState = settingsStore.settingsFlow
        .map { settings ->
            !BuildConfig.IS_PLAY_STORE &&
                !settings.init &&
                settings.displaySetting.updateCheckDisabledUntilEpochMillis <= System.currentTimeMillis()
        }
        .distinctUntilChanged()
        .flatMapLatest { enabled ->
            if (enabled) updateChecker.updateState else flowOf(UiState.Loading)
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            UiState.Loading,
        )

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(content: List<UIMessagePart>,answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        analytics.logEvent("ai_send_message", null)

        chatService.sendMessage(_conversationId, content, answer)
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        analytics.logEvent("ai_edit_message", null)

        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts)
        }
    }

    fun handleCompressContext(additionalPrompt: String, keepRecentMessages: Int): Job {
        return viewModelScope.launch {
            chatService.compressConversation(_conversationId, additionalPrompt, keepRecentMessages).onFailure {
                chatService.addError(it, title = context.getString(R.string.error_title_compress_conversation))
            }
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        return chatService.forkConversationAtMessage(_conversationId, message.id)
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException("请先停止生成再删除消息"),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(message: UIMessage) {
        analytics.logEvent("ai_regenerate_at_message", null)
        chatService.regenerateAtMessage(_conversationId, message.id)
    }

    // ---- 批量重新生成 ----

    val batchSession: StateFlow<BatchSession?> = chatService.batchSession

    /** 全文索引重建完成的一次性提示 */
    val indexRebuildNotice: StateFlow<String?> = chatService.indexRebuildNotice

    fun consumeIndexRebuildNotice() = chatService.consumeIndexRebuildNotice()

    val isBatchLocked: Boolean
        get() = chatService.isBatchLocked(_conversationId)

    /** 上次用过的参数，作为弹窗初始值 */
    val lastBatchConfig: BatchRegenerationConfig
        get() = settings.value.batchRegeneration

    fun startBatchRegenerate(message: UIMessage, config: BatchRegenerationConfig) {
        // 记住这次的参数，下次弹窗直接带上。
        // 走 appScope：点「开始」后马上切走或退出页面时 viewModelScope 会被取消，
        // 那样这次写入就跟着没了，重启后设置回到默认值。
        chatService.saveBatchConfig(config)
        chatService.startBatchRegenerate(_conversationId, message.id, config)
    }

    /**
     * 弹窗里只要改动就存一份，不等「开始」。
     *
     * 走 appScope 而不是 viewModelScope：用户改完参数直接退出页面/杀进程时，
     * viewModelScope 已被取消，写盘会连同协程一起丢掉。
     */
    fun saveBatchConfig(config: BatchRegenerationConfig) {
        chatService.saveBatchConfig(config)
    }

    /** 弹窗里的关键词库：调用方已经算好新列表，这里去空去重后整份存下 */
    fun saveBatchKeywordPresets(presets: List<BatchKeywordPreset>) {
        viewModelScope.launch {
            val cleaned = presets
                .map { it.copy(group = it.group.trim(), keyword = it.keyword.trim()) }
                .filter { it.keyword.isNotEmpty() }
                .distinctBy { Triple(it.category, it.group, it.keyword) }
            settingsStore.update { it.copy(batchKeywordPresets = cleaned) }
        }
    }

    fun stopBatchLane(index: Int) = chatService.stopBatchLane(index)

    fun stopBatchAll() = chatService.stopBatchAll()

    /** 运行中临时再插一路 */
    fun addBatchLane(): Boolean = chatService.addBatchLane()

    /** 运行中只改轮次上限，请求体不动 */
    fun updateBatchMaxRerolls(limit: Int) = chatService.updateBatchMaxRerolls(limit)

    /** 运行中切换「熄屏也跑」 */
    fun updateBatchKeepAwake(enabled: Boolean) = chatService.updateBatchKeepAwake(enabled)

    fun rerunBatchLane(index: Int) = chatService.rerunBatchLane(index)

    /** 该批次是否还需要自动展开工作台（每批只返回一次 true） */
    fun consumeBatchAutoShow(runId: Long): Boolean = chatService.consumeBatchAutoShow(runId)

    fun rerunBatchFailedLanes() = chatService.rerunBatchFailedLanes()

    /** 后台命中时是否推送通知 */
    fun setBatchHitNotification(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(notifyBatchHitInBackground = enabled) }
        }
    }

    // ---- 系统提示词检查器 ----

    var systemPromptBlocks by mutableStateOf<List<SystemPromptBlock>>(emptyList())

    /** 请求体里实际会发出去的消息（只读预览） */
    var requestPreviewMessages by mutableStateOf<List<me.rerere.ai.ui.UIMessage>>(emptyList())
        private set

    /** 最近一次应用方案的结果，界面提示一次后清掉 */
    var systemPromptApplyNotice by mutableStateOf<String?>(null)
        private set

    fun dismissSystemPromptApplyNotice() {
        systemPromptApplyNotice = null
    }

    /** 读取当前生效的全部提示词块 */
    fun loadSystemPromptBlocks() {
        viewModelScope.launch {
            systemPromptBlocks = chatService.buildSystemPromptBlocks(_conversationId)
        }
        viewModelScope.launch {
            // 请求体预览里的「消息」部分：与真实组装同源
            requestPreviewMessages = chatService.buildRequestMessages(_conversationId)
        }
    }

    /**
     * 纯文本文段的改写。content 为空表示「恢复跟随」：移除改写，重新跟随源头。
     */
    fun saveSystemPromptText(blockId: String, content: String?) {
        viewModelScope.launch {
            settingsStore.update { current ->
                val others = current.systemPromptOverrides.filter { it.blockId != blockId }
                current.copy(
                    systemPromptOverrides = if (content.isNullOrBlank()) {
                        others
                    } else {
                        others + SystemPromptOverride(blockId, content = content)
                    },
                )
            }
            loadSystemPromptBlocks()
        }
    }

    /**
     * 分部件段的改写：保存的是每个文本框的文字。
     *
     * 与所选方案文字相同的槽位不写入——那样「跟随方案」才成立，
     * 用户只保留自己真正改过的那几个框。
     */
    fun saveSystemPromptSlots(blockId: String, texts: Map<String, String>) {
        viewModelScope.launch {
            settingsStore.update { current ->
                val scheme = PromptSchemes.of(current.promptSchemeId)
                val changed = texts.filter { (slot, text) -> scheme.texts[slot] != text }
                val others = current.systemPromptOverrides.filter { it.blockId != blockId }
                current.copy(
                    systemPromptOverrides = if (changed.isEmpty()) {
                        others
                    } else {
                        others + SystemPromptOverride(blockId, slots = changed)
                    },
                )
            }
            loadSystemPromptBlocks()
        }
    }

    /** 切换方案：只换文本框里的文字，骨架与数据块不变 */
    /**
     * 另存为方案：把「当前生效方案的文字 + 你在界面上的全部槽位改写」整体快照一份。
     *
     * 存完立刻切到新方案；之后改它不会影响内置两套。
     */
    fun saveCustomPromptScheme(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            settingsStore.update { current ->
                // of() 依赖登记表，先把当前的自定义方案装上再取文字
                PromptSchemes.installCustom(current.customPromptSchemes)
                val base = PromptSchemes.of(current.promptSchemeId).texts
                val overrides = current.systemPromptOverrides
                    .flatMap { override -> override.slots.entries }
                    .associate { it.key to it.value }
                val scheme = CustomPromptScheme(
                    id = "custom-" + Uuid.random().toString().take(8),
                    name = trimmed,
                    texts = base + overrides,
                )
                current.copy(
                    customPromptSchemes = current.customPromptSchemes + scheme,
                    promptSchemeId = scheme.id,
                )
            }
            loadSystemPromptBlocks()
        }
    }

    /** 删掉一个自定义方案；正用着它就回落到英文方案 */
    fun deleteCustomPromptScheme(id: String) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(
                    customPromptSchemes = current.customPromptSchemes.filterNot { it.id == id },
                    promptSchemeId = if (current.promptSchemeId == id) PromptSchemes.ENGLISH.id else current.promptSchemeId,
                )
            }
            loadSystemPromptBlocks()
        }
    }

    fun selectPromptScheme(id: String) {
        viewModelScope.launch {
            settingsStore.update { it.copy(promptSchemeId = id) }
            loadSystemPromptBlocks()
        }
    }

    /** 把当前所有改写存成一套方案 */
    fun saveSystemPromptPreset(name: String) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(
                    systemPromptPresets = current.systemPromptPresets + SystemPromptPreset(
                        id = Uuid.random().toString(),
                        name = name,
                        overrides = current.systemPromptOverrides,
                    ),
                )
            }
        }
    }

    /**
     * 应用方案时只覆盖方案里明确改写过的块。
     *
     * 当前已经改写、但方案没涉及的块保持原样：方案是「一组可以随时贴上去的改写」，
     * 不是「一整套会清空其余内容的快照」。想退回某个精确状态就先恢复默认再应用。
     */
    fun applySystemPromptPreset(preset: SystemPromptPreset) {
        viewModelScope.launch {
            var replacedCount = 0
            var keptCount = 0
            settingsStore.update { current ->
                val presetIds = preset.overrides.mapTo(mutableSetOf()) { it.blockId }
                val kept = current.systemPromptOverrides.filter { it.blockId !in presetIds }
                replacedCount = preset.overrides.size
                keptCount = kept.size
                current.copy(systemPromptOverrides = kept + preset.overrides)
            }
            loadSystemPromptBlocks()
            systemPromptApplyNotice = buildString {
                append("已应用「${preset.name}」：覆盖 $replacedCount 块")
                // 没被方案涉及、但用户手上有改写的块被保留下来了，明确说一声
                if (keptCount > 0) append("，保留 $keptCount 块当前改写")
            }
        }
    }

    fun deleteSystemPromptPreset(id: String) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(systemPromptPresets = current.systemPromptPresets.filter { it.id != id })
            }
        }
    }

    fun resetAllSystemPromptOverrides() {
        viewModelScope.launch {
            settingsStore.update { it.copy(systemPromptOverrides = emptyList()) }
            systemPromptApplyNotice = null
            loadSystemPromptBlocks()
        }
    }

    fun unlockBatch() = chatService.unlockBatch()

    fun discardBatch() = chatService.discardBatch()

    fun commitBatchLane(laneIndex: Int, target: CommitTarget) {
        viewModelScope.launch {
            // 不传当前对话：落盘目标由批次自己决定，允许在别的对话里打开工作台后落盘
            chatService.commitBatchResult(laneIndex, target)
        }
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = ""
    ) {
        analytics.logEvent("ai_tool_approval", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved, reason)
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        analytics.logEvent("ai_tool_answer", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    // ---- 本次并发期间的余额消耗 ----

    private val _batchSpent = mutableStateOf<java.math.BigDecimal?>(null)

    /** 本批已经花掉的钱；查不到余额时为 null */
    val batchSpent: java.math.BigDecimal?
        get() = _batchSpent.value

    private var spendTrackingJob: Job? = null

    /**
     * 盯余额计算本批消耗。
     *
     * 只累计「下降」的部分：中途充值会让余额上涨，若直接用「初始 - 当前」会把充值冲抵掉，
     * 所以每次只把相邻两次的下降量加进去，上涨只更新基准、不计入。
     */
    private fun startSpendTracking() {
        if (spendTrackingJob?.isActive == true) return
        _batchSpent.value = java.math.BigDecimal.ZERO
        spendTrackingJob = viewModelScope.launch {
            var lastBalance: java.math.BigDecimal? = null
            var spent = java.math.BigDecimal.ZERO
            while (isActive) {
                val balance = queryBalance()
                if (balance != null) {
                    val previous = lastBalance
                    if (previous != null && balance < previous) {
                        spent = spent.add(previous.subtract(balance))
                        _batchSpent.value = spent
                    }
                    lastBalance = balance
                }
                delay(10_000)
            }
        }
    }

    private fun stopSpendTracking(reset: Boolean) {
        spendTrackingJob?.cancel()
        spendTrackingJob = null
        if (reset) _batchSpent.value = null
    }

    /** 直接查提供商额度：不走界面那层两分钟缓存，否则轮询拿不到变化 */
    private suspend fun queryBalance(): java.math.BigDecimal? = runCatching {
        val current = settingsStore.awaitLoaded()
        val conversation = conversation.value
        val model = current.getChatModelOf(conversation) ?: return@runCatching null
        val provider = model.findProvider(current.providers) ?: return@runCatching null
        val quota = providerManager.getProviderByType(provider).getQuota(provider)
        val text = (quota as? me.rerere.ai.provider.ProviderQuota.Balance)?.value
            ?: return@runCatching null
        Regex("-?\\d+(?:\\.\\d+)?").find(text)?.value?.toBigDecimalOrNull()
    }.getOrNull()

    /** 从最后一条助手消息接着往下写 */
    fun continueGeneration() {
        chatService.continueGeneration(_conversationId)
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            chatService.updateTitle(_conversationId, title)
        }
    }

    fun deleteConversation(conversation: Conversation): Job =
        viewModelScope.launch {
            chatService.deleteConversation(conversation.id)
        }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            chatService.toggleConversationPinned(conversation.id)
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            chatService.moveConversationToAssistant(conversation.id, targetAssistantId)
            if (conversation.id == _conversationId) {
                settingsStore.selectAssistant(targetAssistantId)
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            chatService.generateTitle(conversation.id, force)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        viewModelScope.launch {
            chatService.clearTranslationField(_conversationId, messageId)
        }
    }

    fun selectMessageNode(nodeId: Uuid, selectIndex: Int) {
        viewModelScope.launch {
            try {
                chatService.selectMessageNode(_conversationId, nodeId, selectIndex)
            } catch (e: ApiException) {
                // 界面上的分支已经过时（比如刚被清理掉），切换不了就算了
                Log.w(TAG, "selectMessageNode: ${e.message}")
            }
        }
    }

    fun updateConversationSystemPrompt(systemPrompt: String?) {
        viewModelScope.launch {
            chatService.updateConversationSystemPrompt(_conversationId, systemPrompt)
        }
    }

    fun updateWorkspaceCwd(workspaceCwd: String?) {
        viewModelScope.launch {
            chatService.updateWorkspaceCwd(_conversationId, workspaceCwd)
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.setNodeFavorite(_conversationId, node.id, favorite = !currentlyFavorited)
        }
    }

}
