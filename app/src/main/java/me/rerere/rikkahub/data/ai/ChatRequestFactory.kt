package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.RetryPolicy
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.withRetry
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.buildMemoryPrompt
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.INJECTION_AFTER_ID
import me.rerere.rikkahub.data.ai.transformers.INJECTION_BEFORE_ID
import me.rerere.rikkahub.data.ai.transformers.INJECTION_TOP_ID
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TIME_REMINDER_ID
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WORKSPACE_REMINDER_ID
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.buildWorkspaceReminderContent
import me.rerere.rikkahub.data.ai.transformers.collectInjections
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transformThinkTags
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.utils.toLocalDateTime
import me.rerere.workspace.WorkspaceShellStatus
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

private const val TAG = "ChatRequestFactory"

/**
 * 一轮对话的请求上下文，按会话固定的配置解析一次。
 *
 * 正常对话和压缩用同一份上下文组装请求，系统提示词、工具定义、参数和 sessionId 都相同，
 * 压缩请求的前缀才能和上一轮对话一致，命中提示词缓存。
 */
class ChatTurnContext(
    val settings: Settings,
    val assistant: Assistant,
    val model: Model,
    val providerSetting: ProviderSetting,
    val conversationId: Uuid,
    val conversationSystemPrompt: String?,
    val workspaceCwd: String?,
    val memories: List<MemoryFile>,
    val tools: List<Tool>,
)

