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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
        Modifier.fillMaxWidth().hoverable(src).clip(RoundedCornerShape(8.dp))
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
 * 新建独立运行器会话；同一弹窗请求重试复用其标识，避免网络重试产生重复任务。
 * 出错留在弹窗里显示，成了才关；成了把新会话交给 [onCreated]。
 */
@Composable
fun NewSessionDialog(conn: Conn, onDismiss: () -> Unit, collaborationGroup: String = "", groupContext: String = "", onCodexConversation: ((String, String) -> Unit)? = null, initialDirectory: String? = null, initialAgent: String? = null, onCreated: (Session) -> Unit) {
    val scope = rememberCoroutineScope()
    // 预填现有会话的父目录（工作区），只用补项目名；不写死路径，换台机器就不一样
    var path by remember { mutableStateOf(initialDirectory ?: Dirs.parentsOf(conn.sessions.map { it.cwd }).firstOrNull()?.let { "$it/" }.orEmpty()) }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var agent by remember { mutableStateOf(initialAgent?.takeIf { RunnerCatalog.find(it) != null } ?: "claude") }
    val selectedRunner = RunnerCatalog.find(agent)!!
    var installation by remember(conn, agent) { mutableStateOf("checking") }
    LaunchedEffect(conn, agent) {
        installation = if (selectedRunner.command == null) "unknown" else try {
            kotlinx.coroutines.withTimeout(10000) { conn.ssh.exec(RunnerCatalog.probeCommand(agent)).trim() }.takeIf { it in setOf("available", "missing") } ?: "unknown"
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) { "unknown" }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { "unknown" }
    }
    var initialPrompt by remember { mutableStateOf(groupContext) }
    var isolatedWorktree by remember { mutableStateOf(false) }
    var permissionMode by remember { mutableStateOf(app.yxi.agent.PermissionMode.Manual) }
    var permissionsOpen by remember { mutableStateOf(false) }
    val requestId = remember(path, agent, initialPrompt, collaborationGroup, isolatedWorktree, permissionMode) { DesktopLaunchPlan.newRequestId() }
    WorkbenchDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("在 ${conn.host.label} 上新建会话") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RunnerCatalog.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { runner ->
                            QuietChoice(selected = agent == runner.id, onClick = { agent = runner.id; err = "" }, enabled = !busy,
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                                leadingIcon = { RunnerBrandIcon(runner.id, Modifier.size(20.dp)) },
                                label = { Column { Text(runner.title); if (!runner.serverCreation) Text("创建接入中", style = MaterialTheme.typography.labelSmall) } })
                        }
                    }
                }
                Text(when (installation) {
                    "checking" -> "正在检查 ${conn.host.label} 上的 ${selectedRunner.title}…"
                    "available" -> "${selectedRunner.title} 已安装" + if (selectedRunner.serverCreation) "，可创建会话" else "；启动和会话状态适配尚未完成"
                    "missing" -> "服务器未找到 ${selectedRunner.title}" + if (selectedRunner.serverCreation) "，请先安装并登录" else "；创建适配也尚未完成"
                    else -> if (selectedRunner.serverCreation) "无法确认服务器安装状态，请检查连接后重新选择运行器" else "该运行器的创建接入尚未完成，暂不能启动"
                }, style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                if (initialDirectory != null) Text("已填入所选目录，可在创建前调整。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                Text("先检查运行器，再创建独立会话。目录不存在会创建；已有任务继续运行。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                OutlinedTextField(path, { path = it }, enabled = !busy, singleLine = true, label = { Text("服务器工作目录") }, placeholder = { Text("/opt/workspace/…") }, modifier = Modifier.fillMaxWidth())
                if (agent == "claude") Box {
                    TextButton({ permissionsOpen = true }, enabled = !busy) { Text("权限：${permissionMode.title} ⌄") }
                    androidx.compose.material3.DropdownMenu(permissionsOpen, { permissionsOpen = false }) {
                        app.yxi.agent.PermissionMode.entries.forEach { mode ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(mode.title) }, onClick = {
                                permissionMode = mode; permissionsOpen = false
                            })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(isolatedWorktree, { isolatedWorktree = it }, enabled = !busy)
                    Text("使用独立 Git worktree", style = MaterialTheme.typography.bodySmall)
                }
                if (isolatedWorktree) Text("上方路径作为源仓库，在仓库旁创建独立目录并从当前 HEAD 开始；不带入未提交改动，不自动合并或清理。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                OutlinedTextField(initialPrompt, { initialPrompt = it }, enabled = !busy, minLines = 2, maxLines = 5,
                    label = { Text("启动提示词（可留空）") }, placeholder = { Text("描述目标、分工及需要遵守的项目约定") }, modifier = Modifier.fillMaxWidth())
                if (initialPrompt.isNotBlank()) Text("创建后会把这段内容直接交给运行器，可能立即开始工作。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                Text("运行器沿用服务器的登录与权限设置。创建会话不代表模型请求已经成功。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                if (agent == "codex" && onCodexConversation != null && collaborationGroup.isBlank()) {
                    TextButton({ onCodexConversation(path, initialPrompt) }, enabled = !busy && !isolatedWorktree) { Text("在对话工作台继续") }
                    Text(if (isolatedWorktree) "对话工作台暂需使用已存在的目录；独立工作树可通过下方终端入口创建。" else "支持排队、引导和侧栏预览；提示词先进入草稿，由你确认发送。",
                        style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                }
                if (collaborationGroup.isNotBlank()) Text("创建前加入「$collaborationGroup」；启动失败可能留下未在线成员，可在组编辑中移除。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                if (err.isNotBlank()) Text(err, style = MaterialTheme.typography.bodySmall, color = Tokens.current.danger)
            }
        },
        confirmButton = {
            TextButton(enabled = path.isNotBlank() && !busy && selectedRunner.serverCreation && installation == "available", onClick = {
                busy = true
                scope.launch {
                    try { createSession(conn, DesktopLaunchPlan(path.trim(), agent, requestId, initialPrompt, collaborationGroup, isolatedWorktree,
                        permissionMode.takeIf { agent == "claude" })).fold(onCreated) { err = it.message.orEmpty() } }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { err = e.message.orEmpty() }
                    finally { busy = false }
                }
            }) { Text(if (busy) "正在开…" else "开起来") }
        },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("取消") } },
    )
}

