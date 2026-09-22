package app.yxi.desktop

import app.yxi.agent.*
import app.yxi.ssh.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest

/** First-turn orchestration. Keeps the original interactive process and never restores code. */
internal object NativeFirstTurnController {
    suspend fun restore(conn: Conn, session: Session, source: RewindTarget, text: String,
        gate: RewindDeliveryGate = RewindDelivery.gate, progress: (String) -> Unit = {}) {
        require(source.parentUuid == null && !source.unsupportedContent) { "首轮图片或特殊内容暂未支持原生恢复。" }
        require(text.isNotBlank() && text.length <= 100_000 && text.none { it.isISOControl() && it != '\n' && it != '\t' }) {
            "消息为空、过长或包含不支持的终端控制字符。"
        }
        check(conn.ssh.isConnected && !session.isCodex)
        val key = taskNavigationKey(conn.host, session)
        conn.instructionDeliveryMutex.withLock {
            check(!gate.blocked(key)) { "已有回退等待核对，请先恢复。" }
            val target = "=" + session.name + ":"
            suspend fun screen() = conn.ssh.exec("tmux capture-pane -p -t ${Shell.q(target)}")
            suspend fun awaitScreen(predicate: (String) -> Boolean): String {
                repeat(50) {
                    val captured = screen()
                    if (predicate(captured)) return captured
                    delay(100)
                }
                error("原生回退界面未能确认，请查看终端。")
            }
            val initial = screen()
            check(Model.borrowable(initial) && Prompt.parse(initial) == null) { "终端正忙或还有未发送内容。" }
            val snapshot = TranscriptBranchStart.load(conn.ssh, source.file, 2000) ?: error("无法读取首轮历史。")
            check(snapshot.size == source.size && snapshot.lines != null) { "历史已变化或缺少完整消息链。" }
            val parser = Transcript.Incremental().apply { add(snapshot.lines.asSequence()) }
            val users = parser.snapshot().filterIsInstance<ChatItem.UserText>()
            check(users.isNotEmpty() && users.first().sourceUuid == source.messageUuid) { "所选消息不是当前分支首轮。" }
            val rows = users.map { it.text }
            val captured = Rewind.parseCapture(conn.ssh.exec(Rewind.captureCommand(session.name))) as? Rewind.Got
                ?: error("无法确认当前 Claude 进程。")
            val cap = captured.capture
            var ticket: RewindDeliveryGate.Ticket? = null
            var menuOpened = false
            var query: NativeRootVerification.Query? = null
            var hash = ""
            try {
                val version = conn.ssh.exec("timeout 10s ${Shell.q(cap.exe)} --version").trim()
                check(version == "${FirstTurnRewindMenu.VERSION} (Claude Code)") { "此运行器版本尚未验证首轮自动回退。" }
                hash = conn.ssh.exec(NativeFirstTurnKeys.fingerprintCommand(source)).trim()
                check(Regex("[0-9a-f]{64}").matches(hash)) { "历史版本无法确认，请重新打开编辑。" }
                val now = conn.ssh.exec("python3 -c 'import time; print(time.time())'").trim().toDouble()
                val promptHash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                val prepared = NativeRootVerification.Query(RewindLiveVerification.RuntimeIdentity(session.name, session.runtimeId,
                    cap.paneId, cap.exe, cap.pid, source.sessionId, now), source.file, source.messageUuid, promptHash)
                query = prepared
                suspend fun send(action: NativeFirstTurnKeys.Action, capturedScreen: String, count: Int = 1) {
                    check(NativeFirstTurnKeys.sent(conn.ssh.exec(NativeFirstTurnKeys.command(prepared.runtime, source, hash, capturedScreen, action, count)))) {
                        "回退操作前状态发生变化，已停止；请查看终端。"
                    }
                }
                progress("正在定位首条消息…")
                // From this point cleanup may be needed even if the acknowledgement is lost.
                menuOpened = true
                send(NativeFirstTurnKeys.Action.Open, awaitScreen { Model.borrowable(it) && Prompt.parse(it) == null })
                val menu = awaitScreen { FirstTurnRewindMenu.parse(it, FirstTurnRewindMenu.VERSION)?.let { view -> view.matches(rows) && view.currentSelected } == true }
                send(NativeFirstTurnKeys.Action.Up, menu, rows.size)
                val selected = awaitScreen { FirstTurnRewindMenu.parse(it, FirstTurnRewindMenu.VERSION)?.let { view -> view.matches(rows) && view.selectedIndex == 0 } == true }
                send(NativeFirstTurnKeys.Action.Enter, selected)
                val confirmation = awaitScreen { FirstTurnRewindMenu.confirmsConversationOnly(it, FirstTurnRewindMenu.VERSION, rows.first()) }
                ticket = gate.beginNativeRoot(key, prepared)
                progress("正在恢复首轮上下文…")
                send(NativeFirstTurnKeys.Action.Enter, confirmation)
                val restored = awaitScreen { "Confirm you want to restore" !in it && Live.inputEmpty(it) == false && Prompt.parse(it) == null }
                send(NativeFirstTurnKeys.Action.Clear, restored)
                val empty = awaitScreen { Model.borrowable(it) && Prompt.parse(it) == null }
                check(NativeFirstTurnKeys.paste(conn, prepared, source, hash, empty, text, ticket.operationId)) { "编辑内容尚未确认写入，发送保持暂停。" }
                val pasted = awaitScreen { Live.inputEmpty(it) == false && Prompt.parse(it) == null }
                send(NativeFirstTurnKeys.Action.Enter, pasted)
            } catch (e: Exception) {
                if (ticket == null && menuOpened && query != null) {
                    // Before restore confirmation only navigation occurred. Cancel only a recognizable menu.
                    val cancelled = withContext(NonCancellable) {
                        runCatching { withTimeout(5_000) {
                            repeat(2) {
                                val current = screen()
                                if (!Model.borrowable(current) && FirstTurnRewindMenu.canCancelNavigation(
                                        current, FirstTurnRewindMenu.VERSION, rows.first())) {
                                    check(NativeFirstTurnKeys.sent(conn.ssh.exec(NativeFirstTurnKeys.command(query!!.runtime,
                                        source, hash, current, NativeFirstTurnKeys.Action.Cancel))))
                                    delay(150)
                                }
                            }
                            val current = screen()
                            Model.borrowable(current) && Prompt.parse(current) == null &&
                                MessageDigest.getInstance("SHA-256").digest(current.trimEnd('\n').toByteArray()).contentEquals(
                                    MessageDigest.getInstance("SHA-256").digest(initial.trimEnd('\n').toByteArray()))
                        } }.getOrDefault(false)
                    }
                    if (!cancelled) {
                        // Preserve the original failure if persisting the recovery guard also fails.
                        runCatching { gate.beginNativeRoot(key, query!!) }.exceptionOrNull()?.let(e::addSuppressed)
                    }
                }
                throw e
            } finally {
                withContext(NonCancellable) { runCatching { withTimeout(3_000) { conn.ssh.exec(Rewind.cleanupCommand(cap)) } } }
            }
        }
        progress("正在确认首轮恢复状态…")
        recheckRewindRecovery(conn, session, gate, rootTimeoutSec = 600)
    }
}
