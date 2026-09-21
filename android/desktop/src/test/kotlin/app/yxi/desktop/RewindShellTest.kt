package app.yxi.desktop

import app.yxi.agent.Rewind
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * 生成的命令要在**真 bash 里跑得过且不出事**（老板令）：假 tmux（按参数回数据、
 * 按键记录进日志）、假 claude（bash 二进制副本 —— `/proc/<pid>/exe` 落在假路径，
 * argv 里带白名单旗标）、假 HOME（无登记表，走 pane_pid 回退）。全链隔离在临时目录：
 * 无生产回退、无付费请求、零权限绕过。
 *
 * 覆盖四条硬红线：
 *  1. argv 捕获全链在真 shell + 真 /proc 上工作（exe/model/effort/pane/pid + argv 落盘可清）；
 *  2. 引号 / `$()` / 反引号的会话名与目录**一个都不执行**（注入只进按键文本，不进 shell）；
 *  3. send-keys 失败绝不报 sent（`&&` 逐级链）；
 *  4. `/exit` 前身份核对不过，一个键都不发。
 */
@EnabledOnOs(OS.LINUX) // These are remote Linux /proc commands, not Windows client commands.
class RewindShellTest {

    private val root = Files.createTempDirectory("yxi-rltest")
    private val fakeBin = Files.createDirectories(root.resolve("bin"))
    private val fakeHome = Files.createDirectories(root.resolve("home"))
    private val tmuxLog = root.resolve("tmux.log")

    /** 假 claude：bash 二进制副本 —— exe 解析落在假路径且含 /claude，argv 可自由构造。 */
    private val claudeBin = fakeBin.resolve("claude").also { p ->
        Files.copy(Path.of("/bin/bash"), p)
        p.toFile().setExecutable(true, false)
    }

    private val sid = "4b1d8860-e7b2-4d5e-82eb-fc05a8996fac"
    private val runtimeId = "4321:3:1727000000"

    /** 假 tmux：display-message 按 fmt 回数据；send-keys 记录进日志（可注入失败）。 */
    private val tmuxScript = fakeBin.resolve("tmux").also { p ->
        Files.writeString(
            p, """
            #!/bin/bash
            printf '%s\n' "tmux ${'$'}*" >> "${'$'}YXI_TMUX_LOG"
            case "${'$'}1" in
              display-message)
                case "${'$'}5" in
                  *pane_pid*) echo "${'$'}YXI_PPID"; exit 0;;
                  *pane_id*) echo "${'$'}YXI_PANE"; exit 0;;
                  *pane_current_command*) echo "${'$'}YXI_PCC"; exit 0;;
                  *session_id*) echo "${'$'}YXI_RT"; exit 0;;
                esac
                exit 0;;
              send-keys)
                [ -n "${'$'}YXI_FAIL_SENDKEYS" ] && exit 1
                shift
                printf '%s\n' "SENDKEYS ${'$'}*" >> "${'$'}YXI_TMUX_LOG"
                exit 0;;
            esac
            exit 0
            """.trimIndent(),
        )
        p.toFile().setExecutable(true, false)
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun resetLog() {
        Files.deleteIfExists(tmuxLog)
    }

    private fun readLog(): String =
        if (Files.exists(tmuxLog)) Files.readString(tmuxLog) else ""

    /** 在真 bash 里跑生成的命令；PATH 前置假 bin（tmux 是假货，其余真家伙）。 */
    private fun run(cmd: String, vararg env: Pair<String, String>): String {
        val p = ProcessBuilder("bash", "-c", cmd).apply {
            val e = environment()
            e["HOME"] = fakeHome.toString()
            e["PATH"] = "$fakeBin:/usr/bin:/bin"
            e["YXI_TMUX_LOG"] = tmuxLog.toString()
            e["YXI_PPID"] = ""
            e["YXI_PANE"] = "%42"
            e["YXI_RT"] = runtimeId
            e["YXI_PCC"] = ""
            e["YXI_FAIL_SENDKEYS"] = ""
            env.forEach { (k, v) -> e[k] = v }
        }.start()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "远端命令 60s 没跑完: $cmd")
        return p.inputStream.readBytes().toString(Charsets.UTF_8)
    }

