package app.yxi.agent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RewindLiveVerification 针对性小测：解析/校验纯单测 + **真 bash、真 python3、假 tmux、真进程树**
 * 的隔离 fixture 执行（临时 HOME，不写真实登记、不碰生产 tmux、零付费、零模型调用）。
 * 链核对的对抗样本（侧链/半行/环/重复uuid）全部走真 bash 执行生成命令，不是改断言迎合代码。
 * 无 bash/python3 的机器自动跳过 fixture 段。
 */
class RewindLiveVerificationTest {

    private val sid = "11111111-1111-1111-1111-111111111111"
    private val anchor = "22222222-2222-2222-2222-222222222222"
    private val target = "33333333-3333-3333-3333-333333333333"
    private val rootU = "44444444-4444-4444-4444-444444444444"
    private val oldTail = "55555555-5555-5555-5555-555555555555"
    private val newLeaf = "66666666-6666-6666-6666-666666666666"

    private fun unixTools(): Boolean = runCatching {
        ProcessBuilder("bash", "-c", "command -v python3 >/dev/null").start().waitFor() == 0
    }.getOrDefault(false)

    // ---------- 解析 / 校验（纯单测） ----------

    @Test
    fun `parse认ok带pid与各代号`() {
        assertEquals(RewindLiveVerification.Result.Ok("123"),
            RewindLiveVerification.parse("noise\n__YXI_REWIND_LIVE__:ok pid=123\n"))
        assertEquals(RewindLiveVerification.Result.Failed("sid-mismatch"),
            RewindLiveVerification.parse("__YXI_REWIND_LIVE__:sid-mismatch"))
        assertEquals(RewindLiveVerification.Result.Failed("not-ready"),
            RewindLiveVerification.parse("__YXI_REWIND_LIVE__:not-ready"))
        assertEquals(RewindLiveVerification.Result.Failed("chain-anchor-missing"),
            RewindLiveVerification.parse("__YXI_REWIND_LIVE__:chain-anchor-missing"))
        assertEquals(RewindLiveVerification.Result.Failed("chain-cycle"),
            RewindLiveVerification.parse("__YXI_REWIND_LIVE__:chain-cycle"))
        assertEquals(RewindLiveVerification.Result.Failed("chain-parent-missing"),
            RewindLiveVerification.parse("__YXI_REWIND_LIVE__:chain-parent-missing"))
        // 最后一行赢（重试场景里旧输出可能还在缓冲）
        assertEquals(RewindLiveVerification.Result.Ok("7"),
            RewindLiveVerification.parse("__YXI_REWIND_LIVE__:no-reg\n__YXI_REWIND_LIVE__:ok pid=7"))
        assertEquals(RewindLiveVerification.Result.Failed("noresult"), RewindLiveVerification.parse(""))
        assertEquals(RewindLiveVerification.Result.Failed("noresult"), RewindLiveVerification.parse("garbage"))
        // ok 后面不是数字 pid = 不认
        assertEquals(RewindLiveVerification.Result.Failed("ok pid=abc"),
            RewindLiveVerification.parse("__YXI_REWIND_LIVE__:ok pid=abc"))
    }

    private fun vq(
        sessionName: String = "cc-demo",
        runtimeId: String = "261740:\$13:1789447091",
        paneId: String = "%13",
        exe: String = "/usr/local/bin/claude",
        oldPid: String = "261740",
        sessionId: String = sid,
        transcriptPath: String = "/home/u/.claude/projects/x/1.jsonl",
    ) = RewindLiveVerification.Query(
        sessionName, runtimeId, paneId, exe, oldPid, sessionId,
        anchor, target, transcriptPath, 1000.0,
    )

    @Test
    fun `validate挡坏形态`() {
        assertEquals(null, RewindLiveVerification.validate(vq()))
        assertEquals("sid", RewindLiveVerification.validate(vq(sessionId = "not-a-uuid")))
        assertEquals("runtime-id", RewindLiveVerification.validate(vq(runtimeId = "weird")))
        assertEquals("pane-id", RewindLiveVerification.validate(vq(paneId = "13")))
        assertEquals("exe-path", RewindLiveVerification.validate(vq(exe = "/opt/x y/claude")))
        assertEquals("old-pid", RewindLiveVerification.validate(vq(oldPid = "x1")))
        // 相对路径拒绝（必须绝对路径，否则落到不可知的 cwd 上）
        assertEquals("transcript-path", RewindLiveVerification.validate(vq(transcriptPath = "rel/path.jsonl")))
        assertEquals("transcript-path", RewindLiveVerification.validate(vq(transcriptPath = ".claude/a.jsonl")))
        assertEquals("session-name", RewindLiveVerification.validate(vq(sessionName = "cc-a; rm -rf")))
    }

