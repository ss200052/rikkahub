package me.rerere.ai.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.sum
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.mergeAdjacentSameType
import me.rerere.ai.util.StreamIdleTimeoutException
import me.rerere.ai.util.json
import me.rerere.ai.util.withIdleTimeout
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// 输出为空的工具会被当成没执行过，之后每一步都会再执行一次，所以换成占位文本
private const val EMPTY_TOOL_OUTPUT = "(no output)"

// 流式响应两段空闲上限：还没收到第一个内容时可能只是上游在排队，给得宽一些；
// 开始产出之后长时间静默基本就是连接死了，判定得紧一些。
private const val STREAM_IDLE_BEFORE_FIRST_CHUNK_MS = 180_000L
private const val STREAM_IDLE_AFTER_CHUNK_MS = 90_000L

private class StreamChunkHandlingException(override val cause: Throwable) : RuntimeException(cause)

internal class AgentLoop(
    private val request: AgentRequest,
    private val hooks: AgentHooks,
    private val events: FlowCollector<AgentEvent>,
) {
    private val history: List<UIMessage>

    // 本轮的助手消息；新的一轮在第一次请求模型时才创建
    private var message: UIMessage?

    /**
     * 接着一条已有回复往下写时，第一步只是在延续那条消息，不算新增一次请求。
     *
     * 这样「打断后继续生成」的步数和「一次写完」保持一致；
     * 之后的步骤（工具调用重来一轮）照常计步。
     */
    private var firstStepContinuesExisting = false

    init {
        val last = request.messages.lastOrNull()
        if (last?.role == MessageRole.ASSISTANT) {
            history = request.messages.dropLast(1)
            message = last
            firstStepContinuesExisting = true
        } else {
            history = request.messages
            message = null
        }
    }

    suspend fun run() {
        var steps = 0
        while (true) {
            val current = message
            var toolsExecuted = false
            if (current != null && current.getTools().any { !it.isExecuted }) {
                val pending = requestApprovals(current)
                if (pending.isNotEmpty()) return finish(AgentFinishReason.AwaitingApproval(pending))
                executeTools()
                toolsExecuted = true
            } else if (current != null && steps > 0) {
                return finish(AgentFinishReason.Completed)
            }
            if (steps == request.maxSteps) return finish(AgentFinishReason.StepLimitReached)
            // 只在刚执行完工具时问：接着一条已经执行完的消息继续时不问，否则暂停之后再运行又会立刻暂停
            if (toolsExecuted && hooks.shouldPause(checkNotNull(message))) return finish(AgentFinishReason.Paused)
            generateStep()
            steps++
        }
    }

    // 新的工具调用按定义判断是否需要审批，需要的标为 Pending；返回仍在等待审批的调用
    private suspend fun requestApprovals(current: UIMessage): Set<String> {
        val updated = current.copy(
            parts = current.parts.map { part ->
                if (part is UIMessagePart.Tool && !part.isExecuted &&
                    part.approvalState == ToolApprovalState.Auto &&
                    findTool(part.toolName)?.needsApproval?.invoke(part.inputAsJson()) == true
                ) {
                    part.copy(approvalState = ToolApprovalState.Pending)
                } else {
                    part
                }
            }
        )
        if (updated != current) update(updated)
        return updated.getTools().filter { it.isPending }.mapTo(LinkedHashSet()) { it.toolCallId }
    }

    private suspend fun generateStep() {
        appendPendingParts()
        val current = message
        // 接着一条已有回复往下写时，只把正文（和工具结果）当前缀，把上一次的思考剥掉。
        // 否则模型看到 assistant 消息里已经带着 reasoning，会理解成「上一轮思考还没写完」，
        // 于是从头重想一遍，而不是接着已有正文继续写——用户看到的就是「续写从重跑思维链开始」。
        val prefix = if (firstStepContinuesExisting) {
            current?.withoutReasoningForContinuation()
        } else {
            current
        }
        val step = hooks.prepareStep(if (prefix == null) history else history + prefix)
        // 接着已有正文续写时关掉思考：模型该做的是把正文往下写，
        // 再给它开着思考只会从头想一遍，续写出来的东西又要多出一段思考卡片。
        val params = step.params.copy(
            tools = request.tools,
            reasoningLevel = if (firstStepContinuesExisting && current?.hasTextContent() == true) {
                ReasoningLevel.OFF
            } else {
                step.params.reasoningLevel
            },
        )
        // 只带着追加内容创建的消息还没有模型 id
        val base = current?.let { it.copy(modelId = it.modelId ?: params.model.id) } ?: UIMessage(
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            modelId = params.model.id,
        )
        // 紧接着续写的第一步不额外计步，之后恢复正常
        val countStep = !firstStepContinuesExisting
        firstStepContinuesExisting = false
        val generated = if (request.stream) {
            stream(step.messages, params, base, countStep)
        } else {
            generate(step.messages, params, base, countStep)
        }
        // 输出变换按 base.parts.size 定位新内容，必须拿未合并的结构；
        // 变换完成后再把相邻的正文/思考并回一段，消息里就只剩一段思考、一段正文。
        val finished = hooks.finishStep(generated, newPartsFrom = base.parts.size).copy(finishedAt = now())
        update(finished.mergedTextParts())
    }

    // 续写普通回复时最后一个 part 不是执行完的工具或通知，这时追加会把续写变成新的一轮
    private suspend fun appendPendingParts() {
        val current = message
        if (current != null && !current.parts.lastOrNull().isModelsTurn()) return
        val parts = hooks.pendingParts()
        if (parts.isEmpty()) return
        val base = current ?: UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        update(base.copy(parts = base.parts + parts))
    }

    private suspend fun stream(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        base: UIMessage,
        countStep: Boolean = true,
    ): UIMessage {
        // 多轮工具调用共用同一条助手消息，消息上已有的 usage 和耗时来自之前的步骤。
        // 本步从清空的统计开始（merge 只适合同一次请求内的快照），再与之前的步骤累加。
        val stepStart = base.copy(usage = null, generationTimeMs = null)
        // 上一次尝试被判定为空闲超时时，已经生成的内容留在这里。
        // 重试时把它交给模型接着写，而不是从用户消息开始整段重来：
        // 批量并发下重来一遍就是双份开销，还会把已经展示给用户的内容冲掉。
        var interrupted: UIMessage? = null
        return try {
            withRetry(request.retry, onRetry = { events.emit(AgentEvent.Retrying(it)) }) {
                // 每次重试都从本步开始时的消息重新合并，不会把重试的响应接在已经展示的半截回复后面；
                // 各次尝试共用同一个消息 id，调用方直接覆盖即可。
                val handler = StreamChunkHandler(params.model)
                var attempt = interrupted ?: stepStart
                var firstChunk: TimeMark? = null
                // 有中断前的正文才谈得上续写；空内容的中断直接重新请求
                val continuation = interrupted?.takeIf { it.parts.isNotEmpty() }
                val attemptMessages = when {
                    continuation == null -> messages
                    // 末尾已经是助手消息时替换它，避免出现两条相邻的 assistant
                    messages.lastOrNull()?.role == MessageRole.ASSISTANT ->
                        messages.dropLast(1) + continuation

                    else -> messages + continuation
                }
                try {
                    // SSE 的 keep-alive 注释会让 readTimeout 永远不触发，这里按「有没有真实内容」判定
                    withIdleTimeout(
                        firstElementMillis = STREAM_IDLE_BEFORE_FIRST_CHUNK_MS,
                        subsequentMillis = STREAM_IDLE_AFTER_CHUNK_MS,
                    ) { onElement ->
                        request.provider.streamText(request.providerSetting, attemptMessages, params)
                            .collect { chunk ->
                                try {
                                    onElement()
                                    val mark = firstChunk ?: TimeSource.Monotonic.markNow().also { firstChunk = it }
                                    attempt = handler.handle(listOf(attempt), chunk).single()
                                    preview(
                                        attempt.mergedTextParts()
                                            .withStepStats(base, mark.elapsedNow(), countStep),
                                        newPartsFrom = base.parts.size,
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    // 合并失败或下游处理失败都不是网络故障，不能重放模型请求
                                    throw StreamChunkHandlingException(e)
                                }
                            }
                    }
                } catch (e: StreamIdleTimeoutException) {
                    // 留作下一次尝试的续写起点
                    interrupted = attempt
                    throw e
                }
                attempt.withStepStats(base, firstChunk?.elapsedNow() ?: Duration.ZERO, countStep)
            }
        } catch (e: StreamChunkHandlingException) {
            throw e.cause
        }
    }

    private suspend fun generate(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        base: UIMessage,
        countStep: Boolean = true,
    ): UIMessage {
        // 非流式拿不到首 token 时间，只能统计最后一次尝试的整个请求耗时
        var requestMark = TimeSource.Monotonic.markNow()
        val result = withRetry(request.retry, onRetry = { events.emit(AgentEvent.Retrying(it)) }) {
            requestMark = TimeSource.Monotonic.markNow()
            request.provider.generateText(request.providerSetting, messages, params)
        }
        val requestTime = requestMark.elapsedNow()
        // 和流式一样只追加 part，不并进之前步骤的文本或推理里，否则 newPartsFrom 之后就不全是这一步的内容了。
        // 一次请求只有一份 usage；没有返回时保持为空，累加时沿用之前步骤的统计
        val generated = base.copy(
            parts = base.parts + result.message.parts,
            annotations = result.message.annotations.ifEmpty { base.annotations },
            modelId = params.model.id,
            usage = result.usage,
        ).finishReasoning().withStepStats(base, requestTime, countStep)
        preview(generated, newPartsFrom = base.parts.size)
        return generated
    }

    // 按调用顺序逐个执行，每执行完一个就更新一次，中途取消时已经执行的结果不会丢
    private suspend fun executeTools() {
        val tools = checkNotNull(message).getTools().filter { !it.isExecuted }
        for (tool in tools) {
            // 只给真正运行的工具计时；开始时先写一次，界面才能在执行中实时显示耗时
            val runs = tool.approvalState == ToolApprovalState.Auto || tool.approvalState == ToolApprovalState.Approved
            if (runs) updateTool(tool.toolCallId) { it.copy(startedAt = Clock.System.now(), finishedAt = null) }
            val output = execute(tool)
            updateTool(tool.toolCallId) {
                it.copy(output = output, finishedAt = if (runs) Clock.System.now() else it.finishedAt)
            }
        }
    }

    private suspend fun updateTool(toolCallId: String, transform: (UIMessagePart.Tool) -> UIMessagePart.Tool) {
        val current = checkNotNull(message)
        update(
            current.copy(
                parts = current.parts.map { part ->
                    if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) transform(part) else part
                }
            )
        )
    }

    private suspend fun execute(tool: UIMessagePart.Tool): List<UIMessagePart> =
        when (val state = tool.approvalState) {
            is ToolApprovalState.Denied -> errorOutput(
                "Tool execution denied by user. Reason: ${state.reason.ifBlank { "No reason provided" }}"
            )

            is ToolApprovalState.Answered -> listOf(UIMessagePart.Text(state.answer))
            ToolApprovalState.Auto, ToolApprovalState.Approved -> runTool(tool)
            ToolApprovalState.Pending -> error("Tool ${tool.toolCallId} is still waiting for approval")
        }

    private suspend fun runTool(tool: UIMessagePart.Tool): List<UIMessagePart> = try {
        val definition = findTool(tool.toolName) ?: error("Tool ${tool.toolName} not found")
        val args = runCatching { json.parseToJsonElement(tool.input.ifBlank { "{}" }) }
            .getOrElse { error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}") }
        definition.execute(args).ifEmpty { listOf(UIMessagePart.Text(EMPTY_TOOL_OUTPUT)) }
    } catch (e: CancellationException) {
        // 取消必须向上传播，否则停止生成会被误报为工具执行错误
        throw e
    } catch (e: Throwable) {
        errorOutput("[${e.javaClass.name}] ${e.message}\n${e.stackTraceToString()}")
    }

    private fun findTool(name: String) = request.tools.find { it.name == name }

    private suspend fun update(updated: UIMessage) {
        message = updated
        events.emit(AgentEvent.MessageUpdated(updated))
    }

    // 生成中途的内容只发给调用方，这一步结束前循环内部一直基于原始内容合并
    private suspend fun preview(generating: UIMessage, newPartsFrom: Int) {
        events.emit(AgentEvent.MessageUpdated(hooks.previewStep(generating, newPartsFrom)))
    }

    private suspend fun finish(reason: AgentFinishReason) {
        events.emit(AgentEvent.Finished(checkNotNull(message), reason))
    }
}

