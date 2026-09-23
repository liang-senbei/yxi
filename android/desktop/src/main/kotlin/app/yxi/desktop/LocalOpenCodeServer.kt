package app.yxi.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Owns only the process launched here; native user data and other servers are untouched. */
internal class LocalOpenCodeServer private constructor(private val process: Process, val client: OpenCodeClient) : AutoCloseable {
    private val closed = AtomicBoolean()
    val alive: Boolean get() = !closed.get() && process.isAlive
    override fun close() {
        if (closed.compareAndSet(false, true)) LocalRuntimeDiscovery.stopOwnedProcess(process)
    }

    companion object {
        suspend fun start(runtime: LocalRuntimeInstallation, directory: File,
            environment: Map<String, String> = System.getenv(), startupTimeoutMillis: Long = 30_000,
            sharedMcp: List<SharedMcpDefinition> = emptyList()): LocalOpenCodeServer {
            require(runtime.engine == "opencode" && runtime.ready) { "请选择可用的 OpenCode 安装" }
            require(directory.isAbsolute && directory.isDirectory) { "本机工作目录不存在" }
            require(startupTimeoutMillis in 100..60_000)
            val launchEnvironment = OpenCodeStartupMcp.environment(environment, sharedMcp)
            val password = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
            var owned: Process? = null
            try {
                val process = withContext(Dispatchers.IO) {
                    ProcessBuilder(runtime.command + listOf("serve", "--hostname", "127.0.0.1", "--port", "0", "--no-mdns"))
                        .directory(directory.canonicalFile).redirectErrorStream(true).apply {
                            environment().clear(); environment().putAll(launchEnvironment)
                            environment()["OPENCODE_SERVER_USERNAME"] = "opencode"
                            environment()["OPENCODE_SERVER_PASSWORD"] = password
                        }.start().also { owned = it }
                }
                val port = CompletableDeferred<Int>()
                // Drain output for the lifetime of the child, without keeping logs or credentials.
                thread(name = "yxi-opencode-output", isDaemon = true) {
                    try {
                        process.inputStream.reader(Charsets.UTF_8).use { reader ->
                            val line = StringBuilder()
                            var overflow = false
                            while (true) {
                                val ch = reader.read()
                                if (ch < 0) break
                                if (ch == 10) {
                                    if (!overflow) listeningPort(line.toString())?.let { port.complete(it) }
                                    line.setLength(0); overflow = false
                                } else if (line.length < 4096) line.append(ch.toChar()) else overflow = true
                            }
                        }
                        port.completeExceptionally(IllegalStateException("OpenCode 在服务就绪前退出"))
                    } catch (e: Exception) { port.completeExceptionally(IllegalStateException("无法读取 OpenCode 服务启动结果")) }
                }
                val result = withTimeout(startupTimeoutMillis) {
                    val client = OpenCodeClient(port.await(), password, directory.canonicalPath)
                    val health = client.health()
                    check(process.isAlive && health.optBoolean("healthy") && health.optString("version").isNotBlank()) {
                        "OpenCode 服务健康检查未通过"
                    }
                    LocalOpenCodeServer(process, client)
                }
                return result
            } catch (e: Exception) {
                owned?.let(LocalRuntimeDiscovery::stopOwnedProcess)
                throw e
            }
        }

        internal fun listeningPort(line: String): Int? = Regex("^opencode server listening on http://127\\.0\\.0\\.1:([0-9]{1,5})\\r?$")
            .matchEntire(line)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..65535 }
    }
}
