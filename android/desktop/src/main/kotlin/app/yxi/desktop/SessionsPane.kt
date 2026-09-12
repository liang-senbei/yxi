package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yxi.agent.Dirs
import app.yxi.agent.Session
import app.yxi.agent.SessionState
import app.yxi.ssh.catching
import kotlinx.coroutines.launch

/** 会话行尾的徽标（措辞照 design/desktop-reference.md §2.6）。 */
enum class Badge(val label: String) { Approval("等待批准"), Input("需要用户输入"), Running("正在运行"), Done("已完成"), Idle("空闲") }

private val APPROVAL = Regex("permission|sandbox|approval|proposal|决策")

/**
 * SessionState 只有四档；「等待批准」和「需要用户输入」都落在 NeedsYou 里，靠 detail 再分 ——
 * 那是 Claude Code 自己写的 waitingFor（permission prompt / sandbox request / goal proposal 是批准，
 * question / input needed / dialog open 是输入）。认不出来的当「需要用户输入」：反正都要人来看。
 */
fun Session.badge(): Badge = when (state) {
    SessionState.NeedsYou -> if (APPROVAL.containsMatchIn(detail)) Badge.Approval else Badge.Input
    SessionState.Working -> Badge.Running
    SessionState.Done -> Badge.Done
    SessionState.Idle -> Badge.Idle
}

/** 一行会话：短名 + 相对时间 / cwd 末段 + 徽标。选中底色 selected、悬停 hover。 */
@Composable
fun SessionRow(s: Session, selected: Boolean, displayName: String? = null, onClick: () -> Unit) {
    val t = Tokens.current
    val b = s.badge()
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val badgeColor = when (b) { Badge.Approval -> t.warning; Badge.Input -> t.accent; Badge.Running -> t.success; else -> t.textMuted }
    Column(
        Modifier.fillMaxWidth().hoverable(src)
            .background(if (selected) t.selected else if (hovered) t.hover else Color.Transparent)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(start = 24.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (s.isCodex) Text("Codex", style = MaterialTheme.typography.labelSmall, color = t.accent)
            Text(
                displayName ?: s.short, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = t.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(ago(s.lastActivity), style = MaterialTheme.typography.labelSmall, color = t.textMuted)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                s.cwd.trimEnd('/').substringAfterLast('/'), style = MaterialTheme.typography.labelSmall, color = t.textSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            // 空闲 / 已完成不画点，只有要人看的和在跑的才亮
            if (b != Badge.Idle && b != Badge.Done) Box(Modifier.size(6.dp).background(badgeColor, CircleShape))
            Text(b.label, style = MaterialTheme.typography.labelSmall, color = badgeColor)
        }
    }
}

/**
 * 「新建会话」弹窗：目录不存在会建出来，然后在那儿起 tmux 会话跑 claude（命令由 Dirs.createCommand 拼好并转义）。
 * 出错留在弹窗里显示，成了才关；成了把新会话交给 [onCreated]。
 */
@Composable
fun NewSessionDialog(conn: Conn, onDismiss: () -> Unit, onCreated: (Session) -> Unit) {
    val scope = rememberCoroutineScope()
    // 预填现有会话的父目录（工作区），只用补项目名；不写死路径，换台机器就不一样
    var path by remember { mutableStateOf(Dirs.parentsOf(conn.sessions.map { it.cwd }).firstOrNull()?.let { "$it/" }.orEmpty()) }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("在 ${conn.host.label} 上新建会话") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("目录不存在会建出来，然后在那儿起一个 tmux 会话并跑 claude。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                OutlinedTextField(path, { path = it }, singleLine = true, placeholder = { Text("/opt/workspace/…") }, modifier = Modifier.fillMaxWidth())
                if (err.isNotBlank()) Text(err, style = MaterialTheme.typography.bodySmall, color = Tokens.current.danger)
            }
        },
        confirmButton = {
            TextButton(enabled = path.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch { createSession(conn, path.trim()).fold(onCreated) { err = it.message.orEmpty() }; busy = false }
            }) { Text(if (busy) "正在开…" else "开起来") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

/** 会话名 = 目录末段，跟手机端 SessionsScreen 同一套规则。失败的原因放在 Result 的异常消息里。 */
private suspend fun createSession(conn: Conn, dir: String): Result<Session> {
    val base = dir.trimEnd('/').substringAfterLast('/').filter { it.isLetterOrDigit() || it in "._-" }.ifBlank { "work" }
    val name = "cc-$base"
    val made = catching { conn.ssh.exec(Dirs.createCommand(dir, name, "claude")) }
        .map { Dirs.madeFrom(it) }.getOrElse { Dirs.Made.Failed("unknown", it.message.orEmpty()) }
    if (made is Dirs.Made.Failed) {
        // 只认脚本自己回报的那行（tmux 对不存在的目录也返回 0，见 Dirs.createCommand）
        return Result.failure(IllegalStateException("没开成：" + when (made.code) {
            "nodir" -> "建不了这个目录 —— 没权限，或者上级路径不对"
            "failed" -> "tmux 起不来这个会话"
            "wrongdir" -> "tmux 没开在你指定的目录（跑到 ${made.detail.ifBlank { "别处" }} 去了），已经撤销"
            "noresult" -> "没拿到结果 —— 连接可能断了"
            else -> made.detail.ifBlank { "说不上来" }
        }))
    }
    conn.refresh()
    return Result.success(conn.sessions.firstOrNull { it.name == name }
        ?: Session(name, 1, false, dir, System.currentTimeMillis() / 1000, SessionState.Idle, "", 0.0))
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
