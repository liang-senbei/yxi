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
class AppState {
    val conns = mutableStateListOf<Conn>()                 // 连着的主机（顺序 = 侧栏分组顺序）
    var conn by mutableStateOf<Conn?>(null)                // 当前会话所在的主机
    var session by mutableStateOf<Session?>(null)          // 当前会话
    var tab by mutableStateOf(0)                           // 0 对话 1 终端
    var sidebarOpen by mutableStateOf(true)                // Ctrl+B；窗口 < 700 时自动收起
    var newSessionRequest by mutableStateOf(0)             // Ctrl+N：+1 一次，侧栏看到就弹「新建会话」
    /** 侧栏上一次处理过的 [newSessionRequest] 值。放 AppState 里而不是 Sidebar 的 remember：
     * Sidebar 收起时整个不在组合里，remember 跟着丢——重新展开时 remember 重新初始化成当前值，
     * 恰好等于刚才 Ctrl+N 加到的那个值，`== seen` 成立，弹窗就不弹了。 */
    internal var newSessionSeen by mutableStateOf(0)
    var showSettings by mutableStateOf(false)              // Ctrl+,
    var showShortcuts by mutableStateOf(false)             // Ctrl+/

    fun select(c: Conn, s: Session?) { conn = c; session = s }

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
