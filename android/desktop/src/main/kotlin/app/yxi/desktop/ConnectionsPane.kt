package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable internal fun ConnectionsPane(state: AppState) {
    val conn = state.conn
    val links = state.deviceLinks
    val scope = rememberCoroutineScope()
    var peers by remember(conn) { mutableStateOf<List<TailPeer>>(emptyList()) }
    var tailError by remember(conn) { mutableStateOf("") }
    var loading by remember(conn) { mutableStateOf(false) }
    var review by remember(conn) { mutableStateOf(false) }
    fun refresh() {
        if (conn == null || loading) return
        loading = true
        scope.launch {
            try { peers = LinkProtocol.peers(conn.ssh.exec("tailscale status --json")); tailError = "" }
            catch (e: Exception) { peers = emptyList(); tailError = "未读到 Tailscale 状态，请检查该服务器是否已安装并登录。${e.message?.take(120).orEmpty()}" }
            finally { loading = false }
        }
    }
    LaunchedEffect(conn, conn?.status) { if (conn?.status == Conn.Status.Connected) refresh() }
    LazyColumn(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("连接", style = MaterialTheme.typography.headlineMedium) }
        item { Text(conn?.host?.let { "当前服务器 · ${it.label} · ${it.hostname}" } ?: "请在左侧选择服务器", color = Tokens.current.textMuted) }
        links.status["storage"]?.let { item { Text(it, color = Tokens.current.danger) } }
        if (conn != null) item {
            val key = projectKey(conn.host, "/")
            val record = links.record(conn)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("SSH 双向连接", style = MaterialTheme.typography.titleLarge)
                    Text("服务器 → 本机 SSH：127.0.0.1:${record?.reversePort ?: 2222} → 本机 :22\n本机 → 服务器服务：127.0.0.1:${record?.localPort ?: 5901} → 服务器 :5901")
                    Text("连接由 Yxi 在后台保持，保活间隔 30 秒；退出 Yxi 会断开。断开不会删除双方公钥。", style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                    if (System.getProperty("os.name").contains("Mac")) Text("macOS：请先自行开启“系统设置 → 通用 → 共享 → 远程登录”，并允许当前用户。")
                    links.status[key]?.let { Text(it, color = if (links.connected(conn)) Tokens.current.accent else Tokens.current.textSecondary) }
                    if (key in links.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button({ review = true }, enabled = key !in links.busy && conn.status == Conn.Status.Connected) { Text("一键配置并连接") }
                        if (links.connected(conn)) OutlinedButton({ links.disconnect(conn) }, enabled = key !in links.busy) { Text("断开") }
                        else OutlinedButton({ links.connect(conn) }, enabled = record?.ready == true && key !in links.busy) { Text("连接") }
                        TextButton({
                            state.localAgentPrompt = "请检查此电脑连接 ${conn.host.label} 的部署环境。只做诊断并说明修复步骤，不修改系统、SSH 公钥、服务或防火墙。\n当前状态：${links.status[key].orEmpty()}\n需要 OpenSSH Server、本机 22 端口，以及反向端口 ${record?.reversePort ?: 2222}。"
                            state.page = Page.LocalAgents
                        }) { Text("本机 AI 协助") }
                    }
                }
            }
        }
        val others = state.conns.filter { it !== conn && links.record(it) != null }
        items(others, key = { "other:${it.host.id}" }) { other ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${other.host.label} · ${links.record(other)?.reversePort} / ${links.record(other)?.localPort} · ${if (links.connected(other)) "已连接" else "未连接"}", Modifier.weight(1f))
                TextButton({ if (links.connected(other)) links.disconnect(other) else links.connect(other) }, enabled = projectKey(other.host, "/") !in links.busy) { Text(if (links.connected(other)) "断开" else "连接") }
            }
        }
        item {
            Row { Text("Tailscale 设备", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton({ refresh() }, enabled = conn?.status == Conn.Status.Connected && !loading) { Text("刷新") } }
            Text("显示当前服务器可见的设备；“在线”不代表正在与它通信，“活跃链路”才表示近期有连接活动。Tailscale 由你自行配置。", color = Tokens.current.textMuted, style = MaterialTheme.typography.bodySmall)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (tailError.isNotBlank()) Text(tailError, color = Tokens.current.warning)
        }
        items(peers, key = { "peer:${it.id}" }) { peer ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Computer, null)
                    Column(Modifier.weight(1f)) { Text(peer.name); Text(peer.addresses, style = MaterialTheme.typography.bodySmall) }
                    Text(if (peer.active) "活跃链路 · ${peer.route}" else if (peer.online) "在线" else "离线", color = Tokens.current.textMuted)
                }
            }
        }
    }
    if (review && conn != null) AlertDialog(onDismissRequest = { review = false }, title = { Text("配置 ${conn.host.label} 与这台电脑的连接") },
        text = { Text("将为双方添加 Yxi 公钥，并验证服务器登录本机 SSH。Windows 会请求管理员权限，安装并启动 OpenSSH Server；macOS 需要你已开启远程登录。完成后自动建立后台隧道，端口冲突会尝试下一组。\n\n服务器使用已保存的地址 ${conn.host.hostname}:${conn.host.port}。如需走公网，请先在服务器配置中填写并验证公网地址。管理员账户的 Windows 公钥由系统管理员组共用。") },
        confirmButton = { Button({ review = false; links.deploy(conn) }) { Text("开始配置") } },
        dismissButton = { TextButton({ review = false }) { Text("取消") } })
}

@Composable internal fun LocalAgentsPane(state: AppState) {
    var engine by remember { mutableStateOf("codex") }
    var directory by remember { mutableStateOf(System.getProperty("user.home")) }
    var error by remember { mutableStateOf("") }
    val agents = state.localAgents
    LazyColumn(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("本地 Agent", style = MaterialTheme.typography.headlineMedium) }
        item { Text("任务在这台电脑运行，使用本机运行器的登录与权限设置。系统管理员操作仍由系统授权。", color = Tokens.current.textMuted) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("codex" to "Codex", "claude" to "Claude Code").forEach { (id, title) ->
                FilterChip(engine == id, { engine = id }, label = { Text(title) }, leadingIcon = { RunnerBrandIcon(id, Modifier.size(18.dp)) })
            }
        } }
        item { OutlinedTextField(directory, { directory = it }, Modifier.fillMaxWidth(), label = { Text("本机工作目录") }, singleLine = true) }
        item { OutlinedTextField(state.localAgentPrompt, { state.localAgentPrompt = it }, Modifier.fillMaxWidth(), label = { Text("让本机 AI 完成什么任务") }, minLines = 3) }
        item { Row {
            Button({ runCatching { agents.start(engine, directory, state.localAgentPrompt); error = "" }.onFailure { error = it.message.orEmpty() } }, enabled = state.localAgentPrompt.isNotBlank()) { Text("在本机运行") }
            if (error.isNotBlank()) Text(error, Modifier.padding(12.dp), color = Tokens.current.danger)
        } }
        items(agents.jobs, key = { it.id }) { job ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row { Text("${job.engine} · ${job.status}", Modifier.weight(1f)); if (job.running) TextButton({ agents.stop(job) }) { Text("停止") } }
                Text(job.directory.path, color = Tokens.current.textMuted)
                SelectionContainer { Text(job.output.ifBlank { "等待运行器输出…" }, style = MaterialTheme.typography.bodySmall, maxLines = 30) }
                TextButton({ runCatching { java.awt.Desktop.getDesktop().open(job.log) } }, enabled = job.log.exists()) { Text("打开完整日志") }
            } }
        }
    }
}
