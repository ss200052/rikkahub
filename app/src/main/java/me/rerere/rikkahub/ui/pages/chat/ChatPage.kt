package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ui.components.RikkaConfirmDialog
import me.rerere.ui.toast.ToastType
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Share04
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.BackgroundShellStatus
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.SessionActivity
import me.rerere.rikkahub.ui.components.ai.BackgroundTasksSheet
import me.rerere.rikkahub.ui.components.ai.ChatAttachmentPickerActions
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.ChatTopBarCapsule
import me.rerere.rikkahub.ui.components.ai.SystemPromptInspectorDialog
import me.rerere.rikkahub.ui.components.ai.FilesPicker
import me.rerere.rikkahub.ui.components.ai.SearchMode
import me.rerere.rikkahub.ui.components.ai.completion.WorkspaceCompletionProvider
import me.rerere.rikkahub.ui.components.ai.rememberChatAttachmentPickerActions
import me.rerere.rikkahub.ui.components.message.BatchCommitTargetDialog
import me.rerere.rikkahub.ui.components.message.BatchGenerationPanel
import me.rerere.rikkahub.ui.components.message.BatchLaneDetailDialog
import me.rerere.rikkahub.ui.components.message.BatchRegenerateDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

@Composable
fun ChatPage(
    id: Uuid,
    text: String?,
    files: List<Uri>,
    nodeId: Uuid? = null,
    messageId: Uuid? = null,
    autoSelectBranch: Boolean = false,
) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()

    // 新会话发出第一条消息前，跟随抽屉当前选中的文件夹（#2133）
    val drawerVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = LocalContext.current as ComponentActivity)
    val selectedFolderId by drawerVm.selectedFolderId.collectAsStateWithLifecycle()
    LaunchedEffect(selectedFolderId) {
        vm.updateDraftFolder(selectedFolderId)
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // Clear input focus so popup transitions cannot reopen the keyboard.
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
    }

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    // 进入大屏（永久抽屉）模式时重置抽屉状态为关闭，
    // 避免从横屏旋转回竖屏后，模态抽屉残留为打开状态且无法关闭（#1304）
    LaunchedEffect(isBigScreen) {
        if (isBigScreen && drawerState.isOpen) {
            drawerState.close()
        }
    }

    val startVoiceMode = rememberVoiceModeStarter(vm, setting)

    val inputState = vm.inputState

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            val localFiles = filesManager.createChatFilesByContents(files)
            val contentTypes = files.mapNotNull { file ->
                filesManager.getFileMimeType(file)
            }
            val parts = buildList {
                localFiles.forEachIndexed { index, file ->
                    val type = contentTypes.getOrNull(index)
                    if (type?.startsWith("image/") == true) {
                        add(UIMessagePart.Image(url = file.toString()))
                    } else if (type?.startsWith("video/") == true) {
                        add(UIMessagePart.Video(url = file.toString()))
                    } else if (type?.startsWith("audio/") == true) {
                        add(UIMessagePart.Audio(url = file.toString()))
                    }
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()

    // 搜索结果命中的是另一条分支时，只提示、不擅自改动对话状态
    var pendingBranchSwitch by remember { mutableStateOf<PendingBranchSwitch?>(null) }
    LaunchedEffect(nodeId, messageId, conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            if (nodeId != null) {
                val index = jumpTargetIndex(conversation.messageNodes, nodeId)
                if (index != null) {
                    if (messageId != null) {
                        val node = conversation.messageNodes[index]
                        val branchIndex = node.messages.indexOfFirst { it.id == messageId }
                        if (branchIndex >= 0 && branchIndex != node.selectIndex) {
                            if (autoSelectBranch) {
                                // 用户明确选了「跳转到该候选项」，直接切过去
                                vm.selectMessageNode(node.id, branchIndex)
                            } else {
                                // 只定位并提示，切不切由用户决定
                                pendingBranchSwitch = PendingBranchSwitch(
                                    nodeId = node.id,
                                    index = branchIndex,
                                    total = node.messages.size,
                                )
                            }
                        }
                    }
                    chatListState.scrollToItem(index)
                }
            } else {
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            }
            vm.chatListInitialized = true
        }
    }

    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    onStartVoiceMode = startVoiceMode,
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    assistant = assistant,
                    currentChatModel = currentChatModel,
                    bigScreen = true,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                    pendingBranchSwitch = pendingBranchSwitch,
                    onSwitchBranch = {
                        pendingBranchSwitch?.let { pending ->
                            vm.selectMessageNode(pending.nodeId, pending.index)
                        }
                        pendingBranchSwitch = null
                    },
                    onDismissBranchSwitch = { pendingBranchSwitch = null },
                )
            }
        }

        else -> {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    onStartVoiceMode = startVoiceMode,
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    assistant = assistant,
                    currentChatModel = currentChatModel,
                    bigScreen = false,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                    pendingBranchSwitch = pendingBranchSwitch,
                    onSwitchBranch = {
                        pendingBranchSwitch?.let { pending ->
                            vm.selectMessageNode(pending.nodeId, pending.index)
                        }
                        pendingBranchSwitch = null
                    },
                    onDismissBranchSwitch = { pendingBranchSwitch = null },
                )
            }
            BackHandler(drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }
        }
    }
}

