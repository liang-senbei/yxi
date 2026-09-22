package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shared by the actual chat and isolated window verification. */
@Composable
internal fun RewindMessageDialog(
    text: String,
    onTextChange: (String) -> Unit,
    canRewind: Boolean,
    showRewind: Boolean,
    canOpenNative: Boolean,
    onDismiss: () -> Unit,
    onDraft: () -> Unit,
    onRewind: () -> Unit,
    onNative: () -> Unit,
) {
    WorkbenchDialog(onDismissRequest = onDismiss, title = { Text("编辑这条消息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(text, onTextChange,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp),
                    label = { Text("消息内容") })
                Text("回退对话上下文，然后发送编辑后的内容。已有文件修改不会自动撤销。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (showRewind) TextButton(onNative, enabled = canOpenNative,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                    Text("打开 Claude 回退选择器")
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onDraft, enabled = text.isNotBlank()) { Text("仅载入草稿") }
                if (showRewind) Button(onRewind, enabled = canRewind && text.isNotBlank()) { Text("回到这里并继续") }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}
