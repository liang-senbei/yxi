package app.yxi.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class PendingWork(val files: List<String>, val drafts: Int, val feedback: Int, val operations: Int) {
    val needsReview get() = files.isNotEmpty() || drafts > 0 || feedback > 0 || operations > 0
    val canDiscard get() = operations == 0
}

internal fun pendingWorkOf(documents: List<FileDocument>, drafts: List<String>, browsers: Collection<BrowserPreview>) = PendingWork(
    documents.filter { it.dirty }.map { it.path }, drafts.count { it.isNotBlank() },
    browsers.count { it.hasUnsubmittedFeedback },
    documents.count { it.busy } + browsers.count { it.preparing || it.stylePending != null },
)
fun AppState.pendingWork() = pendingWorkOf(documents, chatDrafts.values.map { it.value.text }, browsers.values)

@Composable
fun ExitReviewDialog(work: PendingWork, onCancel: () -> Unit, onDiscard: () -> Unit) {
    WorkbenchDialog(onDismissRequest = onCancel,
        title = { Text(if (!work.canDiscard) "操作尚未完成" else if (work.needsReview) "还有未保存的内容" else "操作已完成") },
        text = { Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
            if (work.files.isNotEmpty()) {
                Text("${work.files.size} 个文件有未保存编辑")
                work.files.take(4).forEach { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted) }
                if (work.files.size > 4) Text("以及另外 ${work.files.size - 4} 个文件", style = MaterialTheme.typography.bodySmall)
            }
            if (work.drafts > 0) Text("${work.drafts} 个对话仍有未发送草稿", Modifier.padding(top = 8.dp))
            if (work.feedback > 0) Text("${work.feedback} 个网页预览有尚未加入对话的反馈", Modifier.padding(top = 8.dp))
            if (work.operations > 0) Text("正在保存文件或确认网页操作，请等待完成后再退出。", Modifier.padding(top = 12.dp), color = Tokens.current.warning)
            else Text(if (work.needsReview) "退出会丢弃这些本机编辑和草稿；服务器会话不会被停止。" else "当前没有待保存内容，可以退出。", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onCancel) { Text("继续编辑") } },
        dismissButton = { TextButton(onDiscard, enabled = work.canDiscard) { Text(if (work.needsReview) "丢弃并退出" else "退出", color = Tokens.current.danger) } })
}
