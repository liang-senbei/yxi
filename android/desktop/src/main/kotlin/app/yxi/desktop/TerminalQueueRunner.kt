package app.yxi.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.yxi.agent.SessionProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

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
                    if (first?.status != InstructionStatus.Local || state.instructions.error.isNotBlank()) continue
                    try {
                        val (pending, live) = SessionProbe.snapshot(conn.ssh, session.name)
                        val barrier = conn.terminalAwaiting[session.runtimeId]
                        if (barrier != null) {
                            if (live.busy) conn.terminalAwaiting[session.runtimeId] = barrier.first to true
                            val completed = (conn.terminalCompletion[session.runtimeId] ?: 0L) > barrier.first
                            if (!completed && !(barrier.second && !live.busy && pending == null)) continue
                            conn.terminalAwaiting.remove(session.runtimeId)
                        }
                        if (live.busy || pending != null || !conn.ssh.isConnected) continue
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
