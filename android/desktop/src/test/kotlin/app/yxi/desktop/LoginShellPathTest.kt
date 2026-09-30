package app.yxi.desktop

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.*

class LoginShellPathTest {
    @TempDir lateinit var root: File
    private val os = System.getProperty("os.name")
    private val mac = os.startsWith("Mac")

    /** 假 shell：sh 脚本，收到的参数应是 `-lic <命令>`；输出由脚本模拟。 */
    private fun fakeShell(body: String): File {
        assumeTrue(!os.startsWith("Windows"), "假 shell 是 sh 脚本，Windows 上跳过")
        return File(root, "fake-shell").apply { writeText("#!/bin/sh\n" + body.trimIndent() + "\n"); setExecutable(true) }
    }
    private fun kill(pidFile: File) {
        runCatching { ProcessHandle.of(pidFile.readText().trim().toLong()).ifPresent { it.destroyForcibly() } }
    }

    @Test fun `合并时登录 shell 的在前、继承的在后、补 Homebrew 目录，去重并丢掉相对路径`() {
        assertEquals("/Users/u/.nvm/versions/node/v22/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/local/bin",
            LoginShellPath.merge("/Users/u/.nvm/versions/node/v22/bin:/opt/homebrew/bin:/usr/bin:bin:", "/usr/bin:/bin:./node_modules/.bin"))
        assertEquals("/usr/bin:/bin:/opt/homebrew/bin:/usr/local/bin", LoginShellPath.merge(null, "/usr/bin:/bin"))
        assertEquals("/opt/homebrew/bin:/usr/local/bin", LoginShellPath.merge(null, null))
    }

    @Test fun `只认两个标记之间的那一行，rc 的欢迎语和提示符不算`() {
        val m = LoginShellPath.MARK
        assertEquals("/opt/homebrew/bin:/usr/bin", LoginShellPath.parse("Last login: Mon\nwelcome\n$m\n/opt/homebrew/bin:/usr/bin\n$m\nbye\n"))
        assertEquals("/usr/bin", LoginShellPath.parse("motd without newline$m\n/usr/bin\n$m\n"))
        assertNull(LoginShellPath.parse("welcome\n/usr/bin\n"))          // 没有标记：rc 里 exec 了别的程序
        assertNull(LoginShellPath.parse("$m\n/usr/bin\n"))               // 只有开头标记：被截断
        assertNull(LoginShellPath.parse("$m\n$m\n"))                     // PATH 没设
        assertNull(LoginShellPath.parse("$m\n/usr/bin\nnoise\n$m\n"))   // 标记之间混进了别的输出
        assertNull(LoginShellPath.parse("$m\nrelative/bin\n$m\n"))
    }

    @Test fun `SHELL 不是可执行文件的绝对路径就用 zsh`() {
        assertEquals("/bin/zsh", LoginShellPath.shell(null))
        assertEquals("/bin/zsh", LoginShellPath.shell("zsh"))
        assertEquals("/bin/zsh", LoginShellPath.shell(File(root, "missing").path))
        assertEquals("/bin/zsh", LoginShellPath.shell(root.path))
        val shell = fakeShell("exit 0")
        assertEquals(shell.path, LoginShellPath.shell(shell.path))
    }

    @Test fun `用 -lic 起登录 shell，从 rc 的噪音里取出 PATH，退出码不看`() {
        val args = File(root, "args")
        val shell = fakeShell("""
            printf '%s\n' "$1" "$2" > "${args.path}"
            echo "welcome from rc"
            printf 'motd without newline'
            echo ${LoginShellPath.MARK}
            echo /fixture/login/bin:/usr/bin
            echo ${LoginShellPath.MARK}
            echo bye
            exit 3
        """)
        assertEquals("/fixture/login/bin:/usr/bin", LoginShellPath.read(shell.path))
        val (flag, command) = args.readLines()
        assertEquals("-lic", flag)
        assertTrue("/usr/bin/printenv PATH" in command, command)
    }