private fun UIMessagePart?.isModelsTurn() =
    (this is UIMessagePart.Tool && isExecuted) || this is UIMessagePart.Notice

private fun errorOutput(error: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject { put("error", error) }.toString()))

// 把本步的 usage 和生成耗时累加到之前步骤的统计上；接收者只带本步的 usage
private fun UIMessage.withStepStats(stepBase: UIMessage, stepTime: Duration, countStep: Boolean = true): UIMessage = copy(
    usage = if (countStep) {
        stepBase.usage.sum(usage)
    } else {
        // 续写：输入沿用基座那次（也就是从用户消息起算的真实输入），输出累加。
        // 续写请求的输入里含已有正文，累加会把同一段上下文算两遍，
        // 这里按「一次性生成到当前内容」的等效口径记。
        val baseUsage = stepBase.usage
        val stepUsage = usage
        val prompt = baseUsage?.promptTokens ?: stepUsage?.promptTokens ?: 0
        val completion = (baseUsage?.completionTokens ?: 0) + (stepUsage?.completionTokens ?: 0)
        TokenUsage(
            promptTokens = prompt,
            completionTokens = completion,
            cachedTokens = baseUsage?.cachedTokens ?: stepUsage?.cachedTokens ?: 0,
            totalTokens = prompt + completion,
        )
    },
    generationTimeMs = (stepBase.generationTimeMs ?: 0L) + stepTime.inWholeMilliseconds,
    // 上下文占用只看最后一次请求，usage 是多步累计值；本步没返回 usage 时沿用之前步骤的
    lastUsage = usage ?: stepBase.lastUsage,
    stepCount = if (countStep) (stepBase.stepCount ?: 0) + 1 else (stepBase.stepCount ?: 0),
)

private fun now() = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

/**
 * 续写时给模型看的前缀：去掉思考，只留正文与工具结果。
 *
 * 只剩思考、还没有正文时原样返回——那种情况下模型确实需要把正文补出来，
 * 把思考也拿掉反而会让它没有任何上下文可接。
 */
private fun UIMessage.withoutReasoningForContinuation(): UIMessage {
    val hasText = parts.any { it is UIMessagePart.Text && it.text.isNotBlank() }
    if (!hasText) return this
    val kept = parts.filterNot { it is UIMessagePart.Reasoning }
    return if (kept.isEmpty()) this else copy(parts = kept)
}

/** 消息级的合并入口，具体规则见 [mergeAdjacentSameType] */
private fun UIMessage.mergedTextParts(): UIMessage {
    val merged = parts.mergeAdjacentSameType()
    return if (merged.size == parts.size) this else copy(parts = merged)
}

/** 消息里有没有非空正文；没有正文的续写要从思考接着往下写 */
private fun UIMessage.hasTextContent(): Boolean =
    parts.any { it is UIMessagePart.Text && it.text.isNotBlank() }
