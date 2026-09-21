package app.yxi.desktop

import app.yxi.agent.ChatItem
import app.yxi.agent.Transcript
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 真 python3 进程执行生产 TranscriptBranchStart.script 的 **snapshot** 模式（8910777 起，
 * 624fed9 修行序）：只回当前链最新 payload + 未出队 queue + 最新 mode，resume=最后完整换行。
 * ChatPane 用它 seed Entry(initialOffset=resume) 再 streamFrom(resume)，弃支字节不再过网。
 * 行序必须是祖先→leaf：重复祖先的最新 payload 物理上可能在 leaf 之后，按 offset 排序会让
 * 它像新 root，喂进 parser 就会清掉整条链（624fed9 回归钉）。
 */
class TranscriptBranchSnapshotInspectionTest {
    private class Builder {
        val sb = StringBuilder()
        val off = mutableMapOf<String, Long>()
        fun add(id: String, line: String) { off[id] = sb.length.toLong(); sb.append(line).append('\n') }
        fun offset(id: String): Long = off.getValue(id)
    }

    private class Run(val code: Int, val out: String, val err: String)

    private fun tempJsonl(body: String) =
        Files.createTempDirectory("yxi-snap").toFile().apply { deleteOnExit() }
            .resolve("session-x.jsonl")
            .apply { writeText(body).also { deleteOnExit() } }

    private fun runScript(path: String, count: String = "400"): Run {
        val stdout = Files.createTempFile("yxi-snap", ".out").toFile()
        val stderr = Files.createTempFile("yxi-snap", ".err").toFile()
        val python = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
        val p = ProcessBuilder(python, "-c", TranscriptBranchStart.script, path, count, "snapshot")
            .redirectOutput(stdout).redirectError(stderr).start()
        try {
            check(p.waitFor(15, TimeUnit.SECONDS)) { "snapshot 脚本超时" }
            return Run(p.exitValue(), stdout.readText(), stderr.readText())
        } finally {
            if (p.isAlive) { p.destroyForcibly(); p.waitFor(5, TimeUnit.SECONDS) }
            stdout.delete(); stderr.delete()
        }
    }

    private fun user(id: String, parent: String?, text: String = "t-$id"): String =
        JSONObject().put("uuid", id).put("parentUuid", parent ?: JSONObject.NULL)
            .put("type", "user")
            .put("message", JSONObject().put("role", "user").put("content", text)).toString()

