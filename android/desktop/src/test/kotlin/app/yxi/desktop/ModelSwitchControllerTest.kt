package app.yxi.desktop

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ModelSwitchControllerTest {
    /** 脚本化假终端：按命令内容分发 —— send-keys 计数并摘出 -l 文本，capture-pane 回屏面。
     * failSend 模拟命令脚本未跑完（无 marker 返回）；throwOnSend 模拟发送 exec 抛异常。 */
    private class Terminal {
        var screen = ""
        /** 发送后才出现/新增的屏面变化：只有发送后的 after 捕获能看见（CLI 回显天然晚一拍）。 */
        var screenAfterSend: String? = null
        var failSend = false
        var throwOnSend = false
        var sends = 0
        private var sentSinceCapture = false
        val typed = mutableListOf<String>()
        val exec: suspend (String) -> String = { command ->
            when {
                "send-keys" in command -> {
                    sends++
                    sentSinceCapture = true
                    if (throwOnSend) throw java.io.IOException("ssh 连接中断")
                    if (failSend) ""
                    else {
                        typed += Regex("-l '([^']*)'").find(command)!!.groupValues[1]
                        "__YXI_MODEL_REQUEST__"
                    }
                }
                "capture-pane" in command -> {
                    val fresh = screenAfterSend?.takeIf { sentSinceCapture }
                    sentSinceCapture = false
                    fresh ?: screen
                }
                else -> ""
            }
        }
    }

    /** 假转录证据源：file+offset+ctx 可在步骤之间拨动，模拟「发送后新字节/新回执到达」。 */
    private class FakeTranscript {
        var file = "/srv/session.jsonl"
        var offset = 100L
        var model = ""
        var effort = ""
        fun view(taskKey: String) = ModelEvidenceView(file, offset, model, effort)
    }

    private fun fixture(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("yxi-model-switch").toFile()
        try { block(File(dir, "model-changes.json")) } finally { dir.deleteRecursively() }
    }

    private fun controller(file: File, terminal: Terminal, transcript: FakeTranscript, allow: Boolean = true) =
        ModelChangeStore(file).let {
            it to ModelSwitchController(it, borrowable = { allow }, pendingPrompt = { null },
                sessionEvidence = transcript::view)
        }

    /** 直读磁盘 JSON：不经 init 恢复（恢复把在途一律转 Unknown，那是重启语义，不该由观察触发）。 */
    private fun rawStatus(file: File) = ModelChangeStatus.valueOf(
        org.json.JSONObject(file.readText()).getJSONArray("items").getJSONObject(0).getString("status"))

    private val taskKey = "host-a|win1"

    @Test fun `intent lands durable before any key is sent`() = fixture { file ->
        val terminal = Terminal()
        val (_, c) = controller(file, terminal, FakeTranscript(), allow = false) // 门不让过
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

    @Test fun `only post send transcript evidence counts and both segments verify`() = fixture { file ->
        val terminal = Terminal()
        val transcript = FakeTranscript()
        transcript.model = "old-model"; transcript.effort = "mid" // 发送时的旧缓存
        val (store, c) = controller(file, terminal, transcript)
        runBlocking {
            assertTrue(c.step(terminal.exec, taskKey, "rt-1", "win", ConversationModelChange("glm-5.3", "high")))
            assertEquals(listOf("/model glm-5.3"), terminal.typed)
            assertEquals(ModelChangeStatus.SentAwaitEvidence, store.entries.single().status)
            // 新字节已到但 ctx 还是发送时的旧值：绝不是新回执，绝不假 Applied（boss 2026-09-21 指出）
            transcript.offset = 200L
            c.step(terminal.exec, taskKey, "rt-1", "win", null)
            assertEquals(ModelChangeStatus.SentAwaitEvidence, store.entries.single().status)
            // 新回执到达：model 段过 → effort 回到待发
            transcript.model = "glm-5.3" // effort 仍是旧值 mid：effort 段不看这条旧值
            c.step(terminal.exec, taskKey, "rt-1", "win", null)
            assertEquals(ModelChangeStatus.Pending, store.entries.single().status)
            c.step(terminal.exec, taskKey, "rt-1", "win", null) // 发 /effort high（基线按本次发送重采）
            assertEquals(listOf("/model glm-5.3", "/effort high"), terminal.typed)
            assertEquals(ModelChangeStatus.SentAwaitEvidence, store.entries.single().status)
            transcript.offset = 300L
            transcript.model = "glm-5.3"; transcript.effort = "high"
            c.step(terminal.exec, taskKey, "rt-1", "win", null)
        }
        assertEquals(2, terminal.sends)
        val done = ModelChangeStore(file).entries.single()
        assertEquals(ModelChangeStatus.Applied, done.status)
        assertTrue(done.detail.contains("新回执"))
    }

    @Test fun `one m context suffix mismatch on fresh evidence is unknown not applied`() = fixture { file ->
        val terminal = Terminal()
        val transcript = FakeTranscript()
        val (_, c) = controller(file, terminal, transcript)
        runBlocking {
            c.step(terminal.exec, taskKey, "rt-1", "win", ConversationModelChange("claude-opus-5[1m]", null))
            transcript.offset = 200L // 新回执：不带 [1m] 的普通变体 —— 不同的选择，判失败
            transcript.model = "claude-opus-5"
            c.step(terminal.exec, taskKey, "rt-1", "win", null)
        }
        val entry = ModelChangeStore(file).entries.single()
        assertEquals(ModelChangeStatus.Unknown, entry.status)
        assertTrue(entry.detail.contains("claude-opus-5"))
        assertTrue(entry.detail.contains("不符"))
    }

    @Test fun `confirm dialog waits for the user and never auto-enters`() = fixture { file ->
        val terminal = Terminal()
        val transcript = FakeTranscript()
        terminal.screenAfterSend = "  Switch model?  ▸ opus" // 发送后新弹的确认框（发送前没有 → 新增变化）
        val (store, c) = controller(file, terminal, transcript)
        runBlocking {
            c.step(terminal.exec, taskKey, "rt-1", "win", ConversationModelChange("opus", null))
            assertEquals(ModelChangeStatus.AwaitConfirm, store.entries.single().status)
            terminal.screen = "  Switch model?  ▸ opus" // 框还开着
            c.step(terminal.exec, taskKey, "rt-1", "win", null) // 绝不代按：绝不再发键
            assertEquals(1, terminal.sends)
            terminal.screen = "" // 用户已在终端自己确认
            transcript.offset = 200L
            transcript.model = "opus"
            c.step(terminal.exec, taskKey, "rt-1", "win", null)
        }
        assertEquals(1, terminal.sends) // 全程只有最初那一次发送
        assertEquals(ModelChangeStatus.Applied, ModelChangeStore(file).entries.single().status)
    }

    @Test fun `stale rejection text on screen is not this attempt s result`() = fixture { file ->
        val terminal = Terminal()
        val transcript = FakeTranscript()
        terminal.screen = "Model 'older-attempt' not found. Please check the model name." // 发送前就在屏上
        val (store, c) = controller(file, terminal, transcript)
        runBlocking {
            c.step(terminal.exec, taskKey, "rt-1", "win", ConversationModelChange("opus", null))
        }
        // 发送后屏上仍是同一段旧文本：不算这次的拒绝 → 已发送待证据，不误判
        assertEquals(ModelChangeStatus.SentAwaitEvidence, store.entries.single().status)
    }

    @Test fun `fresh rejection after send names the failure and does not resend`() = fixture { file ->
        val terminal = Terminal()
        terminal.screenAfterSend = "Model 'nope-9000' not found. Please check the model name." // 发送后新增
        val (_, c) = controller(file, terminal, FakeTranscript())
        runBlocking {
            c.step(terminal.exec, taskKey, "rt-1", "win", ConversationModelChange("nope-9000", null))
            c.step(terminal.exec, taskKey, "rt-1", "win", null) // Unknown 不再投递
        }
        assertEquals(1, terminal.sends)
        val entry = ModelChangeStore(file).entries.single() // 终态，reopen 安全
        assertEquals(ModelChangeStatus.Unknown, entry.status)
        assertTrue(entry.detail.contains("未接受"))
    }

    @Test fun `unconfirmable send becomes unknown and never replays`() = fixture { file ->
        val terminal = Terminal()
        terminal.failSend = true
        val (_, c) = controller(file, terminal, FakeTranscript())
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
        val (store, c) = controller(file, terminal, FakeTranscript())
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

    @Test fun `in flight delivery survives restart as unknown`() = fixture { file ->
        val store = ModelChangeStore(file)
        assertTrue(store.propose(taskKey, "rt-1", "opus", "high"))
        store.beginSend(taskKey, ModelEvidenceBaseline("", -1L, "", "", false, false))
        assertEquals(ModelChangeStatus.Delivering, rawStatus(file))
        val reopened = ModelChangeStore(file) // 模拟重启：init 恢复
        val entry = reopened.entries.single()
        assertEquals(ModelChangeStatus.Unknown, entry.status)
        assertTrue(entry.detail.contains("核对"))
        val terminal = Terminal()
        runBlocking { ModelSwitchController(reopened, borrowable = { true }, pendingPrompt = { null }, sessionEvidence = { null }).step(terminal.exec, taskKey, "rt-1", "win", null) }
        assertEquals(0, terminal.sends)
    }

    @Test fun `effort only request sends a single effort command`() = fixture { file ->
        val terminal = Terminal()
        val transcript = FakeTranscript()
        val (_, c) = controller(file, terminal, transcript)
        runBlocking {
            c.step(terminal.exec, taskKey, "rt-1", "win", ConversationModelChange(null, "xhigh"))
            assertEquals(listOf("/effort xhigh"), terminal.typed)
            transcript.offset = 200L
            transcript.model = "whatever"; transcript.effort = "xhigh"
            c.step(terminal.exec, taskKey, "rt-1", "win", null)
        }
        assertEquals(ModelChangeStatus.Applied, ModelChangeStore(file).entries.single().status)
    }

    @Test fun `delivering and awaiting confirm refuse new intent but awaiting evidence yields`() = fixture { file ->
        val store = ModelChangeStore(file)
        assertTrue(store.propose(taskKey, "rt-1", "opus", null))
        store.beginSend(taskKey, ModelEvidenceBaseline("", -1L, "", "", false, false))
        assertFalse(store.propose(taskKey, "rt-1", "sonnet", null)) // 发送中不接收
        store.markAwaitEvidence(taskKey, store.entries.single().revision)
        assertTrue(store.propose(taskKey, "rt-1", "sonnet", "low")) // 待证据可被新意图顶掉
        val entry = store.entries.single()
        assertEquals(ModelChangeStatus.Pending, entry.status)
        assertEquals("sonnet", entry.model)
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
