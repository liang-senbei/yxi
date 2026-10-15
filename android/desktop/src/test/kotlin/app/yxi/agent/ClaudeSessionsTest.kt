package app.yxi.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M4s：服务器上 Yxi 之外起的 Claude Code 会话 —— 输出解析（[ClaudeSessions.parse]）、去重和标活（[ClaudeSessions.visible]），
 * 以及那段 Python 本身：本机有 python3 时在临时目录里造一个假的 HOME 真跑一遍，结果和 Kotlin 这边的预期逐项对（没有 python3 就跳过）。
 */
class ClaudeSessionsTest {
    private val a = "11111111-1111-4111-8111-111111111111"
    private val b = "22222222-2222-4222-8222-222222222222"
    private val c = "33333333-3333-4333-8333-333333333333"
    private val d = "44444444-4444-4444-8444-444444444444"
    private val home = "/home/dev"

    private fun item(id: String, cwd: String = "/srv/web", dir: String = "-srv-web", title: String = "标题 $id", last: Long = 1_000, entry: String = "cli") =
        JSONObject().put("id", id).put("path", "$home/.claude/projects/$dir/$id.jsonl").put("cwd", cwd).put("title", title).put("last", last)
            .put("entrypoint", entry).put("branch", "main").put("messages", 12).put("exact", true).put("bytes", 4096)
    private fun proc(pid: Long, id: String, live: Boolean, tmux: String = "", resumed: String = "", status: String = "idle", entry: String = "cli") =
        JSONObject().put("pid", pid).put("id", id).put("live", live).put("tmux", tmux).put("resumed", resumed).put("status", status).put("entrypoint", entry)
    private fun output(history: List<JSONObject>, registry: List<JSONObject> = emptyList(), extra: JSONObject.() -> Unit = {}) =
        "some motd\n" + ClaudeSessions.MARKER + JSONObject().put("v", 1).put("home", home).put("history", JSONArray(history))
            .put("registry", JSONArray(registry)).put("scanned", history.size).put("truncated", false).put("ms", 7).apply(extra) + "\n"

    @Test fun `parses entries and processes behind the marker`() {
        val snap = ClaudeSessions.parse(output(listOf(item(a, title = "修复\u0007登录页", entry = "sdk-cli")), listOf(proc(42, b, live = true, tmux = "cc-web", status = "busy"))) {
            put("truncated", true); put("scanned", 230)
        })
        val e = snap.history.single()
        assertEquals(a, e.id); assertEquals("/srv/web", e.cwd); assertEquals("修复登录页", e.title)
        assertEquals("sdk-cli", e.entrypoint); assertEquals("main", e.branch); assertEquals(12, e.messages); assertEquals(1_000, e.lastActivity)
        assertEquals("$home/.claude/projects/-srv-web/$a.jsonl", e.path)
        val p = snap.registry.single()
        assertEquals(42, p.pid); assertEquals(b, p.sessionId); assertTrue(p.live); assertEquals("cc-web", p.tmux); assertEquals("busy", p.status)
        assertTrue(snap.truncated); assertEquals(230, snap.scanned); assertEquals(7, snap.millis)
    }

    @Test fun `drops entries whose path or directory could point elsewhere`() {
        val bad = listOf(
            item("not-a-uuid"),
            item(b).put("path", "$home/.claude/projects/../../etc/$b.jsonl"),
            item(c).put("path", "/tmp/$c.jsonl"),
            item(d, cwd = "relative/dir"),
            item("55555555-5555-4555-8555-555555555555", cwd = "/srv/x\ny"),
            item("66666666-6666-4666-8666-666666666666").put("path", "$home/.claude/projects/a/b/66666666-6666-4666-8666-666666666666.jsonl"),
            item("77777777-7777-4777-8777-777777777777").put("path", "$home/.claude/projects/-srv-web/$a.jsonl"),
        )
        val snap = ClaudeSessions.parse(output(bad + item(a, last = 5) + item(a, last = 9), listOf(proc(0, a, true), proc(9, "nope", true))))
        assertEquals(listOf(a), snap.history.map { it.id })
        assertEquals(5, snap.history.single().lastActivity, "同一编号出现两次留前面（脚本按修改时间排好的）那份")
        assertTrue(snap.registry.isEmpty())
    }

