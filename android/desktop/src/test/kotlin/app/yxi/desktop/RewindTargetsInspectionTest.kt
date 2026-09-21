package app.yxi.desktop

import org.json.JSONObject
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 用真 python3 进程执行生产 inspectionScript 本体（不复制第二份算法），
 * 隔离临时 JSONL，断言 rewind 目标勘察语义（boss 2026-09-22 点名六例）：
 * 工具结果不计用户轮、isMeta 不计、旧旁支拒绝、首条 parent null、末行不完整忽略、
 * 选中项 parent/size 字段与真实文件一致。零网络、零真实凭据、零付费 CLI。
 */
class RewindTargetsInspectionTest {
    private class Run(val code: Int, val out: String, val err: String)

    private fun inspect(path: String, target: String): Run {
        val stdout = Files.createTempFile("yxi-inspection", ".out").toFile()
        val stderr = Files.createTempFile("yxi-inspection", ".err").toFile()
        val python = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
        val p = ProcessBuilder(python, "-c", RewindTargets.inspectionScript, path, target)
            .redirectOutput(stdout).redirectError(stderr).start()
        try {
            check(p.waitFor(15, TimeUnit.SECONDS)) { "inspection 超时" }
            return Run(p.exitValue(), stdout.readText(), stderr.readText())
        } finally {
            if (p.isAlive) { p.destroyForcibly(); p.waitFor(5, TimeUnit.SECONDS) }
            stdout.delete(); stderr.delete()
        }
    }

    private fun user(id: String, parent: String?, text: String, isMeta: Boolean = false): String =
        JSONObject().put("uuid", id).put("parentUuid", parent ?: JSONObject.NULL)
            .put("type", "user").apply { if (isMeta) put("isMeta", true) }
            .put("message", JSONObject().put("role", "user").put("content", text)).toString() + "\n"

