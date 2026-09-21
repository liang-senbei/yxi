package app.yxi.desktop

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * 真实隔离转录 → 产品解析/控制链路的实测（boss 2026-09-21 要求：不用口头形状吻合替代实测）。
 *
 * fixture 是真 Claude Code CLI（2.1.267，隔离 HOME + 本地 stub HTTP）转录原样字节：
 * hi → assistant(glm-5.3-stub, effort=high) → /model 回执 → /effort 回执 → hi3 → assistant(deepseek-stub-v3, effort=xhigh)。
 * 两条回执之后到下一条 assistant 之间没有任何 assistant 行 —— 正是「新回执没有下一 assistant」的实形。
 *
 * 全链走产品代码：DesktopTranscriptMemory.Entry（claim/append 字节偏移）+ 生产同款
 * sessionEvidence 取值（DesktopTranscriptMemory.get → ModelEvidenceView）+ ModelSwitchController/ModelChangeStore。
 * 未覆盖（如实声明）：生产输入框门 Model.borrowable / 真实 tmux 发送 —— 本类用注入假门与假终端，
 * 只测「门放行后」的解析与状态迁移；门本身另有纯函数测试。
 */
class ModelSwitchRealTranscriptTest {
    private class Terminal {
        var screen = ""
        var sends = 0
        val typed = mutableListOf<String>()
        val exec: suspend (String) -> String = { command ->
            when {
                "send-keys" in command -> {
                    sends++
                    typed += Regex("-l '([^']*)'").find(command)!!.groupValues[1]
                    "__YXI_MODEL_REQUEST__"
                }
                "capture-pane" in command -> screen
                else -> ""
            }
        }
    }

    /** fixture 逐行字节偏移：行文本 + 该行首字节偏移（offsets.size = lines.size + 1，末元素 = 总字节）。 */
    private class Fixture(lines: List<String>, val offsets: List<Long>) {
        val lines = lines
        fun bytes(from: Int, toInclusive: Int) = offsets[toInclusive + 1] - offsets[from]
    }

    private fun loadFixture(): Fixture {
        val text = javaClass.classLoader
            .getResourceAsStream("modelswitch/real-claude-transcript.jsonl")!!
            .readBytes().toString(Charsets.UTF_8)
        val lines = text.lineSequence().filter { it.isNotEmpty() }.toList()
        val offsets = mutableListOf(0L)
        lines.forEach { offsets += offsets.last() + it.toByteArray(Charsets.UTF_8).size + 1 }
        return Fixture(lines, offsets)
    }

    private fun Fixture.indexOfText(needle: String): Int {
        val i = lines.indexOfFirst { needle in it }
        check(i >= 0) { "fixture 缺少期望行：$needle" }
        return i
    }

    private fun tempCopy(fx: Fixture): File {
        val f = Files.createTempDirectory("yxi-msw-fixture").toFile().resolve("session.jsonl")
        f.writeText(fx.lines.joinToString("\n", postfix = "\n"))
        return f
    }

    /** 生产同款证据取值：不经注入假 view，证据直接来自 Entry 真实解析结果。 */
    private val productionEvidence: (String) -> ModelEvidenceView? = { taskKey ->
        DesktopTranscriptMemory.get(taskKey)?.let {
            ModelEvidenceView(it.file, it.view.offset, it.view.context?.model.orEmpty(), it.view.context?.effort.orEmpty())
        }
    }

    private fun rawStatus(file: File) = ModelChangeStatus.valueOf(
        org.json.JSONObject(file.readText()).getJSONArray("items").getJSONObject(0).getString("status"))

