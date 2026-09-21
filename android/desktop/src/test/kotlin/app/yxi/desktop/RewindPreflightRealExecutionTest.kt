package app.yxi.desktop

import app.yxi.agent.ChatItem
import app.yxi.agent.Rewind
import app.yxi.agent.Transcript
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * 预检链路的**真实执行**实测（cc-yxi_pilot，基线 009fae1）：
 *
 * A. 真实 fixture（modelswitch/real-claude-transcript.jsonl，23 行真 CLI 转录）跑生产
 *    RewindTargets.inspectionScript / TranscriptBranchStart.script：
 *    - afec039：`/model`、`/effort` 的命令回执行（第 13/14/17/18 行）**没有 isMeta**，
 *      旧逻辑会当成真人轮次 —— 现按 8 类注入标签（含 local-command-stdout/command-name）
 *      一律不算人话、不可选为目标；
 *    - snapshot 快照仍须把这些回执行保留在链上，喂进 Transcript 后 model/effort 照常更新。
 *
 * B. Rewind.verifyCommand（5b2f5e3 起为真 Python 解析）在**真 bash + 真 python3** 里执行：
 *    - 带空格的合法 JSON 能过（旧 grep `"uuid":"x"` 子串匹配会误杀）；
 *    - 正文里嵌着假 uuid 文本不算命中（旧 grep 会误中）；
 *    - parentUuid 不对拒绝；size/mtime 快照变了报 stale；显式 sourceFile 优先于 cwd 推导。
 *
 * 零网络、零生产试跑、零付费；全部进程用例内清理。
 */
@EnabledOnOs(OS.LINUX) // verifyCommand 生成的是远端 bash 命令，本地真 bash 实测。
class RewindPreflightRealExecutionTest {

    private val first = "f1af5213-d099-4f69-a8a4-094ebe095bc9"      // fixture 第 5 行 "hi"
    private val modelStdout = "708bc40e-63f3-414d-b227-74dcaee6ccaf" // 第 14 行 /model 回执（无 isMeta）
    private val effortStdout = "fedd7ec9-1f48-4fa2-a107-aad66e4979c6" // 第 18 行 /effort 回执（无 isMeta）

