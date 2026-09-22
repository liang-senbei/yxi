package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.agent.*
import app.yxi.ssh.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** Called only after an explicit Bypass selection for this newly created launch plan. */
internal suspend fun confirmBypassStartup(conn: Conn, session: Session, plan: DesktopLaunchPlan) {
    check(plan.permissionMode == PermissionMode.Bypass && plan.agent == "claude" && plan.sessionName == session.name)
    conn.instructionDeliveryMutex.withLock { confirmExplicitBypass(conn, session) }
}

/** Caller holds the delivery mutex and has explicit permission to enable bypass. */
internal suspend fun confirmExplicitBypass(conn: Conn, session: Session) {
        val target = Shell.q("=" + session.name + ":")
        var moved = false
        var accepted = false
        repeat(80) {
            val screen = conn.ssh.exec("tmux capture-pane -p -t $target")
            if (PermissionMode.fromScreen(screen) == PermissionMode.Bypass) return
            val warning = "WARNING: Claude Code running in Bypass Permissions mode" in screen &&
                "Enter to confirm" in screen && "Yes, I accept" in screen && "No, exit" in screen
            val key = when {
                warning && !moved && screen.lines().any { it.trim() == "❯ No, exit" } -> "Down"
                warning && moved && !accepted && screen.lines().any { it.trim() == "❯ Yes, I accept" } -> "Enter"
                else -> null
            }
            if (key != null) {
                val script = "pane=\$(tmux display-message -p -t $target '#{pane_id}') && " +
                    "test \"\$(tmux display-message -p -t \"\$pane\" '#{pid}:#{session_id}:#{session_created}')\" = ${Shell.q(session.runtimeId)} && " +
                    "test \"\$(tmux capture-pane -p -t \"\$pane\")\" = ${Shell.q(screen.trimEnd('\n'))} && " +
                    "tmux send-keys -t \"\$pane\" $key && printf '__YXI_BYPASS_START__'"
                check(conn.ssh.exec(script).trim() == "__YXI_BYPASS_START__") { "启动确认页已变化，请查看终端" }
                if (key == "Down") moved = true else accepted = true
            }
            delay(250)
        }
        error("完全访问模式尚未确认，请在新会话终端查看启动提示")
}

internal class PermissionModeUnavailable(val desired: PermissionMode) : IllegalStateException(
    if (desired == PermissionMode.Bypass) "这个会话启动时没有启用完全访问。可配置并重启当前会话，保留原会话历史；不会修改其他会话。"
    else "当前运行器没有开放${desired.title}，可能受版本、模型或服务器配置限制。")

internal suspend fun changeConversationPermission(conn: Conn, session: Session, desired: PermissionMode): PermissionMode {
    return conn.instructionDeliveryMutex.withLock {
        check(conn.ssh.isConnected && !session.isCodex && session.runtimeId.isNotBlank()) { "当前运行器暂不支持切换" }
        check(!RewindDelivery.gate.blocked(taskNavigationKey(conn.host, session))) { "请先完成回退核验" }
        val target = Shell.q("=" + session.name + ":")
        suspend fun capture() = conn.ssh.exec("tmux capture-pane -p -t $target")
        val seen = mutableSetOf<PermissionMode>()
        repeat(PermissionMode.entries.size + 1) {
            val screen = capture()
            check(Model.borrowable(screen) && Prompt.parse(screen) == null) { "请等待任务空闲并处理终端中的未发送内容" }
            val actual = PermissionMode.fromScreen(screen) ?: error("无法确认运行器当前权限模式，请查看终端")
            if (actual == desired) return@withLock actual
            if (!seen.add(actual)) throw PermissionModeUnavailable(desired)
            val script = "pane=\$(tmux display-message -p -t $target '#{pane_id}') && " +
                "test \"\$(tmux display-message -p -t \"\$pane\" '#{pid}:#{session_id}:#{session_created}')\" = ${Shell.q(session.runtimeId)} && " +
                "test \"\$(tmux capture-pane -p -t \"\$pane\")\" = ${Shell.q(screen.trimEnd('\n'))} && " +
                "tmux send-keys -t \"\$pane\" BTab && printf '__YXI_PERMISSION_STEP__'"
            check(conn.ssh.exec(script).trim() == "__YXI_PERMISSION_STEP__") { "会话状态已变化，切换已停止" }
            delay(250)
        }
        error("权限切换尚未确认，请查看终端")
    }
}

@Composable
internal fun ConversationPermissionMenu(conn: Conn, session: Session, enabled: Boolean, onTerminal: () -> Unit) {
    var open by remember(session.runtimeId) { mutableStateOf(false) }
    var actual by remember(session.runtimeId) { mutableStateOf<PermissionMode?>(null) }
    var error by remember(session.runtimeId) { mutableStateOf("") }
    var changing by remember(session.runtimeId) { mutableStateOf(false) }
    var canConfigure by remember(session.runtimeId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    NativeOverlay(open)
    LaunchedEffect(session.runtimeId, open, changing) {
        if (!changing && conn.ssh.isConnected) {
            try { actual = PermissionMode.fromScreen(conn.ssh.exec("tmux capture-pane -p -t ${Shell.q("=" + session.name + ":")}")) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { actual = null }
        }
    }
    Box {
        TextButton({ open = true }, enabled = enabled && !changing) {
            Icon(Icons.Outlined.Shield, null, Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(if (changing) "切换中…" else actual?.title ?: "权限模式")
        }
        DropdownMenu(open, { if (!changing) open = false }, Modifier.width(300.dp)) {
            Text("当前会话权限", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.labelMedium)
            PermissionMode.entries.forEach { mode ->
                DropdownMenuItem(text = { Column {
                    Text(mode.title + if (actual == mode) "  ✓" else "")
                    Text(mode.description, style = MaterialTheme.typography.bodySmall)
                } }, enabled = enabled && !changing, onClick = {
                    changing = true; error = ""; canConfigure = false
                    scope.launch {
                        try { actual = changeConversationPermission(conn, session, mode); open = false }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { actual = null; error = e.message ?: "切换失败"; canConfigure = e is PermissionModeUnavailable && e.desired == PermissionMode.Bypass }
                        finally { changing = false }
                    }
                })
            }
            if (error.isNotBlank()) {
                Text(error, Modifier.padding(16.dp, 8.dp), color = MaterialTheme.colorScheme.error)
                if (canConfigure) DropdownMenuItem(text = { Text("配置并重启当前会话") }, enabled = enabled && !changing, onClick = {
                    changing = true; canConfigure = false
                    scope.launch {
                        try { configureConversationBypass(conn, session); actual = PermissionMode.Bypass; error = ""; open = false }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { actual = null; error = e.message ?: "配置未完成，请查看终端" }
                        finally { changing = false }
                    }
                })
                DropdownMenuItem(text = { Text("查看终端") }, onClick = { open = false; onTerminal() })
            }
        }
    }
}
