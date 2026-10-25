package me.rerere.rikkahub.data.batch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.runAgent
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

/** 被服务端限流时最多退避重试几次 */
private const val MAX_RATE_LIMIT_RETRIES = 5

/** 首次退避 2 秒，之后每次翻倍（2/4/8/16/32 秒） */
private const val RATE_LIMIT_BASE_DELAY_MS = 2_000L

/**
 * 判断异常是否表示被服务端限流。
 *
 * provider 会把响应体原样透传上来（例如 `429 too many requests`），
 * 而 ai 模块里没有对应的错误类型，只能按文本识别；顺带兼容常见的限流措辞。
 */
private fun Throwable.isRateLimited(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        val text = current.message?.lowercase().orEmpty()
        if (text.contains("429") ||
            text.contains("too many request") ||
            text.contains("rate limit") ||
            text.contains("concurrent")
        ) {
            return true
        }
        current = current.cause
        depth++
    }
    return false
}

/**
 * 批量重新生成的编排器。
 *
 * 生命周期挂在 [appScope] 上而不是 ViewModel：面板关闭、切换对话都不影响正在跑的路，
 * 只有用户主动停止或重启进程才会终止。
 *
 * 每路独立执行，互相之间只共享进度状态：
 * 流式过程中用 [BatchRegenerationConfig.rerollKeywords] 实时判定，命中即中止当前轮并重新发起，
 * 重 roll 不改变并发路数；自然跑完后再用 [BatchRegenerationConfig.commitKeywords] 标记结果。
 */
