package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.batch.CommitTarget
import me.rerere.rikkahub.data.batch.LaneState
import me.rerere.rikkahub.data.batch.isComplete
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock

/**
 * 查看单路结果：上方是思考内容，下方是正文。
 * 内容较长的思维链默认折叠，避免一进来就淹没正文。
 */
@Composable
fun BatchLaneDetailDialog(
    lane: LaneState,
    onDismiss: () -> Unit,
    onCommit: () -> Unit,
) {
    val message = lane.laneMessageOrNull()
    var reasoningExpanded by remember(lane) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("路 ${lane.index + 1} · ${lane.detailTitle()}")
                if (!lane.isComplete) {
                    Text(
                        text = "这条内容不完整（被截断或中途停止），落盘后对话里就是现在看到的样子",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        text = {
            if (message == null) {
                Text("这一路没有留下内容。")
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val reasoningText = message.parts
                        .filterIsInstance<UIMessagePart.Reasoning>()
                        .joinToString("\n\n") { it.reasoning }
                        .trim()

                    if (reasoningText.isNotEmpty()) {
                        Text("思考内容", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = if (reasoningExpanded) reasoningText else reasoningText.take(400) + if (reasoningText.length > 400) "…" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (reasoningText.length > 400) {
                            TextButton(onClick = { reasoningExpanded = !reasoningExpanded }) {
                                Text(if (reasoningExpanded) "收起思考" else "展开全部思考")
                            }
                        }
                        HorizontalDivider()
                    }

                    val bodyText = message.parts
                        .filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n\n") { it.text }
                        .trim()

                    Text("正文", style = MaterialTheme.typography.titleSmall)
                    if (bodyText.isEmpty()) {
                        Text("（没有正文内容）", style = MaterialTheme.typography.bodySmall)
                    } else {
                        MarkdownBlock(
                            content = bodyText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    val toolCount = message.parts.count { it is UIMessagePart.Tool }
                    if (toolCount > 0) {
                        Text(
                            text = "包含 $toolCount 个工具调用",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = message != null, onClick = onCommit) { Text("落盘") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 选择把这一路放到哪里 */
@Composable
fun BatchCommitTargetDialog(
    lane: LaneState,
    isLocked: Boolean,
    onDismiss: () -> Unit,
    onPick: (CommitTarget) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("落盘 路 ${lane.index + 1}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!lane.isComplete) {
                    Text(
                        text = "注意：这条内容不完整，落盘后对话里会保留截断的样子。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                CommitOption(
                    title = "替换当前回复",
                    desc = "就地覆盖这条用户消息下面的回复，分支选中项不变",
                    onClick = { onPick(CommitTarget.REPLACE_CURRENT) },
                )
                CommitOption(
                    title = "追加为新候选",
                    desc = "作为这条用户消息的另一个分支，当前显示的那条不变",
                    onClick = { onPick(CommitTarget.APPEND_CANDIDATE) },
                )
                CommitOption(
                    title = "新建分支对话",
                    desc = "从这条用户消息分叉出一个新对话再写进去，附件会复制一份",
                    onClick = { onPick(CommitTarget.NEW_BRANCH) },
                )
                if (isLocked) {
                    Text(
                        text = "对话处于锁定状态时仍然可以落盘。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun CommitOption(
    title: String,
    desc: String,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        TextButton(onClick = onClick) { Text(title) }
        Text(
            text = desc,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

private fun LaneState.laneMessageOrNull(): UIMessage? = when (this) {
    is LaneState.Hit -> message
    is LaneState.Clean -> message
    is LaneState.Truncated -> message
    is LaneState.Cancelled -> message
    else -> null
}

private fun LaneState.detailTitle(): String = when (this) {
    is LaneState.Hit -> "命中落盘词"
    is LaneState.Clean -> "完成 · 未命中"
    is LaneState.Truncated -> "被截断（共 $attempt 轮）"
    is LaneState.Cancelled -> "已停止"
    is LaneState.Failed -> "失败"
    is LaneState.Running -> "运行中"
    is LaneState.Idle -> "等待开始"
}
