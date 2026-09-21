package app.yxi.desktop

import app.yxi.agent.Transcript
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ModelSwitchControllerTest {
    /** 脚本化假终端：按命令内容分发 —— send-keys 计数并摘出 -l 文本，capture-pane 回屏面。
     * failSend 模拟命令脚本未跑完（无 marker 返回）；throwOnSend 模拟发送 exec 抛异常。 */
    private class Terminal {
        var screen = ""
        var failSend = false
        var throwOnSend = false
        var sends = 0
        val typed = mutableListOf<String>()
        val exec: suspend (String) -> String = { command ->
            when {
                "send-keys" in command -> {
                    sends++
                    if (throwOnSend) throw java.io.IOException("ssh 连接中断")
                    if (failSend) ""
                    else {
                        typed += Regex("-l '([^']*)'").find(command)!!.groupValues[1]
                        "__YXI_MODEL_REQUEST__"
                    }
                }
                "capture-pane" in command -> screen
                else -> ""
            }
        }
    }

    private fun fixture(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("yxi-model-switch").toFile()
        try { block(File(dir, "model-changes.json")) } finally { dir.deleteRecursively() }
    }

    /** 会话转录证据按 taskKey 注入（与 DesktopTranscriptMemory 同形的读取面）。 */
    private class CtxSource {
        val byTask = HashMap<String, Transcript.Ctx?>()
        fun of(taskKey: String) = byTask[taskKey]
    }

    private fun controller(file: File, terminal: Terminal, ctx: CtxSource, allow: Boolean = true) =
        ModelChangeStore(file).let {
            it to ModelSwitchController(it, borrowable = { allow }, pendingPrompt = { null },
                sessionCtx = ctx::of)
        }

    /** 直读磁盘 JSON：不经 init 恢复（恢复把在途一律转 Unknown，那是重启语义，不该由观察触发）。 */
    private fun rawStatus(file: File) = ModelChangeStatus.valueOf(
        org.json.JSONObject(file.readText()).getJSONArray("items").getJSONObject(0).getString("status"))

    private val taskKey = "host-a|win1"

    @Test fun `intent lands durable before any key is sent`() = fixture { file ->
        val terminal = Terminal()
        val ctx = CtxSource()
        val (_, c) = controller(file, terminal, ctx, allow = false) // 门不让过
        val accepted: Boolean
        runBlocking { accepted = c.step(terminal.exec, taskKey, "rt-1", "win", ConversationModelChange("opus", null)) }
        assertTrue(accepted)
        assertEquals(0, terminal.sends)
        assertEquals(ModelChangeStatus.Pending, rawStatus(file)) // 先落盘、未发键
        val reopened = ModelChangeStore(file)
        val entry = reopened.entries.single()
        assertEquals(taskKey, entry.taskKey)
        assertEquals("rt-1", entry.runtimeId)
        assertEquals("opus", entry.model)
    }

    @Test fun `store keys by task key so same runtime id on two hosts cannot collide`() = fixture { file ->
        val store = ModelChangeStore(file)
        assertTrue(store.propose("host-a|win", "rt-1", "opus", null))
        assertTrue(store.propose("host-b|win", "rt-1", "sonnet", null)) // 同 runtimeId、不同主机：并存
        assertEquals(2, store.entries.size)
        assertEquals("opus", store.active("host-a|win")?.model)
        assertEquals("sonnet", store.active("host-b|win")?.model)
    }

    @Test fun `verbatim name without claude prefix and both segments verified by session transcript`() = fixture { file ->
        val terminal = Terminal()
        val ctx = CtxSource()
        val (store, c) = controller(file, terminal, ctx)
        runBlocking {
            assertTrue(c.step(terminal.exec, taskKey, "rt-1", "win", ConversationModelChange("glm-5.3", "high")))
            assertEquals(listOf("/model glm-5.3"), terminal.typed)
            assertFalse(terminal.typed.single().contains("claude-"))
            ctx.byTask[taskKey] = Transcript.Ctx(1L, model = "glm-5.3") // 本会话转录出现「Set model to」回执
            c.step(terminal.exec, taskKey, "rt-1", "win", null) // model 段证据过 → effort 回到待发
            assertEquals(ModelChangeStatus.Pending, store.entries.single().status)
            c.step(terminal.exec, taskKey, "rt-1", "win", null) // 发 /effort high
            assertEquals(listOf("/model glm-5.3", "/effort high"), terminal.typed)
            ctx.byTask[taskKey] = Transcript.Ctx(1L, model = "glm-5.3", effort = "high")
            c.step(terminal.exec, taskKey, "rt-1", "win", null)
        }
        assertEquals(2, terminal.sends)
        val done = ModelChangeStore(file).entries.single()
        assertEquals(ModelChangeStatus.Applied, done.status)
        assertTrue(done.detail.contains("转录"))
    }

    @Test fun `one m context suffix is not conflated with plain variant`() = fixture { file ->
        val terminal = Terminal()
        val ctx = CtxSource()
        val (store, c) = controller(file, terminal, ctx)
        runBlocking {
            c.step(terminal.exec, taskKey, "rt-1", "win", ConversationModelChange("claude-opus-5[1m]", null))
            // 会话实际生效的是不带 [1m] 的普通变体：不同的选择，绝不能判成功
            ctx.byTask[taskKey] = Transcript.Ctx(1L, model = "claude-opus-5")
            c.step(terminal.exec, taskKey, "rt-1", "win", null)
            assertEquals(ModelChangeStatus.Delivering, store.entries.single().status)
            assertTrue(store.entries.single().readbackTries > 0)
        }
    }

    @Test fun `confirm dialog waits for the user and never auto-enters`() = fixture { file ->
        val terminal = Terminal()
        val ctx = CtxSource()
        val (store, c) = controller(file, terminal, ctx)
        runBlocking {
            c.step(terminal.exec, taskKey, "rt-1", "win", ConversationModelChange("opus", null))
            terminal.screen = "  Switch model?  ▸ opus" // 发送后弹确认框
            c.step(terminal.exec, taskKey, "rt-1", "win", null)
            assertEquals(ModelChangeStatus.AwaitConfirm, store.entries.single().status)
            c.step(terminal.exec, taskKey, "rt-1", "win", null) // 框还开着：绝不再发键
            assertEquals(1, terminal.sends)
            terminal.screen = "" // 用户已在终端自己确认
            ctx.byTask[taskKey] = Transcript.Ctx(1L, model = "opus")
            c.step(terminal.exec, taskKey, "rt-1", "win", null)
        }
        assertEquals(1, terminal.sends) // 全程只有最初那一次发送
        assertEquals(ModelChangeStatus.Applied, ModelChangeStore(file).entries.single().status)
    }

    @Test fun `unconfirmable send becomes unknown and never replays`() = fixture { file ->
        val terminal = Terminal()
        terminal.failSend = true
        val (_, c) = controller(file, terminal, CtxSource())
        runBlocking {
            c.step(terminal.exec, taskKey, "rt-1", "win", ConversationModelChange("opus", null))
            assertEquals(1, terminal.sends) // 尝试过一次
            c.step(terminal.exec, taskKey, "rt-1", "win", null) // Unknown 不再投递
        }
        assertEquals(1, terminal.sends)
        val entry = ModelChangeStore(file).entries.single()
        assertEquals(ModelChangeStatus.Unknown, entry.status)
        assertTrue(entry.detail.contains("不会自动重发"))
    }

    @Test fun `send exec failure cannot leave delivering behind`() = fixture { file ->
        val terminal = Terminal()
        terminal.throwOnSend = true
        val (store, c) = controller(file, terminal, CtxSource())
        runBlocking {
            c.step(terminal.exec, taskKey, "rt-1", "win", ConversationModelChange("opus", null))
        }
        // beginSend 之后 exec 抛异常：必须收口 Unknown（按键是否送达不明），不能停在 Delivering
        val entry = store.entries.single()
        assertEquals(ModelChangeStatus.Unknown, entry.status)
        assertTrue(entry.detail.contains("是否送达不明"))
        terminal.throwOnSend = false
        runBlocking { c.step(terminal.exec, taskKey, "rt-1", "win", null) }
        assertEquals(1, terminal.sends) // 首次尝试已计入；Unknown 后不再新增投递
    }

    @Test fun `readback mismatch three times turns unknown with transcript evidence`() = fixture { file ->
        val terminal = Terminal()
        val ctx = CtxSource()
        ctx.byTask[taskKey] = Transcript.Ctx(1L, model = "claude-sonnet-4-5") // 永远对不上
        val (_, c) = controller(file, terminal, ctx)
        runBlocking {
            c.step(terminal.exec, taskKey, "rt-1", "win", ConversationModelChange("opus", null))
            repeat(3) { c.step(terminal.exec, taskKey, "rt-1", "win", null) }
        }
        assertEquals(1, terminal.sends) // 不自动重放
        val entry = ModelChangeStore(file).entries.single()
        assertEquals(ModelChangeStatus.Unknown, entry.status)
        assertTrue(entry.detail.contains("model=claude-sonnet-4-5"))
    }

    @Test fun `in-flight delivery survives restart as unknown`() = fixture { file ->
        val store = ModelChangeStore(file)
        assertTrue(store.propose(taskKey, "rt-1", "opus", "high"))
        store.beginSend(taskKey)
        assertEquals(ModelChangeStatus.Delivering, rawStatus(file))
        val reopened = ModelChangeStore(file) // 模拟重启：init 恢复
        val entry = reopened.entries.single()
        assertEquals(ModelChangeStatus.Unknown, entry.status)
        assertTrue(entry.detail.contains("核对"))
        val terminal = Terminal()
        runBlocking { ModelSwitchController(reopened, borrowable = { true }, pendingPrompt = { null }, sessionCtx = { null }).step(terminal.exec, taskKey, "rt-1", "win", null) }
        assertEquals(0, terminal.sends)
    }

    @Test fun `rejected screen names the failure and does not resend`() = fixture { file ->
        val terminal = Terminal()
        val (_, c) = controller(file, terminal, CtxSource())
        runBlocking {
            c.step(terminal.exec, taskKey, "rt-1", "win", ConversationModelChange("nope-9000", null))
            terminal.screen = "Model 'nope-9000' not found. Please check the model name."
            c.step(terminal.exec, taskKey, "rt-1", "win", null)
        }
        assertEquals(1, terminal.sends)
        val entry = ModelChangeStore(file).entries.single()
        assertEquals(ModelChangeStatus.Unknown, entry.status)
        assertTrue(entry.detail.contains("未接受"))
    }

    @Test fun `effort only request sends a single effort command`() = fixture { file ->
        val terminal = Terminal()
        val ctx = CtxSource()
        val (_, c) = controller(file, terminal, ctx)
        runBlocking {
            c.step(terminal.exec, taskKey, "rt-1", "win", ConversationModelChange(null, "xhigh"))
            assertEquals(listOf("/effort xhigh"), terminal.typed)
            ctx.byTask[taskKey] = Transcript.Ctx(1L, model = "whatever", effort = "xhigh")
            c.step(terminal.exec, taskKey, "rt-1", "win", null)
        }
        assertEquals(ModelChangeStatus.Applied, ModelChangeStore(file).entries.single().status)
    }

    @Test fun `busy store refuses new intent so the runner keeps it pending`() = fixture { file ->
        val terminal = Terminal()
        val ctx = CtxSource() // 证据一直空：条目停在在途
        val (store, c) = controller(file, terminal, ctx)
        runBlocking {
            c.step(terminal.exec, taskKey, "rt-1", "win", ConversationModelChange("opus", null)) // → Delivering
            val accepted = c.step(terminal.exec, taskKey, "rt-1", "win", ConversationModelChange("sonnet", "low"))
            assertFalse(accepted) // 在途不接收；调用方把意图留在 conn.modelChanges
            val entry = store.entries.single()
            assertEquals(ModelChangeStatus.Delivering, entry.status)
            assertEquals("opus", entry.model)
        }
    }

    @Test fun `protocol matchers stay exact and honest`() {
        assertTrue(ModelSwitchProtocol.sameModel("Claude-Opus-5[1m]", "claude-opus-5[1m]")) // 仅大小写归一
        assertFalse(ModelSwitchProtocol.sameModel("claude-opus-5[1m]", "claude-opus-5")) // 上下文后缀不剥
        assertFalse(ModelSwitchProtocol.sameModel("opus", "opusplan"))
        assertFalse(ModelSwitchProtocol.sameModel("opus", ""))
        assertTrue(ModelSwitchProtocol.sameEffort("high", "high"))
        assertFalse(ModelSwitchProtocol.sameEffort("mid", "medium"))
        assertTrue(ModelSwitchProtocol.rejected("Kept model as claude-sonnet-4-5."))
        assertTrue(ModelSwitchProtocol.confirmDialog("Change effort level?"))
        assertEquals("/model default", ModelSwitchProtocol.command("default", null))
    }
}