/** 搜索结果命中到另一条分支时的待切换信息 */
private data class PendingBranchSwitch(
    val nodeId: Uuid,
    val index: Int,
    val total: Int,
)

@Composable
private fun ChatPageContent(
    onStartVoiceMode: () -> Unit,
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    assistant: Assistant,
    currentChatModel: Model?,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    pendingBranchSwitch: PendingBranchSwitch? = null,
    onSwitchBranch: () -> Unit = {},
    onDismissBranchSwitch: () -> Unit = {},
) {
            // 空输入点发送（＝继续生成）前的二次确认
            var showContinueConfirm by remember { mutableStateOf(false) }

            // 续写条件：最后一条是助手消息、它还有内容（正文或思维链）、当前没在生成、没被批量锁
            val continueTarget = conversation.currentMessages.lastOrNull()
                ?.takeIf { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }
            val continueHasText = continueTarget?.parts?.any {
                it is me.rerere.ai.ui.UIMessagePart.Text && it.text.isNotBlank()
            } == true
            val continueHasReasoning = continueTarget?.parts?.any {
                it is me.rerere.ai.ui.UIMessagePart.Reasoning && it.reasoning.isNotBlank()
            } == true
            val canContinue = continueTarget != null &&
                (continueHasText || continueHasReasoning) &&
                loadingJob == null &&
                !vm.isBatchLocked

    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current

    // 全文索引后台重建完成后提示一次
    val indexNotice by vm.indexRebuildNotice.collectAsStateWithLifecycle()
    LaunchedEffect(indexNotice) {
        indexNotice?.let {
            toaster.show(it, type = ToastType.Info)
            vm.consumeIndexRebuildNotice()
        }
    }

    val workspaceRepository: WorkspaceRepository = koinInject()
    val sessionActivity by vm.sessionActivity.collectAsStateWithLifecycle()

    var previewMode by rememberSaveable { mutableStateOf(false) }
    val selectionState = rememberChatSelectionState()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    var showFilesSheet by remember { mutableStateOf(false) }
    var showSystemPromptInspector by remember { mutableStateOf(false) }
    val attachmentPickerActions = rememberChatAttachmentPickerActions(
        inputState = inputState,
        setting = setting,
        onAttachmentAdded = { showFilesSheet = false },
    )
    val allowAudioVideoAttachments =
        currentChatModel?.findProvider(setting.providers) is ProviderSetting.Google

    val completionProviders = remember(assistant.workspaceId, conversation.workspaceCwd, workspaceRepository) {
        assistant.workspaceId?.let { workspaceId ->
            listOf(
                WorkspaceCompletionProvider(
                    workspaceId = workspaceId.toString(),
                    repository = workspaceRepository,
                    currentCwd = conversation.workspaceCwd,
                )
            )
        }.orEmpty()
    }

    // 列表滚到标题栏下面时才显示胶囊背景，空页面或在顶部时按钮直接浮在背景上
    // 概览页有自己的列表，切过去后跟着它判断
    val previewListState = rememberLazyListState()
    val listScrolledUnderTopBar by remember(chatListState, previewListState) {
        derivedStateOf {
            if (previewMode) previewListState.canScrollBackward else chatListState.canScrollBackward
        }
    }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    BackHandler(previewMode) {
        previewMode = false
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        AssistantBackground(setting = setting, modifier = Modifier.hazeSource(hazeState))
        Scaffold(
            topBar = {
                TopBar(
                    settings = setting,
                    assistant = assistant,
                    chatModel = currentChatModel,
                    conversation = conversation,
                    bigScreen = bigScreen,
                    drawerState = drawerState,
                    hazeState = hazeState,
                    showCapsuleBackground = listScrolledUnderTopBar,
                    onNewChat = {
                        navigateToChatPage(navController)
                    },
                    onShowOverview = {
                        selectionState.selecting = false
                        previewMode = true
                    },
                    onExport = {
                        // 跟消息上的分享一样进入多选，默认全选，用户可以再挑
                        previewMode = false
                        selectionState.start(conversation.messageNodes.map { it.id })
                    },
                    onDelete = {
                        showDeleteConfirm = true
                    },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    }
                )
            },
            bottomBar = {
                val messageQueue by vm.messageQueue.collectAsStateWithLifecycle()
                val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
                val backgroundTasks by vm.backgroundTasks.collectAsStateWithLifecycle()
                var showBackgroundTasks by remember { mutableStateOf(false) }
                if (showBackgroundTasks) {
                    BackgroundTasksSheet(
                        tasks = backgroundTasks,
                        onStop = vm::stopBackgroundTask,
                        onDismiss = { showBackgroundTasks = false },
                    )
                }
                ChatInput(
                    onStartVoiceMode = onStartVoiceMode,
                    voiceState = voiceState,
                    onStopVoiceMode = vm.voiceSession::stop,
                    onInspectSystemPrompt = {
                        vm.loadSystemPromptBlocks()
                        vm.dismissSystemPromptApplyNotice()
                        showSystemPromptInspector = true
                    },
                    state = inputState,
                    messageQueue = messageQueue,
                    onRemoveQueuedMessage = vm::removeQueuedMessage,
                    onBeginEditQueuedMessage = vm::beginEditQueuedMessage,
                    onFinishEditQueuedMessage = vm::finishEditQueuedMessage,
                    onResumeMessageQueue = vm::resumeMessageQueue,
                    runningBackgroundTasks = backgroundTasks.count { it.status == BackgroundShellStatus.RUNNING },
                    onShowBackgroundTasks = { showBackgroundTasks = true },
                    loading = loadingJob != null,
                    settings = setting,
                    assistant = assistant,
                    chatModel = currentChatModel,
                    hazeState = hazeState,
                    completionProviders = completionProviders,
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    allowEmptySend = canContinue,
                    onSendClick = {
                        if (currentChatModel == null) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                            inputState.clearInput()
                        } else if (inputState.isEmpty() && canContinue) {
                            // 空输入＝接着上一条助手消息往下写，起点要用户确认
                            showContinueConfirm = true
                        } else {
                            vm.handleMessageSend(inputState.getContents())
                            scope.launch {
                                delay(100.milliseconds)
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                            inputState.clearInput()
                        }
                    },
                    onLongSendClick = {
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(content = inputState.getContents(), answer = false)
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onUpdateChatModel = vm::setChatModel,
                    onUpdateAssistant = vm::updateAssistant,
                    onMoreClick = {
                        showFilesSheet = true
                    },
                )
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            // ---- 批量重新生成 ----
            var batchTarget by remember { mutableStateOf<UIMessage?>(null) }
            var showBatchPanel by remember { mutableStateOf(false) }
            var batchInspect by remember { mutableStateOf<Int?>(null) }
            var batchCommit by remember { mutableStateOf<Int?>(null) }
            val batchSession by vm.batchSession.collectAsStateWithLifecycle()
            // 跳转列的「跳到批量目标」需要二次确认
            var showJumpToBatchConfirm by remember { mutableStateOf(false) }
            LaunchedEffect(batchSession?.runId) {
                val session = batchSession ?: return@LaunchedEffect
                // 批次不属于当前对话就不打扰：切换会话时不该弹别人那批的面板
                if (session.conversationId != conversation.id.toString()) return@LaunchedEffect
                // 同一个批次只自动展开一次，用户收起后不再强弹
                if (vm.consumeBatchAutoShow(session.runId)) showBatchPanel = true
            }

            ChatList(
                innerPadding = innerPadding,
                conversation = conversation,
                state = chatListState,
                previewListState = previewListState,
                selectionState = selectionState,
                loading = loadingJob != null,
                compressing = sessionActivity == SessionActivity.Compressing,
                processingStatus = processingStatus,
                previewMode = previewMode,
                onPreviewModeChange = { previewMode = it },
                settings = setting,
                hazeState = hazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = {
                    vm.regenerateAtMessage(it)
                },
                onContinue = { vm.continueGeneration() },
                onBatchRegenerate = { target ->
                    // 已经有批次（可能属于别的对话、可能还在跑）就直接打开工作台，
                    // 否则会弹出一个注定报「已有一个批量任务在运行」的配置窗
                    val session = batchSession
                    if (session != null &&
                        (!session.finished || session.conversationId == conversation.id.toString())
                    ) {
                        showBatchPanel = true
                    } else {
                        batchTarget = target
                    }
                },
                onEdit = {
                    inputState.editingMessage = it.id
                    inputState.setContents(it.parts)
                },
                onForkMessage = {
                    scope.launch {
                        val fork = vm.forkMessage(message = it)
                        navigateToChatPage(navController, chatId = fork.id)
                    }
                },
                onDelete = {
                    if (loadingJob != null) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessage(it)
                    }
                },
                onUpdateMessage = { newNode ->
                    vm.selectMessageNode(newNode.id, newNode.selectIndex)
                },
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onJumpToMessage = { index ->
                    previewMode = false
                    scope.launch {
                        chatListState.requestScrollToItem(index)
                    }
                },
                // 只要存在批量任务（哪怕是别的对话的），跳转列里就多出这个入口
                showBatchJump = batchSession != null,
                onJumpToBatchTarget = { showJumpToBatchConfirm = true },
                onToolApproval = { toolCallId, approved, reason ->
                    vm.handleToolApproval(toolCallId, approved, reason)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                onConversationSystemPromptChange = { newPrompt ->
                    vm.updateConversationSystemPrompt(newPrompt)
                },
            )

            // ---- 批量重新生成 ----
            // 设置没加载完之前不能开这个弹窗：vm.settings 的初始值是 Settings.dummy()，
            // 它的 batchRegeneration 是全默认（并发 4、关键词空）。用户在这份默认值上改一下再保存，
            // 就会把盘上真实的关键词和并发路数覆盖成默认——重启后看起来就是「设置被清掉了」。
            val pendingBatchTarget = batchTarget
            if (pendingBatchTarget != null && !setting.init) {
                BatchRegenerateDialog(
                    initial = vm.lastBatchConfig,
                    presets = setting.batchKeywordPresets,
                    onDismiss = { batchTarget = null },
                    onStart = { config ->
                        batchTarget = null
                        vm.startBatchRegenerate(pendingBatchTarget, config)
                    },
                    onPresetsChange = { vm.saveBatchKeywordPresets(it) },
                    // 弹窗里每改一处就落库：不点「开始」、直接取消、甚至重启 App 都还在
                    onConfigChange = { vm.saveBatchConfig(it) },
                )
            }

            // 跳到批量任务发起的那一轮：同一个对话直接滚过去，别的对话则切过去再定位
            val jumpTargetSession = batchSession
            if (showJumpToBatchConfirm && jumpTargetSession != null) {
                val sameConversation = jumpTargetSession.conversationId == conversation.id.toString()
                AlertDialog(
                    onDismissRequest = { showJumpToBatchConfirm = false },
                    title = { Text("跳到批量任务") },
                    text = {
                        Text(
                            if (sameConversation) {
                                "跳到这一批发起时的那条消息。"
                            } else {
                                "这一批属于「${jumpTargetSession.conversationTitle.ifBlank { "另一个对话" }}」，" +
                                    "确认后会切到那个对话并定位到发起的那一轮。"
                            },
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showJumpToBatchConfirm = false
                                val targetNode = Uuid.parse(jumpTargetSession.targetNodeId)
                                if (sameConversation) {
                                    // 已经在这个对话里：直接滚到对应层
                                    val index = conversation.messageNodes
                                        .indexOfFirst { it.id == targetNode }
                                    if (index >= 0) {
                                        // 和「跳到消息」用同一条路径：列表还没布局好时它会把请求排队
                                        scope.launch { chatListState.requestScrollToItem(index) }
                                    }
                                } else {
                                    navigateToChatPage(
                                        navigator = navController,
                                        chatId = Uuid.parse(jumpTargetSession.conversationId),
                                        nodeId = targetNode,
                                    )
                                }
                            },
                        ) { Text("跳转") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showJumpToBatchConfirm = false }) { Text("取消") }
                    },
                )
            }

            val batchLaneSession = batchSession
            if (showBatchPanel && batchLaneSession != null) {
                BatchGenerationPanel(
                    session = batchLaneSession,
                    // 在别的对话里打开这一批时，说清楚落盘会写回哪个对话
                    ownerNotice = batchLaneSession
                        .takeIf { it.conversationId != conversation.id.toString() }
                        ?.let { s ->
                            val title = s.conversationTitle.ifBlank { "另一个对话" }
                            "这批结果属于「$title」，落盘会写回那个对话"
                        },
                    // 切到发起批次的那个对话，并定位到发起时的那一轮（目标用户消息）
                    onGoToOwner = batchLaneSession
                        .takeIf { it.conversationId != conversation.id.toString() }
                        ?.let { s ->
                            {
                                showBatchPanel = false
                                navigateToChatPage(
                                    navigator = navController,
                                    chatId = Uuid.parse(s.conversationId),
                                    nodeId = Uuid.parse(s.targetNodeId),
                                )
                            }
                        },
                    onStopLane = { vm.stopBatchLane(it) },
                    onStopAll = { vm.stopBatchAll() },
                    onUnlock = { vm.unlockBatch() },
                    onDismiss = { showBatchPanel = false },
                    onInspect = { batchInspect = it },
                    onCommit = { batchCommit = it },
                    onRerunLane = { vm.rerunBatchLane(it) },
                    onRerunFailed = { vm.rerunBatchFailedLanes() },
                    onAddLane = { vm.addBatchLane() },
                    onUpdateMaxRerolls = { vm.updateBatchMaxRerolls(it) },
                    quotaProvider = currentChatModel?.findProvider(setting.providers),
                    batchSpent = vm.batchSpent,
                    onToggleKeepAwake = { vm.updateBatchKeepAwake(it) },
                    notifyOnHit = setting.notifyBatchHitInBackground,
                    onToggleNotify = { vm.setBatchHitNotification(it) },
                )
            }

            batchInspect?.let { index ->
                batchLaneSession?.lane(index)?.let { lane ->
                    BatchLaneDetailDialog(
                        lane = lane,
                        onDismiss = { batchInspect = null },
                        onCommit = {
                            batchInspect = null
                            batchCommit = index
                        },
                    )
                }
            }

            batchCommit?.let { index ->
                batchLaneSession?.lane(index)?.let { lane ->
                    BatchCommitTargetDialog(
                        lane = lane,
                        isLocked = batchLaneSession.locked,
                        onDismiss = { batchCommit = null },
                        onPick = { target ->
                            batchCommit = null
                            vm.commitBatchLane(index, target)
                        },
                    )
                }
            }
        }

        RikkaConfirmDialog(
            show = showDeleteConfirm,
            title = stringResource(R.string.confirm_delete),
            confirmText = stringResource(R.string.delete),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                showDeleteConfirm = false
                scope.launch {
                    vm.deleteConversation(conversation).join()
                    navigateToChatPage(navController)
                }
            },
            onDismiss = { showDeleteConfirm = false },
            text = { Text(stringResource(R.string.chat_page_delete_conversation_confirm)) },
        )

        if (showFilesSheet) {
            ChatFilesPickerSheet(
                inputState = inputState,
                setting = setting,
                conversation = conversation,
                assistant = assistant,
                chatModel = currentChatModel,
                vm = vm,
                attachmentPickerActions = attachmentPickerActions,
                onStartVoiceMode = onStartVoiceMode,
                onDismiss = { showFilesSheet = false },
            )
        }

        // 当前生效的系统提示词：分块查看、逐块改写、整套存为方案
        if (showSystemPromptInspector) {
            SystemPromptInspectorDialog(
                blocks = vm.systemPromptBlocks,
                messages = vm.requestPreviewMessages,
                presets = setting.systemPromptPresets,
                schemeId = setting.promptSchemeId,
                onDismiss = { showSystemPromptInspector = false },
                onSelectScheme = { id -> vm.selectPromptScheme(id) },
                customSchemes = setting.customPromptSchemes,
                onSaveCustomScheme = { vm.saveCustomPromptScheme(it) },
                onDeleteCustomScheme = { vm.deleteCustomPromptScheme(it) },
                onSaveText = { blockId, content -> vm.saveSystemPromptText(blockId, content) },
                onSaveSlots = { blockId, texts -> vm.saveSystemPromptSlots(blockId, texts) },
                onSavePreset = { name -> vm.saveSystemPromptPreset(name) },
                onApplyPreset = { preset -> vm.applySystemPromptPreset(preset) },
                onDeletePreset = { id -> vm.deleteSystemPromptPreset(id) },
                onResetAll = { vm.resetAllSystemPromptOverrides() },
                applyNotice = vm.systemPromptApplyNotice,
                onDismissApplyNotice = { vm.dismissSystemPromptApplyNotice() },
            )
        }

        // 搜索命中的内容在别的分支：只提示，切不切由用户决定，避免擅自改动对话上下文
        pendingBranchSwitch?.let { pending ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 84.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    tonalElevation = 3.dp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "命中的内容在第 ${pending.index + 1}/${pending.total} 条分支",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        TextButton(onClick = onSwitchBranch) { Text("切换") }
                        TextButton(onClick = onDismissBranchSwitch) { Text("忽略") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    chatModel: Model?,
    vm: ChatVM,
    attachmentPickerActions: ChatAttachmentPickerActions,
    onStartVoiceMode: () -> Unit,
    onDismiss: () -> Unit,
) {
    val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var showCompressDialog by remember { mutableStateOf(false) }

    fun dismissAll() {
        showCompressDialog = false
        onDismiss()
    }

    val filesSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    ModalBottomSheet(
        sheetState = filesSheetState,
        onDismissRequest = { dismissAll() },
    ) {
        FilesPicker(
            conversation = conversation,
            state = inputState,
            assistant = assistant,
            chatModel = chatModel,
            mcpManager = vm.mcpManager,
            onCompressContext = vm::handleCompressContext,
            onUpdateAssistant = vm::updateAssistant,
            onUpdateWorkspaceCwd = vm::updateWorkspaceCwd,
            onUpdateSearchMode = { mode ->
                vm.updateSearch(
                    enableWebSearch = mode == SearchMode.LOCAL,
                    builtInSearch = mode == SearchMode.BUILT_IN,
                )
            },
            onUpdateSearchService = { index ->
                vm.updateSettings { it.copy(searchServiceSelected = index) }
            },
            showCompressDialog = showCompressDialog,
            onShowCompressDialogChange = { showCompressDialog = it },
            onDismiss = { dismissAll() },
            onTakePic = attachmentPickerActions.onTakePicture,
            onPickImage = attachmentPickerActions.onPickImage,
            onPickVideo = attachmentPickerActions.onPickVideo,
            onPickAudio = attachmentPickerActions.onPickAudio,
            onPickFile = attachmentPickerActions.onPickFile,
            onSketch = attachmentPickerActions.onSketch,
            onStartVoiceMode = if (
                setting.getSelectedASRProvider()?.supportsServerVadVoiceMode == true &&
                voiceState.phase == VoicePhase.Off
            ) {
                {
                    dismissAll()
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    onStartVoiceMode()
                }
            } else null,
        )
    }
}

@Composable
private fun TopBar(
    settings: Settings,
    assistant: Assistant,
    chatModel: Model?,
    conversation: Conversation,
    drawerState: DrawerState,
    bigScreen: Boolean,
    hazeState: HazeState,
    showCapsuleBackground: Boolean,
    onNewChat: () -> Unit,
    onShowOverview: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onUpdateTitle: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            if (!bigScreen) {
                ChatTopBarCapsule(
                    hazeState = hazeState,
                    displaySetting = settings.displaySetting,
                    showBackground = showCapsuleBackground,
                    modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                ) {
                    IconButton(
                        onClick = {
                            scope.launch { drawerState.open() }
                        }
                    ) {
                        Icon(HugeIcons.Menu03, "Messages")
                    }
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Column {
                    val provider = chatModel?.findProvider(providers = settings.providers, checkOverwrite = false)
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (chatModel != null && provider != null) {
                        Text(
                            text = "${assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }} / ${chatModel.displayName} (${provider.name})",
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(0.65f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                            )
                        )
                    }
                }
            }
        },
        actions = {
            ChatTopBarCapsule(
                hazeState = hazeState,
                displaySetting = settings.displaySetting,
                showBackground = showCapsuleBackground,
                modifier = Modifier.padding(start = 4.dp, end = 8.dp),
            ) {
                IconButton(
                    onClick = {
                        onNewChat()
                    }
                ) {
                    Icon(HugeIcons.MessageAdd01, "New Message")
                }
                ChatPageMenu(
                    enabled = conversation.messageNodes.isNotEmpty(),
                    onShowOverview = onShowOverview,
                    onExport = onExport,
                    onDelete = onDelete,
                )
            }
        },
    )
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}

