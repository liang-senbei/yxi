package app.yxi.desktop

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.yxi.agent.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 服务器列表、连接 / 断开和各主机弹窗的状态。原来是 [Sidebar] 里的一串 remember：侧栏收起（Ctrl+B）
 * 或进「我的」时 Sidebar 整个不在组合里，开着的弹窗、[FileHostKeys] 跟着丢，指纹弹窗也不显示
 * （jsch 只能干等到超时），开机恢复也不跑。现在挂在 App 上，侧栏和 Code 风格的入口共用一份。
 */
internal class HostActions(val state: AppState, private val scope: CoroutineScope) {
    var hosts by mutableStateOf(Store.hosts())
    var editing by mutableStateOf<Host?>(null)
    var coloring by mutableStateOf<Host?>(null)
    var deleting by mutableStateOf<Host?>(null)
    var creatingOn by mutableStateOf<Conn?>(null)
    var creatingFavorite by mutableStateOf<FavoriteLaunch?>(null)
    var openingFavorite by mutableStateOf(false)
    var installingKey by mutableStateOf<Host?>(null)
    var note by mutableStateOf("")       // 不属于某条连接的错（Conn 都没建出来）
    var importing by mutableStateOf<HostImportPlan?>(null)
    var pickingTransfer by mutableStateOf(false)
    var recoveringHosts by mutableStateOf(false)
    val keys = FileHostKeys()

