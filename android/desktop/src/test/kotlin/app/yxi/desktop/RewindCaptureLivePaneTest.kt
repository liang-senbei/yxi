package app.yxi.desktop

import app.yxi.agent.Rewind
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * 42d5040 capture 定位补测（cc-yxi_pilot）：capture 必须认 **pane_pid 及其直接子进程的
 * 真实 exe**，旧 `.claude/sessions` 登记表彻底不再参与 —— 旧代码 grep 同名首条登记
 * 可能拿到别的活进程的 pid（同名旧 pane 还活着时尤其致命）。多候选 = ambiguous 拒绝。
 *
 * 基建与 RewindShellTest 同款：假 tmux（display-message 按环境变量回数据）、
 * 假 claude（/bin/bash 副本，`/proc/<pid>/exe` 落在含 /claude 的假路径）、真 bash、真 /proc。
 * 全链隔离临时目录，无生产试跑。
 */
@EnabledOnOs(OS.LINUX) // 远端 Linux /proc 命令，不是 Windows 客户端命令。
class RewindCaptureLivePaneTest {

    private val root = Files.createTempDirectory("yxi-capture")
    private val fakeBin = Files.createDirectories(root.resolve("bin"))
    private val fakeHome = Files.createDirectories(root.resolve("home"))

    /** 假 claude：bash 二进制副本 —— exe 解析落在含 /claude 的假路径。 */
    private val claudeBin = fakeBin.resolve("claude").also { p ->
        Files.copy(Path.of("/bin/bash"), p)
        p.toFile().setExecutable(true, false)
    }

    private val tmuxScript = fakeBin.resolve("tmux").also { p ->
        Files.writeString(
            p, """
            #!/bin/bash
            case "${'$'}1" in
              display-message)
                case "${'$'}5" in
                  *pane_pid*) echo "${'$'}YXI_PPID"; exit 0;;
                  *pane_id*) echo "${'$'}YXI_PANE"; exit 0;;
                esac
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

    private fun run(cmd: String, vararg env: Pair<String, String>): String {
        val p = ProcessBuilder("bash", "-c", cmd).apply {
            val e = environment()
            e["HOME"] = fakeHome.toString()
            e["PATH"] = "$fakeBin:/usr/bin:/bin"
            e["YXI_PPID"] = ""
            e["YXI_PANE"] = "%42"
            env.forEach { (k, v) -> e[k] = v }
        }.start()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "capture 命令 60s 没跑完")
        return p.inputStream.readBytes().toString(Charsets.UTF_8)
    }

    /**
     * 起一个「窗格里的 claude」。⚠️ `-c` 末条必须是内建 `:`：bash 会把 -c 末条直接 exec
     * 掉自己，exe 会变成 /usr/bin/sleep；放内建则 bash 本尊活着，exe/argv 都在。
     */
    private fun spawnClaude(vararg flags: String): Process = ProcessBuilder(
        claudeBin.toString(), "-c", "sleep 30; :", *flags,
    ).start()

    private fun killTree(p: Process) {
        p.descendants().forEach { it.destroyForcibly() }
        p.destroyForcibly()
    }

    /** 旧 pane 遗留的活 claude：包一层 bash（JVM 只直接持有 bash，pgrep -P 扫不到孙进程）。 */
    private fun spawnDecoyClaude(): Long {
        val pidFile = root.resolve("decoy.pid")
        val wrapper = ProcessBuilder(
            "bash", "-c",
            "\"$claudeBin\" -c 'sleep 60; :' --model legacy >/dev/null 2>&1 & echo \$! > '$pidFile'; wait",
        ).start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (Files.exists(pidFile)) {
                val pid = Files.readString(pidFile).trim()
                if (pid.isNotEmpty()) return pid.toLong()
            }
            if (!wrapper.isAlive) error("decoy wrapper 提前退出")
            Thread.sleep(50)
        }
        error("decoy claude 10s 没起来")
    }

    @Test
    fun `旧sessions登记指向别的活claude不影响capture`() {
        // 旧 pane 的 claude 还活着，登记表把它记在同名 session 名下 —— 旧代码会 grep 到它
        val stalePid = spawnDecoyClaude()
        val paneClaude = spawnClaude("--model", "opus", "--effort", "high")
        try {
            val reg = fakeHome.resolve(".claude/sessions")
            Files.createDirectories(reg)
            Files.writeString(
                reg.resolve("cc-test.json"),
                """{"tmux":"cc-test:%42","pid":$stalePid,"cwd":"/old/pane"}""",
            )
            val out = run(
                Rewind.captureCommand("cc-test"),
                "YXI_PPID" to ProcessHandle.current().pid().toString(),
            )
            val got = Rewind.parseCapture(out) as? Rewind.Got
                ?: error("capture 失败: out=[${out.take(400)}]")
            assertEquals(claudeBin.toString(), got.capture.exe, "必须解析本 pane 的假 claude")
            assertEquals(paneClaude.pid().toString(), got.capture.pid, "认 pane_pid 子进程，不认登记表")
            assertNotEquals(stalePid.toString(), got.capture.pid, "拿到的正是登记表里的旧活 pid —— 修错了")
            assertEquals("opus", got.capture.model)
        } finally {
            killTree(paneClaude)
            ProcessHandle.of(stalePid).ifPresent { h ->
                h.descendants().forEach(ProcessHandle::destroyForcibly)
                h.destroyForcibly()
            }
        }
    }

    @Test
    fun `pane_pid下两个claude候选一律ambiguous拒绝`() {
        val a = spawnClaude("--model", "opus")
        val b = spawnClaude("--model", "sonnet")
        try {
            val out = run(
                Rewind.captureCommand("cc-test"),
                "YXI_PPID" to ProcessHandle.current().pid().toString(),
            )
            val failed = Rewind.parseCapture(out) as? Rewind.Failed
            assertEquals("ambiguous", failed?.code, "多候选必须拒绝而不是静默选一个: out=[${out.take(400)}]")
        } finally {
            killTree(a); killTree(b)
        }
    }

    @Test
    fun `pane_pid本身是claude时直接认pane进程`() {
        // tmux new-session 直接跑 claude 的形态：pane_pid 自己就是目标，pgrep 孩子全是误报源
        val paneClaude = spawnClaude("--model", "opus")
        try {
            val out = run(
                Rewind.captureCommand("cc-test"),
                "YXI_PPID" to paneClaude.pid().toString(),
            )
            val got = Rewind.parseCapture(out) as? Rewind.Got
                ?: error("capture 失败: out=[${out.take(400)}]")
            assertEquals(paneClaude.pid().toString(), got.capture.pid, "pane_pid 本尊就是 claude")
            assertEquals(claudeBin.toString(), got.capture.exe)
            assertEquals("opus", got.capture.model)
        } finally {
            killTree(paneClaude)
        }
    }

    @Test
    fun `pane_pid没有任何claude时报gone不猜`() {
        // pp 是一个不存在的 pid：候选全空 → gone（fail-closed），登记表也不许救场
        val out = run(
            Rewind.captureCommand("cc-test"),
            "YXI_PPID" to "999999",
        )
        assertEquals("gone", (Rewind.parseCapture(out) as? Rewind.Failed)?.code,
            "定位不到活 pane 必须报 gone，不得退回登记表")
    }
}
