package app.yxi.desktop

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.agent.Lines
import app.yxi.agent.SessionState
import app.yxi.ssh.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

internal class DeferredRoute(val conn: Conn, line: Lines.Line, val directory: String?, catalog: List<Lines.Line>) {
    val id = UUID.randomUUID().toString()
    val line = line.copy(extra = org.json.JSONObject(line.extra.toString()))
    val catalog = catalog.map { it.copy(extra = org.json.JSONObject(it.extra.toString())) }
    var applying by mutableStateOf(false)
    var status by mutableStateOf("等待目标范围内的任务空闲")
}

@Composable
internal fun DeferredRouteStatus(state: AppState) {
    val request = state.deferredRoute
    if (request == null && state.deferredRouteNotice.isBlank()) return
    Surface(color = Tokens.current.surface1, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (request != null) {
                    Text("${request.conn.host.label} · ${request.line.name}", style = MaterialTheme.typography.labelMedium)
                    Text("${request.status} · ${request.directory ?: "用户级配置"}", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                } else Text(state.deferredRouteNotice, style = MaterialTheme.typography.bodySmall)
            }
            if (request != null) {
                if (request.applying) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else TextButton({
                    if (state.deferredRoute === request && !request.applying) {
                        state.deferredRoute = null
                        state.deferredRouteNotice = "已取消 ${request.conn.host.label} 的等待切换，未写入配置"
                    }
                }) { Text("取消等待") }
            } else TextButton({ state.deferredRouteNotice = "" }) { Text("关闭提示") }
        }
    }
}

@Composable
internal fun DeferredRouteRunner(state: AppState) {
    val request = state.deferredRoute ?: return
    LaunchedEffect(request.id) { runDeferredRoute(state, request) }
}

/** Execution is separate from Compose; cancellation is checked before each polling cycle. */
internal suspend fun runDeferredRoute(state: AppState, request: DeferredRoute) {
        try {
            while (true) {
                if (state.deferredRoute !== request) return
                delay(2000)
                if (state.deferredRoute !== request) return
                val conn = request.conn
                if (conn !in state.conns || !conn.ssh.isConnected) {
                    request.status = "等待原服务器重新连接"
                    continue
                }
                conn.refresh()
                if (!conn.ssh.isConnected) continue
                val affected = conn.sessions.filter { it.isCodex == request.line.isCodex && (request.directory == null || it.cwd == request.directory) }
                if (affected.any { it.state in setOf(SessionState.Working, SessionState.NeedsYou) }) {
                    request.status = "等待生成或待处理交互结束"
                    continue
                }
                var inputsReady = true
                for (task in affected) {
                    val target = Shell.q("=" + task.name + ":")
                    val identity = conn.ssh.exec("tmux display-message -p -t $target '#{pid}:#{session_id}:#{session_created}' 2>/dev/null").trim()
                    val screen = conn.ssh.exec("tmux capture-pane -p -t $target 2>/dev/null")
                    if (task.runtimeId.isBlank() || identity != task.runtimeId || !app.yxi.agent.Model.borrowable(screen)) { inputsReady = false; break }
                }
                if (!inputsReady) { request.status = "等待运行器输入状态可确认"; continue }
                // Reuse the same runtime configuration path as explicit application.
                val current = Lines.list(conn.ssh) ?: error("无法读取线路清单，已取消等待")
                check(routeCatalogEqual(current, request.catalog)) { "线路清单已改变，已取消等待；请重新选择" }
                if (state.deferredRoute !== request) return
                request.applying = true
                request.status = "正在写入配置"
                withContext(NonCancellable) {
                    try {
                        val restart = if (request.line.isCodex) {
                            Lines.applyCodex(conn.ssh, request.line)?.let { error(it) }
                            "Codex 需重开任务后生效"
                        } else {
                            val result = Lines.apply(conn.ssh, request.line, request.directory, request.catalog)
                            result.err?.let { error(it) }
                            if (result.restart.isNotEmpty()) "${result.restart.joinToString()} 需重开任务后生效" else "后续请求是否生效需由运行器确认"
                        }
                        state.deferredRouteNotice = "${conn.host.label} · ${request.line.name} 配置已写入；$restart。"
                    } catch (e: Exception) {
                        state.deferredRouteNotice = "${conn.host.label} 延后切换未确认：${e.message}。请刷新配置核对，不会自动重试写入。"
                    } finally { state.deferredRoute = null }
                }
                break
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            state.deferredRouteNotice = "${request.conn.host.label}：${e.message}"
            if (state.deferredRoute === request) state.deferredRoute = null
        }
}