    fun connOf(h: Host) = state.conns.firstOrNull { it.host.id == h.id }
    fun save(list: List<Host>) { Store.save(list); hosts = list }
    fun safely(action: () -> Unit) { runCatching(action).onFailure { note = "操作未保存：${it.message}" } }
    fun disconnect(h: Host) {
        // 这台的指纹弹窗还挂着就按「取消」答掉，不然 jsch 的 connect 线程会一直等到超时
        keys.pending?.takeIf { it.host == keys.jschHost(h) }?.answer?.complete(false)
        val c = connOf(h) ?: return
        state.codexWorkspace.disconnect(c)
        state.remoteOpenCodeTasks.disconnect(c)
        state.remoteAcpTasks.disconnect(c)
        c.close(); state.conns.remove(c)
        if (state.conn === c) { state.rememberTaskView(); state.conn = null; state.session = null }
    }
    fun connect(h: Host) {
        if (h.keyPath.isBlank() && h.password.isBlank()) {
            editing = h; note = "请先填写这台服务器的认证信息，再连接。"; return
        }
        disconnect(h); note = ""
        // 私钥文件没了会在 Conn 构造时就炸（toConfig 读文件），不算连接错误
        val c = runCatching { Conn(h, keys) }.getOrElse { note = "连不了 ${h.label}：${it.message}"; return }
        // conns 的顺序 = 侧栏顺序（Ctrl+Tab / Ctrl+1…9 按它摊平）
        val order = hosts.map { it.id }
        val at = state.conns.indexOfFirst { order.indexOf(it.host.id) > order.indexOf(h.id) }
        state.conns.add(if (at < 0) state.conns.size else at, c)
        c.start()
        if (state.conn == null) state.conn = c
    }
    fun addHost() { editing = Host(id = UUID.randomUUID().toString(), alias = "", hostname = "", keyPath = defaultKey()) }
    fun importHosts() {
        pickingTransfer = true
        try { chooseHostTransferFile(false)?.let { file ->
            check(file.length() <= 4 * 1024 * 1024) { "导入文件最多4MiB" }
            importing = HostTransfer.preview(file.readText(), hosts)
        } } catch (e: Exception) { note = e.message ?: "导入文件无法读取" }
        finally { pickingTransfer = false }
    }
    fun exportHosts() {
        pickingTransfer = true
        try { chooseHostTransferFile(true)?.let { file ->
            val target = file.canonicalFile
            check(!target.toPath().startsWith(Store.dir.canonicalFile.toPath())) { "请选择应用配置目录之外的位置" }
            check(hosts.none { it.keyPath.isNotBlank() && java.io.File(it.keyPath).canonicalFile == target }) { "不能覆盖现有私钥文件" }
            DurableFile.replace(target, HostTransfer.export(hosts))
            note = "已导出服务器地址，未包含密码或私钥路径。"
        } } catch (e: Exception) { note = e.message ?: "导出失败" }
        finally { pickingTransfer = false }
    }
    fun newConversation() {
        if (state.isLocal) { state.page = Page.LocalWorkspace; return }
        val c = if (state.hostScope.isEmpty()) state.conn else state.conns.firstOrNull { it.host.id == state.hostScope }
        if (c?.status == Conn.Status.Connected) creatingOn = c else note = "先选择并连接要运行 Agent 的主机"
    }
    /** 收藏的会话：服务器上还活着就直接选中，没了就按收藏的目录 / Agent 弹「新建会话」。 */
    fun openFavorite(c: Conn, favorite: FavoriteLaunch) {
        if (openingFavorite) return
        openingFavorite = true
        scope.launch {
            try {
                c.refresh()
                check(c.ssh.isConnected) { "请先连接服务器" }
                val live = c.sessions.firstOrNull { taskNavigationKey(c.host, it) == favorite.key }
                if (live != null) state.select(c, live)
                else { creatingFavorite = favorite; creatingOn = c }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { note = e.message.orEmpty() }
            finally { openingFavorite = false }
        }
    }
    /**
     * 新会话建好以后（经典对话框、Code 新会话页共用）：没名字就起个默认名，从收藏来的按收藏改名并把收藏挪过去，再选中。
     */
    fun sessionCreated(c: Conn, s: Session, favorite: FavoriteLaunch?) {
        val key = taskNavigationKey(c.host, s)
        if (state.navigation.title(key) == null) state.navigation.rename(key, favorite?.title ?: ("新对话 · " + if (s.isCodex) "Codex" else "Claude Code"))
        favorite?.let { state.navigation.moveFavorite(it.key, key, c.host, s) }
        state.select(c, s)
    }
}

/** App 级只建一份：协程跟 App 的组合走，收起侧栏不会取消正在打开的收藏。 */
@Composable
internal fun rememberHostActions(state: AppState): HostActions {
    val scope = rememberCoroutineScope()
    return remember(state) { HostActions(state, scope) }
}

/**
 * 开机恢复上次的主机 / 会话，以及 Ctrl+N / 托盘「新建会话」。原来在 Sidebar 里，
 * 窄窗口（侧栏自动收起）或停在「我的」时不跑；挂到 App 上以后跟侧栏开没开无关。
 */
@Composable
internal fun HostEffects(actions: HostActions) {
    with(actions) {
        LaunchedEffect(Unit) {
            if (!state.startupRestored) {
                state.startupRestored = true
                if (!state.isLocal && hosts.none { it.id == state.hostScope }) state.scopeHost("")
                if (!state.isLocal && Store.pref("reconnectOnStart", "1") == "1") {
                    hosts.firstOrNull { it.id == Store.pref("lastHost", "") }?.let { h ->
                        val previousSession = Store.pref("lastSession", "")
                        connect(h)
                        state.restoreSession = previousSession.takeIf { it.isNotEmpty() }
                        state.restoreRuntime = Store.pref("lastRuntime", "").takeIf { it.isNotBlank() }
                    }
                }
            }
        }
        LaunchedEffect(state.conn, state.conn?.sessions, state.restoreSession) {
            val c = state.conn
            val wanted = state.restoreSession
            if (c != null && wanted != null) c.sessions.firstOrNull { if (state.restoreRuntime != null) it.runtimeId == state.restoreRuntime else it.name == wanted }?.let { state.select(c, it) }
        }

        val code = LocalThemeSpec.current.style == UiStyle.Code
        // Ctrl+N：对当前主机弹「新建会话」；本机时经典切到工作台，Code 回本机首页（同侧栏「新会话」）
        LaunchedEffect(state.newSessionRequest) {
            if (state.newSessionRequest == state.newSessionSeen) return@LaunchedEffect
            state.newSessionSeen = state.newSessionRequest
            if (state.isLocal) { if (code) openLocalHome(state) else state.page = Page.LocalWorkspace; return@LaunchedEffect }
            creatingOn = state.conn?.takeIf { it.status == Conn.Status.Connected } ?: state.conns.firstOrNull { it.status == Conn.Status.Connected }
            if (creatingOn == null) note = "先连上一台主机，再新建会话"
        }
        // Code 风格不弹「新建会话」：各入口（Ctrl+N、侧栏、托盘、收藏）照旧设 creatingOn，这里转成 CodeHome 的输入区，创建路径同对话框
        LaunchedEffect(creatingOn, code) {
            val c = creatingOn ?: return@LaunchedEffect
            if (!code) return@LaunchedEffect
            state.newSessionDraft.begin(c, creatingFavorite)
            creatingOn = null; creatingFavorite = null
            state.select(c, null)
        }
    }
}

/**
 * 服务器相关的弹窗（导入 / 恢复 / 编辑 / 颜色 / 删除 / 新建会话 / 装公钥 / 指纹）。挂在 App 上而不是 Sidebar 里：
 * 侧栏收起时开着的弹窗不丢，指纹确认也照样弹得出来。指纹弹窗放最后，叠在最上面。
 */
@Composable
internal fun HostDialogs(actions: HostActions) {
    val t = Tokens.current
    val code = LocalThemeSpec.current.style == UiStyle.Code
    with(actions) {
        NativeOverlay(pickingTransfer)
        importing?.let { plan -> HostImportDialog(plan, { importing = null }) {
            val merged = plan.merge(hosts)
            Store.save(merged)
            hosts = merged; importing = null
            note = "已导入 ${plan.additions.size} 台服务器，请编辑认证设置后连接。"
        } }
        if (recoveringHosts) HostRecoveryDialog({ recoveringHosts = false }) { copy ->
            check(state.conns.isEmpty()) { "请先断开现有连接再恢复服务器列表" }
            hosts = Store.recoverHosts(copy)
            recoveringHosts = false
        }
        editing?.let { h ->
            HostForm(h, isNew = hosts.none { it.id == h.id }, onSave = { n ->
                save(if (hosts.any { it.id == n.id }) hosts.map { if (it.id == n.id) n else it } else hosts + n)
                // 地址 / 认证改了，连着的那条作废重连；只改名字或颜色不用动
                if (connOf(h) != null && n.copy(alias = h.alias, color = h.color, region = h.region) != h) connect(n)
                editing = null
            }, onClose = { editing = null })
        }
        coloring?.let { h ->
            ColorDialog(h.tint(hosts.indexOf(h)), onPick = { col -> safely { save(hosts.map { if (it.id == h.id) it.copy(color = col) else it }); coloring = null } }, onClose = { coloring = null })
        }
        deleting?.let { h ->
            WorkbenchDialog(
                onDismissRequest = { deleting = null },
                title = { Text("删除 ${h.label}？") },
                text = { Text("只删这里的记录和指纹，服务器上的会话不受影响。") },
                confirmButton = { TextButton({ safely { save(hosts.filter { it.id != h.id }); disconnect(h); keys.forget(h); if (state.hostScope == h.id) state.scopeHost(""); deleting = null } }) { Text("删除", color = t.danger) } },
                dismissButton = { TextButton({ deleting = null }) { Text("取消") } },
            )
        }
        if (!code) creatingOn?.let { c -> NewSessionDialog(c, onDismiss = { creatingOn = null; creatingFavorite = null }, sharedMcpRegistry = state.sharedMcp, onCodexConversation = { directory, prompt ->
            creatingOn = null
            creatingFavorite = null
            state.prepareCodexTask(c, directory, prompt)
        }, onOpenCodeConversation = { directory, prompt ->
            creatingOn = null; creatingFavorite = null; state.prepareOpenCodeTask(c, directory, prompt)
        }, onAcpConversation = { engine, directory, prompt ->
            creatingOn = null; creatingFavorite = null; state.prepareAcpTask(c, engine, directory, prompt)
        }, initialDirectory = creatingFavorite?.directory, initialAgent = creatingFavorite?.agent) { s ->
            val favorite = creatingFavorite
            creatingFavorite = null
            creatingOn = null
            sessionCreated(c, s, favorite)
        } }
        installingKey?.let { h ->
            CopyIdDialog(h, keys, onSaved = { n ->
                save(hosts.map { if (it.id == n.id) n else it })
                if (connOf(h) != null) connect(n)   // 装成了就切到密钥重连（密码还留着当后备）
            }, onClose = { installingKey = null })
        }
        keys.pending?.let { p -> FingerprintDialog(p, alias = hosts.firstOrNull { keys.jschHost(it) == p.host }?.label ?: p.host) }
    }
}