/** 把会话变成发给模型的请求：系统提示词、上下文裁剪、输入输出转换都在这里 */
class ChatRequestFactory(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
    private val chatToolFactory: ChatToolFactory,
    private val memoryRepository: MemoryRepository,
    templateTransformer: TemplateTransformer,
    private val workspaceRepository: WorkspaceRepository,
) {
    private val inputTransformers: List<InputMessageTransformer> = listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
        templateTransformer,
        WorkspaceReminderTransformer(workspaceRepository),
    )

    private val outputTransformers: List<OutputMessageTransformer> = listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )

    /** 解析本轮的助手、模型、记忆和工具；MCP 服务名不合法时抛出 InvalidMcpServerNamesException */
    suspend fun createContext(settings: Settings, conversation: Conversation): ChatTurnContext {
        // 模型、思考级别、搜索、工具等以会话上固定的配置为准
        val assistant = settings.getAssistantOf(conversation)
        val model = settings.getChatModelOf(conversation)
            ?: throw IllegalStateException("No chat model selected")
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        return ChatTurnContext(
            settings = settings,
            assistant = assistant,
            model = model,
            providerSetting = provider,
            conversationId = conversation.id,
            conversationSystemPrompt = conversation.customSystemPrompt,
            workspaceCwd = conversation.workspaceCwd,
            memories = if (assistant.enableMemory) memoryRepository.getFiles(assistant.memoryId) else emptyList(),
            // 保持原始文案，改写留到组装请求时套用，这样预览能拿到英文基线做对比
            tools = chatToolFactory.createTools(
                settings = settings,
                assistant = assistant,
                model = model,
                workspaceCwd = conversation.workspaceCwd,
            ),
        )
    }

    /**
     * 本轮交给 runAgent 的请求和钩子。[messages] 的最后一条是助手消息时从它继续，否则新建一条助手消息。
     * [processingStatus] 用来显示 OCR 这类输入转换的进度。
     */
    fun createAgent(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        processingStatus: MutableStateFlow<String?>,
    ): Pair<AgentRequest, AgentHooks> {
        // 工具文案的改写在这里生效：工具名不动，只换描述和参数说明
        val toolOverrides = turn.settings.systemPromptOverrides
            .filter { it.content.isNotBlank() }
            .associate { it.blockId to it.content }
        val request = AgentRequest(
            provider = usageRecorder.wrap(
                providerManager.getProviderByType(turn.providerSetting),
                UsageTag(UsageSource.CHAT, turn.assistant.id, turn.conversationId),
            ),
            providerSetting = turn.providerSetting,
            messages = messages,
            tools = turn.tools.map { it.withPromptOverrides(toolOverrides) },
            stream = turn.assistant.streamOutput,
            retry = turn.settings.retryPolicy(),
        )
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
                Log.i(TAG, "prepareStep: ${turn.model.id}")
                return buildStep(turn, messages, processingStatus)
            }

            override suspend fun previewStep(message: UIMessage, newPartsFrom: Int) =
                message.transformNewParts(newPartsFrom) {
                    it.visualTransforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                }

            override suspend fun finishStep(message: UIMessage, newPartsFrom: Int) =
                message.transformNewParts(newPartsFrom) {
                    it.transforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                        .visualTransforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                        .onGenerationFinish(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                }
        }
        return request to hooks
    }

    /**
     * 生成压缩摘要。
     *
     * 请求按正常对话组装（系统提示词、工具定义、参数和 sessionId 都相同），只在末尾追加压缩指令，
     * 前缀与上一轮对话一致，可以命中提示词缓存，模型也能看到工具调用和结果。
     * 工具定义必须保留：它属于请求前缀，去掉或改动 tool_choice 都会让缓存失效。
     */
    suspend fun generateCompressionSummary(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        compressPrompt: String,
    ): String {
        val (requestMessages, params) = buildStep(turn, messages)
        // 压缩指令不经过输入转换器，模板转换器会把其中的花括号当成模板语法
        val result = withRetry(
            policy = turn.settings.retryPolicy(),
            onRetry = { Log.w(TAG, "Compression request failed, retrying in ${it.delay}", it.cause) },
        ) {
            usageRecorder.wrap(
                providerManager.getProviderByType(turn.providerSetting),
                UsageTag(UsageSource.COMPRESSION, turn.assistant.id, turn.conversationId),
            ).generateText(
                providerSetting = turn.providerSetting,
                messages = requestMessages + UIMessage.user(compressPrompt),
                // 助手的输出上限是给聊天回复定的，结构化摘要写不完会被截断；max_tokens 不属于请求前缀，改了不影响缓存
                params = params.copy(maxTokens = null),
            )
        }
        val summary = result.message.compressionSummaryOrNull()
            ?: error(context.getString(R.string.chat_page_compress_tool_call_response))
        return summary.ifBlank { error("Failed to generate compressed summary") }
    }

    /**
     * 把这一轮的系统提示词拆成按注入顺序排列的块。
     *
     * 正常组装和「查看当前生效的提示词」共用这里，保证预览和实际发出的内容同源。
     * 用户的覆盖值会替换对应块的内容。
     */
    suspend fun buildSystemPromptBlocks(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
    ): List<SystemPromptBlock> {
        val assistant = turn.assistant
        // 空白改写按「没有改写」处理：这一块继续跟随助手 / 记忆等来源
        val overrides = turn.settings.systemPromptOverrides
            .filter { it.content.isNotBlank() }
            .associate { it.blockId to it.content }

        fun SystemPromptBlock.applyOverride(): SystemPromptBlock =
            overrides[id]?.let { copy(content = it, hasOverride = true) } ?: this

        val blocks = mutableListOf<SystemPromptBlock>()

        // ① 助手/对话自己的提示词
        val basePrompt =
            if (assistant.allowConversationSystemPrompt && !turn.conversationSystemPrompt.isNullOrBlank()) {
                turn.conversationSystemPrompt
            } else {
                assistant.systemPrompt
            }
        blocks += SystemPromptBlock(
            id = SystemPromptBlockId.BASE,
            title = if (assistant.allowConversationSystemPrompt && !turn.conversationSystemPrompt.isNullOrBlank()) {
                "对话系统提示词"
            } else {
                "助手系统提示词"
            },
            content = basePrompt,
            defaultContent = basePrompt,
        ).applyOverride()

        // ② 记忆
        if (assistant.enableMemory) {
            val memory = buildMemoryPrompt(turn.memories)
            blocks += SystemPromptBlock(
                id = SystemPromptBlockId.MEMORY,
                title = "记忆系统",
                content = memory,
                defaultContent = memory,
                dynamic = true,
            ).applyOverride()
        }

        // ③ 各工具自带的提示词
        turn.tools.forEach { tool ->
            val prompt = tool.systemPrompt(turn.model, messages)
            if (prompt.isNotBlank()) {
                blocks += SystemPromptBlock(
                    id = SystemPromptBlockId.TOOL_PREFIX + tool.name,
                    title = "工具：${tool.name}",
                    content = prompt,
                    defaultContent = prompt,
                    dynamic = true,
                ).applyOverride()
            }
        }

        return blocks
    }

    /**
     * 完整的请求体分段，供「查看当前生效的提示词」用。
     *
     * 比 [buildSystemPromptBlocks] 多出工具描述、注入、工作区提醒、时间提醒——
     * 那几处要么走 tools 参数、要么在转换阶段才生成，正常组装不需要，只有预览才需要。
     */
    suspend fun buildAllPromptSegments(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
    ): List<SystemPromptBlock> {
        val overrides = turn.settings.systemPromptOverrides
            .filter { it.content.isNotBlank() }
            .associate { it.blockId to it.content }
        val blocks = buildSystemPromptBlocks(turn, messages).toMutableList()

        // ④ 工具的 description 与参数说明（走 tools 参数，不占系统提示词）
        turn.tools.forEach { tool ->
            val descId = toolDescriptionId(tool.name)
            val descOverride = overrides[descId]
            blocks += SystemPromptBlock(
                id = descId,
                title = "工具描述 · ${tool.name}",
                content = descOverride ?: tool.description,
                defaultContent = tool.description,
                dynamic = true,
                hasOverride = descOverride != null,
                group = PromptSegmentGroup.TOOLS,
            )

            val schema = runCatching { tool.parameters() }.getOrNull() as? InputSchema.Obj
                ?: return@forEach
            schema.properties.forEach { (paramName, value) ->
                val default = (value as? JsonObject)
                    ?.get("description")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: return@forEach
                val paramId = toolParamDescriptionId(tool.name, paramName)
                val paramOverride = overrides[paramId]
                blocks += SystemPromptBlock(
                    id = paramId,
                    title = "参数 · ${tool.name}.$paramName",
                    content = paramOverride ?: default,
                    defaultContent = default,
                    dynamic = true,
                    hasOverride = paramOverride != null,
                    group = PromptSegmentGroup.TOOLS,
                )
            }
        }

        // ⑤ 提示词注入：按位置汇总
        val injections = collectInjections(
            messages = messages,
            assistant = turn.assistant,
            modeInjections = turn.settings.modeInjections,
            lorebooks = turn.settings.lorebooks,
        )
        if (injections.isNotEmpty()) {
            val byPosition = injections
                .sortedByDescending { it.priority }
                .groupBy { it.position }
            listOf(
                Triple(
                    INJECTION_BEFORE_ID,
                    "系统提示词之前",
                    InjectionPosition.BEFORE_SYSTEM_PROMPT,
                ),
                Triple(
                    INJECTION_AFTER_ID,
                    "系统提示词之后",
                    InjectionPosition.AFTER_SYSTEM_PROMPT,
                ),
                Triple(
                    INJECTION_TOP_ID,
                    "首条用户消息之前",
                    InjectionPosition.TOP_OF_CHAT,
                ),
            ).forEach { (id, title, position) ->
                val joined = byPosition[position]?.joinToString("\n") { it.content }.orEmpty()
                if (joined.isNotBlank() || overrides[id] != null) {
                    blocks += SystemPromptBlock(
                        id = id,
                        title = "注入 · $title",
                        content = overrides[id] ?: joined,
                        defaultContent = joined,
                        dynamic = true,
                        hasOverride = overrides[id] != null,
                    )
                }
            }
        }

        // ⑥ 工作区提醒：追加到系统提示词末尾
        val workspaceId = turn.assistant.workspaceId?.toString()
        if (workspaceId != null) {
            // 预览时按当前环境真实生成一次，失败就留空，不影响其它段
            val preview = runCatching {
                val workspace = workspaceRepository.getById(workspaceId) ?: return@runCatching ""
                if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return@runCatching ""
                buildWorkspaceReminderContent(
                    repository = workspaceRepository,
                    workspaceId = workspaceId,
                    workspace = workspace,
                    cwd = turn.workspaceCwd,
                )
            }.getOrDefault("")
            if (preview.isNotBlank() || overrides[WORKSPACE_REMINDER_ID] != null) {
                blocks += SystemPromptBlock(
                    id = WORKSPACE_REMINDER_ID,
                    title = "工作区提醒",
                    content = overrides[WORKSPACE_REMINDER_ID] ?: preview,
                    defaultContent = preview,
                    dynamic = true,
                    hasOverride = overrides[WORKSPACE_REMINDER_ID] != null,
                )
            }
        }

        // ⑦ 时间提醒：按首条用户消息的时间生成
        if (turn.assistant.enableTimeReminder) {
            val firstUser = messages.firstOrNull { it.role == MessageRole.USER }
            if (firstUser != null) {
                val preview = runCatching {
                    val javaInstant = firstUser.createdAt.toInstant(TimeZone.currentSystemDefault()).toJavaInstant()
                    val dayOfWeek = javaInstant.atZone(ZoneId.systemDefault()).dayOfWeek
                        .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
                    "<time_reminder>Current time: $dayOfWeek, ${javaInstant.toLocalDateTime()}</time_reminder>"
                }.getOrDefault("")
                if (preview.isNotBlank() || overrides[TIME_REMINDER_ID] != null) {
                    blocks += SystemPromptBlock(
                        id = TIME_REMINDER_ID,
                        title = "时间提醒",
                        content = overrides[TIME_REMINDER_ID] ?: preview,
                        defaultContent = preview,
                        dynamic = true,
                        hasOverride = overrides[TIME_REMINDER_ID] != null,
                        group = PromptSegmentGroup.MESSAGES,
                    )
                }
            }
        }

        return blocks
    }

    /** 一步请求实际发出的消息和参数；正常对话和压缩共用，二者的前缀必须一致才能命中提示词缓存 */
    private suspend fun buildStep(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    ): StepRequest {
        val assistant = turn.assistant
        val requestMessages = buildList {
            // 块与预览同源；空块直接跳过，避免多余的空白行
            val system = buildSystemPromptBlocks(turn, messages)
                .map { it.content.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n\n")
            if (system.isNotBlank()) {
                add(UIMessage.system(prompt = system).copy(isSynthetic = true))
            }
            addAll(messages.limitContext(assistant.contextMessageLimit))
        }.transforms(
            transformers = inputTransformers,
            context = context,
            model = turn.model,
            assistant = assistant,
            settings = turn.settings,
            processingStatus = processingStatus,
            workspaceCwd = turn.workspaceCwd,
        )
        val params = TextGenerationParams(
            model = turn.model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = turn.tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(turn.model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(turn.model.customBodies)
            },
            sessionId = turn.conversationId.toString(),
        )
        return StepRequest(requestMessages, params)
    }
}

