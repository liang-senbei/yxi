package app.yxi.desktop

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AndroidEmulatorLauncher 定向测试：进程与日志目录全部注入假件，**零真模拟器**、
 * 零下载、零许可。核对：同名不重复起、只停自己的句柄、退出后可再起、日志上限与保留策略。
 */
class AndroidEmulatorLauncherTest {

    /** 假进程：destroy 不算死（模拟无视优雅停止），exit()/destroyForcibly 才真退。 */
    private class FakeProcess(outputBytes: ByteArray = ByteArray(0)) : AndroidEmulatorLauncher.EmuProcess {
        private val exited = CountDownLatch(1)
        val destroyed = AtomicInteger()
        val forcibly = AtomicInteger()
        @Volatile private var code = 0
        override val pid: Long = 4242L
        override fun isAlive(): Boolean = exited.count > 0
        override fun destroy() { destroyed.incrementAndGet() }
        override fun destroyForcibly() { forcibly.incrementAndGet(); exit(137) }
        override fun waitFor(): Int { exited.await(); return code }
        override fun waitFor(seconds: Long): Boolean = exited.await(seconds, TimeUnit.SECONDS)
        override val output: InputStream = ByteArrayInputStream(outputBytes)
        fun exit(c: Int = 0) { code = c; exited.countDown() }
    }

    private fun env(avds: List<String>, withEmulator: Boolean = true): AndroidEmulatorEnvironment.Result =
        AndroidEmulatorEnvironment.Result(
            sdkRoot = if (withEmulator) "/sdk" else null,
            rootsChecked = listOf("/sdk"),
            tools = if (withEmulator)
                listOf(AndroidEmulatorEnvironment.Tool("emulator", "/sdk/emulator/emulator.exe"))
            else emptyList(),
            missing = if (withEmulator) emptyList() else listOf("emulator"),
            avds = avds, avdSource = null, errors = emptyList(),
        )

    private fun eventually(seconds: Double = 5.0, cond: () -> Boolean): Boolean {
        val end = System.nanoTime() + (seconds * 1e9).toLong()
        while (System.nanoTime() < end) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    @Test
    fun `launch starts once and refuses duplicate same-name avd`() {
        val fake = FakeProcess()
        var starts = 0
        val l = AndroidEmulatorLauncher(env(listOf("MyAVD")), logDir = Files.createTempDirectory("yxi-emul").toFile(),
            startProcess = { starts++; fake })
        val first = l.launch("MyAVD")
        assertTrue(first.started); assertEquals(4242L, first.pid)
        assertEquals(1, starts)
        assertEquals(listOf("MyAVD"), l.runningAvds())
        val second = l.launch("MyAVD")
        assertFalse(second.started)
        assertTrue(second.reason!!.contains("已在运行"))
        assertEquals(1, starts)   // 不再起第二份
    }

    @Test
    fun `unknown avd or missing emulator never touches processes`() {
        var starts = 0
        val logDir = Files.createTempDirectory("yxi-emul").toFile()
        val noAvd = AndroidEmulatorLauncher(env(listOf("Real")), logDir = logDir, startProcess = { starts++; FakeProcess() })
        val r1 = noAvd.launch("Ghost")
        assertFalse(r1.started); assertTrue(r1.reason!!.contains("已存在的 AVD")); assertEquals(0, starts)
        val noEmu = AndroidEmulatorLauncher(env(listOf("Real"), withEmulator = false), logDir = logDir, startProcess = { starts++; FakeProcess() })
        val r2 = noEmu.launch("Real")
        assertFalse(r2.started); assertTrue(r2.reason!!.contains("emulator")); assertEquals(0, starts)
    }

    @Test
    fun `exit frees the slot so the same avd can start again`() {
        val fake = FakeProcess()
        var starts = 0
        val l = AndroidEmulatorLauncher(env(listOf("MyAVD")), logDir = Files.createTempDirectory("yxi-emul").toFile(),
            startProcess = { starts++; fake })
        l.launch("MyAVD")
        fake.exit(0)
        assertTrue(eventually { l.runningAvds().isEmpty() }, "退出后句柄应被清掉")
        val again = l.launch("MyAVD")
        assertTrue(again.started); assertEquals(2, starts)
    }

    @Test
    fun `stop destroys only its own handle`() {
        val a = FakeProcess(); val b = FakeProcess()
        val logDir = Files.createTempDirectory("yxi-emul").toFile()
        val procs = mapOf("MyAVD" to a, "OtherAVD" to b)
        val l = AndroidEmulatorLauncher(env(listOf("MyAVD", "OtherAVD")), logDir = logDir,
            startProcess = { args -> procs[args.last()]!! })
        l.launch("MyAVD"); l.launch("OtherAVD")
        assertTrue(l.stop("MyAVD"))
        assertEquals(1, a.destroyed.get())
        assertEquals(0, b.destroyed.get())   // 别人的句柄一根手指都不碰
        assertFalse(l.stop("NeverStarted"))
        l.stopAll()
        assertEquals(1, b.destroyed.get())
    }

    @Test
    fun `ignored graceful destroy escalates to force kill`() {
        val a = FakeProcess()
        val l = AndroidEmulatorLauncher(env(listOf("MyAVD")), logDir = Files.createTempDirectory("yxi-emul").toFile(),
            startProcess = { a }, stopGraceSeconds = 0)
        l.launch("MyAVD")
        assertTrue(l.stop("MyAVD"))
        assertTrue(eventually { a.forcibly.get() == 1 }, "优雅停止被无视后应强杀")
        a.exit(137)
    }

    @Test
    fun `log is capped and old logs are pruned on launch`() {
        val logDir = Files.createTempDirectory("yxi-emul").toFile()
        // 预置 12 个旧日志（上限 10）；i 越大 lastModified 越早 = 越老
        val old = (0 until 12).map { i ->
            File(logDir, "old-$i.log").apply {
                createNewFile(); setLastModified(System.currentTimeMillis() - (100 + i) * 60_000L)
            }
        }
        val big = ByteArray((AndroidEmulatorLauncher.MAX_LOG_BYTES + 1000).toInt()) { 'x'.code.toByte() }
        val fake = FakeProcess(big)
        val l = AndroidEmulatorLauncher(env(listOf("MyAVD")), logDir = logDir, startProcess = { fake })
        assertTrue(l.launch("MyAVD").started)
        // i 越大减得越多 = 越老；最老的 2 个是 old-11/old-10，该被清掉
        assertTrue(eventually { !old[11].exists() && !old[10].exists() }, "最老的 2 个旧日志应被清掉")
        // 新日志被上限截住：截到 MAX 为止 + 截断标记，而不是把 2MB+1000 全量落盘
        val newLog = logDir.listFiles { f -> f.name.startsWith("MyAVD-") }!!.single()
        assertTrue(eventually { newLog.length() >= AndroidEmulatorLauncher.MAX_LOG_BYTES }, "等待日志写完")
        assertTrue(newLog.length() > AndroidEmulatorLauncher.MAX_LOG_BYTES && newLog.length() < AndroidEmulatorLauncher.MAX_LOG_BYTES + 200,
            "日志应截在上限附近，实际 ${newLog.length()}")
        fake.exit(0)
    }
}
