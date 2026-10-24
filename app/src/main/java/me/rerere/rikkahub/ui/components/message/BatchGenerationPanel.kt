package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.rerere.rikkahub.data.batch.BatchSession
import me.rerere.rikkahub.data.batch.CommitTarget
import me.rerere.rikkahub.data.batch.KeywordSource
import me.rerere.rikkahub.data.batch.LaneCommitRecord
import me.rerere.rikkahub.data.batch.LaneState
import me.rerere.rikkahub.data.batch.isComplete
import me.rerere.rikkahub.data.batch.isInspectable

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
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("批量重新生成", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = summary(session),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!session.finished) {
                        TextButton(onClick = onStopAll) { Text("全部停止") }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    session.lanes.forEach { lane ->
                        LaneTile(
                            state = lane,
                            commits = session.commits.filter { it.laneIndex == lane.index },
                            onStop = { onStopLane(lane.index) },
                            onInspect = { onInspect(lane.index) },
                            onCommit = { onCommit(lane.index) },
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (session.locked) {
                        OutlinedButton(onClick = onUnlock) { Text("解锁对话") }
                        Text(
                            text = "解锁前该对话不能编辑、删除或继续生成",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Text(
                            text = "已解锁，格子里未落盘的内容仍然保留",
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
}

private fun summary(session: BatchSession): String {
    val total = session.lanes.size
    val parts = mutableListOf<String>()
    if (session.finished) parts += "已全部结束" else parts += "进行中 ${session.finishedCount}/$total"
    parts += "命中 ${session.hitCount}"
    if (session.commits.isNotEmpty()) parts += "已落盘 ${session.commits.size}"
    return parts.joinToString(" · ")
}

@Composable
private fun LaneTile(
    state: LaneState,
    commits: List<LaneCommitRecord>,
    onStop: () -> Unit,
    onInspect: () -> Unit,
    onCommit: () -> Unit,
) {
    val inspectable = state.isInspectable
    val accent = state.accentColor()

    Card(
        modifier = Modifier.width(158.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        onClick = { if (inspectable) onInspect() },
        enabled = inspectable,
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "路 ${state.index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                )
            }

            Text(
                text = state.statusLabel(),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            state.detailLabel()?.let { detail ->
                // 重 roll 次数异常多时标红，提醒关键词可能配得太严
                val tooManyRerolls = state is LaneState.Running && state.rerollCount >= 10
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tooManyRerolls) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (commits.isNotEmpty()) {
                Text(
                    text = "已落盘 ${commits.size} 处" + commits.joinToString("") { " · " + it.target.shortLabel() },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state is LaneState.Running) {
                    TextButton(onClick = onStop) { Text("停止", style = MaterialTheme.typography.labelSmall) }
                }
                if (inspectable) {
                    TextButton(onClick = onInspect) { Text("查看", style = MaterialTheme.typography.labelSmall) }
                    TextButton(onClick = onCommit) { Text("落盘", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
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

private fun LaneState.detailLabel(): String? = when (this) {
    is LaneState.Running -> buildString {
        if (rerollCount > 0) append("已重 roll $rerollCount 次")
        lastHit?.let {
            if (isNotEmpty()) append(" · ")
            append("命中${if (it.source == KeywordSource.REASONING) "思考" else "正文"}：${it.keyword}")
        }
        if (waitingSeconds != null) {
            if (isNotEmpty()) append(" · ")
            append("服务端限流，${waitingSeconds} 秒后自动重试")
        }
    }.ifEmpty { null }

    is LaneState.Truncated -> buildString {
        append("共 $attempt 轮均被截断")
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

@Composable
private fun LaneState.accentColor(): Color = when (this) {
    is LaneState.Hit -> MaterialTheme.colorScheme.primary
    is LaneState.Truncated -> MaterialTheme.colorScheme.error
    is LaneState.Failed -> MaterialTheme.colorScheme.error
    is LaneState.Running -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun CommitTarget.shortLabel(): String = when (this) {
    CommitTarget.REPLACE_CURRENT -> "当前"
    CommitTarget.APPEND_CANDIDATE -> "候选"
    CommitTarget.NEW_BRANCH -> "分支"
}