    private fun assistant(id: String, parent: String?, text: String): String =
        JSONObject().put("uuid", id).put("parentUuid", parent)
            .put("type", "assistant")
            .put("message", JSONObject().put("role", "assistant")
                .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", text))))
            .toString() + "\n"

    private fun toolResult(id: String, parent: String?): String =
        JSONObject().put("uuid", id).put("parentUuid", parent)
            .put("type", "user")
            .put("message", JSONObject().put("role", "user")
                .put("content", org.json.JSONArray().put(JSONObject().put("type", "tool_result")
                    .put("tool_use_id", "call-1").put("content", "ok"))))
            .toString() + "\n"

    private fun tempFile(body: String): String {
        // 固定文件名 session-x.jsonl：sessionId 断言用（脚本取 basename 去 .jsonl）
        val dir = Files.createTempDirectory("yxi-inspect").toFile().apply { deleteOnExit() }
        val f = dir.resolve("session-x.jsonl")
        f.writeText(body)
        f.deleteOnExit()
        return f.absolutePath
    }

    @Test fun `tool_result turns never count as later user messages and are rejected as targets`() {
        val path = tempFile(user("u1", null, "first") + assistant("a1", "u1", "answer") +
            toolResult("tr1", "a1") + user("u2", "tr1", "second"))
        // 叶子 u2 → tr1 → a1 → u1：人话轮只有 u2，tr1 不计
        val ok = inspect(path, "u1")
        assertEquals(0, ok.code, ok.err)
        assertEquals(1, JSONObject(ok.out).getInt("later"), "tool_result 轮不得计入用户轮（47bb9ec）")
        // tool_result 行本身不能被选为回退目标
        val reject = inspect(path, "tr1")
        assertNotEquals(0, reject.code, "tool_result 轮不是人话，不能作为回退目标")
        assertTrue(reject.err.contains("Not a user message"), reject.err)
    }

    @Test fun `isMeta receipt lines neither count nor qualify as targets`() {
        val path = tempFile(user("u1", null, "first") +
            user("m1", "u1", "<local-command-stdout>Set effort level to xhigh", isMeta = true) +
            user("u2", "m1", "second"))
        val ok = inspect(path, "u1")
        assertEquals(0, ok.code, ok.err)
        assertEquals(1, JSONObject(ok.out).getInt("later"), "isMeta 回实行不得计入用户轮")
        val reject = inspect(path, "m1")
        assertNotEquals(0, reject.code, "isMeta 行不能作为回退目标")
        assertTrue(reject.err.contains("Not a user message"), reject.err)
    }

    @Test fun `target on an abandoned side branch is refused not accepted`() {
        // u3 从 u1 重新开分支后，旧支 u2/a2 不在当前链上
        val path = tempFile(user("u1", null, "first") + assistant("a1", "u1", "answer") +
            user("u2", "a1", "old second") + assistant("a2", "u2", "old answer") +
            user("u3", "u1", "new second"))
        val reject = inspect(path, "u2")
        assertNotEquals(0, reject.code, "旧旁支上的消息必须拒绝，不得静默当成当前链目标")
        assertTrue(reject.err.contains("not on current branch"), reject.err)
        // 当前链上的 u3 照常可勘察：之后没有人话轮
        val ok = inspect(path, "u3")
        assertEquals(0, ok.code, ok.err)
        assertEquals(0, JSONObject(ok.out).getInt("later"))
        assertEquals("u1", JSONObject(ok.out).getString("parent"))
    }

    @Test fun `first message with null parent is selectable and reports null parent`() {
        val path = tempFile(user("u1", null, "first") + assistant("a1", "u1", "answer"))
        val ok = inspect(path, "u1")
        assertEquals(0, ok.code, ok.err)
        val result = JSONObject(ok.out)
        assertTrue(result.isNull("parent"), "首条消息 parent 必须是 null 而不是字符串")
        assertEquals(0, result.getInt("later"), "之后没有别的人话轮（assistant 不计）")
    }

    @Test fun `incomplete trailing line without newline is ignored not counted`() {
        // ghost 行 JSON 完整但没有结尾换行：readline 不以 \n 结尾即 break，整行忽略
        val complete = user("u1", null, "first") + assistant("a1", "u1", "answer") + user("u2", "a1", "second")
        val ghost = """{"uuid":"ghost","parentUuid":"u2","type":"user","message":{"role":"user","content":"ghost"}}"""
        val dir = Files.createTempDirectory("yxi-inspect").toFile().apply { deleteOnExit() }
        val f = dir.resolve("session-x.jsonl")
        f.writeText(complete + ghost) // 故意不写末尾 \n
        f.deleteOnExit()
        val ok = inspect(f.absolutePath, "u1")
        assertEquals(0, ok.code, ok.err)
        val result = JSONObject(ok.out)
        assertEquals(1, result.getInt("later"), "无结尾换行的 ghost 行必须整行忽略")
        assertEquals(complete.length + ghost.length, result.getLong("size").toInt(),
            "size 报告整个文件字节数（含未完成尾部）")
    }

    @Test fun `selected target reports the real parent size sessionId and mtime`() {
        val path = tempFile(user("u1", null, "first") + assistant("a1", "u1", "answer") +
            user("u2", "a1", "second"))
        val file = java.io.File(path)
        val ok = inspect(path, "u2")
        assertEquals(0, ok.code, ok.err)
        val result = JSONObject(ok.out)
        assertEquals("a1", result.getString("parent"), "parent 必须是真实 parentUuid")
        assertEquals(file.length(), result.getLong("size"), "size 必须是真实字节数")
        assertEquals("session-x", result.getString("sessionId"), "sessionId 取文件名去 .jsonl")
        val ns = java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
            .lastModifiedTime().toInstant().let { it.epochSecond * 1_000_000_000L + it.nano }
        assertEquals(ns.toString(), result.getString("modifiedNs"), "modifiedNs 必须是真实 mtime_ns")
        assertEquals(0, result.getInt("later"), "目标是叶子时 later 为 0")
    }
}
