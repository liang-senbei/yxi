package app.yxi.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.yxi.agent.Session
import app.yxi.agent.SessionState

/**
 * 全局状态（一份，`App()` 里 remember）。
 * 左栏 = 主机分组 → cc-* 会话行（Codex 的「项目 → 线程」），所以可以同时连着多台主机（[conns]）；
 * [conn] / [session] 是右边正在看的那一个。
 */
/**
 * 右边整页显示哪一块 —— 对齐手机底部四栏。
 *
 * ⚠️ 手机那四栏里「会话」和「主机」在桌面上**合成了左栏**（Claude Desktop 的形态：
 * 主机分组 → 会话行都在侧栏里），所以这里只剩另外两个整页入口。
 * 它跟 [AppState.tab] 是两层：`page` 决定右边整块是什么，`tab` 只在工作区里选 对话/终端/文件。
 */
enum class Page { Workspace, Config, Me }

class AppState {
    val documents = mutableStateListOf<FileDocument>()
    val documentSelection = androidx.compose.runtime.mutableStateMapOf<String, String>()
    val chatDrafts = mutableMapOf<String, androidx.compose.runtime.MutableState<androidx.compose.ui.text.input.TextFieldValue>>()
    fun appendDocumentQuote(hostId: String, task: String, quote: String) {
        val holder = chatDrafts.getOrPut(hostId + "\u0000" + task) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue()) }
        val old = holder.value.text
        val text = old + (if (old.isBlank()) "" else "\n\n") + quote + "\n"
        holder.value = androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length))
    }
    var filePanelOpen by mutableStateOf(false)
    var filePanelWidth by mutableStateOf(480f)
    var workspaceError by mutableStateOf("")

    suspend fun openDocument(c: Conn, task: Session, path: String) {
        val sftp = c.ssh.openSftp()
        val canonical = try { sftp.realpath(if (path.startsWith('/')) path else task.cwd.trimEnd('/') + "/" + path) } finally { sftp.close() }
        if (documents.none { it.hostId == c.host.id && it.task == task.name && it.path == canonical }) documents += FileDocument(c.host.id, task.name, canonical)
        documentSelection[c.host.id + "\u0000" + task.name] = canonical
        if (conn === c && session?.name == task.name) { filePanelOpen = true; tab = 0 }
    }
    var hostScope by mutableStateOf(Store.pref("hostScope", ""))
    internal var startupRestored = false
    internal var restoreSession by mutableStateOf<String?>(null)
    val conns = mutableStateListOf<Conn>()                 // 连着的主机（顺序 = 侧栏分组顺序）
    var conn by mutableStateOf<Conn?>(null)                // 当前会话所在的主机
    var session by mutableStateOf<Session?>(null)          // 当前会话
    var tab by mutableStateOf(0)                           // 0 对话 1 终端 2 文件
    var page by mutableStateOf(Page.Workspace)             // 左栏底部的「配置」「我的」切这个
    var sidebarOpen by mutableStateOf(true)                // Ctrl+B；窗口 < 700 时自动收起
    var newSessionRequest by mutableStateOf(0)             // Ctrl+N：+1 一次，侧栏看到就弹「新建会话」
    /** 侧栏上一次处理过的 [newSessionRequest] 值。放 AppState 里而不是 Sidebar 的 remember：
     * Sidebar 收起时整个不在组合里，remember 跟着丢——重新展开时 remember 重新初始化成当前值，
     * 恰好等于刚才 Ctrl+N 加到的那个值，`== seen` 成立，弹窗就不弹了。 */
    internal var newSessionSeen by mutableStateOf(0)
    var showSettings by mutableStateOf(false)              // Ctrl+,
    var showShortcuts by mutableStateOf(false)             // Ctrl+/

    /** ⚠️ 选会话顺带回工作区：人在「配置」页点了侧栏的会话，意思显然是「我要去看那个会话」。 */
    fun select(c: Conn, s: Session?) {
        conn = c; session = s; page = Page.Workspace; restoreSession = null
        Store.setPref("lastHost", c.host.id)
        Store.setPref("lastSession", s?.name.orEmpty())
        if (hostScope.isNotEmpty()) scopeHost(c.host.id)
    }

    fun scopeHost(id: String) { hostScope = id; Store.setPref("hostScope", id) }

    /** 所有主机的会话按侧栏顺序摊平（Ctrl+Tab / Ctrl+1…9 用）。 */
    fun allSessions(): List<Pair<Conn, Session>> = conns.flatMap { c -> c.sessions.map { c to it } }

    fun selectByOffset(delta: Int) {
        val all = allSessions(); if (all.isEmpty()) return
        val i = all.indexOfFirst { it.first === conn && it.second.name == session?.name }
        val (c, s) = all[((if (i < 0) 0 else i + delta) % all.size + all.size) % all.size]
        select(c, s)
    }

    fun selectIndex(i: Int) { allSessions().getOrNull(i)?.let { (c, s) -> select(c, s) } }

    /** Ctrl+Alt+A：跳到下一个「等待批准 / 需要输入」的会话；没有就不动。侧栏实现里按 SessionState 判。 */
    fun jumpToAttention() {
        val all = allSessions(); if (all.isEmpty()) return
        val i = all.indexOfFirst { it.first === conn && it.second.name == session?.name }
        for (k in 1..all.size) {
            val (c, s) = all[(i + k) % all.size]
            if (s.needsAttention()) { select(c, s); return }
        }
    }
}

/** 等人处理 = 在等审批或等输入。core 的 SessionProbe 把 Claude Code 的 `waiting` 解成 NeedsYou，批准还是输入都在里面（细分见 SessionsPane 的 badge）。 */
fun Session.needsAttention(): Boolean = state == SessionState.NeedsYou
