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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.ai.PromptPart
import me.rerere.rikkahub.data.ai.PromptSchemes
import me.rerere.rikkahub.data.ai.SystemPromptBlock
import me.rerere.rikkahub.data.ai.SystemPromptPreset

/**
 * 请求体检查器：把请求体里的每一段按「文本框 / 固定结构 / 数据块」摆出来。
 *
 * 能改的只有文本框；固定结构与数据块不可改，但必须显示——用户要知道相邻文本框处在什么上下文里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemPromptInspectorDialog(
    blocks: List<SystemPromptBlock>,
    presets: List<SystemPromptPreset>,
    schemeId: String,
    onDismiss: () -> Unit,
    onSelectScheme: (String) -> Unit,
    onSaveText: (blockId: String, content: String?) -> Unit,
    onSaveSlots: (blockId: String, texts: Map<String, String>) -> Unit,
    onSavePreset: (name: String) -> Unit,
    onApplyPreset: (SystemPromptPreset) -> Unit,
    onDeletePreset: (String) -> Unit,
    onResetAll: () -> Unit,
    applyNotice: String? = null,
    onDismissApplyNotice: () -> Unit = {},
) {
    // 纯文本段的草稿：blockId -> 文本
    val textDrafts = remember { mutableStateMapOf<String, String>() }
    // 分部件段的草稿：blockId::slot -> 文本
    val slotDrafts = remember { mutableStateMapOf<String, String>() }
    var presetName by remember { mutableStateOf("") }
    var showPresetInput by remember { mutableStateOf(false) }
    var confirmRestoreText by remember { mutableStateOf<SystemPromptBlock?>(null) }

    confirmRestoreText?.let { block ->
        AlertDialog(
            onDismissRequest = { confirmRestoreText = null },
            title = { Text("恢复跟随？") },
            text = {
                Text(
                    if (block.dynamic) {
                        "这一块会重新由运行时生成（记忆、技能等），数据变化时也会跟着变。刚才的改写会丢失。"
                    } else {
                        "这一块会重新跟随助手或方案，以后源头的改动也会跟着变。刚才的改写会丢失。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRestoreText = null
                        textDrafts.remove(block.id)
                        onSaveText(block.id, null)
                    },
                ) { Text("恢复跟随") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestoreText = null }) { Text("取消") }
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("请求体")
                Text(
                    text = "共 ${blocks.size} 段。文本框可改，固定结构与数据块不可改",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 方案切换：只换文本框里的文字，骨架不动
                item(key = "scheme") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "方案",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        SingleChoiceSegmentedButtonRow {
                            PromptSchemes.all.forEachIndexed { index, scheme ->
                                SegmentedButton(
                                    selected = scheme.id == schemeId,
                                    onClick = { onSelectScheme(scheme.id) },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = PromptSchemes.all.size,
                                    ),
                                ) {
                                    Text(PromptSchemes.displayName(scheme))
                                }
                            }
                        }
                    }
                }

                applyNotice?.let { notice ->
                    item(key = "apply-notice") {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 10.dp, end = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = notice,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = onDismissApplyNotice) { Text("知道了") }
                            }
                        }
                    }
                }

                blocks.groupBy { it.group }.forEach { (group, groupBlocks) ->
                    item(key = "header-${group.name}") {
                        Text(
                            text = "${group.label}（${groupBlocks.size}）",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    groupBlocks.forEach { block ->
                        if (block.isTokenized) {
                            // 分部件段按单元逐个成行：一个段的槽位可能有几十个，
                            // 全挤在一个 item 里会在展开时一次性组合出几十个输入框
                            item(key = "${block.id}-header") {
                                TokenizedHeader(block = block)
                            }
                            block.parts.forEachIndexed { groupIndex, partGroup ->
                                partGroup.parts.forEachIndexed { partIndex, part ->
                                    item(key = "${block.id}-$groupIndex-$partIndex") {
                                        TokenizedPartRow(
                                            block = block,
                                            part = part,
                                            slotDrafts = slotDrafts,
                                            onDraftChange = { slot, value ->
                                                slotDrafts["${block.id}::$slot"] = value
                                            },
                                        )
                                    }
                                }
                            }
                            item(key = "${block.id}-footer") {
                                TokenizedFooter(
                                    block = block,
                                    onSave = {
                                        val texts = block.slots.associateWith { slot ->
                                            slotDrafts["${block.id}::$slot"]
                                                ?: block.slotTexts[slot].orEmpty()
                                        }
                                        texts.forEach { (slot, _) ->
                                            slotDrafts.remove("${block.id}::$slot")
                                        }
                                        onSaveSlots(block.id, texts)
                                    },
                                    onReset = {
                                        block.slots.forEach { slot ->
                                            slotDrafts.remove("${block.id}::$slot")
                                        }
                                        onSaveSlots(block.id, emptyMap())
                                    },
                                )
                            }
                        } else {
                            item(key = block.id) {
                                TextBlock(
                                    block = block,
                                    draft = textDrafts[block.id],
                                    onDraftChange = { textDrafts[block.id] = it },
                                    onEditStart = { textDrafts[block.id] = block.content },
                                    onEditCancel = { textDrafts.remove(block.id) },
                                    onSave = {
                                        val value = textDrafts[block.id].orEmpty()
                                        if (value.isBlank()) {
                                            confirmRestoreText = block
                                        } else {
                                            textDrafts.remove(block.id)
                                            onSaveText(block.id, value)
                                        }
                                    },
                                    onRestore = { confirmRestoreText = block },
                                )
                            }
                        }
                    }
                }

                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "已保存的方案",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showPresetInput = !showPresetInput }) {
                            Text("保存当前")
                        }
                    }
                    Text(
                        text = "应用方案只覆盖它包含的块，其余块保持当前改写不动",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
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

/**
 * 分部件段的标题行。
 *
 * 段内每个单元单独成行（见 [TokenizedPartRow]），所以这里只放标题与「已改写」标记。
 */
