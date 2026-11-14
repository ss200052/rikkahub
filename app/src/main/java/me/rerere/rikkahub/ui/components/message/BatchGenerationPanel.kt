package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.batch.BatchRegenerationConfig
import me.rerere.rikkahub.data.batch.BatchSession
import me.rerere.rikkahub.data.batch.CommitTarget
import me.rerere.rikkahub.data.batch.KeywordSource
import me.rerere.rikkahub.data.batch.LaneCommitRecord
import me.rerere.rikkahub.data.batch.KeywordScope
import me.rerere.rikkahub.data.batch.LaneState
import me.rerere.rikkahub.ui.components.ai.ProviderQuotaText
import me.rerere.rikkahub.ui.components.ai.rememberProviderQuota
import me.rerere.rikkahub.data.batch.isComplete
import me.rerere.rikkahub.data.batch.isInspectable

/** 每格固定高度：内容多少都不改变卡片大小 */
private val TILE_HEIGHT = 84.dp

/**
 * 批量生成工作台：每一路一个格子。
 *
 * 运行中（A 状态）的格子不渲染正文，只显示轮次、重 roll 次数和最近命中的词，也不能点开；
 * 进入终态后点开可以查看完整的思维链与正文，并从那里发起落盘。
 */
@Composable
fun BatchGenerationPanel(
    session: BatchSession,
    onStopLane: (Int) -> Unit,
    onStopAll: () -> Unit,
    onUnlock: () -> Unit,
    onDismiss: () -> Unit,
    onInspect: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    onRerunLane: (Int) -> Unit = {},
    onRerunFailed: () -> Unit = {},
    notifyOnHit: Boolean = true,
    onToggleNotify: (Boolean) -> Unit = {},
    /** 这一批不属于当前对话时给出的提示，说明落盘会写回哪里；为空则不显示 */
    ownerNotice: String? = null,
    /** 跨对话时提供的「回到所属对话」动作；为 null 则不显示入口 */
    onGoToOwner: (() -> Unit)? = null,
    /** 运行中临时插一路，返回 false 表示插不进去（已到上限或批次结束） */
    onAddLane: () -> Boolean = { false },
    /** 运行中调整轮次上限；只改这一个值，请求体不动 */
    onUpdateMaxRerolls: (Int) -> Unit = {},
    /** 当前对话用的提供商；支持查余额时会在顶部显示剩余额度 */
    quotaProvider: me.rerere.ai.provider.ProviderSetting? = null,
    /** 本批已经花掉的金额；null 表示还没统计出来 */
    batchSpent: java.math.BigDecimal? = null,
    /** 运行中切换「熄屏也跑」 */
    onToggleKeepAwake: (Boolean) -> Unit = {},
) {
    var confirmUnlock by remember { mutableStateOf(false) }
    var showRunningSettings by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("批量重新生成", style = MaterialTheme.typography.titleMedium)
                        if (ownerNotice != null) {
                            Text(
                                text = ownerNotice,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            if (onGoToOwner != null) {
                                TextButton(
                                    onClick = onGoToOwner,
                                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                                    modifier = Modifier.heightIn(min = 0.dp),
                                ) {
                                    Text(
                                        text = "回到该任务所属对话",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                        // 每一项独立成块，宽度不够时自动换行，不会挤成一团
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            summaryItems(session).forEach { item ->
                                Text(
                                    text = item,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    if (!session.finished) {
                        TextButton(onClick = onStopAll) { Text("全部停止") }
                    }
                    if (session.lanes.any { it is me.rerere.rikkahub.data.batch.LaneState.Failed }) {
                        TextButton(onClick = onRerunFailed) { Text("重跑全部失败") }
                    }
                }

                // 额度：只在提供商支持查询时显示，查不到就静默不占位。
                // 面板会一直开着跑几十分钟，必须定时重查，否则数字停在打开那一刻
                if (quotaProvider != null) {
                    val quotaResult = rememberProviderQuota(quotaProvider, refreshIntervalMillis = 5_000)
                    val quota = quotaResult?.getOrNull()
                    if (quota != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "余额",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                            ProviderQuotaText(
                                providerSetting = quotaProvider,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            batchSpent?.let { spent ->
                                Text(
                                    text = "· 本次已用 ${spent.stripTrailingZeros().toPlainString()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                // 运行中才出现的两个操作：看当前设置、临时加一路
                if (!session.finished) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { showRunningSettings = true }) { Text("当前设置") }
                        OutlinedButton(
                            onClick = { onAddLane() },
                            enabled = session.lanes.size < BatchRegenerationConfig.MAX_PARALLEL,
                        ) { Text("增加路数") }
                        Text(
                            text = "共 ${session.lanes.size} 路（上限 ${BatchRegenerationConfig.MAX_PARALLEL}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // 两个开关排一行，缩小以省纵向空间
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TogglePair(
                        label = "命中通知",
                        checked = notifyOnHit,
                        onCheckedChange = onToggleNotify,
                        modifier = Modifier.weight(1f),
                    )
                    TogglePair(
                        label = "熄屏继续",
                        checked = session.config.keepAwakeWhileScreenOff,
                        onCheckedChange = onToggleKeepAwake,
                        modifier = Modifier.weight(1f),
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // 一行固定两个格子，宽度随屏幕自适应
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) {
                    items(session.lanes, key = { it.index }) { lane ->
                        LaneTile(
                            state = lane,
                            rerollLimit = session.config.maxRerollsPerLane,
                            onStop = { onStopLane(lane.index) },
                            onInspect = { onInspect(lane.index) },
                            onCommit = { onCommit(lane.index) },
                            onRerun = { onRerunLane(lane.index) },
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (session.locked) {
                        OutlinedButton(onClick = { confirmUnlock = true }) { Text("解锁对话") }
                        Text(
                            text = "解锁前该对话不能编辑、删除或继续生成",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Text(
                            text = "已解锁",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("收起") }
                }
            }
        }
    }

    if (showRunningSettings) {
        RunningSettingsDialog(
            session = session,
            onDismiss = { showRunningSettings = false },
            onConfirm = { limit ->
                showRunningSettings = false
                onUpdateMaxRerolls(limit)
            },
        )
    }

    // 解锁会清空格子，先确认一次
    if (confirmUnlock) {
        AlertDialog(
            onDismissRequest = { confirmUnlock = false },
            title = { Text("解锁对话") },
            text = {
                Text(
                    buildString {
                        val running = session.lanes.count { it is LaneState.Running }
                        if (running > 0) {
                            append("还有 $running 路正在生成，解锁会先停止它们。")
                            appendLine()
                        }
                        append("解锁后所有格子都会被清空，未落盘的结果会丢失。")
                        if (session.commits.isNotEmpty()) {
                            appendLine()
                            append("已经落盘到对话里的 ${session.commits.size} 处内容不受影响。")
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUnlock = false
                        onUnlock()
                    },
                ) { Text("解锁并清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnlock = false }) { Text("取消") }
            },
        )
    }
}

/** 顶部总览的每一项；分开返回以便按宽度自动换行 */
private fun summaryItems(session: BatchSession): List<String> = buildList {
    add(
        if (session.finished) "已全部结束"
        else "进行中 ${session.finishedCount}/${session.lanes.size}",
    )
    add("命中 ${session.hitCount}")
    if (session.totalHits > 0) add("累计命中 ${session.totalHits} 次")
    add("重 roll ${session.totalRerolls} 次")
    // 落盘统计统一放这里，卡片上不再各自重复
    if (session.commits.isNotEmpty()) {
        val byTarget = session.commits.groupingBy { it.target }.eachCount()
        CommitTarget.entries.forEach { target ->
            val count = byTarget[target] ?: return@forEach
            add("落盘·${target.shortLabel()} $count")
        }
    }
}

@Composable
private fun LaneTile(
    state: LaneState,
    rerollLimit: Int,
    onStop: () -> Unit,
    onInspect: () -> Unit,
    onCommit: () -> Unit,
    onRerun: () -> Unit,
) {
    val inspectable = state.isInspectable
    val accent = state.accentColor()
    var confirmRerun by remember(state.index) { mutableStateOf(false) }
    // 次要文字跟随卡片底色，换配色时不用逐个改
    val secondary = LocalContentColor.current.copy(alpha = 0.72f)

    Card(
        // 每格高度写死：状态、明细、动作三块位置固定，不会因为文字长短上下跳
        modifier = Modifier
            .fillMaxWidth()
            .height(TILE_HEIGHT),
        colors = state.tileColors(),
        onClick = { if (inspectable) onInspect() },
        enabled = inspectable,
    ) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 5.dp),
            ) {
                // 文字区占满动作行以上的空间：写不下就自然裁掉，不显示省略号
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clipToBounds(),
                ) {
                    // 状态是第一行；右侧留出角标的位置，免得被压住
                    Text(
                        text = state.statusLabel(),
                        style = MaterialTheme.typography.bodySmall,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.padding(end = 28.dp),
                    )

                    state.detailLabel(rerollLimit)?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = secondary,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }

                // 动作固定一行，等宽均分，不再折行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state is LaneState.Running) {
                        CompactAction("停止", onStop, Modifier.weight(1f))
                    }
                    if (state !is LaneState.Running && state !is LaneState.Idle) {
                        // 任何终态都能重跑：失败、截断、或者对结果不满意
                        CompactAction("重跑", { confirmRerun = true }, Modifier.weight(1f))
                    }
                    if (inspectable) {
                        CompactAction("查看", onInspect, Modifier.weight(1f))
                        CompactAction("落盘", onCommit, Modifier.weight(1f))
                    }
                }
            }

            // 路号做成右上角角标：单独一行会白占一行高度
            Text(
                text = "路 ${state.index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 8.dp),
            )
        }
    }

    // 重跑会丢弃这一路当前的内容，先确认一次
    if (confirmRerun) {
        AlertDialog(
            onDismissRequest = { confirmRerun = false },
            title = { Text("重跑 路 ${state.index + 1}") },
            text = {
                Text("这一路当前的内容会被丢弃，重新生成一次。已经落盘到对话里的内容不受影响。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRerun = false
                        onRerun()
                    },
                ) { Text("重跑") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRerun = false }) { Text("取消") }
            },
        )
    }
}

/** 紧凑的小按钮：去掉默认的大内边距，字也小一号 */
@Composable
private fun CompactAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
        modifier = modifier.height(24.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

private fun LaneState.statusLabel(): String = when (this) {
    is LaneState.Idle -> "等待开始"
    is LaneState.Running ->
        if (waitingSeconds != null) "限流等待 ${waitingSeconds}s" else "运行中 · 第 $attempt 轮"
    is LaneState.Hit -> "命中落盘词"
    is LaneState.Clean -> "完成 · 未命中"
    is LaneState.Truncated -> "未完成 · 被截断"
    is LaneState.Failed -> "失败"
    is LaneState.Cancelled -> "已停止"
}

private fun LaneState.detailLabel(rerollLimit: Int): String? = when (this) {
    // 轮次只写「已用/上限」，不再铺一句「已重 roll N 次」
    is LaneState.Running -> buildString {
        append(if (rerollLimit > 0) "重roll $rerollCount/$rerollLimit" else "重roll $rerollCount/不限")
        lastHit?.let {
            append(" · 命中${if (it.source == KeywordSource.REASONING) "思考" else "正文"}：${it.keyword}")
        }
        if (waitingSeconds != null) append(" · 限流等待 $waitingSeconds 秒")
    }

    is LaneState.Truncated -> buildString {
        // 被截断这一路没有独立的重 roll 计数，用总轮次表达同一个意思
        append(if (rerollLimit > 0) "轮次 $attempt≥$rerollLimit" else "轮次 $attempt")
        lastHit?.let { append(" · 最后命中${if (it.source == KeywordSource.REASONING) "思考" else "正文"}：${it.keyword}") }
    }

    is LaneState.Failed -> error
    is LaneState.Hit -> hit?.let {
        "命中${if (it.source == KeywordSource.REASONING) "思考" else "正文"}：${it.keyword}"
    } ?: "点击查看完整内容"
    is LaneState.Clean -> "点击查看完整内容"
    is LaneState.Cancelled -> if (resultOrNull() == null) "本轮没有留下内容" else "内容不完整，可选落盘"
    is LaneState.Idle -> null
}

private fun LaneState.resultOrNull() = when (this) {
    is LaneState.Hit -> message
    is LaneState.Clean -> message
    is LaneState.Truncated -> message
    is LaneState.Cancelled -> message
    else -> null
}

// 三档状态用固定色相：命中绿、轮次用尽黄、仍在跑浅红。深浅模式各一套，保证对比度
private val HitLight = Color(0xFFC8F0D2)
private val HitLightText = Color(0xFF0A3D1C)
private val HitDark = Color(0xFF1F4430)
private val HitDarkText = Color(0xFFB8F0C8)

private val TruncatedLight = Color(0xFFFDEFB2)
private val TruncatedLightText = Color(0xFF3A2E00)
private val TruncatedDark = Color(0xFF443A10)
private val TruncatedDarkText = Color(0xFFF5E3A0)

private val RunningLight = Color(0xFFFFDAD6)
private val RunningLightText = Color(0xFF410002)
private val RunningDark = Color(0xFF561E1B)
private val RunningDarkText = Color(0xFFFFB4AB)

/** 卡片底色：命中绿、轮次用尽黄、仍在跑浅红，其余保持中性 */
@Composable
private fun LaneState.tileColors(): CardColors {
    val dark = isSystemInDarkTheme()
    return when (this) {
        is LaneState.Hit -> CardDefaults.cardColors(
            containerColor = if (dark) HitDark else HitLight,
            contentColor = if (dark) HitDarkText else HitLightText,
        )

        is LaneState.Running -> CardDefaults.cardColors(
            containerColor = if (dark) RunningDark else RunningLight,
            contentColor = if (dark) RunningDarkText else RunningLightText,
        )

        is LaneState.Truncated -> CardDefaults.cardColors(
            containerColor = if (dark) TruncatedDark else TruncatedLight,
            contentColor = if (dark) TruncatedDarkText else TruncatedLightText,
        )

        is LaneState.Failed -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        )

        else -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}

@Composable
private fun LaneState.accentColor(): Color {
    val dark = isSystemInDarkTheme()
    return when (this) {
        // 强调色跟底色配套，保证文字和角标在有色底上仍看得清
        is LaneState.Hit -> if (dark) HitDarkText else HitLightText
        is LaneState.Running -> if (dark) RunningDarkText else RunningLightText
        is LaneState.Truncated -> if (dark) TruncatedDarkText else TruncatedLightText
        is LaneState.Failed -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun CommitTarget.shortLabel(): String = when (this) {
    CommitTarget.REPLACE_CURRENT -> "当前"
    CommitTarget.APPEND_CANDIDATE -> "候选"
    CommitTarget.NEW_BRANCH -> "分支"
}

/** 运行中查看当前设置：只允许改轮次上限，其余参数开跑时已固定 */
@Composable
private fun RunningSettingsDialog(
    session: BatchSession,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val config = session.config
    var limit by remember(config.maxRerollsPerLane) {
        mutableStateOf(config.maxRerollsPerLane)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("当前设置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "请求体、关键词和落盘范围在开跑时就固定了，这里只能调轮次；" +
                        "改完从下一轮起生效。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingRow("并发路数", "${session.lanes.size}")
                SettingRow("落盘词匹配范围", config.commitScope.scopeLabel())
                SettingRow("落盘标记词", config.commitKeywords.keywordsLabel())
                SettingRow("重 roll · 仅思考", config.rerollReasoningKeywords.keywordsLabel())
                SettingRow("重 roll · 仅正文", config.rerollTextKeywords.keywordsLabel())
                SettingRow(
                    "思考深度",
                    config.reasoningLevelOverride?.let { levelLabel(it) } ?: "跟随会话",
                )
                SettingRow("未命中关键词也重 roll", if (config.rerollOnNoHit) "是" else "否")
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                OutlinedTextField(
                    value = if (limit <= 0) "" else limit.toString(),
                    onValueChange = { input ->
                        limit = input.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
                    },
                    label = { Text("最大重 roll 轮次（0 = 不限）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(limit) }) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun KeywordScope.scopeLabel(): String = when (this) {
    KeywordScope.TEXT_ONLY -> "仅正文"
    KeywordScope.REASONING_ONLY -> "仅思考"
    KeywordScope.TEXT_AND_REASONING -> "正文+思考"
}

private fun List<String>.keywordsLabel(): String =
    if (isEmpty()) "（空）" else joinToString("、")

private fun levelLabel(level: ReasoningLevel): String = when (level) {
    ReasoningLevel.OFF -> "关闭"
    ReasoningLevel.AUTO -> "自动"
    ReasoningLevel.LOW -> "低"
    ReasoningLevel.MEDIUM -> "中"
    ReasoningLevel.HIGH -> "高"
    ReasoningLevel.XHIGH -> "更高"
    ReasoningLevel.MAX -> "最高"
}

/** 一行「文字 + 缩小开关」，两个并排放得下 */
@Composable
private fun TogglePair(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.72f),
        )
    }
}
