package me.rerere.rikkahub.data.batch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.sum
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** 关键词在哪些内容里参与匹配 */
@Serializable
enum class KeywordScope {
    TEXT_ONLY,
    REASONING_ONLY,
    TEXT_AND_REASONING,
}

/** 关键词库的分类，与批量弹窗里的三组关键词一一对应 */
@Serializable
enum class BatchKeywordCategory {
    /** 落盘标记词 */
    @SerialName("commit")
    COMMIT,

    /** 重 roll · 仅思考内容 */
    @SerialName("reasoning")
    REASONING,

    /** 重 roll · 仅正文 */
    @SerialName("text")
    TEXT,

    /** 重 roll · 正文或思考都算 */
    @SerialName("any")
    ANY,
}

/**
 * 用户显式建的一个词库，在界面上就是一个 tab。
 *
 * 库名单独存一份，所以空库也能存在——不靠「里面有没有词」来推断库是否存在，
 * 也就不会出现那种谁都没建过、却一直挂在那里的「默认」库。
 */
@Serializable
data class BatchKeywordLibrary(
    val category: BatchKeywordCategory,
    val name: String,
)

/**
 * 一条存进关键词库的词。
 *
 * [group] 是它所属的库名，对应 [BatchKeywordLibrary.name]；分类决定它出现在哪一组关键词下面。
 * 空串只用于兼容早期没有「库」这个概念时的数据，会在界面里被归到显式建出的库里。
 */
@Serializable
data class BatchKeywordPreset(
    val category: BatchKeywordCategory,
    val group: String = "",
    val keyword: String,
)

/** 命中来源：思考内容还是正文 */
@Serializable
enum class KeywordSource {
    REASONING,
    TEXT,
}

/** 一次关键词命中 */
@Serializable
data class KeywordHit(
    val keyword: String,
    val source: KeywordSource,
)

/** 落盘时把这一路的内容放到哪里 */
@Serializable
enum class CommitTarget {
    /** 就地覆盖目标位置的那条回复（复用原 message.id，不改变分支选中项） */
    REPLACE_CURRENT,

    /** 作为当前对话的新候选追加（新建 message.id，不改变分支选中项） */
    APPEND_CANDIDATE,

    /** 从目标用户消息处分叉出一个新对话再写入 */
    NEW_BRANCH,
}

/**
 * 批量重新生成的配置。
 *
 * 落盘词只用于标记结果（命中即视为「像样」的样本），不会自动落盘。
 * 重 roll 词在流式过程中实时判定，命中即截断当前轮并重新发起；
 * 思维链与正文的重 roll 词分开配置，互不干扰。
 */
@Serializable
data class BatchRegenerationConfig(
    /** 并行路数，上限 20 */
    val parallelCount: Int = 4,
    /** 落盘标记词；为空表示全部标记为命中 */
    val commitKeywords: List<String> = emptyList(),
    /** 只在思考内容里匹配的重 roll 词；为空表示不按思考内容重 roll */
    val rerollReasoningKeywords: List<String> = emptyList(),
    /** 只在正文里匹配的重 roll 词；为空表示不按正文重 roll */
    val rerollTextKeywords: List<String> = emptyList(),
    val commitScope: KeywordScope = KeywordScope.TEXT_ONLY,
    val ignoreCase: Boolean = true,
    /** 单路最多重 roll 几次；0 表示不设限，只能靠手动停止 */
    val maxRerollsPerLane: Int = 5,
    /** 打开后即使本轮没命中重 roll 词也继续重来，直到跑满轮次 */
    val rerollOnNoHit: Boolean = false,
    /**
     * 这一批统一使用的思考深度。
     *
     * 为空表示跟随会话当前设置；填了就在这里临时覆盖，不改动会话本身，
     * 用来在不动全局配置的前提下对比「同一批换一个思考深度」的结果。
     */
    val reasoningLevelOverride: ReasoningLevel? = null,
    /**
     * 熄屏后是否继续跑。
     *
     * 关掉时靠前台服务保证切到别的 App 也能跑完；开启后会额外持有 CPU 唤醒锁，
     * 屏幕关闭也能继续，代价是熄屏期间更耗电。
     */
    val keepAwakeWhileScreenOff: Boolean = false,
) {
    /** 有任意一组重 roll 词需要实时检测 */
    val hasRerollKeywords: Boolean
        get() = rerollReasoningKeywords.isNotEmpty() || rerollTextKeywords.isNotEmpty()

    companion object {
        /** 硬上限，与 OkHttp 放开后的并发量对齐 */
        const val MAX_PARALLEL = 20
    }
}