    /**
     * 起一个「窗格里的 claude」：假 binary 直拉成测试 JVM 的子进程，
     * 假 tmux 的 pane_pid 报**本 JVM** 的 pid —— captureCommand 的 pgrep -P 回退路径
     * 就能找到这个 cmdline 含 claude 的孩子。⚠️ `-c` 体的**最后一条必须是内建命令**
     * （`sleep 30; :`）：bash 会把 -c 的末条命令直接 exec 掉自己 —— exe 变 /usr/bin/sleep、
     * argv 全丢，单条命令如此、复合列表的末条也如此；放 `:` 内建则 sleep 成被等待的
     * 子进程，bash 本尊活着，exe/argv 都在。
     * 白名单旗标放 `-c` 之后当 bash 位置参数 —— 进 argv，但不会被 bash 解析。
     */
    private fun spawnPaneClaude(): Process = ProcessBuilder(
        claudeBin.toString(), "-c", "sleep 30; :", "--model", "opus", "--effort", "high",
    ).start()

    private fun killPane(p: Process) {
        p.descendants().forEach { it.destroyForcibly() }
        p.destroyForcibly()
    }

    @Test
    fun `真bash里argv捕获全链_读的是真proc假claude`() {
        resetLog()
        val claudeProc = spawnPaneClaude()
        try {
            val out = run(
                Rewind.captureCommand("cc-test"),
                "YXI_PPID" to ProcessHandle.current().pid().toString(),
            )
            val got = Rewind.parseCapture(out) as? Rewind.Got
                ?: error("capture 失败: out=[${out.take(600)}] pid池=" +
                    ProcessBuilder("pgrep", "-P", ProcessHandle.current().pid().toString())
                        .start().inputStream.readBytes().toString(Charsets.UTF_8))
            val c = got.capture
            // exe 是 /proc/<pid>/exe 解析出的假 binary 本体（含 /claude，过 validateExe）
            assertEquals(claudeBin.toString(), c.exe)
            assertNull(Rewind.validateExe(c.exe))
            assertEquals("opus", c.model)
            assertEquals("high", c.effort)
            assertEquals("%42", c.paneId)
            // 捕获到的正是假 claude 进程本尊
            assertEquals(claudeProc.pid().toString(), c.pid)
            assertFalse(c.inPlaceAllowed)          // -c 旗标不在白名单 → 如实进 others
            assertTrue("-c" in c.others)
            // argv 真的落了盘，且 cleanupCommand 删得掉
            val argv = Path.of(c.tmpFile)
            assertTrue(Files.exists(argv))
            assertTrue(Files.readString(argv).contains("--model"))
            run(Rewind.cleanupCommand(c))
            assertFalse(Files.exists(argv), "cleanup 后 argv 文件还在")
        } finally {
            killPane(claudeProc)
        }
    }

    @Test
    fun `恶意会话名的捕获命令在真bash里不执行注入`() {
        resetLog()
        val pwned = root.resolve("PWNED_CAP")
        val evil = "x\$(touch $pwned)'y"
        val claudeProc = spawnPaneClaude()
        try {
            // 捕获照样工作（回退路径按字面用名字），注入一个字都不执行
            val out = run(
                Rewind.captureCommand(evil),
                "YXI_PPID" to ProcessHandle.current().pid().toString(),
            )
            val got = Rewind.parseCapture(out) as Rewind.Got
            assertEquals(claudeBin.toString(), got.capture.exe)
            assertFalse(Files.exists(pwned), "会话名里的 \$(touch) 被执行了 —— 注入！")
        } finally {
            killPane(claudeProc)
        }
    }

