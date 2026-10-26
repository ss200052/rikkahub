package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R

/** 一片最多多少字。超过这个长度的内容会被切片编辑 */
internal const val REASONING_SLICE_SIZE = 5000

/**
 * 就地编辑思考内容的对话框。
 *
 * 思考内容可能有数万字。把整段塞进一个输入框会出两类问题：
 * 输入框每按一次键都要复制并重新布局全文；外层再套滚动容器时还会拿到无限高度约束，
 * 逼它把所有行一次性量完——那是「点开就卡死」的直接原因。
 * 所以这里按 [REASONING_SLICE_SIZE] 切片：输入框只装当前这一片，保存时按序拼回去。
 * 切分优先落在换行、句号这类断点上，尽量不把一句话劈开。
 *
 * 只有点击保存才会通过 [onConfirm] 回传修改，取消不做任何改动。
 * 是否允许编辑由调用方按 [me.rerere.rikkahub.ui.hooks.entriesEditableReasoningOrNull] 判断，
 * 与官方「编辑消息」里的限制完全一致。
 */
@Composable
fun ChatMessageReasoningEditDialog(
    reasoning: UIMessagePart.Reasoning,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val original = reasoning.reasoning
    val slices = remember(original) { sliceReasoning(original) }
    // 只记录被改过的片；没改的片直接用原文，省一份全文副本
    val edits = remember(original) { mutableStateMapOf<Int, String>() }
    var index by remember(original) { mutableIntStateOf(0) }
    val safeIndex = index.coerceIn(0, slices.lastIndex)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_input_edit_reasoning)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (slices.size > 1) {
                    Text(
                        text = "共 ${original.length} 字，按每片 $REASONING_SLICE_SIZE 字切成 ${slices.size} 片，" +
                            "只显示当前这一片",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "第 ${safeIndex + 1}/${slices.size} 片",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            enabled = safeIndex > 0,
                            onClick = { index = safeIndex - 1 },
                        ) { Text("上一片") }
                        TextButton(
                            enabled = safeIndex < slices.lastIndex,
                            onClick = { index = safeIndex + 1 },
                        ) { Text("下一片") }
                    }
                }

                OutlinedTextField(
                    value = edits[safeIndex] ?: slices[safeIndex],
                    onValueChange = { edits[safeIndex] = it },
                    // 固定高度交给输入框自己滚动：外面那层 verticalScroll 会
                    // 给它无限高度约束，是数万字时卡死的根源
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    label = { Text(stringResource(R.string.chat_input_edit_reasoning)) },
                    singleLine = false,
                )

                if (edits[safeIndex] != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            enabled = edits[safeIndex] != slices[safeIndex],
                            onClick = { edits.remove(safeIndex) },
                        ) { Text("恢复这一片") }
                        Text(
                            text = "已改动 ${edits.size} 片",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = edits.isNotEmpty(),
                onClick = {
                    onConfirm((0 until slices.size).joinToString("") { edits[it] ?: slices[it] })
                },
            ) {
                Text(stringResource(R.string.chat_page_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_page_cancel))
            }
        },
    )
}

/**
 * 把长文本切成不超过 [size] 字的若干片，拼起来与原文完全一致。
 *
 * 断点从目标位置往前找换行、句号、空白，找不到就硬切；
 * 至少保留半片的长度，避免切出很碎的片段。
 */
internal fun sliceReasoning(text: String, size: Int = REASONING_SLICE_SIZE): List<String> {
    if (size <= 0) return listOf(text)
    if (text.length <= size) return listOf(text)

    val result = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + size, text.length)
        if (end < text.length) {
            val lowerBound = (start + size / 2).coerceAtLeast(start + 1)
            var cursor = end
            while (cursor > lowerBound) {
                when (text[cursor - 1]) {
                    '\n', '。', '！', '？', '.', '!', '?', ' ', '\t' -> {
                        end = cursor
                        cursor = lowerBound
                    }

                    else -> cursor--
                }
            }
        }
        result += text.substring(start, end)
        start = end
    }
    return result
}