    @Test fun `incomplete output is an error, not an empty list`() {
        assertFailsWith<IllegalStateException> { ClaudeSessions.parse("") }
        assertFailsWith<IllegalStateException> { ClaudeSessions.parse(ClaudeSessions.MARKER + "{\"v\":1,\"home\":\"/h\",\"hist") }
        assertFailsWith<IllegalStateException> { ClaudeSessions.parse(ClaudeSessions.MARKER + "{\"v\":1}") }
        val e = assertFailsWith<IllegalStateException> { ClaudeSessions.parse(ClaudeSessions.MARKER + "{\"error\":\"python3\"}") }
        assertTrue("python3" in e.message.orEmpty())
        assertTrue(ClaudeSessions.parse(output(emptyList())).history.isEmpty())
    }

    @Test fun `sessions running in tmux sessions Yxi already lists are dropped`() {
        val snap = ClaudeSessions.parse(output(
            listOf(item(a, last = 10), item(b, last = 30), item(c, last = 20), item(d, last = 40)),
            listOf(
                proc(1, a, live = true, tmux = "cc-web"),               // Yxi 列着的 tmux 会话
                proc(2, "88888888-8888-4888-8888-888888888888", live = true, tmux = "cc-api", resumed = b),   // 老版本 resume 换了编号：原编号也算
                proc(3, c, live = false, tmux = "cc-old"),             // 登记过期，但 tmux 会话还在：宁可少列
            )))
        val rows = ClaudeSessions.visible(snap, listOf("cc-web", "cc-api", "cc-old", "unrelated"))
        assertEquals(listOf(d), rows.map { it.entry.id })
        assertFalse(rows.single().live)
    }

    @Test fun `live registry entries outside Yxi mark rows read-only`() {
        val snap = ClaudeSessions.parse(output(
            listOf(item(a, last = 10), item(b, last = 30), item(c, last = 20), item(d, last = 5)),
            listOf(
                proc(1, a, live = true, status = "waiting", entry = "sdk-cli"),
                proc(2, "88888888-8888-4888-8888-888888888888", live = true, resumed = b, status = "busy"),
                proc(3, c, live = false),                                  // 进程不在了：只是历史
                proc(4, d, live = true, tmux = "elsewhere"),               // 在 Yxi 没列的 tmux 会话里跑：照样只读
            )))
        val rows = ClaudeSessions.visible(snap, listOf("cc-web"))
        assertEquals(listOf(b, c, a, d), rows.map { it.entry.id }, "新的在前")
        val by = rows.associateBy { it.entry.id }
        assertTrue(by.getValue(a).live); assertEquals(SessionState.NeedsYou, by.getValue(a).state)
        assertTrue(by.getValue(b).live); assertEquals(SessionState.Working, by.getValue(b).state)
        assertFalse(by.getValue(c).live); assertEquals(SessionState.Idle, by.getValue(c).state); assertNull(by.getValue(c).process)
        assertTrue(by.getValue(d).live)
    }

    @Test fun `a session continued in Yxi drops out before its registry entry appears`() {
        val snap = ClaudeSessions.parse(output(listOf(item(a), item(b))))
        assertEquals(listOf(b), ClaudeSessions.visible(snap, listOf("cc-web-resumed"), mapOf(a to "cc-web-resumed")).map { it.entry.id })
        // 那个 tmux 会话已经结束：回到历史里
        assertEquals(setOf(a, b), ClaudeSessions.visible(snap, emptyList(), mapOf(a to "cc-web-resumed")).map { it.entry.id }.toSet())
    }

    @Test fun `origin tags`() {
        assertEquals("终端", ClaudeSessions.originOf("cli"))
        assertEquals("Claude App / SDK", ClaudeSessions.originOf("sdk-cli"))
        assertEquals("SDK", ClaudeSessions.originOf("sdk-ts"))
        assertEquals("VS Code", ClaudeSessions.originOf("claude-vscode"))
        assertEquals("Claude Code", ClaudeSessions.originOf(""))
        val row = ClaudeSessions.Row(ClaudeSessions.Entry(a, "/p", "/srv", "t", 0, entrypoint = ""), ClaudeSessions.Process(1, a, true, entrypoint = "sdk-cli"))
        assertEquals("Claude App / SDK", row.origin, "历史里没写 entrypoint 时用登记里的")
    }

    @Test fun `command is bounded and quotes the script`() {
        val cmd = ClaudeSessions.command(9999)
        assertTrue(cmd.contains("timeout ${ClaudeSessions.SECONDS} python3 -c '"), cmd.take(200))
        assertTrue(cmd.endsWith("' 500; fi"), "条数夹到 500：" + cmd.takeLast(40))
        assertTrue(cmd.startsWith("command -v python3"))
        assertFalse(ClaudeSessions.SCRIPT.contains(".key"), "脚本里不该出现 .key")
    }