class BatchGenerationController(
    private val appScope: CoroutineScope,
) {
    private val startMutex = Mutex()

    private val _session = MutableStateFlow<BatchSession?>(null)
    val session: StateFlow<BatchSession?> = _session.asStateFlow()

    private var batchJob: Job? = null
    private val laneJobs = mutableMapOf<Int, Job>()

    /** 重新发起一路所需的工厂；start 时记下来，供「重跑」使用 */
    private var laneFactory: (() -> Pair<AgentRequest, AgentHooks>)? = null

    /** 批次编号自增源，用于区分新旧两批任务 */
    private var runSeq = 0L

    /** 已经自动展开过工作台的批次编号：同一个批次不再重复弹 */
    private var autoShownRunId: Long? = null

    /** 某一路命中落盘词时发出一次，供后台通知使用 */
    private val _laneHits = MutableSharedFlow<LaneHitEvent>(extraBufferCapacity = 32)
    val laneHits: SharedFlow<LaneHitEvent> = _laneHits.asSharedFlow()

    /** 是否有批量任务正在跑（全部路都已进入终态就为 false） */
    val isRunning: Boolean
        get() = _session.value?.let { !it.finished } == true

    /** 有批次存在且尚未解锁时，所属对话禁止编辑、删除、切换分支、压缩和发起常规生成 */
    val isConversationLocked: Boolean
        get() = _session.value?.let { it.locked && it.conversationId.isNotEmpty() } == true

    /**
     * 启动一次批量任务。已有任务在跑时不启动并返回 false。
     *
     * [newAgent] 每次调用都要产出一路（或一轮重 roll）需要的请求与钩子；调用方必须在其中
     * 为每次调用新建独立的 processingStatus，避免多路互相覆盖输入转换进度。
     */
    suspend fun start(
        conversationId: Uuid,
        targetNodeId: Uuid,
        config: BatchRegenerationConfig,
        newAgent: () -> Pair<AgentRequest, AgentHooks>,
    ): Boolean = startMutex.withLock {
        if (isRunning) return@withLock false

        // 每次开始都换一个新编号：上一批万一还有协程没停干净，它们写不进这一批
        val runId = ++runSeq
        _session.value = BatchSession(
            conversationId = conversationId.toString(),
            targetNodeId = targetNodeId.toString(),
            config = config,
            lanes = List(config.parallelCount) { LaneState.Idle(it) },
            runId = runId,
        )
        laneJobs.clear()
        // 记住重新发起某一路的方式，供「重跑」使用
        laneFactory = newAgent

        batchJob = appScope.launch(Dispatchers.IO) {
            try {
                supervisorScope {
                    val handles = (0 until config.parallelCount).map { index ->
                        launch { runLane(runId, index, config, newAgent) }
                            .also { laneJobs[index] = it }
                    }
                    handles.forEach { it.join() }
                }
            } finally {
                // 被 stopAll 取消时也要置位，否则等待「已完成」的调用方会一直挂着
                withContext(NonCancellable) {
                    _session.update {
                        if (it?.runId == runId) it.copy(finished = true) else it
                    }
                }
            }
        }
        true
    }

    /** 停止某一路；其它路继续 */
    fun stopLane(index: Int) {
        laneJobs[index]?.cancel()
    }

    /**
     * 重跑某一路：失败、被截断、或者对结果不满意都可以重来。
     * 已经在跑的路忽略；重跑期间整批重新回到「未完成」，前台服务和对话锁也随之恢复。
     */
    fun rerunLane(index: Int) {
        val factory = laneFactory ?: return
        val current = _session.value ?: return
        val lane = current.lane(index) ?: return
        if (!lane.isInspectable) return

        val runId = current.runId
        laneJobs[index]?.cancel()
        publish(runId, index, LaneState.Running(index, attempt = 1, rerollCount = 0))

        laneJobs[index] = appScope.launch(Dispatchers.IO) {
            runLane(runId, index, current.config, factory)
        }
    }

    /** 重跑所有失败的路；没有失败时什么也不做 */
    fun rerunFailedLanes() {
        val current = _session.value ?: return
        current.lanes
            .filterIsInstance<LaneState.Failed>()
            .forEach { rerunLane(it.index) }
    }

    /** 是否有需要重跑的失败路 */
    val hasFailedLanes: Boolean
        get() = _session.value?.lanes?.any { it is LaneState.Failed } == true

    /**
     * 这个批次要不要自动展开工作台。
     *
     * 每个批次只返回一次 true：用户收起之后，切换对话、重新进入都不该再被强行弹出来。
     */
    fun consumeAutoShow(runId: Long): Boolean {
        if (autoShownRunId == runId) return false
        autoShownRunId = runId
        return true
    }

    /**
     * 停止全部路。
     *
     * 先把还在跑的路就地标成已停止，界面立刻有反馈；再取消协程——
     * 取消是异步的，等它自己收尾的话，格子会在一小段时间里仍然显示「运行中」。
     * 被取消的协程随后会再写一次带内容的 Cancelled，覆盖这里的占位。
     */
    fun stopAll() {
        _session.update { current ->
            if (current == null) return@update current
            val settled = current.lanes.map { lane ->
                if (lane is LaneState.Running) LaneState.Cancelled(lane.index, null) else lane
            }
            current.copy(lanes = settled, finished = settled.all { it.isInspectable })
        }
        batchJob?.cancel()
        // 重跑启动的 job 不在 batchJob 层级里，单独停
        laneJobs.values.forEach { it.cancel() }
    }

    /** 关闭面板时调用：停止并清空。已落盘的内容不受影响 */
    fun discard() {
        batchJob?.cancel()
        // 重跑启动的 job 不在 batchJob 的层级里，必须单独取消，否则它们会继续跑并写旧数据
        laneJobs.values.forEach { it.cancel() }
        batchJob = null
        laneJobs.clear()
        laneFactory = null
        _session.value = null
    }

    /** 解锁即结束这批结果：停止未完成的路、清空格子，不再缓存，也不会在下次进入对话时弹面板 */
    fun unlock() {
        discard()
    }

    /** 记录一次落盘，用于在格子上显示「已落盘至」 */
    fun markCommitted(record: LaneCommitRecord) {
        _session.update { it?.copy(commits = it.commits + record) }
    }

    fun commitsOf(laneIndex: Int): List<LaneCommitRecord> =
        _session.value?.commits?.filter { it.laneIndex == laneIndex }.orEmpty()

    private suspend fun runLane(
        runId: Long,
        index: Int,
        config: BatchRegenerationConfig,
        newAgent: () -> Pair<AgentRequest, AgentHooks>,
    ) {
        var attempt = 1
        var rerollCount = 0
        var lastHit: KeywordHit? = null
        var rateLimitRetries = 0
        var rateLimitHits = 0

        publish(runId, index, LaneState.Running(index, attempt, rerollCount, lastHit))

        while (true) {
            var latest: UIMessage? = null
            var hit: KeywordHit? = null
            try {
                val (request, hooks) = newAgent()
                runAgent(request, hooks).flowOn(Dispatchers.IO).collect { event ->
                    when (event) {
                        is AgentEvent.MessageUpdated -> {
                            latest = event.message
                            // 重 roll 只在流式过程中实时判定，思考与正文两组词分别检查，命中即中止本轮
                            config.findRerollHit(event.message)?.let { throw LaneRerollSignal(it) }
                        }

                        is AgentEvent.Finished -> latest = event.message

                        // 网络重试的提示只用于显示，不影响判定
                        is AgentEvent.Retrying -> Unit
                    }
                }
            } catch (signal: LaneRerollSignal) {
                hit = signal.hit
            } catch (e: CancellationException) {
                publish(runId, index, LaneState.Cancelled(index, latest))
                throw e
            } catch (e: Exception) {
                // 被服务端限流（429 / too many requests）时先退避重试，而不是直接把这路判死
                if (e.isRateLimited() && rateLimitRetries < MAX_RATE_LIMIT_RETRIES) {
                    rateLimitRetries++
                    val waitMs = RATE_LIMIT_BASE_DELAY_MS shl (rateLimitRetries - 1)
                    val waitSeconds = (waitMs / 1000).toInt()
                    publish(
                        index,
                        LaneState.Running(index, attempt, rerollCount, lastHit, waitSeconds),
                    )
                    delay(waitMs)
                    attempt++
                    rateLimitHits++
                    continue
                }
                publish(
                    index,
                    LaneState.Failed(
                        index,
                        if (rateLimitHits > 0) {
                            "被限流重试 $rateLimitHits 次仍失败：${e.message ?: e.javaClass.simpleName}"
                        } else {
                            e.message ?: e.javaClass.simpleName
                        },
                    ),
                )
                return
            }

            // 轮次上限：0 表示不设限，只能靠手动停止
            val limit = config.maxRerollsPerLane
            val reachedLimit = limit > 0 && rerollCount >= limit

            if (hit != null) {
                // 命中重 roll 词：还有额度就重来，额度用完就保留这轮被截断的内容
                if (reachedLimit) {
                    publish(runId, index, LaneState.Truncated(index, latest, attempt, hit))
                    return
                }
                rerollCount++
                lastHit = hit
                attempt++
                bumpTotalRerolls(runId)
                publish(runId, index, LaneState.Running(index, attempt, rerollCount, lastHit))
                continue
            }

            val message = latest
            if (message == null) {
                publish(runId, index, LaneState.Failed(index, "no message produced"))
                return
            }

            // 落盘词只做标记：配了才判定，没配就一律不算命中
            val commitHit = config.findCommitHit(message)

            // 已经命中落盘词 = 这轮就是要的结果，直接收下，不再重来。
            // 这个判断必须排在「未命中也重 roll」之前，否则会把好结果丢掉。
            if (commitHit != null) {
                publish(runId, index, LaneState.Hit(index, message, commitHit))
                bumpTotalHits(runId)
                // 通知给 ChatService：后台时弹通知栏。旧批次的残余协程不该发通知
                if (_session.value?.runId == runId) {
                    _laneHits.tryEmit(
                        LaneHitEvent(
                            laneIndex = index,
                            laneCount = config.parallelCount,
                            keyword = commitHit.keyword,
                            source = commitHit.source,
                        ),
                    )
                }
                return
            }

            // 没命中落盘词、开关打开：继续重来，直到跑满轮次
            if (config.rerollOnNoHit && !reachedLimit) {
                rerollCount++
                lastHit = null
                attempt++
                bumpTotalRerolls(runId)
                publish(runId, index, LaneState.Running(index, attempt, rerollCount, lastHit))
                continue
            }

            publish(runId, index, LaneState.Clean(index, message))
            return
        }
    }

    /**
     * 写入某一路的状态。
     *
     * [runId] 用来丢弃上一批遗留协程的写入：解锁（discard）后旧协程可能还没完全停下，
     * 不校验的话它们会把上一批的轮次写进新一批的格子，看起来就是数字来回闪。
     */
    private fun publish(runId: Long, index: Int, state: LaneState) {
        _session.update { current ->
            if (current == null || current.runId != runId) current
            else if (index !in current.lanes.indices) current
            else current.withLane(state)
        }
    }

    /** 整批累计重 roll 次数 +1，用于工作台顶部的总览 */
    private fun bumpTotalRerolls(runId: Long) {
        _session.update {
            if (it == null || it.runId != runId) it
            else it.copy(totalRerolls = it.totalRerolls + 1)
        }
    }

    /** 整批累计命中次数 +1 */
    private fun bumpTotalHits(runId: Long) {
        _session.update {
            if (it == null || it.runId != runId) it
            else it.copy(totalHits = it.totalHits + 1)
        }
    }
}
