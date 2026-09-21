package app.yxi.desktop

import app.yxi.agent.Session
import app.yxi.ssh.Shell
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

/** A terminal transport, deliberately not advertised as runtime steering or an
 * accepted-message receipt. Exactly one literal text write and one Enter. */
internal suspend fun deliverInstruction(conn: Conn, session: Session, queue: InstructionQueue, item: QueuedInstruction) = conn.instructionDeliveryMutex.withLock {
    check(conn.ssh.isConnected) { "服务器未连接，指令保留在本地" }
    check(item.taskKey == taskNavigationKey(conn.host, session)) { "任务身份已变化" }
    check(session.runtimeId !in conn.terminalAwaiting) { "上一条正在接续，请等待本轮结束" }
    check(Regex("[0-9]+:\\$[0-9]+:[0-9]+").matches(session.runtimeId)) { "尚未确认任务实例，请刷新后重试" }
    val screen = conn.ssh.exec("tmux capture-pane -p -t ${Shell.q("=" + session.name + ":")} 2>/dev/null")
    check(app.yxi.agent.Model.borrowable(screen)) { "终端正忙、等待选择或输入状态无法确认，指令继续保留在本地" }
    val started = queue.beginDelivery(item.id, item.revision)
    val completionBefore = conn.terminalCompletion[session.runtimeId] ?: 0L
    withContext(NonCancellable) {
        try {
            val raw = conn.ssh.exec(instructionDeliveryCommand(session, item, screen))
            when (raw.lineSequence().lastOrNull { it.startsWith("__YXI_DELIVERY__:") }) {
                "__YXI_DELIVERY__:blocked" -> queue.notDelivered(started.id, started.revision, "任务或画面已变化，未投递；请核对后重试")
                "__YXI_DELIVERY__:attachment" -> queue.notDelivered(started.id, started.revision, "附件不存在或无法读取，请重新上传")
                "__YXI_DELIVERY__:terminal" -> {
                    conn.terminalAwaiting[session.runtimeId] = completionBefore to false
                    queue.confirmTerminalWrite(started.id, started.revision)
                }
                "__YXI_DELIVERY__:reserved" -> queue.markUnknown(started.id, started.revision, "服务端已登记本指令，但没有完整写入记录；可能只写入了部分内容。请查看终端，不要重复发送。")
                else -> queue.markUnknown(started.id, started.revision, "投递结果无法确认，请核对任务；不会自动重发")
            }
        } catch (e: Exception) {
            // Persisting Unknown can itself fail. Delivering then remains on disk
            // and is converted to Unknown on restart, never back to Local.
            if (queue.entries.firstOrNull { it.id == started.id }?.status == InstructionStatus.Delivering)
                queue.markUnknown(started.id, started.revision, "连接或投递中断，请核对任务：${e.message}")
        }
    }
}

internal fun instructionDeliveryCommand(session: Session, item: QueuedInstruction, screen: String): String {
    val journal = deliveryJournalKey(session, item)
    val body = item.attachments.joinToString("") { "[${it.name}] ${it.remotePath}\n" } + item.text
    val checks = item.attachments.joinToString("\n") { "[ -r ${Shell.q(it.remotePath)} ] || { echo '__YXI_DELIVERY__:attachment'; exit 0; }" }
    return """
t=${Shell.q("=" + session.name + ":")}
identity=${'$'}(tmux display-message -p -t "${'$'}t" '#{pid}:#{session_id}:#{session_created}' 2>/dev/null)
[ "${'$'}identity" = ${Shell.q(session.runtimeId)} ] || { echo '__YXI_DELIVERY__:blocked'; exit 0; }
p=${'$'}(tmux display-message -p -t "${'$'}t" '#{pane_id}' 2>/dev/null)
before=${'$'}(tmux capture-pane -p -t "${'$'}p" 2>/dev/null)
[ "${'$'}before" = ${Shell.q(screen.trimEnd('\n'))} ] || { echo '__YXI_DELIVERY__:blocked'; exit 0; }
$checks
umask 077
root="${'$'}HOME/.yxi/instruction-deliveries"
mkdir -p "${'$'}root" || exit 1
record="${'$'}root/$journal"
if ! mkdir "${'$'}record" 2>/dev/null; then
    if [ -f "${'$'}record/terminal" ]; then echo '__YXI_DELIVERY__:terminal'; else echo '__YXI_DELIVERY__:reserved'; fi
    exit 0
fi
date -u '+%Y-%m-%dT%H:%M:%SZ' > "${'$'}record/started"
tmux send-keys -t "${'$'}p" -l ${Shell.q(body)} || exit 1
sleep 0.4
tmux send-keys -t "${'$'}p" Enter || exit 1
date -u '+%Y-%m-%dT%H:%M:%SZ' > "${'$'}record/terminal.tmp" && mv "${'$'}record/terminal.tmp" "${'$'}record/terminal" || exit 1
echo '__YXI_DELIVERY__:terminal'
""".trimIndent()
}

private fun deliveryJournalKey(session: Session, item: QueuedInstruction): String =
    contentHash((session.runtimeId + "\n" + item.taskKey + "\n" + item.id).toByteArray())

private suspend fun readInstructionDeliveryRecord(conn: Conn, session: Session, item: QueuedInstruction): String {
    check(item.taskKey == taskNavigationKey(conn.host, session)) { "任务身份已变化" }
    val key = deliveryJournalKey(session, item)
    return conn.ssh.exec("""
record="${'$'}HOME/.yxi/instruction-deliveries/$key"
if [ -f "${'$'}record/terminal" ]; then
    echo '__YXI_RECORD__:terminal'
    cat "${'$'}record/terminal"
elif [ -d "${'$'}record" ]; then echo '__YXI_RECORD__:reserved'
else echo '__YXI_RECORD__:missing'; fi
""".trimIndent())
}

internal suspend fun reconcileTerminalInstruction(conn: Conn, session: Session, queue: InstructionQueue, item: QueuedInstruction) {
    val result = readInstructionDeliveryRecord(conn, session, item)
    if (result.lineSequence().any { it == "__YXI_RECORD__:terminal" }) {
        val current = queue.entries.firstOrNull { it.id == item.id && it.status == InstructionStatus.Unknown } ?: return
        queue.confirmTerminalWrite(current.id, current.revision)
    }
}

internal suspend fun queryInstructionDelivery(conn: Conn, session: Session, item: QueuedInstruction): String {
    val result = readInstructionDeliveryRecord(conn, session, item)
    return when {
        result.lineSequence().any { it == "__YXI_RECORD__:terminal" } -> "服务端记录：终端文字与回车已写入。运行器接收和执行仍需查看对话确认。\n" + result.lineSequence().filterNot { it.startsWith("__YXI_RECORD__:") }.joinToString("\n").take(100)
        result.lineSequence().any { it == "__YXI_RECORD__:reserved" } -> "服务端已登记本指令，但没有完整写入记录；可能只写入了部分内容。请查看终端，不要重复发送。"
        result.lineSequence().any { it == "__YXI_RECORD__:missing" } -> "未找到服务端记录，旧版投递也可能没有记录；不能据此判定未发送。请查看对话或终端。"
        else -> error("服务器未返回可识别的投递记录，请稍后重新查询")
    }
}
