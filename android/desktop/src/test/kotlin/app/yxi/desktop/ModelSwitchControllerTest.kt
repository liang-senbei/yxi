package app.yxi.desktop

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ModelSwitchControllerTest {
    /** 脚本化假终端：按命令内容分发 —— send-keys 计数并摘出 -l 文本，settings.json 回 JSON，
     * capture-pane 回屏面。failSend 模拟命令脚本未跑完（无 marker 返回）。 */
    private class Terminal {
        var screen = ""
        var settings = "{}"
        var failSend = false
        var sends = 0
        val typed = mutableListOf<String>()
        val exec: suspend (String) -> String = { command ->
            when {
                "send-keys" in command -> {
                    sends++
                    if (failSend) ""
                    else {
                        typed += Regex("-l '([^']*)'").find(command)!!.groupValues[1]
                        "__YXI_MODEL_REQUEST__"
                    }
                }
                "settings.json" in command -> settings
                "capture-pane" in command -> screen
                else -> ""
            }
        }
    }

    private fun fixture(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("yxi-model-switch").toFile()
        try { block(File(dir, "model-changes.json")) } finally { dir.deleteRecursively() }
    }

    private fun controller(file: File, terminal: Terminal, allow: Boolean = true) =
        ModelChangeStore(file).let { it to ModelSwitchController(it, borrowable = { allow }, pendingPrompt = { null }) }

    /** 直读磁盘 JSON：不经 init 恢复（恢复把在途一律转 Unknown，那是重启语义，不该由观察触发）。 */
    private fun rawStatus(file: File) = ModelChangeStatus.valueOf(
        org.json.JSONObject(file.readText()).getJSONArray("items").getJSONObject(0).getString("status"))

    @Test fun `intent lands durable before any key is sent`() = fixture { file ->
        val terminal = Terminal()
        val (_, c) = controller(file, terminal, allow = false) // 门不让过
        val accepted: Boolean
        runBlocking { accepted = c.step(terminal.exec, "rt-1", "win", ConversationModelChange("opus", null)) }
        assertTrue(accepted)
        assertEquals(0, terminal.sends)
        assertEquals(ModelChangeStatus.Pending, rawStatus(file)) // 先落盘、未发键
        val reopened = ModelChangeStore(file)
        val entry = reopened.entries.single()
        assertEquals("rt-1", entry.runtimeId)
        assertEquals("opus", entry.model)
    }

    @Test fun `verbatim name without claude prefix and both segments verified by readback`() = fixture { file ->
        val terminal = Terminal()
        val (store, c) = controller(file, terminal)
        runBlocking {
            assertTrue(c.step(terminal.exec, "rt-1", "win", ConversationModelChange("glm-5.3", "high")))
            assertEquals(listOf("/model glm-5.3"), terminal.typed)
            assertFalse(terminal.typed.single().contains("claude-"))
            terminal.settings = """{"model": "glm-5.3"}"""
            c.step(terminal.exec, "rt-1", "win", null) // model 段回读过 → effort 回到待发
            assertEquals(ModelChangeStatus.Pending, store.entries.single().status)
            c.step(terminal.exec, "rt-1", "win", null) // 发 /effort high
            assertEquals(listOf("/model glm-5.3", "/effort high"), terminal.typed)
            terminal.settings = """{"model": "glm-5.3", "effortLevel": "high"}"""
            c.step(terminal.exec, "rt-1", "win", null)
        }
        assertEquals(2, terminal.sends)
        val done = ModelChangeStore(file).entries.single() // Applied 是终态，重开不受恢复影响
        assertEquals(ModelChangeStatus.Applied, done.status)
        assertTrue(done.detail.contains("回读"))
    }

    @Test fun `confirm dialog waits for the user and never auto-enters`() = fixture { file ->
        val terminal = Terminal()
        val (store, c) = controller(file, terminal)
        runBlocking {
            c.step(terminal.exec, "rt-1", "win", ConversationModelChange("opus", null))
            terminal.screen = "  Switch model?  ▸ opus" // 发送后弹确认框
            c.step(terminal.exec, "rt-1", "win", null)
            assertEquals(ModelChangeStatus.AwaitConfirm, store.entries.single().status)
            c.step(terminal.exec, "rt-1", "win", null) // 框还开着：绝不再发键
            assertEquals(1, terminal.sends)
            terminal.screen = "" // 用户已在终端自己确认
            terminal.settings = """{"model": "opus"}"""
            c.step(terminal.exec, "rt-1", "win", null)
        }
        assertEquals(1, terminal.sends) // 全程只有最初那一次发送
        assertEquals(ModelChangeStatus.Applied, ModelChangeStore(file).entries.single().status)
    }

    @Test fun `unconfirmable send becomes unknown and never replays`() = fixture { file ->
        val terminal = Terminal()
        terminal.failSend = true
        val (_, c) = controller(file, terminal)
        runBlocking {
            c.step(terminal.exec, "rt-1", "win", ConversationModelChange("opus", null))
            assertEquals(1, terminal.sends) // 尝试过一次
            c.step(terminal.exec, "rt-1", "win", null) // Unknown 不再投递
        }
        assertEquals(1, terminal.sends)
        val entry = ModelChangeStore(file).entries.single()
        assertEquals(ModelChangeStatus.Unknown, entry.status)
        assertTrue(entry.detail.contains("不会自动重发"))
    }

    @Test fun `readback mismatch three times turns unknown with actual value`() = fixture { file ->
        val terminal = Terminal()
        terminal.settings = """{"model": "claude-sonnet-4-5"}""" // 永远对不上
        val (_, c) = controller(file, terminal)
        runBlocking {
            c.step(terminal.exec, "rt-1", "win", ConversationModelChange("opus", null))
            repeat(3) { c.step(terminal.exec, "rt-1", "win", null) }
        }
        assertEquals(1, terminal.sends) // 不自动重放
        val entry = ModelChangeStore(file).entries.single()
        assertEquals(ModelChangeStatus.Unknown, entry.status)
        assertTrue(entry.detail.contains("model=claude-sonnet-4-5"))
    }

    @Test fun `in-flight delivery survives restart as unknown`() = fixture { file ->
        val store = ModelChangeStore(file)
        assertTrue(store.propose("rt-1", "opus", "high"))
        store.beginSend("rt-1")
        assertEquals(ModelChangeStatus.Delivering, rawStatus(file))
        val reopened = ModelChangeStore(file) // 模拟重启：init 恢复
        val entry = reopened.entries.single()
        assertEquals(ModelChangeStatus.Unknown, entry.status)
        assertTrue(entry.detail.contains("核对"))
        val terminal = Terminal()
        runBlocking { ModelSwitchController(reopened, borrowable = { true }, pendingPrompt = { null }).step(terminal.exec, "rt-1", "win", null) }
        assertEquals(0, terminal.sends)
    }

    @Test fun `rejected screen names the failure and does not resend`() = fixture { file ->
        val terminal = Terminal()
        val (_, c) = controller(file, terminal)
        runBlocking {
            c.step(terminal.exec, "rt-1", "win", ConversationModelChange("nope-9000", null))
            terminal.screen = "Model 'nope-9000' not found. Please check the model name."
            c.step(terminal.exec, "rt-1", "win", null)
        }
        assertEquals(1, terminal.sends)
        val entry = ModelChangeStore(file).entries.single()
        assertEquals(ModelChangeStatus.Unknown, entry.status)
        assertTrue(entry.detail.contains("未接受"))
    }

    @Test fun `effort only request sends a single effort command`() = fixture { file ->
        val terminal = Terminal()
        val (_, c) = controller(file, terminal)
        runBlocking {
            c.step(terminal.exec, "rt-1", "win", ConversationModelChange(null, "xhigh"))
            assertEquals(listOf("/effort xhigh"), terminal.typed)
            terminal.settings = """{"effortLevel": "xhigh"}"""
            c.step(terminal.exec, "rt-1", "win", null)
        }
        assertEquals(ModelChangeStatus.Applied, ModelChangeStore(file).entries.single().status)
    }

    @Test fun `busy store refuses new intent so the runner keeps it pending`() = fixture { file ->
        val terminal = Terminal()
        terminal.settings = """{"effortLevel": "low"}""" // 回读永远对不上：条目停在在途
        val (store, c) = controller(file, terminal)
        runBlocking {
            c.step(terminal.exec, "rt-1", "win", ConversationModelChange("opus", null)) // → Delivering
            val accepted = c.step(terminal.exec, "rt-1", "win", ConversationModelChange("sonnet", "low"))
            assertFalse(accepted) // 在途不接收；调用方把意图留在 conn.modelChanges
            val entry = store.entries.single()
            assertEquals(ModelChangeStatus.Delivering, entry.status)
            assertEquals("opus", entry.model)
        }
    }

    @Test fun `protocol matchers strip display decoration but not semantics`() {
        assertTrue(ModelSwitchProtocol.sameModel("claude-fable-5-1[1m]", "claude-fable-5-1"))
        assertTrue(ModelSwitchProtocol.sameModel("Sonnet", "sonnet"))
        assertFalse(ModelSwitchProtocol.sameModel("opus", "opusplan"))
        assertFalse(ModelSwitchProtocol.sameModel("opus", ""))
        assertTrue(ModelSwitchProtocol.sameEffort("high", "high"))
        assertFalse(ModelSwitchProtocol.sameEffort("mid", "medium"))
        assertTrue(ModelSwitchProtocol.rejected("Kept model as claude-sonnet-4-5."))
        assertTrue(ModelSwitchProtocol.confirmDialog("Change effort level?"))
        assertEquals("/model default", ModelSwitchProtocol.command("default", null))
    }
}
