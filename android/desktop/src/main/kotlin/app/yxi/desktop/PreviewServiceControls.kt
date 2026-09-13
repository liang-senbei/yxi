package app.yxi.desktop

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.agent.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PreviewServiceControls(state: AppState, conn: Conn, session: Session, configure: Boolean, onConfigured: () -> Unit, onPreview: (String) -> Unit) {
    val project = projectKey(conn.host, session.cwd)
    val settings = state.projectServices.get(project)
    val controller = state.serviceControllers.getOrPut(project) { PreviewServiceController(project, session.cwd) }
    val scope = rememberCoroutineScope()
    var error by remember(project) { mutableStateOf("") }
    var logs by remember(project) { mutableStateOf(false) }
    var more by remember(project) { mutableStateOf(false) }
    NativeOverlay(more)
    fun act(action: suspend () -> Unit) { scope.launch {
        try { error = ""; action() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message.orEmpty() }
    } }
    LaunchedEffect(project, settings, conn.status, configure) {
        if (conn.status == Conn.Status.Connected && (settings != null || configure)) {
            do {
                if (!controller.busy) try { controller.refresh(conn); error = "" } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message.orEmpty() }
                delay(4000)
            } while (settings != null)
        }
    }
    val snapshot = controller.snapshot
    val status = snapshot?.optString("state")
    val owned = status in listOf("starting", "running", "exited", "configuration-conflict")
    val connected = conn.status == Conn.Status.Connected
    if (settings != null || owned) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("开发服务 · " + if (!connected) "未连接" else if (controller.mutating) "操作中…" else when(status) {
                "missing", "stopped" -> "未运行"
                "starting" -> "启动未完成"
                "running" -> "进程运行中"
                "exited" -> "已退出（${snapshot?.opt("exitCode")?.takeUnless { it == org.json.JSONObject.NULL } ?: "未知退出码"}）"
                "port-busy" -> "端口已有服务"
                "unowned" -> "会话归属不匹配"
                "configuration-conflict" -> "配置已变化"
                else -> "状态待检查"
            }, Modifier.weight(1f).padding(start = 12.dp, top = 12.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
            if (owned) TextButton({
                val runtime = snapshot?.optString("runtime").orEmpty(); val config = snapshot?.optString("config").orEmpty()
                act { controller.stop(conn, runtime, config) }
            }, enabled = connected && !controller.mutating && owned) { Text(if (status == "exited") "清理会话" else "停止") }
            else if (settings != null && status in listOf("missing", "stopped")) TextButton({ act { controller.start(conn, settings) } }, enabled = connected && !controller.mutating) { Text("启动") }
            else TextButton({ act { controller.refresh(conn) } }, enabled = connected && !controller.busy) { Text("检查") }
            TextButton({ settings?.let { onPreview(state.projectPreviews.address(project) ?: "http://localhost:${it.port}/") } }, enabled = settings != null && connected) { Text("预览") }
            Box {
                IconButton({ more = true }) { Icon(Icons.Default.MoreVert, "开发服务更多操作") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("查看日志") }, onClick = { more = false; logs = true })
                    DropdownMenuItem(text = { Text("检查状态") }, enabled = connected && !controller.busy, onClick = { more = false; act { controller.refresh(conn) } })
                }
            }
        }
        if (status == "running") Text("网页是否就绪请以预览结果为准。", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
        if (owned && settings != null && snapshot?.optString("config") != settings.plan(project).signature) Text("现有会话使用旧配置，停止后可按新配置启动。", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
    }
    if (controller.error.isNotBlank() || error.isNotBlank()) Text(error.ifBlank { controller.error }, Modifier.padding(horizontal = 12.dp), color = Tokens.current.danger, style = MaterialTheme.typography.bodySmall)
    if (state.projectServices.error.isNotBlank()) Text(state.projectServices.error, Modifier.padding(horizontal = 12.dp), color = Tokens.current.danger, style = MaterialTheme.typography.bodySmall)
    if (configure) {
        val defaultPort = state.projectPreviews.address(project)?.let { runCatching { PreviewAddress.parse(it).remotePort }.getOrNull() } ?: 3000
        val edit = remember { state.serviceEditors.getOrPut(project) { ServiceEditor(project, session.cwd, settings, defaultPort) } }
        var formError by remember(edit) { mutableStateOf("") }
        fun dismiss() { state.serviceEditors.remove(edit.project); onConfigured() }
        WorkbenchDialog(onDismissRequest = ::dismiss, title = { Text("项目开发服务") }, text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("保存后可点击启动，在此服务器执行前台命令。命令需监听指定端口。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(edit.directory, { edit.directory = it }, label = { Text("服务器工作目录") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(edit.command, { edit.command = it }, label = { Text("启动命令") }, placeholder = { Text("npm run dev -- --host 127.0.0.1 --port ${edit.port}") }, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 180.dp))
                OutlinedTextField(edit.port, { edit.port = it }, label = { Text("监听端口") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("仅管理Yxi创建的预览会话；保存配置不会自动执行命令。", style = MaterialTheme.typography.bodySmall)
                if (formError.isNotBlank()) Text(formError, color = Tokens.current.danger)
                if (edit.project != project) Text("当前项目已变化，请关闭后重新打开配置。", color = Tokens.current.danger)
            }
        }, confirmButton = { TextButton({ runCatching { check(edit.project == project); state.projectServices.save(project, edit.value(), edit.saved); dismiss() }.onFailure { formError = it.message.orEmpty() } }, enabled = !controller.mutating && edit.project == project) { Text("保存配置") } }, dismissButton = { TextButton(::dismiss) { Text("取消") } })
    }
    if (logs) WorkbenchDialog(onDismissRequest = { logs = false }, title = { Text("开发服务日志") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            SelectionContainer { Text(snapshot?.optString("log").orEmpty().trimEnd().ifBlank { "尚无可用日志，可先检查状态。" }, style = CodeStyle) }
        }
    }, confirmButton = { TextButton({ act { controller.refresh(conn) } }, enabled = connected && !controller.busy) { Text("刷新日志") } }, dismissButton = { TextButton({ logs = false }) { Text("关闭") } })
}
