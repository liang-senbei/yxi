package app.yxi.desktop

import org.json.JSONObject
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 用真 python3 进程执行生产 TranscriptBranchStart.script 本体（不复制第二份算法）：
 * 冷载入字节起点必须取自**当前 parent 链**最后 count 条消息，而不是废弃尾行（36882fe）。
 * 已知边界（如实声明，不做性能/分页验收）：区间是连续字节段，弃支的大段内容仍会被
 * 一起传输——那是记录在案的待优化项，这里不断言区间最小化。
 */
class TranscriptBranchStartInspectionTest {
    private class Builder {
        val sb = StringBuilder()
        val off = mutableMapOf<String, Long>()
        fun add(id: String, line: String) { off[id] = sb.length.toLong(); sb.append(line).append('\n') }
        fun offset(id: String): Long = off.getValue(id)
    }

    private class Run(val code: Int, val out: String, val err: String)

    private fun inspect(path: String, count: Int): Run {
        val p = ProcessBuilder("python3", "-c", TranscriptBranchStart.script, path, count.toString()).start()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        check(p.waitFor(15, TimeUnit.SECONDS)) { "start 脚本超时" }
        p.destroyForcibly()
        return Run(p.exitValue(), out, err)
    }

    private fun user(id: String, parent: String?, text: String = "t-$id"): String =
        JSONObject().put("uuid", id).put("parentUuid", parent ?: JSONObject.NULL)
            .put("type", "user")
            .put("message", JSONObject().put("role", "user").put("content", text)).toString()

    private fun assistant(id: String, parent: String?): String =
        JSONObject().put("uuid", id).put("parentUuid", parent)
            .put("type", "assistant")
            .put("message", JSONObject().put("role", "assistant")
                .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "a-$id"))))
            .toString()

    @Test fun `branch back to an early ancestor anchors the range before the abandoned tail`() {
        val b = Builder()
        b.add("u1", user("u1", null))
        for (i in 2..450) b.add("u$i", user("u$i", "u${i - 1}"))
        // 新回合挂回早期祖先 u2：锚点在窗口外很远，废弃尾支 u3..u450 都不在当前链
        b.add("u451", user("u451", "u2"))
        val dir = Files.createTempDirectory("yxi-tstart").toFile().apply { deleteOnExit() }
        val f = dir.resolve("session-x.jsonl").apply { writeText(b.sb.toString()).also { deleteOnExit() } }
        val ok = inspect(f.absolutePath, 400)
        assertEquals(0, ok.code, ok.err)
        val result = JSONObject(ok.out)
        assertEquals(b.sb.length.toLong(), result.getLong("size"))
        assertEquals(b.offset("u1"), result.getLong("start"),
            "起点必须覆盖链上锚点 u1，而不是按行数取尾（那会落在弃支中部）")
    }

    @Test fun `new explicit root starts after the old branch and carries none of it`() {
        val b = Builder()
        b.add("u1", user("u1", null))
        b.add("a1", assistant("a1", "u1"))
        b.add("u2", user("u2", "a1"))
        b.add("root", user("root", null))
        val dir = Files.createTempDirectory("yxi-tstart").toFile().apply { deleteOnExit() }
        val f = dir.resolve("session-x.jsonl").apply { writeText(b.sb.toString()).also { deleteOnExit() } }
        val ok = inspect(f.absolutePath, 400)
        assertEquals(0, ok.code, ok.err)
        val result = JSONObject(ok.out)
        assertEquals(b.offset("root"), result.getLong("start"), "新根起点必须从 root 行起，旧支字节不进区间")
    }

    @Test fun `normal chain truncates at the message count`() {
        val b = Builder()
        for (i in 1..30) {
            val id = "m$i"
            b.add(id, if (i % 2 == 1) user(id, if (i == 1) null else "m${i - 1}") else assistant(id, "m${i - 1}"))
        }
        val dir = Files.createTempDirectory("yxi-tstart").toFile().apply { deleteOnExit() }
        val f = dir.resolve("session-x.jsonl").apply { writeText(b.sb.toString()).also { deleteOnExit() } }
        val ok = inspect(f.absolutePath, 10)
        assertEquals(0, ok.code, ok.err)
        val result = JSONObject(ok.out)
        assertEquals(b.offset("m21"), result.getLong("start"),
            "30 条链取 10 条：user/assistant 都计数，起点 = 倒数第 10 条 m21 行首")
    }

    @Test fun `repeated uuid update neither moves the leaf nor the first offset`() {
        val b = Builder()
        b.add("u1", user("u1", null))
        b.add("u2", user("u2", "u1"))
        b.add("u3", user("u3", "u2"))
        // u1 的流式重放：同 uuid、新偏移。leaf 须仍是 u3，u1 偏移须仍是首次出现处
        b.add("u1", user("u1", null, "t-u1-updated"))
        val dir = Files.createTempDirectory("yxi-tstart").toFile().apply { deleteOnExit() }
        val f = dir.resolve("session-x.jsonl").apply { writeText(b.sb.toString()).also { deleteOnExit() } }
        val ok = inspect(f.absolutePath, 2)
        assertEquals(0, ok.code, ok.err)
        val result = JSONObject(ok.out)
        assertEquals(b.offset("u2"), result.getLong("start"),
            "count=2 应取 u2..u3：若 leaf 被重放行夺走或偏移被改，起点会错成 u1 的新旧两处之一")
    }

    @Test fun `sidechain lines are never selected as the leaf`() {
        val b = Builder()
        b.add("u1", user("u1", null))
        b.add("a1", assistant("a1", "u1"))
        val side = JSONObject().put("uuid", "s1").put("parentUuid", "a1")
            .put("type", "user").put("isSidechain", true)
            .put("message", JSONObject().put("role", "user").put("content", "side")).toString()
        b.add("s1", side)
        val dir = Files.createTempDirectory("yxi-tstart").toFile().apply { deleteOnExit() }
        val f = dir.resolve("session-x.jsonl").apply { writeText(b.sb.toString()).also { deleteOnExit() } }
        val ok = inspect(f.absolutePath, 10)
        assertEquals(0, ok.code, ok.err)
        val result = JSONObject(ok.out)
        assertEquals(b.offset("u1"), result.getLong("start"),
            "sidechain 行不入选：leaf 仍是 a1，起点含 u1 而不是 sidechain 行首")
    }

    @Test fun `incomplete trailing line is ignored without truncating history`() {
        val b = Builder()
        b.add("u1", user("u1", null))
        b.add("u2", user("u2", "u1"))
        val ghost = """{"uuid":"ghost","parentUuid":"u2","type":"user","message":{"role":"user","content":"g"}}"""
        val dir = Files.createTempDirectory("yxi-tstart").toFile().apply { deleteOnExit() }
        val f = dir.resolve("session-x.jsonl").apply { writeText(b.sb.toString() + ghost).also { deleteOnExit() } }
        val ok = inspect(f.absolutePath, 10)
        assertEquals(0, ok.code, ok.err)
        val result = JSONObject(ok.out)
        assertEquals(b.offset("u1"), result.getLong("start"), "无换行的尾部残行整行忽略")
        assertEquals(b.sb.length + ghost.length.toLong(), result.getLong("size"), "size 仍报全文件字节")
    }
}