    @Test
    fun `parser moves model and effort ctx on receipts with no assistant in between`() {
        val fx = loadFixture()
        val baseline = fx.indexOfText("glm-5.3-stub")
        val modelReceipt = fx.indexOfText("<local-command-stdout>Set model to")
        val effortReceipt = fx.indexOfText("<local-command-stdout>Set effort level to")
        check(modelReceipt > baseline && effortReceipt > modelReceipt)

        val file = tempCopy(fx)
        val entry = DesktopTranscriptMemory.Entry(file.absolutePath, 0)
        val (lease, _) = entry.claim()
        // 基线：只到 baseline assistant 行 —— ctx 来自 assistant 顶层 model/effort
        entry.append(lease, fx.lines.subList(0, baseline + 1), fx.offsets[baseline + 1])
        assertEquals("glm-5.3-stub", entry.view.context?.model)
        assertEquals("high", entry.view.context?.effort)
        // model 回执单独到达（其后没有任何 assistant 行）：ctx.model 立刻换新
        entry.append(lease, listOf(fx.lines[modelReceipt]), fx.bytes(modelReceipt, modelReceipt))
        assertEquals("deepseek-stub-v3", entry.view.context?.model, "回执无下一 assistant 也必须更新")
        assertEquals("high", entry.view.context?.effort, "model 回执不动 effort")
        // effort 回执到达（其间依旧没有 assistant）：effortSwitchOf 即时生效
        entry.append(lease, fx.lines.subList(modelReceipt + 1, effortReceipt + 1),
            fx.bytes(modelReceipt + 1, effortReceipt))
        assertEquals("xhigh", entry.view.context?.effort, "effort 回执无下一 assistant 也必须更新")
        // 之后真正的 assistant 到达：顶层值与回执一致（转录自洽）
        entry.append(lease, fx.lines.subList(effortReceipt + 1, fx.lines.size), fx.bytes(effortReceipt + 1, fx.lines.size - 1))
        assertEquals("deepseek-stub-v3", entry.view.context?.model)
        assertEquals("xhigh", entry.view.context?.effort)
        file.parentFile.deleteRecursively()
    }

