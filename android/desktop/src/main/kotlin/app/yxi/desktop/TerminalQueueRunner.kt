package app.yxi.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.yxi.agent.SessionProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock

/** App-level worker: tab switches do not stop already queued instructions. */
@Composable
internal fun TerminalQueueRunner(state: AppState) {
    LaunchedEffect(state) {
        val modelSwitch = ModelSwitchController(state.modelSwitches)
        while (true) {
            for (conn in state.conns.toList()) {
                if (!conn.ssh.isConnected || state.deferredRoute?.conn === conn) continue
                for (session in conn.sessions.toList()) {
                    if (session.runtimeId.isBlank()) continue
                    val key = taskNavigationKey(conn.host, session)
                    if (RewindDelivery.gate.blocked(key)) continue
                    val first = state.instructions.entries.firstOrNull { it.taskKey == key && it.status in setOf(InstructionStatus.Local, InstructionStatus.Delivering, InstructionStatus.Unknown) }
                    if (first?.status != InstructionStatus.Local && session.runtimeId !in conn.terminalAwaiting && session.runtimeId !in conn.modelChanges && state.modelSwitches.active(key) == null) continue
                    try {
                        if (session.runtimeId in conn.modelChanges || state.modelSwitches.active(key) != null) {
                            val beforeRevision = state.modelSwitches.latest(key)?.revision
                            conn.instructionDeliveryMutex.withLock {
                                if (RewindDelivery.gate.blocked(key)) return@withLock
                                // 持久化切换协议：菜单意图入库后走 Pending→Delivering→SentAwaitEvidence/AwaitConfirm 闭环。
                                // store 以 taskNavigationKey 为键（host+session，跨主机不撞）；
                                // Delivering/AwaitConfirm 在途时不接收新意图（false = 意图留在 conn.modelChanges 下轮再试）。
                                val intent = conn.modelChanges[session.runtimeId]
                                val accepted = modelSwitch.step({ conn.ssh.exec(it) }, key, session.runtimeId, session.name, intent)
                                if (intent != null && accepted && conn.modelChanges[session.runtimeId] === intent) conn.modelChanges.remove(session.runtimeId)
                            }
                            // A CLI command may still be redrawing after its write marker arrives.
                            // Never deliver a prompt in that same scheduler pass; probe afresh next time.
                            if (state.modelSwitches.latest(key)?.revision != beforeRevision) continue
                            // ⚠️ SentAwaitEvidence（已发送、等转录新回执）是唯一不堵普通消息的状态：
                            //    落下去走正常队列路径，新配置下下一条照发，不等下一回复。
                            //    其余状态一律 continue，不可绕过 —— Pending 发送在即、Delivering 投递不明、
                            //    AwaitConfirm 确认框等用户回车（绝不代按）、Unknown 人工核对（不自动重发）。
                        }
                        if (state.modelSwitches.blocksQueue(key)) continue
                        val ready = conn.instructionDeliveryMutex.withLock {
                            if (RewindDelivery.gate.blocked(key)) return@withLock false
                            val (pending, live) = SessionProbe.snapshot(conn.ssh, session.name)
                            val barrier = conn.terminalAwaiting[session.runtimeId]
                            if (barrier != null) {
                                if (live.busy) conn.terminalAwaiting[session.runtimeId] = barrier.first to true
                                val completed = (conn.terminalCompletion[session.runtimeId] ?: 0L) > barrier.first
                                if (!completed && !(barrier.second && !live.busy && pending == null)) return@withLock false
                                conn.terminalAwaiting.remove(session.runtimeId)
                            }
                            !live.busy && pending == null && conn.ssh.isConnected
                        }
                        if (!ready) continue
                        if (first?.status != InstructionStatus.Local || state.instructions.error.isNotBlank()) continue
                        // The adapter rechecks identity, empty prompt and screen immediately before writing.
                        if (QueuePreferences.enabled(key)) deliverInstruction(conn, session, state.instructions, first, automatic = true)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Keep local/unknown state; never replay an uncertain write. */ }
                }
            }
            delay(1500)
        }
    }
}
