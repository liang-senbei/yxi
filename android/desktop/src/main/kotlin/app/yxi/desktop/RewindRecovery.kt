package app.yxi.desktop

import app.yxi.agent.RewindLiveVerification
import app.yxi.agent.NativeRootVerification
import app.yxi.agent.Session
import app.yxi.agent.Live
import app.yxi.ssh.Shell
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock

internal class NativeRootReplyStopped : IllegalStateException("回复已停止，原生输入已保留；上下文尚未恢复确认，发送仍暂停。")

internal class NativeRootRecoveryFailure(val receipt: String) : IllegalStateException("首轮回退尚未确认，发送仍暂停。请核对终端状态。")

/** Recheck persisted evidence without sending a prompt or restarting any process. */
internal suspend fun recheckRewindRecovery(conn: Conn, session: Session, gate: RewindDeliveryGate = RewindDelivery.gate,
    rootTimeoutSec: Int = 20) {
    check(conn.ssh.isConnected) { "请先重新连接服务器" }
    val key = taskNavigationKey(conn.host, session)
    val ticket = gate.pending(key) ?: error("没有可核对的回退记录")
    ticket.nativeRoot?.let { root ->
        check(root.runtime.sessionName == session.name && root.runtime.runtimeId == session.runtimeId) { "会话实例已变化，发送仍暂停" }
        val deadline = System.nanoTime() + rootTimeoutSec * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000_000L).toInt().coerceIn(1, 600)
            val result = runRewindCommand(conn.ssh, NativeRootVerification.command(root, remaining))
            if (NativeRootVerification.becameBusy(result)) { delay(200); continue }
            if (NativeRootVerification.verified(result)) {
                conn.instructionDeliveryMutex.withLock { gate.finishVerified(ticket) }
                return
            }
            if (!NativeRootVerification.needsIdleInputProof(result)) throw NativeRootRecoveryFailure(result)
            val screen = conn.ssh.exec("tmux capture-pane -p -t ${Shell.q("=" + session.name + ":")}")
            if (RestoredRewindDraft.matches(screen, root.editedTextSha256)) throw NativeRootReplyStopped()
            delay(200)
        }
        error("运行器尚未回到空闲输入状态，发送仍暂停。")
    }
    val query = ticket.verification ?: error("这次回退尚未进入恢复确认阶段")
    check(query.sessionName == session.name && query.runtimeId == session.runtimeId) { "会话实例已变化，发送仍暂停" }
    val result = RewindLiveVerification.parse(runRewindCommand(conn.ssh, RewindLiveVerification.command(query)))
    when (result) {
        is RewindLiveVerification.Result.Ok -> conn.instructionDeliveryMutex.withLock {
            gate.finishVerified(ticket)
        }
        is RewindLiveVerification.Result.Failed -> error(rewindRecoveryMessage(result.code))
    }
}

internal fun rewindRecoveryMessage(code: String): String = when (code) {
    "identity", "process-changed" -> "会话实例发生变化，发送仍暂停。请核对当前会话。"
    "sid-mismatch" -> "运行器载入的会话与回退目标不一致，发送仍暂停。"
    "chain-target-present", "chain-anchor-missing", "chain-parent-missing", "chain-cycle", "chain-changed" ->
        "当前历史尚未确认回到所选轮次，发送仍暂停。"
    "chain-too-large", "chain-node-cap", "chain-timeout" -> "历史核对未完成，发送仍暂停。"
    else -> "运行器尚未就绪或恢复状态无法确认，请稍后重新检查。"
}