    @Test
    fun `command拒绝未过validate的输入`() {
        assertFailsWith<IllegalArgumentException> {
            RewindLiveVerification.command(vq(runtimeId = "bad"))
        }
    }

    @Test
    fun `command把python整体包进单引号`() {
        // root 审查指出的缺陷 #1 的回归锚：q() 只转义不包引号，漏了外引号多行 python 会被 shell 裂碎
        val cmd = RewindLiveVerification.command(vq())
        assertTrue(cmd.contains("python3 -c '"), cmd.take(200))
        assertTrue(!cmd.contains("python3 -c i"), cmd.take(200))
        // 生成的命令必须真的能跑出 TAG（真 bash 执行到 no-reg 也算执行成功；裂碎则无 TAG）
        if (!unixTools()) return
        val out = ProcessBuilder("/bin/bash", "-c", cmd)
            .redirectErrorStream(true).start().inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(out.contains(RewindLiveVerification.TAG), out)
    }

    // ---------- 隔离 fixture（真 bash + python3 + 假 tmux + 真进程树 + 临时 HOME） ----------

    private val runtime = "261740:\$13:1789447091"

    /** 假 tmux：按 fmt 分发应答（全部走 env 变量注入），fmt 是第 5 个位置参数。 */
    private fun writeFakeTmux(bin: Path) {
        val d = '$'
        val sh = """
            #!/usr/bin/env bash
            fmt="${d}5"
            case "${d}fmt" in
              *session_created*) printf '%s\n' "${d}FAKE_RUNTIME";;
              *session_name*:*pane_id*) printf '%s\n' "${d}FAKE_FULL";;
              *pane_pid*) printf '%s\n' "${d}FAKE_PANE_PID";;
              *pane_id*) printf '%s\n' "${d}FAKE_PANE";;
              *) exit 1;;
            esac
        """.trimIndent()
        val f = Files.createFile(bin.resolve("tmux"))
        Files.write(f, sh.toByteArray())
        f.toFile().setExecutable(true)
    }

    /** 起「假 claude」：bash 脚本 exec sleep，进程活着、exe 可读、父进程=测试 JVM。返回 (进程, exe 路径)。 */
    private fun spawnFakeClaude(): Pair<Process, String> {
        val script = Files.createTempFile("fake-claude", ".sh")
        Files.write(script, "exec sleep 60\n".toByteArray())
        val p = ProcessBuilder("/bin/bash", script.toString()).start()
        val exe = (1..50).firstNotNullOf {
            Thread.sleep(40)
            runCatching { osReadlink(p.pid()) }.getOrNull()
        }
        return p to exe
    }

    private fun osReadlink(pid: Long): String =
        ProcessBuilder("bash", "-c", "readlink /proc/$pid/exe").start().inputStream.bufferedReader().readText().trim()

    private fun writeJson(home: Path, name: String, pid: Long, session: String, suMs: Long, status: String = "idle") {
        val dir = home.resolve(".claude/sessions")
        Files.createDirectories(dir)
        val o = """{"tmux":"cc-demo:@13.%13","pid":$pid,"sessionId":"$session","status":"$status","statusUpdatedAt":$suMs,"kind":"interactive"}"""
        Files.write(dir.resolve(name), o.toByteArray())
    }

    /** 转录行：uuid + 可选 parentUuid + type + 可选 isSidechain（链核对对抗样本用）。 */
    private fun line(u: String, parent: String?, type: String = "user", sidechain: Boolean = false): String {
        val side = if (sidechain) ",\"isSidechain\":true" else ""
        return if (parent == null) """{"uuid":"$u","type":"$type"$side}"""
        else """{"uuid":"$u","parentUuid":"$parent","type":"$type"$side}"""
    }

