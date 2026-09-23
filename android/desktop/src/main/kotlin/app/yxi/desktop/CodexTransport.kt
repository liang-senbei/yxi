package app.yxi.desktop

import app.yxi.ssh.SshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Transport ownership stays with the client; closing it never touches another native session. */
internal interface CodexTransport : AutoCloseable {
    val output: InputStream
    val local: Boolean get() = false
    suspend fun write(text: String): Boolean
}

internal class SshCodexTransport(private val shell: SshSession.Shell) : CodexTransport {
    override val output get() = shell.output
    override suspend fun write(text: String) = shell.write(text)
    override fun close() { shell.close() }
}

internal class LocalCodexTransport private constructor(private val process: Process) : CodexTransport {
    override val output get() = process.inputStream
    override val local = true
    private val closed = AtomicBoolean()
    private val writing = Mutex()
    override suspend fun write(text: String): Boolean = writing.withLock {
        if (closed.get() || !process.isAlive) return@withLock false
        withContext(Dispatchers.IO) {
            process.outputStream.write(text.toByteArray(Charsets.UTF_8)); process.outputStream.flush(); true
        }
    }
    override fun close() { if (closed.compareAndSet(false, true)) LocalRuntimeDiscovery.stopOwnedProcess(process) }
    companion object {
        /** Caller supplies an explicit launch plan. No default authentication or permission changes. */
        suspend fun start(runtime: LocalRuntimeInstallation, arguments: List<String>, environment: Map<String, String>): LocalCodexTransport {
            require(runtime.engine == "codex" && runtime.ready)
            require(arguments.lastOrNull() == "app-server")
            require(arguments.none { '\u0000' in it })
            var owned: Process? = null
            try {
                return withContext(Dispatchers.IO) {
                    val process = ProcessBuilder(runtime.command + arguments).directory(File(System.getProperty("user.home")))
                        .redirectError(ProcessBuilder.Redirect.DISCARD).apply {
                            environment().clear(); environment().putAll(environment); environment()["CODEX_HOME"] = runtime.home
                        }.start().also { owned = it }
                    LocalCodexTransport(process)
                }
            } catch (e: Exception) { owned?.let(LocalRuntimeDiscovery::stopOwnedProcess); throw e }
        }
    }
}