    @Test fun `超时就返回 null，并把 shell 连同它拉起的子进程一起杀掉`() {
        val pid = File(root, "child.pid")
        val shell = fakeShell("""
            sleep 60 &
            echo $! > "${pid.path}"
            wait
        """)
        val started = System.nanoTime()
        try {
            assertNull(LoginShellPath.read(shell.path, timeoutMillis = 1_000))
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10))
            val child = ProcessHandle.of(pid.readText().trim().toLong())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (child.map { it.isAlive }.orElse(false) && System.nanoTime() < deadline) Thread.sleep(50)
            assertFalse(child.map { it.isAlive }.orElse(false), "shell 拉起的子进程还活着")
        } finally { kill(pid) }
    }

    @Test fun `rc 拉起的后台进程占着 stdout 也不会卡住`() {
        val pid = File(root, "agent.pid")
        val shell = fakeShell("""
            sleep 60 &
            echo $! > "${pid.path}"
            echo ${LoginShellPath.MARK}
            echo /fixture/bin
            echo ${LoginShellPath.MARK}
        """)
        val started = System.nanoTime()
        try {
            assertEquals("/fixture/bin", LoginShellPath.read(shell.path))
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(4), "在等后台进程关 stdout")
        } finally { kill(pid) }
    }

    @Test fun `shell 起不来或者没打出标记就返回 null`() {
        assertNull(LoginShellPath.read(File(root, "missing-shell").path))
        assertNull(LoginShellPath.read(fakeShell("echo 'exec tmux'; exit 1").path))
    }

    @Test fun `非 macOS 原样用继承的 PATH 和环境`() {
        assumeTrue(!mac, "macOS 上走合并")
        val base = mapOf("PATH" to "/custom/bin", "K" to "v")
        assertSame(base, LoginShellPath.environment(base))
        assertEquals("/custom/bin", LoginShellPath.path("/custom/bin"))
        assertEquals("", LoginShellPath.path(null))
        assertEquals(System.getenv("PATH").orEmpty(), LoginShellPath.await())
        val env = mutableMapOf("PATH" to "/custom/bin")
        LoginShellPath.applyTo(env)
        assertEquals(mapOf("PATH" to "/custom/bin"), env)
    }

    @Test fun `macOS 上 PATH 合进登录 shell 和 Homebrew 目录，其他变量不动`() {
        assumeTrue(mac, "只在 macOS 上合并")
        val merged = LoginShellPath.environment(mapOf("PATH" to "/custom/bin", "K" to "v"))
        assertEquals("v", merged["K"])
        assertTrue(merged.getValue("PATH").split(':').containsAll(listOf("/custom/bin") + LoginShellPath.fallbackDirs), merged["PATH"])
        val env = mutableMapOf("PATH" to "/custom/bin")
        LoginShellPath.applyTo(env)
        assertTrue(env.getValue("PATH").split(':').containsAll(listOf("/custom/bin") + LoginShellPath.fallbackDirs), env["PATH"])
        // 等真实登录 shell 读完：path_helper 至少给出 /usr/bin，读失败只剩 /custom/bin + Homebrew 目录
        val awaited = LoginShellPath.await("/custom/bin")
        assertTrue("/usr/bin" in awaited.split(':'), awaited)
    }

    @Test fun `真实 zsh：登录配置和交互配置里加的 PATH 都读得到`() {
        assumeTrue(mac && File("/bin/zsh").canExecute(), "只在 macOS 上用真实 zsh")
        File(root, ".zprofile").writeText("echo profile-noise\nexport PATH=\"/fixture/profile/bin:\$PATH\"\n")
        File(root, ".zshrc").writeText("echo rc-noise\nexport PATH=\"\$PATH:/fixture/rc/bin\"\n")
        val path = assertNotNull(LoginShellPath.read("/bin/zsh", extraEnvironment = mapOf("HOME" to root.path, "ZDOTDIR" to root.path)))
        val dirs = path.split(':')
        assertEquals("/fixture/profile/bin", dirs.first(), path)
        assertEquals("/fixture/rc/bin", dirs.last(), path)
        assertTrue("/usr/bin" in dirs, path)
    }
}