    @Test
    fun `controller reaches Applied on real receipt bytes even without a following assistant`() {
        val fx = loadFixture()
        val baseline = fx.indexOfText("glm-5.3-stub")
        val modelReceipt = fx.indexOfText("<local-command-stdout>Set model to")
        val file = tempCopy(fx)
        val entry = DesktopTranscriptMemory.Entry(file.absolutePath, 0)
        val taskKey = "msw-real|model-phase"
        DesktopTranscriptMemory.put(taskKey, entry)
        try {
            val (lease, _) = entry.claim()
            entry.append(lease, fx.lines.subList(0, baseline + 1), fx.offsets[baseline + 1])
            val baselineOffset = entry.view.offset

            val dir = Files.createTempDirectory("yxi-msw-store").toFile()
            val storeFile = dir.resolve("changes.json")
            val store = ModelChangeStore(storeFile)
            val terminal = Terminal()
            val c = ModelSwitchController(store, borrowable = { true }, pendingPrompt = { null },
                sessionEvidence = productionEvidence)
            runBlocking {
                c.step(terminal.exec, taskKey, "rt-1", "win", ConversationModelChange("deepseek-stub-v3", null))
            }
            assertEquals(listOf("/model deepseek-stub-v3"), terminal.typed, "直接下发具体第三方 ID，不是别名")
            assertEquals(ModelChangeStatus.SentAwaitEvidence, rawStatus(storeFile))
            // 发送基线必须是真实 Entry 的 file+offset（产品接线证据，不是测试凑数）
            val sent = store.entries.single()
            assertEquals(file.absolutePath, sent.baselineFile)
            assertEquals(baselineOffset, sent.baselineOffset)
            assertEquals("glm-5.3-stub", sent.evidenceModel)

            // 真实新字节但无回执（system/snapshot/caveat/command-name 行）：偏移前进、ctx 未变 → 不假 Applied
            entry.append(lease, fx.lines.subList(baseline + 1, modelReceipt), fx.bytes(baseline + 1, modelReceipt - 1))
            check(entry.view.offset > baselineOffset)
            runBlocking { c.step(terminal.exec, taskKey, "rt-1", "win", null) }
            assertEquals(ModelChangeStatus.SentAwaitEvidence, rawStatus(storeFile), "新字节没有新回执：保持等证据")

            // 真回执字节到达（其后仍无 assistant 行）→ Applied
            entry.append(lease, listOf(fx.lines[modelReceipt]), fx.bytes(modelReceipt, modelReceipt))
            runBlocking { c.step(terminal.exec, taskKey, "rt-1", "win", null) }
            assertEquals(ModelChangeStatus.Applied, rawStatus(storeFile), "无下一 assistant 的真回执也必须 Applied")
            assertTrue(store.entries.single().detail.contains("新回执"))
        } finally {
            DesktopTranscriptMemory.drop(taskKey)
            file.parentFile.deleteRecursively()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `effort switch verifies against real effortSwitchOf receipt without any assistant`() {
        val fx = loadFixture()
        val baseline = fx.indexOfText("glm-5.3-stub")
        val effortReceipt = fx.indexOfText("<local-command-stdout>Set effort level to")
        val file = tempCopy(fx)
        val entry = DesktopTranscriptMemory.Entry(file.absolutePath, 0)
        val taskKey = "msw-real|effort-phase"
        DesktopTranscriptMemory.put(taskKey, entry)
        val dir = Files.createTempDirectory("yxi-msw-store2").toFile()
        try {
            val (lease, _) = entry.claim()
            entry.append(lease, fx.lines.subList(0, baseline + 1), fx.offsets[baseline + 1])
            val storeFile = dir.resolve("changes.json")
            val store = ModelChangeStore(storeFile)
            val terminal = Terminal()
            val c = ModelSwitchController(store, borrowable = { true }, pendingPrompt = { null },
                sessionEvidence = productionEvidence)
            runBlocking {
                c.step(terminal.exec, taskKey, "rt-1", "win", ConversationModelChange(null, "xhigh"))
            }
            assertEquals(listOf("/effort xhigh"), terminal.typed)
            assertEquals(ModelChangeStatus.SentAwaitEvidence, rawStatus(storeFile))
            // 中间夹着 model 回执与快照行（真字节前进）：都不该给 effort 段发 Applied
            entry.append(lease, fx.lines.subList(baseline + 1, effortReceipt), fx.bytes(baseline + 1, effortReceipt - 1))
            runBlocking { c.step(terminal.exec, taskKey, "rt-1", "win", null) }
            assertEquals(ModelChangeStatus.SentAwaitEvidence, rawStatus(storeFile), "effort 证据未变：不假 Applied")
            entry.append(lease, listOf(fx.lines[effortReceipt]), fx.bytes(effortReceipt, effortReceipt))
            runBlocking { c.step(terminal.exec, taskKey, "rt-1", "win", null) }
            assertEquals(ModelChangeStatus.Applied, rawStatus(storeFile), "effort 回执无下一 assistant 也 Applied")
        } finally {
            DesktopTranscriptMemory.drop(taskKey)
            file.parentFile.deleteRecursively()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `blocked gate keeps pending honestly and never sends keys`() {
        val fx = loadFixture()
        val baseline = fx.indexOfText("glm-5.3-stub")
        val file = tempCopy(fx)
        val entry = DesktopTranscriptMemory.Entry(file.absolutePath, 0)
        val taskKey = "msw-real|gate"
        DesktopTranscriptMemory.put(taskKey, entry)
        val dir = Files.createTempDirectory("yxi-msw-store3").toFile()
        try {
            val (lease, _) = entry.claim()
            entry.append(lease, fx.lines.subList(0, baseline + 1), fx.offsets[baseline + 1])
            val storeFile = dir.resolve("changes.json")
            val store = ModelChangeStore(storeFile)
            val terminal = Terminal()
            val c = ModelSwitchController(store, borrowable = { false }, pendingPrompt = { null },
                sessionEvidence = productionEvidence)
            runBlocking {
                c.step(terminal.exec, taskKey, "rt-1", "win", ConversationModelChange("deepseek-stub-v3", null))
            }
            assertEquals(0, terminal.sends, "门不放行：一键不发")
            assertEquals(ModelChangeStatus.Pending, rawStatus(storeFile), "被门阻就是如实 Pending，不谎报任何进展")
        } finally {
            DesktopTranscriptMemory.drop(taskKey)
            file.parentFile.deleteRecursively()
            dir.deleteRecursively()
        }
    }
}