    // ── 和服务器上那段 Python 对一遍 ──

    private fun python(): String? = listOf("python3").firstOrNull { exe ->
        runCatching { ProcessBuilder(exe, "--version").redirectErrorStream(true).start().let { it.waitFor(10, TimeUnit.SECONDS) && it.exitValue() == 0 } }.getOrDefault(false)
    }

    private fun write(file: File, rows: List<String>, mtime: Long) {
        file.parentFile.mkdirs(); file.writeText(rows.joinToString("\n") + "\n"); assertTrue(file.setLastModified(mtime))
    }
    private fun user(sid: String, cwd: String, text: Any, ts: String, extra: JSONObject.() -> Unit = {}) = JSONObject().put("type", "user").put("sessionId", sid)
        .put("cwd", cwd).put("entrypoint", "cli").put("gitBranch", "main").put("timestamp", ts).put("uuid", "u-" + ts).put("parentUuid", JSONObject.NULL)
        .put("message", JSONObject().put("role", "user").put("content", text)).apply(extra).toString()
    private fun assistant(sid: String, cwd: String, text: String, ts: String, branch: String = "main", entry: String = "cli") = JSONObject().put("type", "assistant")
        .put("sessionId", sid).put("cwd", cwd).put("entrypoint", entry).put("gitBranch", branch).put("timestamp", ts).put("uuid", "a-" + ts)
        .put("message", JSONObject().put("role", "assistant").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))).toString()

    @Test fun `python script matches the parser on a fake home`() {
        val exe = python()
        assumeTrue(exe != null, "本机没有 python3，跳过对拍")
        val root = Files.createTempDirectory("yxi-claude-sessions").toFile()
        try {
            val base = File(root, ".claude")
            val web = File(base, "projects/-srv-web")
            val api = File(base, "projects/-srv-api")
            val now = System.currentTimeMillis()
            // a：终端里起的，开头是 /clear 和系统提醒（不算标题），带时区偏移的时间戳
            write(File(web, "$a.jsonl"), listOf(
                JSONObject().put("type", "summary").put("summary", "旧摘要").toString(),
                user(a, "/srv/web", "<command-name>/clear</command-name>", "2026-10-15T08:00:00.000Z"),
                user(a, "/srv/web", JSONArray().put(JSONObject().put("type", "text").put("text", "<system-reminder>x</system-reminder>")), "2026-10-15T08:00:00.500Z") { put("isMeta", true) },
                user(a, "/srv/web", "修一下  登录页的\n报错提示", "2026-10-15T08:00:01.000Z"),
                assistant(a, "/srv/web", "好的", "2026-10-15T16:00:05.250+08:00", branch = "feature/x"),
            ), now - 1_000)
            // b：Claude App（sdk-cli），/rename 起过名字；文件很大，只读头尾：消息数是估的，最后活动取末尾
            val filler = (1..400).map { i -> assistant(b, "/srv/api", "x".repeat(600) + i, "2026-10-14T10:%02d:%02d.000Z".format(i / 60 % 60, i % 60), entry = "sdk-cli") }
            write(File(api, "$b.jsonl"), listOf(user(b, "/srv/api", "梳理接口错误码", "2026-10-14T09:59:00.000Z") { put("entrypoint", "sdk-cli") },
                JSONObject().put("type", "custom-title").put("customTitle", "接口错误码").put("sessionId", b).toString()) + filler +
                assistant(b, "/srv/api", "收尾", "2026-10-14T23:59:59.999Z", entry = "sdk-cli"), now - 2_000)
            // c：只有 lastPrompt 能当标题（开头没有人说的话，只有工具结果）
            write(File(web, "$c.jsonl"), listOf(
                user(c, "/srv/web", JSONArray().put(JSONObject().put("type", "tool_result").put("tool_use_id", "t").put("content", "ok")), "2026-10-13T00:00:00Z"),
                JSONObject().put("type", "last-prompt").put("lastPrompt", "  继续  上次的 ").put("sessionId", c).toString(),
            ), now - 3_000)
            // 不该出现的：子 agent（整份 isSidechain）、agent-*.jsonl、会话子目录里的、符号链接、没有 cwd 的、只有空行的
            write(File(web, "$d.jsonl"), listOf(user(d, "/srv/web", "sub", "2026-10-15T00:00:00Z") { put("isSidechain", true) }), now - 10_000)
            write(File(web, "agent-1234.jsonl"), listOf(user(a, "/srv/web", "sub", "2026-10-15T00:00:00Z")), now)
            write(File(web, "$a/subagents/agent-1.jsonl"), listOf(user(a, "/srv/web", "sub", "2026-10-15T00:00:00Z")), now)
            write(File(web, "99999999-9999-4999-8999-999999999999.jsonl"), listOf(JSONObject().put("type", "summary").put("summary", "s").toString()), now - 20_000)
            write(File(root, "elsewhere.jsonl"), listOf(user(a, "/srv/web", "x", "2026-10-15T00:00:00Z")), now)
            Files.createSymbolicLink(File(web, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.jsonl").toPath(), File(root, "elsewhere.jsonl").toPath())
            // 登记：这个 JVM 自己（活着，tmux 里）、一个不存在的 pid（过期）、一个超大的、一个坏的；旁边放一个读不了的 .key（脚本碰都不该碰）
            val sessions = File(base, "sessions").apply { mkdirs() }
            val me = ProcessHandle.current().pid()
            File(sessions, "$me.json").writeText(JSONObject().put("pid", me).put("sessionId", a).put("cwd", "/srv/web").put("entrypoint", "cli")
                .put("tmux", "cc-web:@3.%7").put("status", "busy").put("startedAt", now).toString())
            File(sessions, "4194000.json").writeText(JSONObject().put("pid", 4_194_000).put("sessionId", c).put("startedAt", now).toString())
            File(sessions, "5.json").writeText(JSONObject().put("pid", 5).put("sessionId", b).put("pad", "x".repeat(70_000)).toString())
            File(sessions, "6.json").writeText("{not json")
            val key = File(sessions, "$me.abcdef.key").apply { writeText("SECRET-KEY-MATERIAL"); setReadable(false, false) }

            fun run(limit: Int): String {
                val pb = ProcessBuilder(exe, "-I", "-c", ClaudeSessions.SCRIPT, limit.toString()).redirectErrorStream(true)
                pb.environment()["HOME"] = root.absolutePath
                val p = pb.start()
                val out = p.inputStream.readBytes().decodeToString()
                assertTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0, out)
                assertFalse("SECRET" in out || ".key" in out, "输出里出现了 .key 的名字或内容")
                return out
            }
            val raw = run(200)
            val snap = ClaudeSessions.parse(raw)
            assertTrue(snap.history.none { it.id == d || it.id.startsWith("9999") || it.id.startsWith("aaaa") }, snap.history.map { it.id }.toString())
            assertEquals(listOf(a, b, c), snap.history.map { it.id }, "按修改时间新的在前")
            val home = root.toPath().toRealPath().toString().let { real -> if (raw.contains(real)) real else root.absolutePath }
            val ea = snap.history[0]
            assertEquals("$home/.claude/projects/-srv-web/$a.jsonl", ea.path)
            assertEquals("修一下 登录页的 报错提示", ea.title)
            assertEquals("/srv/web", ea.cwd); assertEquals("cli", ea.entrypoint); assertEquals("feature/x", ea.branch)
            assertEquals(java.time.Instant.parse("2026-10-15T08:00:05.250Z").toEpochMilli(), ea.lastActivity, "带 +08:00 的换成 UTC")
            assertEquals(3, ea.messages, "summary 和 isMeta 不算"); assertTrue(ea.exact)
            val eb = snap.history[1]
            assertEquals("接口错误码", eb.title, "/rename 的名字优先")
            assertEquals("sdk-cli", eb.entrypoint); assertFalse(eb.exact)
            assertTrue(eb.messages in 300..520, "估的消息数离谱：${eb.messages}")
            assertEquals(java.time.Instant.parse("2026-10-14T23:59:59.999Z").toEpochMilli(), eb.lastActivity)
            assertEquals("继续 上次的", snap.history[2].title)
            assertEquals(2, snap.registry.size, snap.registry.toString())
            val mine = snap.registry.single { it.pid == me }
            assertTrue(mine.live); assertEquals("cc-web", mine.tmux); assertEquals("busy", mine.status)
            assertFalse(snap.registry.single { it.pid == 4_194_000L }.live)
            assertEquals(listOf(b, c), ClaudeSessions.visible(snap, listOf("cc-web")).map { it.entry.id }, "a 在 Yxi 列着的 tmux 会话里")
            // 条数上限：只列最新的，标记截断
            val two = ClaudeSessions.parse(run(2))
            assertEquals(listOf(a, b), two.history.map { it.id }); assertTrue(two.truncated)
            key.setReadable(true, true)
        } finally { root.deleteRecursively() }
    }
}
