package app.yxi.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 6bdde2c 定向检查：__YXI_DELIVERY__:terminal 记为 Sent（只代表终端已写入，
 * 不冒充运行器已接收）；Sent 不再阻塞后续指令；Unknown 依旧阻塞、缺回执场景不放宽；
 * 进入聊天页的 reconcile 允许 Unknown → Sent（服务器有 terminal 记录才发生）。
 * 队列是纯状态机，全部用临时文件，零 SSH、零真实会话。
 */
class InstructionQueueSentTest {
    private fun tempQueue(): Pair<InstructionQueue, java.io.File> {
        val file = Files.createTempDirectory("yxi-queue-sent").resolve("instructions.json").toFile()
        return InstructionQueue(file) to file
    }

    private fun restart(file: java.io.File) = InstructionQueue(file)

    /** terminal 回执 → Sent（带"已投递"话术）；不接受空回执；状态机不冒充 Accepted。 */
    @Test fun `terminal receipt marks Sent not Accepted and Sent never blocks the next instruction`() {
        val (q, _) = tempQueue()
        val a = q.enqueue("t1", "第一条")
        val b = q.enqueue("t1", "第二条")
        val started = q.beginDelivery(a.id, a.revision)
        val sent = q.confirmTerminalWrite(started.id, started.revision)
        assertEquals(InstructionStatus.Sent, sent.status)
        assertEquals("已投递到终端；回复以实际对话为准", sent.detail)
        // Sent 不在阻塞集合里：第二条立即可投递（旧语义这里抛"请先处理前面的指令"）
        val startedB = q.beginDelivery(b.id, b.revision)
        assertEquals(InstructionStatus.Delivering, startedB.status)
        // Sent 不能再被投递/编辑；也不能凭空再次确认为 Accepted
        assertFailsWith<IllegalStateException> { q.beginDelivery(a.id, sent.revision) }
        assertFailsWith<IllegalStateException> { q.confirmAccepted(a.id, sent.revision, "") }
    }

    /** Unknown 依旧阻塞队列与引导 —— 缺回执场景不放宽。 */
    @Test fun `unknown still blocks later instructions and steering`() {
        val (q, _) = tempQueue()
        val a = q.enqueue("t2", "第一条")
        val b = q.enqueue("t2", "第二条")
        val started = q.beginDelivery(a.id, a.revision)
        q.markUnknown(started.id, started.revision, "投递结果无法确认")
        assertFailsWith<IllegalStateException> { q.beginDelivery(b.id, b.revision) }
        // 引导同样不得跳过 Unknown（注释承诺 "never jump over an uncertain delivery"）
        assertFailsWith<IllegalStateException> { q.beginSteering(b.id, b.revision, "turn-1") }
    }

    /** reconcile：服务器有 terminal 记录时允许 Unknown → Sent（聊天页旧记录转正的唯一通道）。 */
    @Test fun `reconcile promotes Unknown to Sent and leaves other states alone`() {
        val (q, _) = tempQueue()
        val a = q.enqueue("t3", "历史遗留")
        val started = q.beginDelivery(a.id, a.revision)
        q.markUnknown(started.id, started.revision, "重启前的待确认")
        val unknown = q.entries.single { it.id == a.id }
        assertEquals(InstructionStatus.Unknown, unknown.status)
        val promoted = q.confirmTerminalWrite(unknown.id, unknown.revision)
        assertEquals(InstructionStatus.Sent, promoted.status)
        // 已是 Sent / Local 等状态时 confirmTerminalWrite 必须拒绝，不能反复改写
        assertFailsWith<IllegalStateException> { q.confirmTerminalWrite(promoted.id, promoted.revision) }
        val c = q.enqueue("t3", "没投递过")
        assertFailsWith<IllegalStateException> { q.confirmTerminalWrite(c.id, c.revision) }
        // 人工核对走 Resolved，不伪装成 Sent
        val d = q.enqueue("t4", "人工核对项")
        val ds = q.beginDelivery(d.id, d.revision)
        q.markUnknown(ds.id, ds.revision, "待人工")
        val resolved = q.resolveManually(q.entries.single { it.id == d.id }.id, q.entries.single { it.id == d.id }.revision)
        assertEquals(InstructionStatus.Resolved, resolved.status)
        assertFailsWith<IllegalStateException> { q.confirmTerminalWrite(d.id, resolved.revision) }
    }

    /** 缺回执路径保持原样：blocked/attachment 回 Local 可重试；无识别回执落 Unknown；Delivering 重启转 Unknown。 */
    @Test fun `missing receipt paths unchanged and Delivering becomes Unknown on restart`() {
        val (q, file) = tempQueue()
        val a = q.enqueue("t5", "被挡回")
        val started = q.beginDelivery(a.id, a.revision)
        q.notDelivered(started.id, started.revision, "任务或画面已变化，未投递")
        val back = q.entries.single { it.id == a.id }
        assertEquals(InstructionStatus.Local, back.status)
        val retried = q.beginDelivery(back.id, back.revision) // Local 可重试
        assertEquals(InstructionStatus.Sent, q.confirmTerminalWrite(retried.id, retried.revision).status)
        val b = q.enqueue("t5", "无识别回执")
        val bs = q.beginDelivery(b.id, b.revision)            // a 已 Sent，不再阻塞
        q.markUnknown(bs.id, bs.revision, "投递结果无法确认，请核对任务；不会自动重发")
        val c = q.enqueue("t6", "投递中退出")                  // 独立任务键：t5 的 Unknown 阻塞不该波及
        q.beginDelivery(c.id, c.revision)
        val reloaded = restart(file)
        val after = reloaded.entries.associate { it.id to it.status }
        assertEquals(InstructionStatus.Sent, after[a.id], "Sent 重启后保持，不得回退 Unknown")
        assertEquals(InstructionStatus.Unknown, after[b.id])
        assertEquals(InstructionStatus.Unknown, after[c.id], "Delivering 重启映射 Unknown")
        assertTrue(reloaded.entries.none { it.status == InstructionStatus.Delivering })
    }
}
