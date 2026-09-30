package app.yxi.desktop

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** macOS 从 Finder / Dock 启动的 App 只拿到 launchd 的精简 PATH（/usr/bin:/bin:/usr/sbin:/sbin）：Homebrew、nvm、npm 全局装的
 * claude / codex / node 都找不到，找到了 `#!/usr/bin/env node` 也起不来。启动时在后台读一次登录 shell 的 PATH（带超时，失败不阻塞），
 * 再补 Homebrew 的默认目录，合进找可执行文件和起子进程的环境。只在 macOS 生效，Windows / Linux 原样用继承的环境。 */
internal object LoginShellPath {
    val fallbackDirs = listOf("/opt/homebrew/bin", "/usr/local/bin")
    internal const val MARK = "__YXI_LOGIN_PATH__"
    private const val TIMEOUT_MILLIS = 5_000L
    private val mac = System.getProperty("os.name").startsWith("Mac")
    private val started = AtomicBoolean(false)
    private val resolved = CompletableFuture<String?>()

    /** App 启动时调一次：后台线程去读，不阻塞调用方；重复调用无副作用。 */
    fun prefetch() {
        if (!mac || !started.compareAndSet(false, true)) return
        runCatching { thread(isDaemon = true, name = "yxi-login-shell-path") { resolved.complete(runCatching { read(shell()) }.getOrNull()) } }
            .onFailure { resolved.complete(null) }
    }

    /** 不阻塞：登录 shell 还没读完就先用继承的 PATH + Homebrew 目录。EDT 上（含默认参数）只能用这个。 */
    fun path(inherited: String? = System.getenv("PATH")): String {
        if (!mac) return inherited.orEmpty()
        prefetch()
        return merge(resolved.getNow(null), inherited)
    }

    /** 阻塞到登录 shell 读完（最多约 6 秒），只在 IO 线程上调：发现运行器、在 PATH 里找可执行文件之前。 */
    fun await(inherited: String? = System.getenv("PATH")): String {
        if (!mac) return inherited.orEmpty()
        prefetch()
        val login = try { resolved.get(TIMEOUT_MILLIS + 1_000, TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); null }
            catch (_: Exception) { null }
        return merge(login, inherited)
    }

    /** 给整份传环境的子进程用；非 macOS 原样返回。 */
    fun environment(base: Map<String, String> = System.getenv()): Map<String, String> =
        if (!mac) base else base + ("PATH" to path(base["PATH"]))

    /** 给继承 JVM 环境的 ProcessBuilder 用：`.apply { LoginShellPath.applyTo(environment()) }`；非 macOS 什么都不改。 */
    fun applyTo(environment: MutableMap<String, String>) {
        if (mac) environment["PATH"] = path(environment["PATH"])
    }

    /** 起一次登录 + 交互 shell（`-lic`：PATH 常写在 .zprofile，也常写在 .zshrc / nvm 里）打印 PATH，rc 的欢迎语、提示符靠前后标记过滤。
     * stdout 写临时文件不走管道：rc 顺手拉起的 ssh-agent / gpg-agent 会继承 stdout，管道就一直等不到 EOF。超时或出错连子孙进程一起杀掉，返回 null。 */
    internal fun read(shell: String, timeoutMillis: Long = TIMEOUT_MILLIS, extraEnvironment: Map<String, String> = emptyMap()): String? {
        val output = kotlin.io.path.createTempFile("yxi-login-path-", ".txt").toFile()
        var process: Process? = null
        try {
            val builder = ProcessBuilder(shell, "-lic", "echo $MARK; /usr/bin/printenv PATH; echo $MARK").directory(File(System.getProperty("user.home")))
                .redirectInput(File("/dev/null")).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(output)
            // oh-my-zsh 的自动更新检查会卡住这一步；YXI_RESOLVING_ENVIRONMENT 让用户能在 rc 里跳过重活（同 VS Code 的 VSCODE_RESOLVING_ENVIRONMENT）
            builder.environment().putAll(mapOf("DISABLE_AUTO_UPDATE" to "true", "YXI_RESOLVING_ENVIRONMENT" to "1") + extraEnvironment)
            process = builder.start()
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) return null
            return if (output.length() > 1_048_576) null else parse(output.readText())
        } catch (_: Exception) {
            return null
        } finally {
            process?.let(LocalRuntimeDiscovery::stopOwnedProcess)
            output.delete()
        }
    }

    /** 取两个标记行之间那一行。rc 输出末尾没换行时，第一个标记会粘在前面的字后面，所以认「以标记结尾」的行。 */
    internal fun parse(output: String): String? {
        val lines = output.lines()
        val begin = lines.indexOfFirst { it.endsWith(MARK) }
        if (begin < 0 || begin + 2 >= lines.size || lines[begin + 2] != MARK) return null
        return lines[begin + 1].trim().takeIf { it.startsWith("/") && '\u0000' !in it }
    }

    /** 登录 shell 的在前（和用户在终端里看到的一致，VS Code 从 Dock 启动时也这么合），再接继承的，最后补 Homebrew 默认目录；只留绝对路径，去重保序。 */
    internal fun merge(login: String?, inherited: String?): String =
        (listOfNotNull(login, inherited).flatMap { it.split(':') } + fallbackDirs).filter { it.startsWith("/") }.distinct().joinToString(":")

    /** $SHELL 不是可执行文件的绝对路径（没设、被改坏）就用 macOS 默认的 zsh。 */
    internal fun shell(value: String? = System.getenv("SHELL")): String =
        value?.takeIf { it.startsWith("/") && File(it).let { file -> file.isFile && file.canExecute() } } ?: "/bin/zsh"
}
