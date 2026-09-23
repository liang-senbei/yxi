package app.yxi.desktop

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.Questioner
import com.jediterm.terminal.TtyConnector
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/** Owned interactive PTY. Output stays in the terminal; it is never treated as a login receipt. */
internal class LocalAuthenticationTerminal private constructor(private val process: PtyProcess) : TtyConnector, AutoCloseable {
    private val closed = AtomicBoolean()
    private val reader = InputStreamReader(process.inputStream, Charsets.UTF_8)
    private val output = process.outputStream
    override fun init(questioner: Questioner) = !closed.get()
    override fun getName() = "本地运行器认证"
    override fun isConnected() = !closed.get() && process.isAlive
    override fun ready() = reader.ready()
    override fun read(buf: CharArray, offset: Int, length: Int) = reader.read(buf, offset, length)
    @Synchronized override fun write(bytes: ByteArray) { check(!closed.get()); output.write(bytes); output.flush() }
    override fun write(string: String) = write(string.toByteArray(Charsets.UTF_8))
    override fun resize(termSize: TermSize) {
        if (!closed.get() && process.isAlive) process.setWinSize(WinSize(termSize.columns.coerceAtLeast(1), termSize.rows.coerceAtLeast(1)))
    }
    override fun waitFor() = process.waitFor()
    suspend fun awaitExit(): Int = withContext(Dispatchers.IO) { process.waitFor() }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            LocalRuntimeDiscovery.stopOwnedProcess(process)
            runCatching { process.destroy() }
        }
    }
    companion object {
        suspend fun start(plan: AcpTerminalAuthPlan): LocalAuthenticationTerminal {
            var owned: LocalAuthenticationTerminal? = null
            try { return withContext(Dispatchers.IO) {
                val environment = plan.environment.toMutableMap().apply { putIfAbsent("TERM", "xterm-256color") }
                val process = PtyProcessBuilder(plan.command.toTypedArray()).setDirectory(plan.directory).setEnvironment(environment)
                    .setInitialColumns(120).setInitialRows(30).setUseWinConPty(true).start()
                try { LocalAuthenticationTerminal(process).also { owned = it } }
                catch (e: Exception) { LocalRuntimeDiscovery.stopOwnedProcess(process); throw e }
            } } catch (e: Exception) { owned?.close(); throw e }
        }
    }
}
