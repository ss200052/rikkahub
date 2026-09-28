package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R

/**
 * 思维链编辑对话框。
 *
 * 修改仅在点击保存后通过 [onConfirm] 回传，取消不做任何改动。
 */
@Composable
fun ChatMessageReasoningEditDialog(
    reasoning: UIMessagePart.Reasoning,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(reasoning.createdAt) { mutableStateOf(reasoning.reasoning) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_reasoning_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                label = { Text(stringResource(R.string.edit_reasoning)) },
                singleLine = false,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text != reasoning.reasoning,
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
