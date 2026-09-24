package app.yxi.desktop

import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Reads native credential identity without sending a prompt. This does not certify endpoint policy or entitlement. */
internal object ClaudeSubscriptionProbe {
    suspend fun verify(binary: File, directory: File, inherited: Map<String, String>,
        settings: JSONObject = ClaudeSubscriptionSettings.overlay(), cancelled: () -> Boolean = { false }): Unit =
        verify(listOf(binary.absolutePath), directory, inherited, settings, cancelled)

    suspend fun verify(command: List<String>, directory: File, inherited: Map<String, String>,
        settings: JSONObject = ClaudeSubscriptionSettings.overlay(), cancelled: () -> Boolean = { false }): Unit = withContext(Dispatchers.IO) {
        require(command.isNotEmpty() && command.none { '\u0000' in it })
        require(File(command.first()).let { it.isFile && it.canExecute() } && directory.isDirectory)
        currentCoroutineContext().ensureActive()
        if (cancelled()) throw CancellationException("订阅检查已取消")
        val process = ProcessBuilder(command + listOf("--settings", settings.toString(), "auth", "status", "--json"))
            .directory(directory.canonicalFile).redirectError(ProcessBuilder.Redirect.DISCARD).apply {
                environment().clear(); environment().putAll(ClaudeSubscriptionSettings.environment(inherited))
                environment()["DISABLE_AUTOUPDATER"] = "1"
            }.start()
        val reading = CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(65_537) } }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                currentCoroutineContext().ensureActive()
                if (cancelled()) throw CancellationException("订阅检查已取消")
                if (reading.isDone) check(reading.get().size <= 65_536) { "Claude 认证检查响应过大，未发送任务" }
                check(System.nanoTime() < deadline) { "Claude 认证检查超时，未发送任务" }
            }
            val bytes = reading.get(2, TimeUnit.SECONDS)
            check(bytes.size in 1..65_536) { "Claude 认证检查响应无效，未发送任务" }
            val status = try { JSONObject(bytes.toString(Charsets.UTF_8)) }
                catch (_: Exception) { error("Claude 未返回可识别的认证状态，未发送任务") }
            ClaudeSubscriptionSettings.requireOAuthIdentity(status)
            check(process.exitValue() == 0) { "Claude 未确认认证检查成功，未发送任务" }
        } finally { LocalRuntimeDiscovery.stopOwnedProcess(process); reading.cancel(true) }
    }
}