@Composable
private fun TokenizedHeader(block: SystemPromptBlock) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 6.dp),
    ) {
        Text(
            text = block.title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (block.isOverridden) {
            AssistChip(onClick = {}, label = { Text("已改写", style = MaterialTheme.typography.labelSmall) })
        }
    }
}

/**
 * 分部件段里的一个单元。
 *
 * 文本框可改；固定结构与数据块只展示——它们不可改，但必须出现，
 * 用户才知道相邻的文本框处在什么上下文里。
 */
@Composable
private fun TokenizedPartRow(
    block: SystemPromptBlock,
    part: PromptPart,
    slotDrafts: Map<String, String>,
    onDraftChange: (slot: String, value: String) -> Unit,
) {
    when (part) {
        is PromptPart.Fixed -> Text(
            text = part.text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 8.dp),
        )

        is PromptPart.Token -> AssistChip(
            onClick = {},
            modifier = Modifier.padding(start = 8.dp),
            label = {
                Text(
                    text = "<${part.id}>",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            },
        )

        is PromptPart.Text -> {
            val key = "${block.id}::${part.slot}"
            OutlinedTextField(
                value = slotDrafts[key] ?: block.slotTexts[part.slot].orEmpty(),
                onValueChange = { onDraftChange(part.slot, it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp),
                textStyle = MaterialTheme.typography.bodySmall,
                label = {
                    Text(
                        text = part.slot,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                },
            )
        }
    }
}

/** 分部件段的保存行 */
@Composable
private fun TokenizedFooter(
    block: SystemPromptBlock,
    onSave: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        TextButton(onClick = onSave) { Text("保存") }
        if (block.isOverridden) {
            TextButton(onClick = onReset) { Text("恢复跟随") }
        }
    }
}

/** 纯文本文段：整段就是一个可编辑字符串 */
@Composable
private fun TextBlock(
    block: SystemPromptBlock,
    draft: String?,
    onDraftChange: (String) -> Unit,
    onEditStart: () -> Unit,
    onEditCancel: () -> Unit,
    onSave: () -> Unit,
    onRestore: () -> Unit,
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
                if (block.isOverridden) {
                    AssistChip(onClick = {}, label = { Text("已改写", style = MaterialTheme.typography.labelSmall) })
                } else {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                text = if (block.dynamic) "跟随·动态" else "跟随",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }

            if (draft != null) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                    textStyle = MaterialTheme.typography.bodySmall,
                    supportingText = {
                        Text(
                            text = "清空后保存＝恢复跟随",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onSave) { Text("保存") }
                    TextButton(onClick = onEditCancel) { Text("取消") }
                    if (block.isOverridden) {
                        TextButton(onClick = onRestore) { Text("恢复跟随") }
                    }
                }
            } else {
                Text(
                    text = block.content.ifBlank { "（这一块当前为空）" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = if (block.isOverridden) FontStyle.Normal else FontStyle.Italic,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onEditStart) { Text("编辑") }
                    if (block.isOverridden) {
                        TextButton(onClick = onRestore) { Text("恢复跟随") }
                    }
                }
            }
        }
    }
}