/** 去掉空词与重复词，保持用户输入顺序 */
internal fun List<String>.normalizedKeywords(): List<String> =
    map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** 规整配置：路数夹到合法区间，关键词去空去重 */
internal fun BatchRegenerationConfig.normalized(): BatchRegenerationConfig = copy(
    parallelCount = parallelCount.coerceIn(1, BatchRegenerationConfig.MAX_PARALLEL),
    commitKeywords = commitKeywords.normalizedKeywords(),
    rerollReasoningKeywords = rerollReasoningKeywords.normalizedKeywords(),
    rerollTextKeywords = rerollTextKeywords.normalizedKeywords(),
    maxRerollsPerLane = maxRerollsPerLane.coerceAtLeast(0),
)

/** 单条路的状态 */
sealed interface LaneState {
    val index: Int

    /** 尚未开始 */
    data class Idle(override val index: Int) : LaneState

    /**
     * 生成中。A 状态不渲染正文，只展示这些元信息。
     * [attempt] 从 1 开始计数，[rerollCount] 是已经重来的次数。
     */
    data class Running(
        override val index: Int,
        val attempt: Int,
        val rerollCount: Int,
        val lastHit: KeywordHit? = null,
        /** 被服务端限流后正在退避等待的秒数，null 表示正在正常生成 */
        val waitingSeconds: Int? = null,
    ) : LaneState

    /** 跑完且命中落盘词；[hit] 记录命中的词和位置，方便在格子上核对 */
    data class Hit(
        override val index: Int,
        val message: UIMessage,
        val hit: KeywordHit? = null,
    ) : LaneState

    /** 跑完但未命中落盘词 */
    data class Clean(override val index: Int, val message: UIMessage) : LaneState

    /**
     * 反复命中重 roll 词直到用户手动停止，没有一次跑到自然结束。
     * [message] 是最后一轮被截断的内容，可能为 null。
     */
    data class Truncated(
        override val index: Int,
        val message: UIMessage?,
        val attempt: Int,
        val lastHit: KeywordHit?,
    ) : LaneState

    /** 请求失败（网络、鉴权、需要人工回答的工具等） */
    data class Failed(override val index: Int, val error: String) : LaneState

    /** 被用户停止 */
    data class Cancelled(override val index: Int, val message: UIMessage?) : LaneState
}

/** 可以点开查看内容的状态；A（Running/Idle）不可点开 */
val LaneState.isInspectable: Boolean
    get() = this is LaneState.Hit || this is LaneState.Clean ||
        this is LaneState.Truncated || this is LaneState.Cancelled

/** 一路产出的可落盘内容 */
val LaneState.resultMessage: UIMessage?
    get() = when (this) {
        is LaneState.Hit -> message
        is LaneState.Clean -> message
        is LaneState.Truncated -> message
        is LaneState.Cancelled -> message
        else -> null
    }

/** 结果是否完整；被截断的内容落盘前需要提示 */
val LaneState.isComplete: Boolean
    get() = this is LaneState.Hit || this is LaneState.Clean

/** 已落盘记录，用于在格子上显示「已落盘至」 */
@Serializable
data class LaneCommitRecord(
    val laneIndex: Int,
    val target: CommitTarget,
    /** NEW_BRANCH 时是新对话的 id */
    val branchConversationId: String? = null,
)

