package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private class PendingRemoteAuthentication(val plan: RemoteAuthenticationPlan, val completion: kotlinx.coroutines.CompletableDeferred<Int?>)

@Composable internal fun RemoteAcpPane(state: AppState) {
    val conn = state.conn ?: run { Text("请先选择服务器"); return }
    val tasks = state.remoteAcpTasks
    val engine = state.remoteAcpEngine
    var authentication by remember(conn, engine) { mutableStateOf<PendingRemoteAuthentication?>(null) }
    DisposableEffect(conn, engine) { onDispose { tasks.abandonPreparation() } }
    authentication?.let { pending ->
        AuthenticationTerminalDialog(pending.plan, "${conn.host.label} · ${LocalRuntimeDiscovery.title(engine)} 认证", { RemoteAuthenticationTerminal.start(pending.plan) }) { code, error ->
            if (error != null) pending.completion.completeExceptionally(IllegalStateException(error)) else pending.completion.complete(code)
        }
    }
    val record = tasks.tasks(conn).singleOrNull { it.key == state.remoteAcpSelectedKey }
    val t = Tokens.current
    Surface(Modifier.fillMaxSize(), color = t.surface0, contentColor = t.textPrimary) {
        if (record != null) AcpConversationPane(state, record, tasks.controllers[record.key], "返回 ${conn.host.label}") { state.remoteAcpSelectedKey = null }
        else {
            val scope = rememberCoroutineScope()
            var directory by remember(conn, engine) { mutableStateOf(state.remoteAcpDirectory) }
            var title by remember(conn, engine) { mutableStateOf("") }
            var connected by remember(conn, engine) { mutableStateOf(false) }
            var working by remember(conn, engine) { mutableStateOf(false) }
            var error by remember(conn, engine) { mutableStateOf("") }
            var reviewed by remember(conn, engine) { mutableStateOf(false) }
            fun act(block: suspend () -> Unit) {
                if (working) return
                working = true; error = ""
                scope.launch { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message.orEmpty(); if (tasks.initialization == null) connected = false }
                    finally { working = false } }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${conn.host.label} · ${LocalRuntimeDiscovery.title(engine)}", style = MaterialTheme.typography.headlineMedium)
                Text("服务器对话预览 · 使用服务器原生配置，提示词仅保存为草稿。")
                OutlinedTextField(directory, { directory = it; connected = false }, Modifier.fillMaxWidth(), label = { Text("已存在的服务器工作目录") }, enabled = !working && !tasks.busy)
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("对话名称") }, enabled = !working && !tasks.busy)
                TextButton({ act { connected = false; tasks.prepare(conn, engine, directory); connected = true } }, enabled = conn.ssh.isConnected && !working && !tasks.busy) { Text("连接服务器运行器") }
                if (connected) {
                    val methods = tasks.initialization?.optJSONArray("authMethods")
                    for (i in 0 until (methods?.length() ?: 0)) {
                        val method = methods!!.getJSONObject(i)
                        val terminal = method.optString("type", "agent") == "terminal"
                        TextButton({ act {
                            if (terminal) tasks.authenticateTerminal(conn, method.getString("id")) { plan ->
                                val completion = kotlinx.coroutines.CompletableDeferred<Int?>()
                                authentication = PendingRemoteAuthentication(plan, completion)
                                try { completion.await() } finally { authentication = null }
                            } else tasks.authenticate(conn, method.getString("id"))
                        } }, enabled = !working && !tasks.busy) { Text(method.optString("name").ifBlank { method.getString("id") }) }
                        if (terminal) Text("在 ${conn.host.label} 的交互终端完成配置，成功退出后重新连接。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                val recovery = tasks.recoverySessionId(conn)
                if (recovery.isNotBlank() || tasks.registry.problem.isNotBlank()) {
                    Text(listOf(recovery, tasks.registry.problem).filter { it.isNotBlank() }.joinToString("\n"), color = t.danger)
                    Row { Checkbox(reviewed, { reviewed = it }); Text("我已核对服务器原生历史与登记") }
                    TextButton({ runCatching {
                        if (tasks.registry.recoveryReviewRequired) tasks.registry.confirmRecoveryReviewed()
                        if (recovery.isNotBlank()) tasks.confirmCreationReviewed(conn)
                        connected = false
                    }.onFailure { error = it.message.orEmpty() } }, enabled = reviewed && !working && !tasks.busy) { Text("确认核对") }
                }
                if (error.isNotBlank()) Text(error, color = t.danger)
                if (working || tasks.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button({ act {
                    val created = tasks.create(conn, title)
                    state.chatDrafts[created.key] = mutableStateOf(TextFieldValue(state.remoteAcpPrompt))
                    state.remoteAcpPrompt = ""; state.remoteAcpSelectedKey = created.key
                } }, enabled = connected && !working && !tasks.busy && conn.ssh.isConnected && recovery.isBlank() && tasks.registry.problem.isBlank()) { Text("创建对话") }
                Text("已登记对话", style = MaterialTheme.typography.titleMedium)
                tasks.tasks(conn).forEach { task -> TextButton({ state.remoteAcpSelectedKey = task.key }) { Text("${task.title} · ${LocalRuntimeDiscovery.title(task.engine)} · ${task.model}") } }
            }
        }
    }
}
