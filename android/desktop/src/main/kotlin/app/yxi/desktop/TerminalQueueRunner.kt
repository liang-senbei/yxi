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
                    if (first?.status != InstructionStatus.Local && session.runtimeId !in conn.terminalAwaiting && session.runtimeId !in conn.modelChanges) continue
                    try {
                        if (session.runtimeId in conn.modelChanges) {
                            conn.instructionDeliveryMutex.withLock {
                                val target = "=" + session.name + ":"
                                val q = app.yxi.ssh.Shell::q
                                val screen = conn.ssh.exec("tmux capture-pane -p -t ${q(target)}")
                                if (app.yxi.agent.Model.borrowable(screen) && app.yxi.agent.Prompt.parse(screen) == null) {
                                    val change = conn.modelChanges[session.runtimeId] ?: return@withLock
                                    val command = if (change.model != null) "/model ${change.model}" else "/effort ${change.effort}"
                                    require(command.none { it < ' ' })
                                    val result = conn.ssh.exec("pane=\$(tmux display-message -p -t ${q(target)} '#{pane_id}') && test \"\$(tmux display-message -p -t \"\$pane\" '#{pid}:#{session_id}:#{session_created}')\" = ${q(session.runtimeId)} && test \"\$(tmux capture-pane -p -t \"\$pane\")\" = ${q(screen.trimEnd('\n'))} && tmux send-keys -t \"\$pane\" -l ${q(command)} && sleep 0.3 && tmux send-keys -t \"\$pane\" Enter && printf '__YXI_MODEL_REQUEST__'")
                                    conn.modelChanges.remove(session.runtimeId)
                                    if (result.contains("__YXI_MODEL_REQUEST__") && change.model != null && change.effort != null)
                                        conn.modelChanges[session.runtimeId] = change.copy(model = null)
                                }
                            }
                            continue
                        }
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
                        if (QueuePreferences.enabled(key)) deliverInstruction(conn, session, state.instructions, first, automatic = true)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Keep local/unknown state; never replay an uncertain write. */ }
                }
            }
            delay(1500)
        }
    }
}
