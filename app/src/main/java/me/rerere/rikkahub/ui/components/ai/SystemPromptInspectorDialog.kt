package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.ai.SystemPromptBlock
import me.rerere.rikkahub.data.ai.SystemPromptPreset

/**
 * 「当前生效的系统提示词」检查器。
 *
 * 按实际注入顺序把系统提示词拆成块展示，每块可以单独改写或恢复默认；
 * 当前这套改写可以整体存成备选方案，随时切回来。
 */
@Composable
fun SystemPromptInspectorDialog(
    blocks: List<SystemPromptBlock>,
    presets: List<SystemPromptPreset>,
    onDismiss: () -> Unit,
    onSaveOverride: (blockId: String, content: String?) -> Unit,
    onSavePreset: (name: String) -> Unit,
    onApplyPreset: (SystemPromptPreset) -> Unit,
    onDeletePreset: (String) -> Unit,
    onResetAll: () -> Unit,
) {
    // blockId -> 正在编辑的草稿
    val drafts = remember { mutableStateMapOf<String, String>() }
    var presetName by remember { mutableStateOf("") }
    var showPresetInput by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("当前生效的系统提示词")
                Text(
                    text = "共 ${blocks.size} 块，按实际注入顺序排列",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(blocks, key = { it.id }) { block ->
                    val draft = drafts[block.id]
                    BlockCard(
                        block = block,
                        editingDraft = draft,
                        onEditStart = { drafts[block.id] = block.content },
                        onDraftChange = { drafts[block.id] = it },
                        onEditCancel = { drafts.remove(block.id) },
                        onEditConfirm = {
                            val value = drafts[block.id]
                            if (value != null) {
                                drafts.remove(block.id)
                                onSaveOverride(block.id, value)
                            }
                        },
                        onRestoreDefault = {
                            drafts.remove(block.id)
                            onSaveOverride(block.id, null)
                        },
                    )
                }

                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "备选方案",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showPresetInput = !showPresetInput }) {
                            Text("保存当前")
                        }
                    }
                    if (showPresetInput) {
                        OutlinedTextField(
                            value = presetName,
                            onValueChange = { presetName = it },
                            label = { Text("方案名称") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                enabled = presetName.isNotBlank(),
                                onClick = {
                                    onSavePreset(presetName.trim())
                                    presetName = ""
                                    showPresetInput = false
                                },
                            ) { Text("保存") }
                            TextButton(onClick = { showPresetInput = false }) { Text("取消") }
                        }
                    }
                    if (presets.isEmpty()) {
                        Text(
                            text = "还没有保存过方案",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }

                items(presets, key = { it.id }) { preset ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${preset.name}（${preset.overrides.size} 块改写）",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onApplyPreset(preset) }) { Text("应用") }
                            TextButton(onClick = { onDeletePreset(preset.id) }) { Text("删除") }
                        }
                    }
                }

                item {
                    TextButton(onClick = onResetAll) { Text("全部恢复默认") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun BlockCard(
    block: SystemPromptBlock,
    editingDraft: String?,
    onEditStart: () -> Unit,
    onDraftChange: (String) -> Unit,
    onEditCancel: () -> Unit,
    onEditConfirm: () -> Unit,
    onRestoreDefault: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = block.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (block.dynamic) {
                    AssistChip(onClick = {}, label = { Text("动态", style = MaterialTheme.typography.labelSmall) })
                }
                if (block.isOverridden) {
                    AssistChip(onClick = {}, label = { Text("已改", style = MaterialTheme.typography.labelSmall) })
                }
            }

            if (editingDraft != null) {
                OutlinedTextField(
                    value = editingDraft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onEditConfirm) { Text("保存") }
                    TextButton(onClick = onEditCancel) { Text("取消") }
                    if (block.isOverridden) {
                        TextButton(onClick = onRestoreDefault) { Text("恢复默认") }
                    }
                }
            } else {
                Text(
                    text = block.content.ifBlank { "（这一块当前为空）" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onEditStart) { Text("编辑") }
                    if (block.isOverridden) {
                        TextButton(onClick = onRestoreDefault) { Text("恢复默认") }
                    }
                    Text(
                        text = "${block.content.length} 字符",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }
    }
}