/** 某一路命中落盘词的事件，用于 App 在后台时推送通知 */
data class LaneHitEvent(
    val laneIndex: Int,
    val laneCount: Int,
    val keyword: String,
    val source: KeywordSource,
)

/** 一次批量任务的全部状态 */
data class BatchSession(
    /** 发起时所属的对话，用于按对话上锁；落盘也必须写回这个对话 */
    val conversationId: String,
    /**
     * 发起时所属对话的标题。
     *
     * 工作台是全局的：用户可能在别的对话里重新打开这一批，需要明确告诉他结果会写回哪个对话。
     */
    val conversationTitle: String = "",
    /** 目标用户消息所在的节点 id */
    val targetNodeId: String,
    val config: BatchRegenerationConfig,
    val lanes: List<LaneState>,
    /** 所有路都已进入终态 */
    val finished: Boolean = false,
    /** 整批累计重 roll 次数（含已跑完的路，重跑不清零） */
    val totalRerolls: Int = 0,
    /** 整批累计命中落盘词的次数（含重跑前后的命中） */
    val totalHits: Int = 0,
    /** 用户手动解锁前一直为 true */
    val locked: Boolean = true,
    val commits: List<LaneCommitRecord> = emptyList(),
    /**
     * 批次编号：每次 start 自增。
     * 解锁后上一批可能还有协程没停干净，靠它把这些残余写入丢掉，避免污染新一批格子。
     */
    val runId: Long = 0L,
) {
    fun lane(index: Int): LaneState? = lanes.getOrNull(index)

    val runningCount: Int get() = lanes.count { it is LaneState.Running || it is LaneState.Idle }
    val finishedCount: Int get() = lanes.count { it.isInspectable }
    val hitCount: Int get() = lanes.count { it is LaneState.Hit }

    /** 写入某一路的最新状态；重跑某一路会让整批重新回到未完成状态 */
    fun withLane(state: LaneState): BatchSession {
        val updated = lanes.toMutableList().also { it[state.index] = state }
        return copy(lanes = updated, finished = updated.all { it.isInspectable })
    }
}

/**
 * 续写模式：把「已被预填进去的那一段」和新生成的部分合成一条完整回复。
 *
 * 思考与正文分别拼接：前缀的思考在前、新思考在后，正文同理；
 * 其它部件（图片、工具调用等）按原顺序留在前面。这样「查看」和「落盘」拿到的都是完整内容，
 * 不需要各自记住前缀在哪。
 */
internal fun UIMessage.withContinuePrefix(prefix: UIMessage): UIMessage {

    fun isReasoning(part: UIMessagePart) = part is UIMessagePart.Reasoning
    fun isText(part: UIMessagePart) = part is UIMessagePart.Text

    val prefixReasoning = prefix.parts.filter(::isReasoning)
    val prefixRest = prefix.parts.filterNot { isReasoning(it) || isText(it) }
    val prefixText = prefix.parts.filter(::isText)
    val newReasoning = parts.filter(::isReasoning)
    val newRest = parts.filterNot { isReasoning(it) || isText(it) }
    val newText = parts.filter(::isText)

    return copy(
        parts = if (prefix.parts.isEmpty()) {
            parts
        } else {
            prefixRest + (prefixReasoning + newReasoning) + (prefixText + newText) + newRest
        },
        // 输入沿用前缀那次（从用户消息起算的真实输入），输出累加：
        // 按「一次性生成到当前内容」的等效口径记，避免把同一段上下文算两遍
        usage = run {
            val baseUsage = prefix.usage
            val newUsage = usage
            val prompt = baseUsage?.promptTokens ?: newUsage?.promptTokens ?: 0
            val completion = (baseUsage?.completionTokens ?: 0) + (newUsage?.completionTokens ?: 0)
            me.rerere.ai.core.TokenUsage(
                promptTokens = prompt,
                completionTokens = completion,
                cachedTokens = baseUsage?.cachedTokens ?: newUsage?.cachedTokens ?: 0,
                totalTokens = prompt + completion,
            )
        },
        generationTimeMs = listOfNotNull(prefix.generationTimeMs, generationTimeMs).sum(),
        // 续写不加步数：和「一次性写完」保持一致
        stepCount = listOfNotNull(prefix.stepCount, stepCount).maxOrNull(),
        // 快照型：lastUsage 是「最后一次请求」的上下文占用与缓存命中，续写后就是新的这次，
        // 相加会把上下文占用算成两倍，所以只取最新
        lastUsage = lastUsage ?: prefix.lastUsage,
        // 这条消息本身从何时开始、到何时结束
        createdAt = prefix.createdAt,
        finishedAt = finishedAt ?: prefix.finishedAt,
        // 引用标注取并集
        annotations = (prefix.annotations + annotations).distinct(),
        // 内容变了，原来的译文不再对应
        translation = null,
    )
}

