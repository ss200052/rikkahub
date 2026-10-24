package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.data.model.MessageNode

/**
 * 分支选择器：左右箭头逐条切换，中间的「2/5」可以点开直接跳到任意一条。
 * 候选很多时不用再一路点箭头过去。
 */
@Composable
fun ChatMessageBranchSelector(
    node: MessageNode,
    modifier: Modifier = Modifier,
    onUpdate: (MessageNode) -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (node.messages.size > 1) {
            val actionColor = MaterialTheme.colorScheme.onSurfaceVariant

            Icon(
                imageVector = HugeIcons.ArrowLeft01,
                contentDescription = "Prev",
                modifier = Modifier
                    .clip(CircleShape)
                    .alpha(if (node.selectIndex == 0) 0.5f else 1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = {
                            if (node.selectIndex > 0) {
                                onUpdate(
                                    node.copy(
                                        selectIndex = node.selectIndex - 1
                                    )
                                )
                            }
                        }
                    )
                    .padding(8.dp)
                    .size(16.dp),
                tint = actionColor
            )

            Box {
                var expanded by remember { mutableStateOf(false) }

                Text(
                    text = "${node.selectIndex + 1}/${node.messages.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = actionColor,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current,
                            onClick = { expanded = true },
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    node.messages.forEachIndexed { index, _ ->
                        val selected = index == node.selectIndex
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "第 ${index + 1} 条",
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color.Unspecified
                                    },
                                )
                            },
                            onClick = {
                                expanded = false
                                if (!selected) {
                                    onUpdate(node.copy(selectIndex = index))
                                }
                            },
                        )
                    }
                }
            }

            Icon(
                imageVector = HugeIcons.ArrowRight01,
                contentDescription = "Next",
                modifier = Modifier
                    .clip(CircleShape)
                    .alpha(if (node.selectIndex == node.messages.lastIndex) 0.5f else 1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = {
                            if (node.selectIndex < node.messages.lastIndex) {
                                onUpdate(
                                    node.copy(
                                        selectIndex = node.selectIndex + 1
                                    )
                                )
                            }
                        }
                    )
                    .padding(8.dp)
                    .size(16.dp),
                tint = actionColor
            )
        }
    }
}
