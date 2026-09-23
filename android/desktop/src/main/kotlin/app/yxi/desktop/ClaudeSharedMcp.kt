package app.yxi.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal object ClaudeSharedMcp {
    /** Adds a private task config only after the native resolver reports every name absent. */
    suspend fun prepare(binary: File, directory: File, definitions: List<SharedMcpDefinition>, destination: File, cancelled: () -> Boolean = { false }): List<String> = withContext(Dispatchers.IO) {
        if (definitions.isEmpty()) return@withContext emptyList()
        require(definitions.all { it.hostKey == "@local" } && definitions.map { it.name }.distinct().size == definitions.size)
        val entries = JSONObject()
        definitions.forEach { definition ->
            definition.validate()
            currentCoroutineContext().ensureActive()
            if (cancelled()) throw CancellationException("任务已取消")
            val process = ProcessBuilder(binary.path, "mcp", "get", definition.name).directory(directory)
                .redirectErrorStream(true).apply { environment()["NO_COLOR"] = "1" }.start()
            val reading = CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(16_385) } }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    currentCoroutineContext().ensureActive()
                    if (cancelled()) throw CancellationException("任务已取消")
                    check(System.nanoTime() < deadline) { "Claude MCP 同名检查超时，未启动任务" }
                }
                val bytes = reading.get(2, TimeUnit.SECONDS)
                check(bytes.size <= 16_384) { "Claude MCP 检查响应过大，未启动任务" }
                val text = bytes.toString(Charsets.UTF_8).replace(Regex("\u001b\\[[0-9;]*m"), "")
                check(process.exitValue() != 0) { "Claude 已有同名 MCP：${definition.name}，未覆盖" }
                check(text.lineSequence().any { it.trim().removePrefix("Error: ") == "No MCP server found with name: ${definition.name}" }) {
                    "无法确认 Claude 的 MCP 名称是否可用：${definition.name}"
                }
            } finally { LocalRuntimeDiscovery.stopOwnedProcess(process); reading.cancel(true) }
            entries.put(definition.name, SharedMcpSettings.forRunner(definition, "claude").getJSONObject("mcpServers").getJSONObject(definition.name))
        }
        DurableFile.replace(destination, JSONObject().put("mcpServers", entries).toString())
        listOf("--mcp-config", destination.absolutePath)
    }
}