    @Test
    fun `恶意目录的重启键在真bash里只进按键文本不进shell`() {
        resetLog()
        val pwnedRl = root.resolve("PWNED_RL")
        val pwnedBt = root.resolve("PWNED_BT")
        val evilCwd = "$root/a\$(touch $pwnedRl)`touch $pwnedBt`b"
        val c = Rewind.Capture(claudeBin.toString(), "/tmp/yxi-argv.shelltest", pid = "1", paneId = "%42")
        val out = run(Rewind.relaunchCommand("cc-test", c, sid, evilCwd, runtimeId))
        assertEquals(null, Rewind.parseRelaunch(out))
        assertFalse(Files.exists(pwnedRl), "目录里的 \$(touch) 被 shell 执行了 —— 注入！")
        assertFalse(Files.exists(pwnedBt), "目录里的反引号被 shell 执行了 —— 注入！")
        // 恶意文本原样进了按键记录（tmux -l 字面投递），一行没少
        val log = readLog()
        assertTrue("\$(touch $pwnedRl)" in log)
        assertTrue("`touch $pwnedBt`" in log)
        assertTrue("cd '$evilCwd' &&" in log)
        assertTrue("--resume '$sid'" in log)
    }

    @Test
    fun `send_keys失败绝不报sent`() {
        resetLog()
        val c = Rewind.Capture(claudeBin.toString(), "/tmp/yxi-argv.shelltest", pid = "1", paneId = "%42")
        val out = run(
            Rewind.relaunchCommand("cc-test", c, sid, fakeHome.toString(), runtimeId),
            "YXI_FAIL_SENDKEYS" to "1",
        )
        // && 逐级链：第一条 send-keys 退出 1，sent 永远出不来（连 ID_TAG 都没有 → noresult）
        assertEquals("noresult", Rewind.parseRelaunch(out))
        assertTrue("__YXI_REWIND_ID__:sent" !in out)
    }

    @Test
    fun `身份不过重启一键不发`() {
        resetLog()
        val c = Rewind.Capture(claudeBin.toString(), "/tmp/yxi-argv.shelltest", pid = "1", paneId = "%42")
        val out = run(
            Rewind.relaunchCommand("cc-test", c, sid, fakeHome.toString(), runtimeId),
            "YXI_RT" to "9999:9:9",
        )
        assertEquals("identity", Rewind.parseRelaunch(out))
        assertTrue("SENDKEYS" !in readLog(), "身份复核不过却发了键！")
    }

    @Test
    fun `真bash里exit命令身份过了才发exit三连`() {
        resetLog()
        val c = Rewind.Capture(claudeBin.toString(), "/tmp/yxi-argv.shelltest", pid = "1", paneId = "%42")
        val out = run(Rewind.exitCommand("cc-test", c, runtimeId))
        assertNull(Rewind.parseExit(out))
        assertTrue("__YXI_REWIND_ID__:exit-sent" in out)
        val log = readLog()
        assertTrue("SENDKEYS -t %42 Escape" in log)
        assertTrue("SENDKEYS -t %42 -l -- /exit" in log)
        assertTrue("SENDKEYS -t %42 Enter" in log)
    }

    @Test
    fun `真bash里exit命令身份不过一个键不发`() {
        resetLog()
        val c = Rewind.Capture(claudeBin.toString(), "/tmp/yxi-argv.shelltest", pid = "1", paneId = "%42")
        val out = run(
            Rewind.exitCommand("cc-test", c, runtimeId),
            "YXI_RT" to "9999:9:9",
        )
        assertEquals("identity", Rewind.parseExit(out))
        assertTrue("SENDKEYS" !in readLog(), "身份复核不过却发了 Esc//exit！")
    }

    @Test
    fun `真bash里等壳命令只认shell_vim和claude都不算回壳`() {
        val short = Rewind.waitShellCommand("cc-test", iterations = 2, intervalSec = "0.1")
        run(short, "YXI_PCC" to "bash").let {
            assertTrue("SHELL" in it && "STUCK" !in it)
        }
        run(short, "YXI_PCC" to "-bash").let {
            assertTrue("SHELL" in it && "STUCK" !in it)   // 登录形态也算
        }
        // vim / top / 退出中的 claude：一律不算 shell → STUCK（fail-closed，一键不发）
        listOf("vim", "top", "claude").forEach { pcc ->
            run(short, "YXI_PCC" to pcc).let {
                assertTrue("STUCK" in it && "SHELL" !in it, "前台 $pcc 被当成了 shell！")
            }
        }
    }
}
