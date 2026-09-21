package app.yxi.desktop

import androidx.compose.runtime.mutableStateMapOf
import app.yxi.agent.Model
import app.yxi.agent.Prompt
import app.yxi.agent.Rewind
import app.yxi.agent.RewindLiveVerification
import app.yxi.agent.Session
import app.yxi.ssh.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.sync.withLock

/** App-lifetime operation: changing the selected conversation does not cancel a submitted rewind. */
internal object ConversationRewind {
    data class State(val running: Boolean, val message: String, val failed: Boolean = false,
        val editedText: String? = null, val messageUuid: String? = null)
    private val states = mutableStateMapOf<String, State>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    fun state(taskKey: String): State? = states[taskKey]
    fun dismiss(taskKey: String) { if (states[taskKey]?.running != true) states.remove(taskKey) }

    fun start(conn: Conn, session: Session, messageUuid: String, text: String): Boolean {
        val key = taskNavigationKey(conn.host, session)
        if (states[key]?.running == true || RewindDelivery.gate.blocked(key)) return false
        states[key] = State(true, "正在核对历史轮次…")
        scope.launch {
            try {
                restore(conn, session, messageUuid, text) { states[key] = State(true, it) }
                states[key] = State(false, "已回到所选轮次，可以继续对话。")
            } catch (e: CancellationException) {
                states[key] = State(false, "回退操作已中断，请核对恢复状态。", true, text, messageUuid)
                throw e
            } catch (e: Exception) {
                states[key] = State(false, e.message ?: "回退未完成，请核对当前会话。", true, text, messageUuid)
            }
        }
        return true
    }

    private suspend fun checkEmptyPrompt(conn: Conn, session: Session) {
        val screen = conn.ssh.exec("tmux capture-pane -p -t ${Shell.q("=" + session.name + ":")}")
        check(Model.borrowable(screen) && Prompt.parse(screen) == null) { "终端正忙、等待确认或仍有输入，请先处理后再回退。" }
    }

    private suspend fun restore(conn: Conn, session: Session, messageUuid: String, text: String, progress: (String) -> Unit) {
        require(text.isNotBlank()) { "请输入继续对话的内容" }
        check(conn.ssh.isConnected && !session.isCodex) { "当前会话暂不支持此回退方式" }
        val target = RewindTargets.inspect(conn, session, messageUuid)
        check(!target.unsupportedContent) { "此消息包含附件或多个内容块，附件恢复尚未接入，未执行回退。" }
        val anchor = target.parentUuid ?: error("首轮自动回退尚未接入，请使用原生回退入口。")
        val plan = Rewind.Plan(target.sessionId, anchor, target.messageUuid,
            target.messageUuid.takeIf { target.laterUserMessages == 0 }, text)
        conn.instructionDeliveryMutex.withLock { checkEmptyPrompt(conn, session) }
        val controller = RewindController(conn)
        progress("正在恢复历史并生成回复…")
        val report = controller.rewind(session.name, plan, target)
        val capture = report.capture
        try {
            val outcome = report.outcome
            if (outcome is Rewind.Outcome.Failed) error(when (outcome.code) {
                "unpreserved" -> "当前启动参数尚不能完整保留，未执行自动回退。请使用原生回退入口。"
                "busy" -> "会话仍在运行或等待确认，请稍后再试。"
                "pending" -> "已有回退等待核对，请先处理恢复状态。"
                "stale" -> "历史刚刚发生变化，请重新选择这一轮。"
                else -> "回退未完成，请核对当前会话；不会自动重复发送。"
            })
            val result = outcome as? Rewind.Outcome.Ok ?: error("无法确认回退结果")
            check(result.sessionId == target.sessionId) { "回退返回的会话与目标不一致，发送仍暂停。" }
            val ticket = requireNotNull(report.ticket)
            val cap = requireNotNull(capture)
            progress("正在载入回退后的会话…")
            conn.instructionDeliveryMutex.withLock { checkEmptyPrompt(conn, session) }
            val serverTime = conn.ssh.exec("python3 -c 'import time; print(time.time())'").trim().toDoubleOrNull()
                ?: error("无法核对服务器时间，发送仍暂停。")
            val query = RewindLiveVerification.Query(session.name, requireNotNull(report.runtimeId), cap.paneId,
                cap.exe, cap.pid, result.sessionId, anchor, target.messageUuid, target.file, serverTime)
            RewindDelivery.gate.prepareVerification(ticket, query)
            val resumed = controller.relaunch(session.name, cap, result.sessionId, report.runtimeId, report.cwd)
            check(resumed.relaunched) { "运行器尚未恢复，发送仍暂停，请查看终端。" }
            progress("正在确认运行器与历史上下文…")
            recheckRewindRecovery(conn, session)
        } finally {
            if (capture != null && conn.ssh.isConnected) {
                try { controller.cleanup(capture) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }
    }
}