    private fun writeTranscript(home: Path, lines: List<Pair<String, String?>>): Path =
        writeTranscriptBytes(home, lines.joinToString("") { (u, p) -> line(u, p) + "\n" })

    /** 原样写字节——半行/无换行尾的对抗样本要用这个，别让 helper 悄悄补 \n。 */
    private fun writeTranscriptBytes(home: Path, body: String): Path {
        val dir = home.resolve(".claude/projects/-tmp-demo")
        Files.createDirectories(dir)
        val f = dir.resolve("$sid.jsonl")
        Files.write(f, body.toByteArray())
        return f
    }

    private fun transcriptRewound(): List<Pair<String, String?>> = listOf(
        rootU to null,
        anchor to rootU,          // 保留锚点
        target to anchor,         // 被回退的目标（旧旁支还在文件里）
        oldTail to target,        // 旧尾巴
        newLeaf to anchor,        // 回退追加的新分支（最新叶子）
    )

    private fun query(home: Path, transcript: Path, exe: String, t0: Double) = RewindLiveVerification.Query(
        sessionName = "cc-demo", runtimeId = runtime, paneId = "%13",
        exe = exe, oldPid = "999999", sessionId = sid,
        anchorUuid = anchor, targetUuid = target,
        transcriptPath = transcript.toString(), notBeforeEpochSec = t0,
    )

    private fun run(cmd: String, home: Path, bin: Path, runtime: String, panePid: String): String {
        val pb = ProcessBuilder("/bin/bash", "-c", cmd)
        pb.environment()["HOME"] = home.toString()
        pb.environment()["PATH"] = "$bin:" + System.getenv("PATH")
        pb.environment()["FAKE_RUNTIME"] = runtime
        pb.environment()["FAKE_PANE"] = "%13"
        pb.environment()["FAKE_FULL"] = "cc-demo:@13.%13"
        pb.environment()["FAKE_PANE_PID"] = panePid
        val p = pb.start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        return out
    }

    private val panePid: String get() = ProcessHandle.current().pid().toString()

    @Test
    fun `fixture全链ok并跳过旧pid登记`() {
        if (!unixTools()) return
        val home = Files.createTempDirectory("rlv-home")
        val bin = Files.createTempDirectory("rlv-bin")
        writeFakeTmux(bin)
        val (proc, exe) = spawnFakeClaude()
        try {
            val now = System.currentTimeMillis()
            writeJson(home, "new.json", proc.pid(), sid, now)
            // 旧 pid 的登记更新——必须被 pid 排除（不是靠新旧排序撞运气）
            writeJson(home, "old.json", 999999, sid, now)
            home.resolve(".claude/sessions/old.json").toFile().setLastModified(now + 5000)
            val tr = writeTranscript(home, transcriptRewound())
            val out = run(
                RewindLiveVerification.command(query(home, tr, exe, (now - 60_000) / 1000.0), regTimeoutSec = 5),
                home, bin, runtime, panePid,
            )
            assertEquals(RewindLiveVerification.Result.Ok(proc.pid().toString()), RewindLiveVerification.parse(out), out)
        } finally {
            proc.destroy()
        }
    }

