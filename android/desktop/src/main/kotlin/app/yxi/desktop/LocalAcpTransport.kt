package app.yxi.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal object AcpLaunch {
    fun arguments(engine: String): List<String> = when (engine) {
        "gemini" -> listOf("--acp")
        "hermes" -> listOf("acp")
        "grok" -> listOf("--no-auto-update", "agent", "stdio")
        else -> error("此运行器未配置 ACP 启动方式")
    }
}

/** Direct argv only. Login and model selection remain separate protocol operations. */
internal class LocalAcpTransport private constructor(private val process: Process) : AcpTransport {
    override val output get() = process.inputStream
    private val writing = Mutex()
    private val closed = AtomicBoolean()
    internal val processId get() = process.pid()
    override suspend fun write(text: String): Boolean = writing.withLock {
        if (closed.get() || !process.isAlive) return@withLock false
        withContext(Dispatchers.IO) { process.outputStream.write(text.toByteArray(Charsets.UTF_8)); process.outputStream.flush(); true }
    }
    override fun close() { if (closed.compareAndSet(false, true)) LocalRuntimeDiscovery.stopOwnedProcess(process) }
    companion object {
        suspend fun start(runtime: LocalRuntimeInstallation, directory: File, environment: Map<String, String> = System.getenv()): LocalAcpTransport {
            require(runtime.ready) { "请先确认运行器安装及版本" }
            require(directory.isAbsolute && directory.isDirectory) { "本机工作目录不存在" }
            val arguments = AcpLaunch.arguments(runtime.engine)
            var owned: Process? = null
            try {
                return withContext(Dispatchers.IO) {
                    val process = ProcessBuilder(runtime.command + arguments).directory(directory.canonicalFile)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).apply {
                            environment().clear(); environment().putAll(environment)
                            when (runtime.engine) {
                                "gemini" -> {
                                    val home = File(runtime.home).absoluteFile
                                    require(home.name == ".gemini") { "Gemini 数据目录无法识别" }
                                    environment()["GEMINI_CLI_HOME"] = home.parent
                                }
                                "hermes" -> environment()["HERMES_HOME"] = runtime.home
                            }
                        }.start().also { owned = it }
                    LocalAcpTransport(process)
                }
            } catch (e: Exception) { owned?.let(LocalRuntimeDiscovery::stopOwnedProcess); throw e }
        }
        suspend fun connect(runtime: LocalRuntimeInstallation, directory: File): AcpClient {
            val client = AcpClient(start(runtime, directory))
            try { client.initialize(); return client } catch (e: Exception) { client.close(); throw e }
        }
    }
}
