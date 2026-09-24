package app.yxi.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class LocalClaudeControlTransport private constructor(private val process: Process, val requestedSessionId: String, private val lease: ClaudeSessionLease) : ClaudeControlTransport {
    init { process.onExit().thenRun { lease.close() } }
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
        suspend fun start(runtime: LocalRuntimeInstallation, directory: File, inherited: Map<String, String> = System.getenv(),
            settings: JSONObject = ClaudeSubscriptionSettings.overlay(), resumeSessionId: String? = null): LocalClaudeControlTransport {
            require(runtime.engine == "claude" && runtime.ready && runtime.command.isNotEmpty())
            require(directory.isAbsolute && directory.isDirectory)
            val sessionId = resumeSessionId?.also {
                require(java.util.UUID.fromString(it).toString() == it) { "Claude 恢复需要完整原生会话 ID" }
            } ?: java.util.UUID.randomUUID().toString()
            val sessionOption = if (resumeSessionId == null) "--session-id" else "--resume"
            var owned: Process? = null
            var lease: ClaudeSessionLease? = null
            try {
                return withContext(Dispatchers.IO) {
                    val acquired = ClaudeSessionLease.acquire(File(Store.dir, "claude-session-locks"), File(runtime.home), sessionId).also { lease = it }
                    val process = ProcessBuilder(runtime.command + listOf("--settings", settings.toString(), sessionOption, sessionId, "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--permission-prompt-tool", "stdio"))
                        .directory(directory.canonicalFile).redirectError(ProcessBuilder.Redirect.DISCARD).apply {
                            environment().clear(); environment().putAll(ClaudeSubscriptionSettings.environment(inherited))
                            environment()["CLAUDE_CONFIG_DIR"] = runtime.home
                            environment()["DISABLE_AUTOUPDATER"] = "1"
                        }.start().also { owned = it }
                    LocalClaudeControlTransport(process, sessionId, acquired)
                }
            } catch (e: Exception) {
                val process = owned
                if (process == null) lease?.close() else {
                    process.onExit().thenRun { lease?.close() }
                    LocalRuntimeDiscovery.stopOwnedProcess(process)
                }
                throw e
            }
        }
    }
}