/**
 * 取出压缩请求回复里的摘要正文。
 *
 * 指令要求不调用工具，模型仍可能发起调用。这时附带的文本只是"我先看看文件"之类的说明，
 * 当成摘要保存会让之后的请求丢掉全部历史，所以返回 null 交给调用方报错。
 * 压缩请求不经过输出转换器，正文开头的 <think> 思考内容也要在这里剥掉，否则会存进摘要。
 */
internal fun UIMessage.compressionSummaryOrNull(): String? {
    if (getTools().isNotEmpty()) return null
    return listOf(this)
        .transformThinkTags(now = Clock.System.now(), generationFinished = true)
        .single()
        .toText()
        .trim()
}

/**
 * 输出转换只处理这一步新生成的 part：之前步骤的内容已经处理过，再处理一遍会让正则这类改写叠加，
 * 也能让每一步开头的 <think> 都被识别出来。
 */
internal suspend fun UIMessage.transformNewParts(
    newPartsFrom: Int,
    transform: suspend (List<UIMessage>) -> List<UIMessage>,
): UIMessage {
    val transformed = transform(listOf(copy(parts = parts.drop(newPartsFrom)))).single()
    return transformed.copy(parts = parts.take(newPartsFrom) + transformed.parts)
}

/** 标题、建议、记忆整理这类后台请求的参数：只带模型自己的自定义请求头和请求体 */
internal fun backgroundTextGenerationParams(
    model: Model,
    sessionId: Uuid,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
    sessionId = sessionId.toString(),
)

internal fun Settings.retryPolicy() =
    if (networkSetting.enableAutoRetry) RetryPolicy.NETWORK else RetryPolicy.NONE