    private fun tempCopyOfFixture(): String {
        val dir = Files.createTempDirectory("yxi-preflight").toFile().apply { deleteOnExit() }
        val f = dir.resolve("real-claude-transcript.jsonl")
        javaClass.classLoader.getResourceAsStream("modelswitch/real-claude-transcript.jsonl")!!
            .use { input -> Files.copy(input, f.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        f.deleteOnExit()
        return f.absolutePath
    }

    private class Run(val code: Int, val out: String, val err: String)

    private fun runPython(script: String, vararg args: String): Run {
        val stdout = Files.createTempFile("yxi-preflight", ".out").toFile()
        val stderr = Files.createTempFile("yxi-preflight", ".err").toFile()
        val python = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
        val p = ProcessBuilder(python, "-c", script, *args)
            .redirectOutput(stdout).redirectError(stderr).start()
        try {
            check(p.waitFor(30, TimeUnit.SECONDS)) { "脚本 30s 没跑完" }
            return Run(p.exitValue(), stdout.readText(), stderr.readText())
        } finally {
            if (p.isAlive) { p.destroyForcibly(); p.waitFor(5, TimeUnit.SECONDS) }
            stdout.delete(); stderr.delete()
        }
    }

    /** 真 bash 执行 verifyCommand 生成的命令串，返回 parseVerify 结果。 */
    private fun verifyInBash(cmd: String): String? {
        val p = ProcessBuilder("bash", "-c", cmd).redirectErrorStream(true).start()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "verify 命令 60s 没跑完")
        return Rewind.parseVerify(p.inputStream.readBytes().toString(Charsets.UTF_8))
    }

    private fun plan(anchor: String, target: String, sid: String = "4b1d8860-e7b2-4d5e-82eb-fc05a8996fac") =
        Rewind.Plan(sessionId = sid, anchorUuid = anchor, targetUuid = target, prompt = "redo")

    // ---------- A. 真实 fixture ----------

    @Test
    fun `真fixture首条人话later恰为1且parent为null`() {
        val ok = runPython(RewindTargets.inspectionScript, tempCopyOfFixture(), first)
        assertEquals(0, ok.code, ok.err)
        val r = JSONObject(ok.out)
        assertEquals(1, r.getInt("later"),
            "f1af5213 之后只有 hi3 是真人轮；/model、/effort 的命令回执（无 isMeta）不得计入")
        assertTrue(r.isNull("parent"), "首条 parent 为 null")
    }

    @Test
    fun `真fixture命令stdout无isMeta也不可作为回退目标`() {
        val reject = runPython(RewindTargets.inspectionScript, tempCopyOfFixture(), modelStdout)
        assertNotEquals(0, reject.code, "/model 的 local-command-stdout 行（无 isMeta）不是真人轮，不可选")
        assertTrue(reject.err.contains("Not a user message"), reject.err)
        // /effort 的回执同理
        val reject2 = runPython(RewindTargets.inspectionScript, tempCopyOfFixture(), effortStdout)
        assertNotEquals(0, reject2.code, "/effort 回执行同样不可选")
    }

    @Test
    fun `真fixture快照保留回执行且model_effort仍正确`() {
        val f = tempCopyOfFixture()
        val ok = runPython(TranscriptBranchStart.script, f, "400", "snapshot")
        assertEquals(0, ok.code, ok.err)
        val arr = JSONObject(ok.out).getJSONArray("lines")
        val lines = (0 until arr.length()).map { arr.getString(it) }
        assertTrue(lines.any { "\"$modelStdout\"" in it && "Set model to" in it }, "快照必须保留 /model 回执行")
        assertTrue(lines.any { "\"$effortStdout\"" in it && "Set effort level" in it }, "快照必须保留 /effort 回执行")
        // 按 ChatPane.load 的接法喂产品 parser：回执驱动 ctx 更新
        val parser = Transcript.Incremental()
        parser.add(lines.asSequence())
        assertEquals(listOf("hi", "hi3"), parser.snapshot().filterIsInstance<ChatItem.UserText>().map { it.text },
            "回执行不是用户气泡，两条真人输入各一次")
        assertEquals("deepseek-stub-v3", parser.ctx?.model, "快照里的回执照常驱动 model 更新")
        assertEquals("xhigh", parser.ctx?.effort, "快照里的回执照常驱动 effort 更新")
    }

    // ---------- B. verifyCommand 真 bash 实测 ----------

    private fun writeSession(body: String, sid: String =
        "4b1d8860-e7b2-4d5e-82eb-fc05a8996fac"): String {
        val dir = Files.createTempDirectory("yxi-verify").toFile().apply { deleteOnExit() }
        val f = dir.resolve("$sid.jsonl")
        f.writeText(body)
        f.deleteOnExit()
        return f.absolutePath
    }

    /** 带空格的合法 JSON 行（json.dumps 形态）—— 旧 grep 子串匹配的天敌。 */
    private fun spacedUser(id: String, parent: String?, text: String) =
        """{ "uuid": "$id", "parentUuid": ${parent?.let { "\"$it\"" } ?: "null"}, """ +
            """"type": "user", "message": { "role": "user", "content": "$text" } }""" + "\n"

    private val anchor = "11111111-2222-4333-8444-555555555555"
    private val target = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val other = "99999999-8888-4777-8666-555555555555"

    @Test
    fun `带空格的合法JSON通过`() {
        val f = writeSession(spacedUser(anchor, null, "first") + spacedUser(target, anchor, "second"))
        val out = verifyInBash(Rewind.verifyCommand("/any/cwd", plan(anchor, target), f))
        assertNull(out, "空格 JSON 是合法转录行，真 Python 解析必须通过: got=$out")
    }

    @Test
    fun `正文嵌套假uuid不算命中`() {
        // 旧行为：grep '"uuid":"<target>"' 会命中正文里的字面文本，把 no-target 误判成可回退。
        // 正文里的引号按 JSON 转义（\"），整行是合法 JSON、能被解析 —— uuid 取的是顶层字段。
        val fakeInBody = """see this {\"uuid\":\"$target\"} fragment"""
        val body = spacedUser(anchor, null, "first") + spacedUser(other, anchor, fakeInBody)
        val f = writeSession(body)
        assertEquals("no-target",
            verifyInBash(Rewind.verifyCommand("/any/cwd", plan(anchor, target), f)),
            "正文里的假 uuid 文本不得当成目标行")
    }

    @Test
    fun `parentUuid不对拒绝`() {
        val f = writeSession(
            spacedUser(anchor, null, "first") + spacedUser(target, other, "not a child"),
        )
        assertEquals("not-child",
            verifyInBash(Rewind.verifyCommand("/any/cwd", plan(anchor, target), f)))
    }

    @Test
    fun `size或mtime与预期不符报stale_一致则通过`() {
        val f = writeSession(spacedUser(anchor, null, "first") + spacedUser(target, anchor, "second"))
        val path = Path.of(f)
        val attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
        val ns = attrs.lastModifiedTime().toInstant().let { it.epochSecond * 1_000_000_000L + it.nano }
        val size = attrs.size()
        val base = Rewind.verifyCommand("/any/cwd", plan(anchor, target), f, size, ns.toString())
        assertNull(verifyInBash(base), "size/mtime 都一致时通过")
        assertEquals("stale",
            verifyInBash(Rewind.verifyCommand("/any/cwd", plan(anchor, target), f, size + 1, ns.toString())),
            "size 变了必须 stale")
        assertEquals("stale",
            verifyInBash(Rewind.verifyCommand("/any/cwd", plan(anchor, target), f, size, (ns + 1).toString())),
            "mtime 变了必须 stale")
    }

    @Test
    fun `显式sourceFile优先于cwd推导`() {
        val sid = "4b1d8860-e7b2-4d5e-82eb-fc05a8996fac"
        val f = writeSession(spacedUser(anchor, null, "first") + spacedUser(target, anchor, "second"), sid)
        val cmd = Rewind.verifyCommand("/nonexistent-cwd-zz", plan(anchor, target, sid), f)
        assertTrue("/nonexistent-cwd-zz" !in cmd, "显式 sourceFile 时 cwd 不应参与路径推导")
        assertNull(verifyInBash(cmd), "按显式文件核对通过")
        // 对照：不给 sourceFile 时走 cwd 推导 → 那条路径不存在 → missing-session
        assertEquals("missing-session",
            verifyInBash(Rewind.verifyCommand("/nonexistent-cwd-zz", plan(anchor, target, sid))),
            "cwd 推导路径不存在必须 missing-session，证明上一例真的没走推导")
    }
}
