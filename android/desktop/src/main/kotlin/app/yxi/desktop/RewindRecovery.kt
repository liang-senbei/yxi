package app.yxi.desktop

import app.yxi.agent.RewindLiveVerification
import app.yxi.agent.Session
import kotlinx.coroutines.sync.withLock

/** Recheck persisted evidence without sending a prompt or restarting any process. */
internal suspend fun recheckRewindRecovery(conn: Conn, session: Session, gate: RewindDeliveryGate = RewindDelivery.gate) {
    check(conn.ssh.isConnected) { "请先重新连接服务器" }
    val key = taskNavigationKey(conn.host, session)
    val ticket = gate.pending(key) ?: error("没有可核对的回退记录")
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
