package app.yxi.desktop

import app.yxi.ssh.HostKeys
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.NullChannel
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files

/** 假 codex 需要 Python；不可用时按项目测试惯例 assumeTrue 跳过，不判失败。 */
internal val fakeRunnerCommand: String? by lazy {
    listOf("python3", "python").firstOrNull { command ->
        runCatching { ProcessBuilder(command, "--version").start().waitFor() == 0 }.getOrDefault(false)
    }
}

internal fun assumeFakeRunner() = assumeTrue(fakeRunnerCommand != null,
    "Requires a local python3/python for the fake codex app-server fixture")

/** 假 codex app-server 进程 + 进程管道桩替的 Shell；仅测试用，零模型/零网络/零登录。 */
internal class FakeRunner(mode: String, threadId: String = "thr-1", otherThreadId: String = "thr-other") : AutoCloseable {
    val dir: File = Files.createTempDirectory("yxi-fakecodex").toFile()
    val log: File = File(dir, "fake.log")
    val process: Process
    val shell: app.yxi.ssh.SshSession.Shell
    val client: CodexAppServer

    init {
        val script = File(dir, "fake_codex.py")
        checkNotNull(FakeRunner::class.java.classLoader.getResourceAsStream("fake_codex.py")) { "缺少 fake_codex.py 资源" }
            .use { input -> script.outputStream().use { output -> input.copyTo(output) } }
        val pb = ProcessBuilder(checkNotNull(fakeRunnerCommand) { "假 codex 运行器需要 python3/python" },
            script.absolutePath, "--mode", mode)
        pb.environment()["FAKE_LOG"] = log.absolutePath
        pb.environment()["FAKE_THREAD_ID"] = threadId
        pb.environment()["FAKE_OTHER_THREAD_ID"] = otherThreadId
        process = pb.start()
        shell = app.yxi.ssh.SshSession.Shell(NullChannel(), process.inputStream, process.outputStream)
        client = CodexAppServer(shell)
    }

    fun inbound(): List<String> = log.readLines().filterNot { it.startsWith(">> ") }
    fun outbound(): List<String> = log.readLines().filter { it.startsWith(">> ") }.map { it.removePrefix(">> ") }
    fun inboundJson(): List<JSONObject> = inbound().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
    fun outboundJson(): List<JSONObject> = outbound().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }

    override fun close() {
        runCatching { client.close() }
        process.destroyForcibly()
        dir.deleteRecursively()
    }
}

/** 等效 CodexAppServer.connect 的握手（不经真实 SSH）。 */
internal fun handshake(runner: FakeRunner) = runBlocking {
    runner.client.request("initialize", JSONObject().put("clientInfo", JSONObject().put("name", "yxi-test").put("title", "T").put("version", "0")))
    check(runner.shell.write(JSONObject().put("method", "initialized").put("params", JSONObject()).toString() + "\n")) { "初始化确认未写入" }
}

/** 轮询到条件成立，否则超时失败（控制器状态在 Swing EDT/IO 线程推进）。 */
internal fun poll(timeoutMs: Long = 5000, cond: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
        check(System.currentTimeMillis() < deadline) { "等待超时" }
        Thread.sleep(25)
    }
}

internal object NoHostKeys : HostKeys {
    override fun userInfo(): UserInfo = object : UserInfo {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = null
        override fun promptPassword(message: String?): Boolean = false
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = false
        override fun showMessage(message: String?) = Unit
    }
    override val changedDetected: Boolean = false
    override fun check(host: String?, key: ByteArray?): Int = HostKeyRepository.NOT_INCLUDED
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, alias: String?) = Unit
    override fun remove(host: String?, alias: String?, buf: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = ""
    override fun getHostKey(): Array<HostKey> = arrayOf()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = arrayOf()
}
