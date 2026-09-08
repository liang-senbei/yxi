package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yxi.agent.Dirs
import app.yxi.agent.Session
import app.yxi.agent.SessionState
import app.yxi.ssh.catching
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 左栏下半：这台主机上的 cc-/cx- 会话。点一项进对话；每 5 秒刷一次（跟手机看板同频）。 */
@Composable
fun SessionsPane(state: AppState, conn: Conn) {
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(conn) { while (true) { conn.refresh(); delay(5_000) } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp, 6.dp, 4.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("会话", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { creating = true }) { Text("新建") }
        }
        note?.let {
            Text(it, Modifier.padding(12.dp, 0.dp, 12.dp, 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (conn.sessions.isEmpty()) {
            Text("这台机器上还没有会话", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(conn.sessions, key = { it.name }) { s ->
                SessionRow(s, selected = s.name == state.session?.name) { state.session = s }
            }
        }
    }

    if (creating) NewSessionDialog(
        // 预填现有会话的父目录（工作区），只用补项目名；不写死路径，换台机器就不一样
        initial = Dirs.parentsOf(conn.sessions.map { it.cwd }).firstOrNull()?.let { "$it/" }.orEmpty(),
        onDismiss = { creating = false },
    ) { dir ->
        creating = false
        // 会话名 = 目录末段，跟手机端 SessionsScreen 同一套规则；命令由 Dirs.createCommand 拼好并转义
        val base = dir.trimEnd('/').substringAfterLast('/').filter { it.isLetterOrDigit() || it in "._-" }.ifBlank { "work" }
        val name = "cc-$base"
        scope.launch {
            val made = catching { conn.ssh.exec(Dirs.createCommand(dir, name, "claude")) }
                .map { Dirs.madeFrom(it) }.getOrElse { Dirs.Made.Failed("unknown", it.message.orEmpty()) }
            if (made is Dirs.Made.Failed) {
                // 只认脚本自己回报的那行（tmux 对不存在的目录也返回 0，见 Dirs.createCommand）
                note = "没开成：" + when (made.code) {
                    "nodir" -> "建不了这个目录 —— 没权限，或者上级路径不对"
                    "failed" -> "tmux 起不来这个会话"
                    "wrongdir" -> "tmux 没开在你指定的目录（跑到 ${made.detail.ifBlank { "别处" }} 去了），已经撤销"
                    "noresult" -> "没拿到结果 —— 连接可能断了"
                    else -> made.detail.ifBlank { "说不上来" }
                }
                return@launch
            }
            note = null
            conn.refresh()
            state.session = conn.sessions.firstOrNull { it.name == name }
                ?: Session(name, 1, false, dir, System.currentTimeMillis() / 1000, SessionState.Idle, "", 0.0)
        }
    }
}

@Composable
private fun SessionRow(s: Session, selected: Boolean, onClick: () -> Unit) {
    val dot = when (s.state) {
        SessionState.NeedsYou -> Color(0xFFE0A030)   // 琥珀：只在需要你动手时出现
        SessionState.Working -> Color(0xFF3DA48A)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick).padding(12.dp, 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            if (s.isCodex) Text("Codex", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            Text(
                s.short, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(ago(s.lastActivity), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Text(
            listOf(s.state.label, s.cwd.trimEnd('/').substringAfterLast('/')).filter { it.isNotEmpty() }.joinToString(" · "),
            Modifier.padding(start = 13.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (s.detail.isNotEmpty()) Text(
            s.detail, Modifier.padding(start = 13.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NewSessionDialog(initial: String, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var path by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新会话开在哪个目录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("目录不存在会建出来，然后在那儿起一个 tmux 会话并跑 claude。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                OutlinedTextField(path, { path = it }, singleLine = true, placeholder = { Text("/opt/workspace/…") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = path.isNotBlank(), onClick = { onCreate(path.trim()) }) { Text("开起来") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

/** 相对时间（抄手机端 TimeFmt.ago）。0 或未来 → 空串。 */
fun ago(epochSec: Long): String {
    val d = System.currentTimeMillis() / 1000 - epochSec
    if (epochSec <= 0L || d < 0) return ""
    return when {
        d < 60 -> "刚刚"
        d < 3600 -> "${d / 60} 分钟前"
        d < 86400 -> "${d / 3600} 小时前"
        else -> "${d / 86400} 天前"
    }
}