    private fun assistant(id: String, parent: String?): String =
        JSONObject().put("uuid", id).put("parentUuid", parent)
            .put("type", "assistant")
            .put("message", JSONObject().put("role", "assistant")
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "a-$id"))))
            .toString()

    private var queueSeq = 0
    private fun queueOp(op: String, content: String = ""): String =
        JSONObject().put("uuid", "q-${op}-$queueSeq").also { queueSeq++ }
            .put("parentUuid", JSONObject.NULL)
            .put("type", "queue-operation").put("operation", op).put("content", content).toString()

    private fun linesOf(ok: Run): List<String> {
        assertEquals(0, ok.code, ok.err)
        val arr = JSONObject(ok.out).getJSONArray("lines")
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun usersOf(items: List<ChatItem>) =
        items.filterIsInstance<ChatItem.UserText>().map { it.text }

    @Test
    fun `450 message abandoned branch never appears in snapshot lines`() {
        val b = Builder()
        b.add("u1", user("u1", null))
        for (i in 2..450) b.add("u$i", user("u$i", "u${i - 1}"))
        b.add("u451", user("u451", "u2")) // 新回合挂回早期祖先：u3..u450 整条弃支不在当前链
        val f = tempJsonl(b.sb.toString())
        val ok = runScript(f.absolutePath)
        assertEquals(0, ok.code, ok.err)
        val snap = JSONObject(ok.out)
        val arr = snap.getJSONArray("lines")
        val lines = (0 until arr.length()).map { arr.getString(it) }
        assertEquals(3, lines.size, "当前链只有 u1→u2→u451 三节点")
        assertEquals(listOf("u1", "u2", "u451"), lines.map { JSONObject(it).getString("uuid") },
            "祖先在前、leaf 在后")
        for (stale in listOf("u100", "u300", "u450")) {
            assertTrue(lines.none { it.contains("\"$stale\"") }, "弃支 $stale 不得出现在 lines")
        }
        assertEquals(b.offset("u1"), snap.getLong("start"))
        assertEquals(b.sb.length.toLong(), snap.getLong("resume"), "全文件无残行：resume=文件末尾")
    }

    @Test
    fun `resume stops at the last complete newline before a partial tail`() {
        val b = Builder()
        b.add("u1", user("u1", null))
        b.add("u2", user("u2", "u1"))
        val ghost = """{"uuid":"ghost","parentUuid":"u2","type":"user","message":{"role":"user","content":"g"}}"""
        val f = tempJsonl(b.sb.toString() + ghost) // 故意无结尾换行
        val ok = runScript(f.absolutePath)
        assertEquals(0, ok.code, ok.err)
        val result = JSONObject(ok.out)
        assertEquals(b.sb.length.toLong(), result.getLong("resume"), "resume=最后完整换行处，不含残行")
        assertEquals(f.readBytes().size.toLong(), result.getLong("size"), "size 仍报全文件字节")
        assertEquals(2, result.getJSONArray("lines").length(), "残行不进 lines")
    }

    @Test
    fun `entry seeded at resume then fed following bytes loses and duplicates nothing`() {
        val b = Builder()
        b.add("u1", user("u1", null))
        b.add("a1", assistant("a1", "u1"))
        b.add("u2", user("u2", "u1"))
        val ghost = """{"uuid":"ghost","parentUuid":"u2","type":"user","message":{"role":"user","content":"g"}}"""
        val f = tempJsonl(b.sb.toString() + ghost)
        val ok = runScript(f.absolutePath)
        assertEquals(0, ok.code, ok.err)
        val snap = JSONObject(ok.out)
        val resume = snap.getLong("resume")
        val arr = snap.getJSONArray("lines")
        val seedLines = (0 until arr.length()).map { arr.getString(it) }
        // 产品接线（ChatPane.load）：resume 处 seed，append 字节 0
        val entry = DesktopTranscriptMemory.Entry(f.absolutePath, resume)
        val lease = entry.claim().first
        entry.append(lease, seedLines, 0)
        assertEquals(resume, entry.view.offset)
        assertEquals(listOf("t-u1", "t-u2"), usersOf(entry.view.items))
        // 模拟 streamFrom(resume)：残行补完落盘 + 新一行。真实链是线性的：u3 的 parent 是 ghost，
        // 不能写成 ghost 的兄弟（那会正确地触发分支切换把 ghost 当弃支清掉）。
        val tail = ghost + "\n" + user("u3", "ghost") + "\n"
        f.appendText(tail)
        val fed = tail.toByteArray(Charsets.UTF_8).size.toLong()
        entry.append(lease, listOf(ghost, user("u3", "ghost")), fed)
        assertEquals(resume + fed, entry.view.offset, "接续后偏移=resume+新字节，不丢不重")
        assertEquals(listOf("t-u1", "t-u2", "g", "t-u3"), usersOf(entry.view.items),
            "四条人话各恰好一次：无丢失、无重复")
    }

    @Test
    fun `queue operations keep fifo with duplicate contents across dequeue remove and popAll`() {
        val b = Builder()
        b.add("u1", user("u1", null))
        val qs = listOf(
            queueOp("enqueue", ""),                                  // 空 content 也占一个队位
            queueOp("enqueue", "AAA"), queueOp("enqueue", "AAA"),    // 重复内容两条
            queueOp("enqueue", "BBB"),
            queueOp("dequeue"),                                      // 弹队头 ""：空内容按 FIFO 出，占位成立 → AAA,AAA,BBB
            queueOp("popAll", "AAA"),                                // 带内容按内容删首个：重复只删一条 → AAA,BBB
            queueOp("remove", "AAA"),                                // remove 同为按内容删首个 → BBB
        )
        qs.forEach { b.sb.append(it).append('\n') }
        b.add("u2", user("u2", "u1"))
        val f = tempJsonl(b.sb.toString())
        val lines = linesOf(runScript(f.absolutePath))
        val queueLines = lines.filter { it.contains("\"queue-operation\"") }
        assertEquals(1, queueLines.size, "七条 queue 操作后只剩 BBB 一条在队")
        assertTrue(queueLines.single().contains("\"BBB\""), queueLines.single())
        assertEquals(listOf("u1", "u2"),
            lines.filter { !it.contains("\"queue-operation\"") }.map { JSONObject(it).getString("uuid") },
            "链行在前、queue 尾随")
    }

    @Test
    fun `repeated uuid serves the newest payload exactly once`() {
        val b = Builder()
        b.add("u1", user("u1", null))
        b.add("a1", assistant("a1", "u1"))
        b.add("u2", user("u2", "a1", "stale"))
        b.add("u2", user("u2", "a1", "updated")) // 流式重放：同 uuid 新偏移新内容
        val lines = linesOf(runScript(tempJsonl(b.sb.toString()).absolutePath))
        assertEquals(3, lines.size, "重放不产生第二行")
        val u2Line = lines.single { it.contains("\"u2\"") }
        assertTrue(u2Line.contains("updated") && !u2Line.contains("stale"), "用最新内容：$u2Line")
    }

    @Test
    fun `replayed ancestor at end of file is rendered first so parsing keeps the chain`() {
        val b = Builder()
        b.add("u1", user("u1", null, "t-u1-old"))
        b.add("a1", assistant("a1", "u1"))
        b.add("u2", user("u2", "a1"))
        // u1 在文件末尾重放（最新 payload 物理位置在 leaf 之后）
        b.add("u1", user("u1", null, "t-u1-new"))
        val lines = linesOf(runScript(tempJsonl(b.sb.toString()).absolutePath))
        assertEquals(listOf("u1", "a1", "u2"), lines.map { JSONObject(it).getString("uuid") },
            "按 parent 链序输出，不按物理 offset")
        assertTrue(lines[0].contains("t-u1-new"), "祖先行用最新 payload")
        // 喂产品 parser（boss 要求）：祖先→leaf 顺序不得触发新 root 清链
        val parser = Transcript.Incremental()
        parser.add(lines.asSequence())
        assertEquals(listOf("t-u1-new", "t-u2"), usersOf(parser.snapshot()),
            "链保持完整：若错按物理序，末尾的 u1 重放会像新 root 把其余清光")
        assertEquals(listOf("a-a1"), parser.snapshot().filterIsInstance<ChatItem.AssistantText>().map { it.markdown },
            "a1 不被清")
    }

    @Test
    fun `no linked chain falls back to tail via null start`() {
        // 无 parentUuid 字段的行（外来格式）不入 nodes；org.json 无序，不能靠字符串切除
        val bare = JSONObject().put("uuid", "x").put("type", "user")
            .put("message", JSONObject().put("role", "user").put("content", "t-x")).toString()
        val f = tempJsonl(bare + "\nnot-json-at-all\n")
        val ok = runScript(f.absolutePath)
        assertEquals(0, ok.code, ok.err)
        // JSONObject.opt 对 JSON null 返回 NULL 哨兵而非 Kotlin null，用 isNull 判
        assertTrue(JSONObject(ok.out).isNull("start"), "无链格式：start 必须为 null（Kotlin 侧回退 tailStart）")
    }
}