    @Test
    fun `fixture_sid不匹配立即失败`() {
        if (!unixTools()) return
        val home = Files.createTempDirectory("rlv-home")
        val bin = Files.createTempDirectory("rlv-bin")
        writeFakeTmux(bin)
        val (proc, exe) = spawnFakeClaude()
        try {
            writeJson(home, "new.json", proc.pid(), "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", System.currentTimeMillis())
            val tr = writeTranscript(home, transcriptRewound())
            val t0 = System.nanoTime()
            val out = run(
                RewindLiveVerification.command(query(home, tr, exe, (System.currentTimeMillis() - 60_000) / 1000.0), regTimeoutSec = 15),
                home, bin, runtime, panePid,
            )
            assertEquals(RewindLiveVerification.Result.Failed("sid-mismatch"), RewindLiveVerification.parse(out), out)
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 10_000, "硬代号必须立即收，不等 15s 超时")
        } finally {
            proc.destroy()
        }
    }

    @Test
    fun `fixture顶包identity`() {
        if (!unixTools()) return
        val home = Files.createTempDirectory("rlv-home")
        val bin = Files.createTempDirectory("rlv-bin")
        writeFakeTmux(bin)
        val (proc, exe) = spawnFakeClaude()
        try {
            writeJson(home, "new.json", proc.pid(), sid, System.currentTimeMillis())
            val tr = writeTranscript(home, transcriptRewound())
            val out = run(
                RewindLiveVerification.command(query(home, tr, exe, (System.currentTimeMillis() - 60_000) / 1000.0), regTimeoutSec = 5),
                home, bin, "1:${'$'}999:1", panePid,   // runtime 与期望不符 = 同名会话被重建
            )
            assertEquals(RewindLiveVerification.Result.Failed("identity"), RewindLiveVerification.parse(out), out)
        } finally {
            proc.destroy()
        }
    }

    @Test
    fun `fixture无登记与状态陈旧`() {
        if (!unixTools()) return
        val home = Files.createTempDirectory("rlv-home")
        val bin = Files.createTempDirectory("rlv-bin")
        writeFakeTmux(bin)
        val (proc, exe) = spawnFakeClaude()
        try {
            val tr = writeTranscript(home, transcriptRewound())
            val now = System.currentTimeMillis()
            // 空登记目录 → 到点 no-reg
            val out1 = run(
                RewindLiveVerification.command(query(home, tr, exe, (now - 60_000) / 1000.0), regTimeoutSec = 1),
                home, bin, runtime, panePid,
            )
            assertEquals(RewindLiveVerification.Result.Failed("no-reg"), RewindLiveVerification.parse(out1), out1)
            // 有登记但 statusUpdatedAt 早于投递时刻 → not-ready
            writeJson(home, "new.json", proc.pid(), sid, now - 600_000)
            val out2 = run(
                RewindLiveVerification.command(query(home, tr, exe, now / 1000.0), regTimeoutSec = 1),
                home, bin, runtime, panePid,
            )
            assertEquals(RewindLiveVerification.Result.Failed("not-ready"), RewindLiveVerification.parse(out2), out2)
        } finally {
            proc.destroy()
        }
    }

    @Test
    fun `fixture链核对三态`() {
        if (!unixTools()) return
        val home = Files.createTempDirectory("rlv-home")
        val bin = Files.createTempDirectory("rlv-bin")
        writeFakeTmux(bin)
        val (proc, exe) = spawnFakeClaude()
        try {
            val now = System.currentTimeMillis()
            writeJson(home, "new.json", proc.pid(), sid, now)
            val t0 = (now - 60_000) / 1000.0
            // 锚点不在链上（转录里没有 anchor 这条）
            val tr1 = writeTranscript(home, listOf(rootU to null, target to rootU, oldTail to target))
            val out1 = run(RewindLiveVerification.command(query(home, tr1, exe, t0), regTimeoutSec = 5), home, bin, runtime, panePid)
            assertEquals(RewindLiveVerification.Result.Failed("chain-anchor-missing"), RewindLiveVerification.parse(out1), out1)
            // target 还在活链上（新叶子接在旧尾巴后面 = 没退成）
            val tr2 = writeTranscript(home, listOf(rootU to null, anchor to rootU, target to anchor, newLeaf to target))
            val out2 = run(RewindLiveVerification.command(query(home, tr2, exe, t0), regTimeoutSec = 5), home, bin, runtime, panePid)
            assertEquals(RewindLiveVerification.Result.Failed("chain-target-present"), RewindLiveVerification.parse(out2), out2)
            // 字节上界 fail-closed
            val tr3 = writeTranscript(home, transcriptRewound())
            val out3 = run(
                RewindLiveVerification.command(query(home, tr3, exe, t0), regTimeoutSec = 5, chainMaxBytes = 16),
                home, bin, runtime, panePid,
            )
            assertEquals(RewindLiveVerification.Result.Failed("chain-too-large"), RewindLiveVerification.parse(out3), out3)
        } finally {
            proc.destroy()
        }
    }

    @Test
    fun `fixture链对抗样本不能假ok`() {
        if (!unixTools()) return
        val home = Files.createTempDirectory("rlv-home")
        val bin = Files.createTempDirectory("rlv-bin")
        writeFakeTmux(bin)
        val (proc, exe) = spawnFakeClaude()
        try {
            val now = System.currentTimeMillis()
            writeJson(home, "new.json", proc.pid(), sid, now)
            val t0 = (now - 60_000) / 1000.0
            val dir = home.resolve(".claude/projects/-tmp-demo")
            Files.createDirectories(dir)
            val tr = dir.resolve("$sid.jsonl")
            fun verify(maxBytes: Long = RewindLiveVerification.CHAIN_MAX_BYTES): Pair<String, RewindLiveVerification.Result> {
                val out = run(
                    RewindLiveVerification.command(query(home, tr, exe, t0), regTimeoutSec = 5, chainMaxBytes = maxBytes),
                    home, bin, runtime, panePid,
                )
                return out to RewindLiveVerification.parse(out)
            }

            // 1. 侧链挂 anchor：假「新分支」藏在 isSidechain 里。不排侧链的话 leaf 会被抢去侧链、
            //    target 又不在侧链上 → 假 ok。排除后主链仍含 target → 必须拒。
            Files.write(tr, listOf(
                line(rootU, null),
                line(anchor, rootU, "assistant"),
                line(target, anchor),
                line(oldTail, target, "assistant"),
                line(newLeaf, anchor, "assistant", sidechain = true),
            ).joinToString("") { it + "\n" }.toByteArray())
            run {
                val (out, r) = verify()
                assertEquals(RewindLiveVerification.Result.Failed("chain-target-present"), r, out)
            }

            // 2. 不完整尾部不能证明新分支：末行是合法 JSON 但没写完（无换行）。
            //    只认完整换行行 → 新叶不存在 → 主链（含 target）仍然是活链 → 必须拒。
            val complete = listOf(
                line(rootU, null),
                line(anchor, rootU, "assistant"),
                line(target, anchor),
                line(oldTail, target, "assistant"),
            ).joinToString("") { it + "\n" }
            val partialLine = """{"uuid":"$newLeaf","parentUuid":"$anchor","type":"user"}""" // 合法 JSON，故意不补 \n
            Files.write(tr, (complete + partialLine).toByteArray())
            run {
                val (out, r) = verify()
                assertEquals(RewindLiveVerification.Result.Failed("chain-target-present"), r, out)
            }

            // 3. 含 anchor 的环：回走会先把 anchor_ok 置位，但遇环必须 chain-cycle，不许带着它回 ok。
            Files.write(tr, listOf(
                line(rootU, newLeaf), // 人为造环：根的 parent 指回新叶
                line(anchor, rootU, "assistant"),
                line(target, anchor),
                line(newLeaf, anchor, "assistant"),
            ).joinToString("") { it + "\n" }.toByteArray())
            run {
                val (out, r) = verify()
                assertEquals(RewindLiveVerification.Result.Failed("chain-cycle"), r, out)
            }

            // 4. 重复 uuid 只更新不夺 leaf：末尾重发旧 uuid（旧分支上的 oldTail）不许把 leaf 抢回去。
            //    若被抢走，回走会落到旧分支 → target-present 假失败；正确行为是新叶仍在回退分支 → ok。
            Files.write(tr, (transcriptRewound().joinToString("") { (u, p) -> line(u, p) + "\n" } +
                line(oldTail, target, "user") + "\n").toByteArray())
            run {
                val (out, r) = verify()
                assertEquals(RewindLiveVerification.Result.Ok(proc.pid().toString()), r, out)
            }
        } finally {
            proc.destroy()
        }
    }

    @Test
    fun `fixture缺parent显式失败`() {
        if (!unixTools()) return
        val home = Files.createTempDirectory("rlv-home")
        val bin = Files.createTempDirectory("rlv-bin")
        writeFakeTmux(bin)
        val (proc, exe) = spawnFakeClaude()
        try {
            writeJson(home, "new.json", proc.pid(), sid, System.currentTimeMillis())
            // newLeaf 的 parent 指向从未出现过的 uuid：宁可拒也不静默当到根
            val tr = writeTranscript(home, listOf(anchor to "cafe0000-0000-0000-0000-000000000000", newLeaf to anchor))
            val out = run(
                RewindLiveVerification.command(query(home, tr, exe, (System.currentTimeMillis() - 60_000) / 1000.0), regTimeoutSec = 5),
                home, bin, runtime, panePid,
            )
            assertEquals(RewindLiveVerification.Result.Failed("chain-parent-missing"), RewindLiveVerification.parse(out), out)
        } finally {
            proc.destroy()
        }
    }
}