// 顶栏右侧的会话菜单；空对话没有内容可概览、导出或删除，菜单项全部置灰
@Composable
private fun ChatPageMenu(
    enabled: Boolean,
    onShowOverview: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    @Composable
    fun MenuItem(
        text: String,
        icon: ImageVector,
        index: Int,
        count: Int,
        destructive: Boolean = false,
        onClick: () -> Unit,
    ) {
        DropdownMenuItem(
            text = { Text(text) },
            onClick = {
                expanded = false
                onClick()
            },
            shape = MenuDefaults.itemShape(index = index, count = count).shape,
            leadingIcon = { Icon(icon, contentDescription = null) },
            enabled = enabled,
            colors = if (destructive) {
                val errorColor = MaterialTheme.colorScheme.error
                MenuDefaults.itemColors(textColor = errorColor, leadingIconColor = errorColor)
            } else {
                MenuDefaults.itemColors()
            },
        )
    }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(HugeIcons.MoreVertical, stringResource(R.string.more_options))
        }
        DropdownMenuPopup(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(index = 0, count = 2)) {
                MenuItem(
                    text = stringResource(R.string.chat_page_conversation_map),
                    icon = HugeIcons.LeftToRightListBullet,
                    index = 0,
                    count = 2,
                    onClick = onShowOverview,
                )
                MenuItem(
                    text = stringResource(R.string.export_title),
                    icon = HugeIcons.Share04,
                    index = 1,
                    count = 2,
                    onClick = onExport,
                )
            }
            Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(index = 1, count = 2)) {
                MenuItem(
                    text = stringResource(R.string.delete),
                    icon = HugeIcons.Delete01,
                    index = 0,
                    count = 1,
                    destructive = true,
                    onClick = onDelete,
                )
            }
        }
    }
}