/** 只有刷新取得真实会话后才进入任务，启动回执不能替代运行状态。 */
private suspend fun createSession(conn: Conn, plan: DesktopLaunchPlan): Result<Session> {
    val name = plan.sessionName
    val raw = conn.ssh.exec(plan.command())
    val failure = raw.lineSequence().lastOrNull { it.startsWith(Dirs.TAG + ":") }?.substringAfter(':')
    if (failure !in listOf("ok", "exists")) return Result.failure(IllegalStateException(when (failure) {
        "missing-tmux" -> "这台服务器未安装 tmux"
        "group-failed" -> "加入协作组失败，请刷新分组后重试；尚未启动新任务"
        "worktree-failed" -> "无法准备独立工作树，请确认路径是 Git 仓库、有已提交的 HEAD、父目录可写且 Git/Python3 可用。已有目录不会删除。"
        "missing-runtime" -> "未找到可执行的 ${plan.agent}，请先在该服务器安装并完成登录"
        "nodir" -> "无法创建或进入目录，请检查路径与访问权限"
        "failed" -> "tmux 未能创建会话，请刷新核对后重试"
        "exited" -> "运行器已提前退出，请在服务器终端检查其登录与配置"
        "conflict" -> "同一启动请求对应的目录不一致，未复用已有会话"
        "changed-directory" -> "启动后的目录发生变化，请检查左侧新会话；未删除可能仍在运行的任务"
        else -> "未能确认启动结果，请刷新核对后重试。请求会话：$name"
    }))
    repeat(5) {
        conn.refresh()
        conn.sessions.firstOrNull { it.name == name }?.let {
            if (plan.permissionMode == app.yxi.agent.PermissionMode.Bypass) confirmBypassStartup(conn, it, plan)
            return Result.success(it)
        }
        kotlinx.coroutines.delay(200)
    }
    return Result.failure(IllegalStateException("会话创建已返回，但尚未读到状态。请刷新核对：$name；在此窗口重试会复用同一请求。"))
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
