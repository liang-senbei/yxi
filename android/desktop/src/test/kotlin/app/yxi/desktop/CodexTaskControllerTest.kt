package app.yxi.desktop

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class CodexTaskControllerTest {

    private fun withController(
        mode: String,
        threadId: String = "thr-1",
        notices: MutableList<String>? = null,
        block: suspend (CodexTaskController, InstructionQueue, FakeRunner) -> Unit,
    ) {
        assumeFakeRunner()
        val dir = Files.createTempDirectory("yxi-controller").toFile()
        try {
            FakeRunner(mode, threadId).use { runner ->
                handshake(runner)
                val queue = InstructionQueue(File(dir, "queue.json"))
                val controller = CodexTaskController("task", threadId, runner.client, queue,
                    onNotice = { title -> notices?.add(title) })
                try { runBlocking { block(controller, queue, runner) } } finally { controller.close() }
            }
        } finally { dir.deleteRecursively() }
    }

    private fun turnStarts(runner: FakeRunner): List<JSONArray> = runner.inboundJson()
        .filter { it.optString("method") == "turn/start" }
        .map { it.getJSONObject("params").getJSONArray("input") }

    private fun rpc(runner: FakeRunner, method: String) = runner.inboundJson()
        .last { it.optString("method") == method }.getJSONObject("params")

    @Test
    fun `auto queue runs two instructions in order`() = withController("seq-auto") { controller, queue, runner ->
        val first = queue.enqueue("task", "第一条")
        val second = queue.enqueue("task", "第二条")
        controller.reconcile()
        controller.setAutoDispatch(true)
        poll { queue.entries.count { it.runtimeTurnState == RuntimeTurnState.Completed } == 2 }
        val done = queue.entries.associateBy { it.id }
        assertEquals("turn-1", done[first.id]!!.runtimeTurnId)
        assertEquals("turn-2", done[second.id]!!.runtimeTurnId)
        assertEquals(2, turnStarts(runner).size)
        // 顺序派发：第一条的 turn/start 先于第二条
        val texts = runner.inbound().filter { it.contains("turn/start") }
        assertTrue(texts[0].contains("第一条") && texts[1].contains("第二条"), "派发顺序错乱：$texts")
    }

    @Test
    fun `completion event before start response still advances the queue`() = withController("completed-first") { controller, queue, _ ->
        val first = queue.enqueue("task", "第一条")
        val second = queue.enqueue("task", "第二条")
        controller.reconcile()
        controller.setAutoDispatch(true)
        poll { queue.entries.count { it.runtimeTurnState == RuntimeTurnState.Completed } == 2 }
        val done = queue.entries.associateBy { it.id }
        // 完成事件先到：终态缓存应在响应确认接收后补上，不丢轮次归属
        assertEquals("turn-1", done[first.id]!!.runtimeTurnId)
        assertEquals("turn-2", done[second.id]!!.runtimeTurnId)
        assertEquals(RuntimeTurnState.Completed, done[first.id]!!.runtimeTurnState)
        assertEquals(null, controller.activeTurnId)
    }

    @Test
    fun `connection loss keeps delivery Unknown and blocks the queue`() = withController("die-on-turn-start") { controller, queue, runner ->
        val first = queue.enqueue("task", "第一条")
        controller.reconcile()
        controller.setAutoDispatch(true)
        poll { queue.entries.single().status == InstructionStatus.Unknown }
        assertFalse(controller.autoDispatch, "断线后自动派发必须停下")
        // 未确认的投递不得自动重发：假运行器只收到一次 turn/start
        assertEquals(1, turnStarts(runner).size)
        val second = queue.enqueue("task", "第二条")
        controller.queueChanged()
        Thread.sleep(300)
        assertEquals(InstructionStatus.Local, queue.entries.single { it.id == second.id }.status, "断线后不得继续派发")
        poll { !controller.ready }
    }

    @Test
    fun `events from another thread cannot touch this task`() = withController("foreign-completion") { controller, queue, runner ->
        val first = queue.enqueue("task", "第一条")
        val second = queue.enqueue("task", "第二条")
        controller.reconcile()
        controller.setAutoDispatch(true)
        poll { queue.entries.single { it.id == first.id }.status == InstructionStatus.Accepted }
        // 等 FIFO 之后的同线程标记事件被处理，保证异线程事件已被消费过
        poll { controller.recentEvents.any { it.optString("method") == "thread/compacted" } }
        Thread.sleep(200)
        val mine = queue.entries.single { it.id == first.id }
        assertEquals(InstructionStatus.Accepted, mine.status)
        assertEquals(RuntimeTurnState.InProgress, mine.runtimeTurnState, "别的任务的完成事件不得改写本任务")
        assertEquals("turn-1", controller.activeTurnId)
        assertEquals(InstructionStatus.Local, queue.entries.single { it.id == second.id }.status, "未确认完成前不得派发下一条")
        val foreign = runner.outboundJson().filter {
            it.optString("method") == "turn/completed" &&
                it.getJSONObject("params").optString("threadId") != "thr-1"
        }
        assertEquals(1, foreign.size, "假运行器确实发过异线程事件")
    }

    @Test
    fun `explicit steer binds the expected turn id`() = withController("steer-mismatch") { controller, queue, runner ->
        val first = queue.enqueue("task", "第一条")
        val second = queue.enqueue("task", "第二条")
        controller.reconcile()
        controller.sendNext() // 第一条进入 turn-1 且保持进行中
        poll { queue.entries.single { it.id == first.id }.runtimeTurnId == "turn-1" }
        val error = assertFailsWith<IllegalStateException> { controller.steerNext() }
        assertEquals("引导响应不属于目标轮次", error.message)
        poll { queue.entries.single { it.id == second.id }.status == InstructionStatus.Unknown }
        // 请求里带的是期望轮次；响应轮次不符被拒后，本任务停在 Unknown，原轮次不受影响
        val steer = rpc(runner, "turn/steer")
        assertEquals("turn-1", steer.getString("expectedTurnId"))
        assertEquals(InstructionStatus.Accepted, queue.entries.single { it.id == first.id }.status)
        assertEquals("turn-1", controller.activeTurnId)
    }

    @Test
    fun `full item replaces streamed deltas without duplicates`() = withController("delta-full") { controller, queue, _ ->
        queue.enqueue("task", "第一条")
        controller.reconcile()
        controller.setAutoDispatch(true)
        poll { queue.entries.single().runtimeTurnState == RuntimeTurnState.Completed }
        poll { controller.messages.size == 2 && controller.messages.last().text == "你好，世界" }
        assertEquals(listOf("msg-0", "msg-1"), controller.messages.map { it.id }, "消息不得重复或乱序")
        assertEquals(CodexMessage("msg-0", "你", "帮我看下"), controller.messages[0])
        assertEquals(CodexMessage("msg-1", "Codex", "你好，世界"), controller.messages[1], "完整 item 应替换增量拼出的文本")
    }

    @Test
    fun `read snapshot merges with streamed messages without losing either`() = withController("read-interleave") { controller, queue, _ ->
        controller.reconcile() // 第一次读：只有历史快照
        assertEquals(listOf("msg-h0", "msg-h1"), controller.messages.map { it.id })
        val first = queue.enqueue("task", "第一条")
        controller.setAutoDispatch(true)
        poll { queue.entries.single { it.id == first.id }.runtimeTurnState == RuntimeTurnState.Completed }
        poll { controller.messages.map { it.id } == listOf("msg-h0", "msg-h1", "msg-live") }
        assertEquals("LIVE完整", controller.messages.last().text)
        controller.reconcile() // 第二次读：快照已含流式消息，合并后不得重复、不得丢失
        assertEquals(listOf("msg-h0", "msg-h1", "msg-live"), controller.messages.map { it.id })
        assertEquals(3, controller.messages.size)
        assertEquals("LIVE完整", controller.messages.last().text)
        assertEquals("回复一", controller.messages[1].text, "历史快照不得被流式状态覆盖丢失")
    }

    @Test
    fun `new thread with no turns reads empty and becomes ready`() = withController("new-thread") { controller, _, _ ->
        controller.reconcile()
        assertTrue(controller.ready)
        assertTrue(controller.messages.isEmpty())
        assertEquals(null, controller.activeTurnId)
        assertEquals("任务已就绪", controller.note)
    }

    @Test
    fun `approval waits for an explicit answer by its request id`() = withController("approval") { controller, queue, runner ->
        val first = queue.enqueue("task", "第一条")
        controller.reconcile()
        poll { controller.pendingRequests.size == 1 }
        assertEquals("item/commandExecution/requestApproval", controller.pendingRequests.values.single().optString("method"))
        // 收集但不自动答复：此刻派发被审批挡住，且运行器没收到任何答复
        val blocked = assertFailsWith<IllegalStateException> { controller.sendNext() }
        assertEquals("当前轮次或审批尚未结束", blocked.message)
        assertTrue(runner.inboundJson().none { it.has("result") && it.optString("id") == "srv-77" })
        // 明确按收到的请求 ID 答复一次；随后队列才继续
        controller.answerRequest("srv-77", JSONObject().put("decision", "accept"))
        poll { controller.pendingRequests.isEmpty() }
        assertEquals(1, runner.inboundJson().count { it.optString("id") == "srv-77" && it.has("result") })
        assertEquals("accept", runner.inboundJson().first { it.optString("id") == "srv-77" }.getJSONObject("result").getString("decision"))
        assertFailsWith<IllegalStateException> { controller.answerRequest("srv-77", JSONObject().put("decision", "accept")) }
        controller.setAutoDispatch(true)
        poll { queue.entries.single { it.id == first.id }.status == InstructionStatus.Accepted }
    }

    @Test
    fun `sendNext refuses a stale expected id`() = withController("new-thread") { controller, queue, _ ->
        val first = queue.enqueue("task", "第一条")
        val second = queue.enqueue("task", "第二条")
        controller.reconcile()
        val stale = assertFailsWith<IllegalStateException> { controller.sendNext(second.id) }
        assertEquals("队列顺序已改变，请重新选择", stale.message)
        assertEquals(InstructionStatus.Local, queue.entries.single { it.id == first.id }.status)
        controller.sendNext(first.id)
        poll { queue.entries.single { it.id == first.id }.status == InstructionStatus.Accepted }
        assertEquals(InstructionStatus.Local, queue.entries.single { it.id == second.id }.status)
    }

    @Test
    fun `model choice serializes into turn start but never into steer`() = withController("models") { controller, queue, runner ->
        controller.reconcile()
        // 分页合并 + 跳过隐藏项（只做列表/参数序列化验证，不碰真实模型）
        controller.refreshModels()
        poll { !controller.modelsLoading }
        assertEquals("", controller.modelError)
        assertEquals(listOf("m-a", "m-b"), controller.models.map { it.model })
        assertEquals(listOf("Alpha", "Beta"), controller.models.map { it.label })
        controller.chooseModel("m-a")
        assertEquals("high", controller.selectedEffort, "默认思考强度应取该模型的默认值")
        assertFailsWith<IllegalStateException> { controller.chooseModel("不存在的模型") }
        controller.chooseEffort("low")
        assertEquals("low", controller.selectedEffort)
        assertFailsWith<IllegalStateException> { controller.chooseEffort("该模型不支持的强度") }
        // turn/start 携带所选模型与强度
        val first = queue.enqueue("task", "第一条")
        controller.sendNext(first.id)
        poll { queue.entries.single { it.id == first.id }.status == InstructionStatus.Accepted }
        val start = rpc(runner, "turn/start")
        assertEquals("m-a", start.getString("model"))
        assertEquals("low", start.getString("effort"))
        // 引导进入同一活动轮次：请求不得携带模型/强度覆盖
        val second = queue.enqueue("task", "第二条")
        controller.steerNext()
        poll { queue.entries.single { it.id == second.id }.status == InstructionStatus.Accepted }
        val steer = rpc(runner, "turn/steer")
        assertEquals("turn-1", steer.getString("expectedTurnId"))
        assertFalse(steer.has("model") || steer.has("effort"), "引导不得携带模型覆盖：$steer")
        assertEquals("m-a", rpc(runner, "turn/start").getString("model"))
    }

    @Test
    fun `image-only and text plus image deliver localImage input`() = withController("seq-auto") { controller, queue, runner ->
        controller.reconcile()
        val image = InstructionAttachment("demo.png", "/srv/demo.png")
        val onlyImage = queue.enqueue("task", "", listOf(image))
        val textImage = queue.enqueue("task", "看这张图", listOf(image))
        controller.setAutoDispatch(true)
        poll { queue.entries.count { it.runtimeTurnState == RuntimeTurnState.Completed } == 2 }
        val inputs = turnStarts(runner)
        assertEquals(2, inputs.size)
        // 仅图片：输入只有 localImage，没有空文本占位
        assertEquals(1, inputs[0].length())
        assertEquals("localImage", inputs[0].getJSONObject(0).getString("type"))
        assertEquals("/srv/demo.png", inputs[0].getJSONObject(0).getString("path"))
        // 图文：文本在前、图片在后
        assertEquals(2, inputs[1].length())
        assertEquals("text", inputs[1].getJSONObject(0).getString("type"))
        assertEquals("看这张图", inputs[1].getJSONObject(0).getString("text"))
        assertEquals("localImage", inputs[1].getJSONObject(1).getString("type"))
        assertEquals("/srv/demo.png", inputs[1].getJSONObject(1).getString("path"))
    }

    @Test
    fun `ordinary attachments serialize as non-embedded text references`() = withController("seq-auto") { controller, queue, runner ->
        controller.reconcile()
        val plain = queue.enqueue("task", "", listOf(InstructionAttachment("数据.txt", "/srv/数据.txt")))
        val mixed = queue.enqueue("task", "看图和文件", listOf(
            InstructionAttachment("demo.png", "/srv/demo.png"),
            InstructionAttachment("数据.txt", "/srv/数据.txt")))
        controller.setAutoDispatch(true)
        poll { queue.entries.count { it.runtimeTurnState == RuntimeTurnState.Completed } == 2 }
        val inputs = turnStarts(runner)
        assertEquals(2, inputs.size)
        // 仅普通附件：单一 text 输入携带名称+绝对路径引用，明示非内嵌，不出现 localImage
        assertEquals(1, inputs[0].length())
        assertEquals("text", inputs[0].getJSONObject(0).getString("type"))
        val reference = inputs[0].getJSONObject(0).getString("text")
        assertTrue(reference.contains("未内嵌"), "应标明非内嵌：$reference")
        val cited = JSONObject(reference.substringAfterLast('\n'))
        assertEquals("数据.txt", cited.getString("name"))
        assertEquals("/srv/数据.txt", cited.getString("path"))
        // 图文混合：用户文本、图片 localImage、文件引用按附件顺序各一项
        assertEquals(3, inputs[1].length())
        assertEquals("text", inputs[1].getJSONObject(0).getString("type"))
        assertEquals("看图和文件", inputs[1].getJSONObject(0).getString("text"))
        assertEquals("localImage", inputs[1].getJSONObject(1).getString("type"))
        assertEquals("/srv/demo.png", inputs[1].getJSONObject(1).getString("path"))
        assertEquals("text", inputs[1].getJSONObject(2).getString("type"))
        val citedMixed = JSONObject(inputs[1].getJSONObject(2).getString("text").substringAfterLast('\n'))
        assertEquals("数据.txt", citedMixed.getString("name"))
        assertEquals("/srv/数据.txt", citedMixed.getString("path"))
    }

    @Test
    fun `invalid attachment is rejected before delivery and stays Local`() = withController("new-thread") { controller, queue, runner ->
        controller.reconcile()
        // 普通文件现已支持；相对路径在队列层就被拒，指令根本不入列
        assertFailsWith<IllegalArgumentException> {
            queue.enqueue("task", "带附件", listOf(InstructionAttachment("a.txt", "relative/a.txt")))
        }
        assertTrue(queue.entries.isEmpty())
        // 队列放行（仅禁 NUL）、Codex 输入层拒绝的控制字符路径：投递前校验失败，指令保留 Local
        val bad = queue.enqueue("task", "带附件", listOf(InstructionAttachment("a.txt", "/srv/a\nb.txt")))
        controller.setAutoDispatch(true)
        poll { !controller.autoDispatch }
        assertEquals(InstructionStatus.Local, queue.entries.single { it.id == bad.id }.status, "非法附件不得进入投递状态")
        assertEquals(0, turnStarts(runner).size)
        assertTrue(controller.note.contains("路径"), "实际 note：${controller.note}")
        // 空文本且无附件：队列层就直接拒绝，根本不入列
        assertFailsWith<IllegalArgumentException> { queue.enqueue("task", "") }
        assertTrue(queue.entries.none { it.text.isBlank() })
    }

    @Test
    fun `snapshot reconciliation validates identity state and queue without reading`() = withController("new-thread") { controller, queue, runner ->
        fun snapshot(id: String = "thr-1", turns: String = "[]", status: String = "idle") = JSONObject()
            .put("id", id).put("cwd", "/srv/demo")
            .put("turns", JSONArray(turns))
            .put("status", JSONObject().put("type", status))

        // 合法 idle+空轮次快照：直接信任，不追加 thread/read
        controller.reconcile(snapshot())
        assertTrue(controller.ready)
        assertTrue(controller.messages.isEmpty())

        // 错误ID / 非空轮次 / 非idle：快照一律不接受
        val bad = listOf(snapshot(id = "thr-other"),
            snapshot(turns = """[{"id":"t0","status":"completed"}]"""),
            snapshot(status = "active"))
        for (candidate in bad) {
            val error = assertFailsWith<IllegalStateException> { controller.reconcile(candidate) }
            assertEquals("新建任务初始状态未确认", error.message)
        }

        // 队列已有指令时必须改走历史核对，快照同样不被信任
        queue.enqueue("task", "第一条")
        val guarded = assertFailsWith<IllegalStateException> { controller.reconcile(snapshot()) }
        assertEquals("已有指令的任务必须读取历史核对", guarded.message)

        // 以上全程零 thread/read：任何分支退回读取都会出现在运行器日志里
        assertTrue(runner.inboundJson().none { it.optString("method") == "thread/read" },
            "快照路径不得发起 thread/read")
    }

    @Test
    fun `completions notify in real time and history reads do not backfill`() {
        val notices = mutableListOf<String>()
        withController("read-interleave", notices = notices) { controller, queue, _ ->
            // 历史快照含已完成 t0：读取不得补发完成回调
            controller.reconcile()
            Thread.sleep(200)
            assertEquals(emptyList(), notices, "历史读取不得补发完成回调：$notices")
            val live = queue.enqueue("task", "第一条")
            controller.setAutoDispatch(true)
            poll { queue.entries.single { it.id == live.id }.runtimeTurnState == RuntimeTurnState.Completed }
            // 实时完成回调恰好一条
            assertEquals(listOf("本轮处理结束"), notices, "实时完成应回调一次：$notices")
            // 再次读取：快照含 t0 与已完成 t1，仍不得补发或重复
            controller.reconcile()
            Thread.sleep(200)
            assertEquals(listOf("本轮处理结束"), notices, "快照不得补发或重复完成回调：$notices")
        }
    }

    @Test
    fun `duplicate completion events for one turn notify once`() {
        val notices = mutableListOf<String>()
        withController("complete-twice", notices = notices) { controller, queue, _ ->
            queue.enqueue("task", "第一条")
            controller.reconcile()
            controller.setAutoDispatch(true)
            poll { queue.entries.single().runtimeTurnState == RuntimeTurnState.Completed }
            Thread.sleep(200)
            assertEquals(listOf("本轮处理结束"), notices, "同轮重复完成事件不得重复回调：$notices")
        }
    }

    @Test
    fun `new pending request notifies exactly once`() {
        val notices = mutableListOf<String>()
        withController("approval", notices = notices) { controller, _, _ ->
            controller.reconcile()
            poll { controller.pendingRequests.size == 1 }
            Thread.sleep(200)
            assertEquals(listOf("需要你处理"), notices, "新待处理请求应恰好回调一次：$notices")
            controller.answerRequest("srv-77", JSONObject().put("decision", "accept"))
            poll { controller.pendingRequests.isEmpty() }
            Thread.sleep(200)
            assertEquals(listOf("需要你处理"), notices, "答复后不得再回调：$notices")
        }
    }

    @Test
    fun `configuration change rejects empty history and unconfirmed deliveries`() = withController("new-thread") { controller, queue, _ ->
        controller.reconcile()
        assertTrue(controller.ready)
        // 空历史：提示直接新建，避免关闭后无法恢复（真实 CLI 空线程限制）
        val empty = assertFailsWith<IllegalStateException> { controller.closeForConfigurationChange() }
        assertEquals("尚无历史的任务请直接新建，以免关闭后无法恢复", empty.message)
        // 状态待确认（Delivering/Unknown）一律先核对再应用；每次变更后 revision 递增，须取当前值
        val item = queue.enqueue("task", "第一条")
        queue.beginDelivery(item.id, item.revision)
        val delivering = assertFailsWith<IllegalStateException> { controller.closeForConfigurationChange() }
        assertEquals("请先核对状态待确认的指令", delivering.message)
        val deliveringEntry = queue.entries.single { it.id == item.id }
        queue.markUnknown(item.id, deliveringEntry.revision, "断线")
        val unknown = assertFailsWith<IllegalStateException> { controller.closeForConfigurationChange() }
        assertEquals("请先核对状态待确认的指令", unknown.message)
        // 核对后（Accepted）不再阻塞队列门，继续落到空历史判断
        val unknownEntry = queue.entries.single { it.id == item.id }
        queue.confirmAccepted(item.id, unknownEntry.revision, "回执")
        val after = assertFailsWith<IllegalStateException> { controller.closeForConfigurationChange() }
        assertEquals("尚无历史的任务请直接新建，以免关闭后无法恢复", after.message)
    }

    @Test
    fun `configuration change waits for pending approvals`() = withController("approval") { controller, _, _ ->
        controller.reconcile()
        poll { controller.pendingRequests.size == 1 }
        // 审批未处理：忙态阻止，不得关闭重连
        val busy = assertFailsWith<IllegalStateException> { controller.closeForConfigurationChange() }
        assertEquals("请等待当前轮次和审批结束后再应用线路", busy.message)
        assertTrue(controller.pendingRequests.isNotEmpty(), "被拒后待审批应原样保留")
    }
}
