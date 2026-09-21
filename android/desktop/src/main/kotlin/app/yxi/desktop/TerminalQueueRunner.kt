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
        while (true) {
            for (conn in state.conns.toList()) {
                if (!conn.ssh.isConnected || state.deferredRoute?.conn === conn) continue
                for (session in conn.sessions.toList()) {
                    if (session.runtimeId.isBlank()) continue
                    val key = taskNavigationKey(conn.host, session)
                    val first = state.instructions.entries.firstOrNull { it.taskKey == key && it.status in setOf(InstructionStatus.Local, InstructionStatus.Delivering, InstructionStatus.Unknown) }
                    if (first?.status != InstructionStatus.Local && session.runtimeId !in conn.terminalAwaiting) continue
                    try {
                        val ready = conn.instructionDeliveryMutex.withLock {
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
                        deliverInstruction(conn, session, state.instructions, first)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Keep local/unknown state; never replay an uncertain write. */ }
                }
            }
            delay(1500)
        }
    }
}
