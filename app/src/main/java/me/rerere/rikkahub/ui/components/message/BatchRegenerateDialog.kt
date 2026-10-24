package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import me.rerere.rikkahub.data.batch.BatchRegenerationConfig
import me.rerere.rikkahub.data.batch.ZeroHitPolicy
import kotlin.math.roundToInt

/**
 * 批量重新生成的参数弹窗。
 *
 * 关键词分三组独立配置：落盘标记词、思考内容重 roll 词、正文重 roll 词。
 * 点击已有关键词可以删除，输入后点「添加」加入。
 */
@Composable
fun BatchRegenerateDialog(
    initial: BatchRegenerationConfig,
    onDismiss: () -> Unit,
    onStart: (BatchRegenerationConfig) -> Unit,
) {
    var parallel by remember { mutableIntStateOf(initial.parallelCount.coerceIn(1, BatchRegenerationConfig.MAX_PARALLEL)) }
    var commitKeywords by remember { mutableStateOf(initial.commitKeywords) }
    var reasoningKeywords by remember { mutableStateOf(initial.rerollReasoningKeywords) }
    var textKeywords by remember { mutableStateOf(initial.rerollTextKeywords) }
    var zeroHit by remember { mutableStateOf(initial.zeroHitPolicy) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量重新生成") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ---- 并行路数 ----
                Column {
                    Text(
                        text = "并行路数：$parallel",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Slider(
                        value = parallel.toFloat(),
                        onValueChange = { parallel = it.roundToInt() },
                        valueRange = 1f..BatchRegenerationConfig.MAX_PARALLEL.toFloat(),
                        steps = BatchRegenerationConfig.MAX_PARALLEL - 2,
                    )
                }

                HorizontalDivider()

                KeywordEditor(
                    title = "落盘标记词",
                    hint = "回复中出现这些词就标记为「像样」，不会自动落盘；留空表示全部标记",
                    keywords = commitKeywords,
                    onChange = { commitKeywords = it },
                )

                KeywordEditor(
                    title = "重 roll · 仅思考内容",
                    hint = "思考内容一出现这些词就截断重来；留空表示不按思考重 roll",
                    keywords = reasoningKeywords,
                    onChange = { reasoningKeywords = it },
                )

                KeywordEditor(
                    title = "重 roll · 仅正文",
                    hint = "正文一出现这些词就截断重来；留空表示不按正文重 roll",
                    keywords = textKeywords,
                    onChange = { textKeywords = it },
                )

                HorizontalDivider()

                // ---- 零命中策略 ----
                Column(modifier = Modifier.selectableGroup()) {
                    Text(
                        text = "全部路都没命中落盘词时",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    ZeroHitPolicy.entries.forEach { policy ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = zeroHit == policy,
                                    role = Role.RadioButton,
                                    onClick = { zeroHit = policy },
                                )
                                .padding(vertical = 2.dp),
                        ) {
                            RadioButton(selected = zeroHit == policy, onClick = null)
                            Text(
                                text = policy.label(),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }

                // ---- 成本提示 ----
                Text(
                    text = "重 roll 不设上限：最坏请求数为 路数 × 无限，靠面板里的停止按钮兜底。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onStart(
                        initial.copy(
                            parallelCount = parallel,
                            commitKeywords = commitKeywords,
                            rerollReasoningKeywords = reasoningKeywords,
                            rerollTextKeywords = textKeywords,
                            zeroHitPolicy = zeroHit,
                        ),
                    )
                },
            ) { Text("开始") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 关键词编辑区：点标签删除，输入后点「添加」新增 */
@Composable
private fun KeywordEditor(
    title: String,
    hint: String,
    keywords: List<String>,
    onChange: (List<String>) -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (keywords.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                keywords.forEach { keyword ->
                    FilterChip(
                        selected = true,
                        onClick = { onChange(keywords - keyword) },
                        label = { Text(keyword) },
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
                placeholder = { Text("输入关键词", style = MaterialTheme.typography.bodySmall) },
            )
            TextButton(
                enabled = draft.isNotBlank(),
                onClick = {
                    val value = draft.trim()
                    if (value.isNotEmpty()) {
                        onChange((keywords + value).distinct())
                        draft = ""
                    }
                },
            ) { Text("添加") }
        }
    }
}

private fun ZeroHitPolicy.label(): String = when (this) {
    ZeroHitPolicy.KEEP_LAST_COMPLETED -> "保留最后完成的一条"
    ZeroHitPolicy.DISCARD_ALL -> "全部丢弃，对话保持原样"
    ZeroHitPolicy.COMMIT_ALL -> "全部落盘，自己挑"
}