/** 只取正文 */
internal fun UIMessage.batchText(): String = buildString {
    parts.forEach { part ->
        if (part is UIMessagePart.Text) appendLine(part.text)
    }
}

/** 只取思考内容 */
internal fun UIMessage.batchReasoningText(): String = buildString {
    parts.forEach { part ->
        if (part is UIMessagePart.Reasoning) appendLine(part.reasoning)
    }
}

/** 按 [scope] 取出参与匹配的文本，落盘词用 */
internal fun UIMessage.batchScanText(scope: KeywordScope): String = when (scope) {
    KeywordScope.TEXT_ONLY -> batchText()
    KeywordScope.REASONING_ONLY -> batchReasoningText()
    KeywordScope.TEXT_AND_REASONING -> batchText() + batchReasoningText()
}

/** 返回文本中出现的第一个关键词，没有命中返回 null */
internal fun List<String>.firstKeywordIn(text: String, ignoreCase: Boolean): String? {
    if (isEmpty() || text.isEmpty()) return null
    return firstOrNull { text.contains(it, ignoreCase = ignoreCase) }
}

/**
 * 分别检查思考与正文两组的重 roll 词。
 * 两组独立配置，任一组命中都算命中，返回的 [KeywordHit] 标明来源。
 */
internal fun BatchRegenerationConfig.findRerollHit(message: UIMessage): KeywordHit? {
    if (!hasRerollKeywords) return null

    rerollReasoningKeywords.firstKeywordIn(message.batchReasoningText(), ignoreCase)?.let {
        return KeywordHit(it, KeywordSource.REASONING)
    }
    rerollTextKeywords.firstKeywordIn(message.batchText(), ignoreCase)?.let {
        return KeywordHit(it, KeywordSource.TEXT)
    }
    return null
}

/**
 * 检查落盘标记词，返回命中的词与位置。
 *
 * 按 [BatchRegenerationConfig.commitScope] 决定查正文、查思考还是两者都查；
 * 命中位置会显示在格子上，避免出现「正文里找不到却被标记命中」的困惑。
 */
internal fun BatchRegenerationConfig.findCommitHit(message: UIMessage): KeywordHit? {
    if (commitKeywords.isEmpty()) return null

    if (commitScope != KeywordScope.REASONING_ONLY) {
        commitKeywords.firstKeywordIn(message.batchText(), ignoreCase)?.let {
            return KeywordHit(it, KeywordSource.TEXT)
        }
    }
    if (commitScope != KeywordScope.TEXT_ONLY) {
        commitKeywords.firstKeywordIn(message.batchReasoningText(), ignoreCase)?.let {
            return KeywordHit(it, KeywordSource.REASONING)
        }
    }
    return null
}

/** 命中关键词即抛此异常中止当前轮；不是 IOException，不会被 [me.rerere.ai.agent.withRetry] 当成网络故障重试 */
internal class LaneRerollSignal(val hit: KeywordHit) : RuntimeException("reroll: ${hit.keyword}/${hit.source}")
