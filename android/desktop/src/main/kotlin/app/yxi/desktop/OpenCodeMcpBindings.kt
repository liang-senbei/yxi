package app.yxi.desktop

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One journal per resource and owned service. Connection is not model-call proof or OAuth success. */
internal class OpenCodeMcpBindings(private val hostKey: String, private val serviceId: String, private val client: OpenCodeClient, file: File) {
    private val operation = Mutex()
    private val disk = DurableFile(file) { raw ->
        val state = JSONObject(raw)
        require(state.getString("phase") in setOf("sending", "unknown", "connected", "needs_auth", "needs_client_registration", "failed", "disabled"))
        require(state.getString("serviceId").isNotBlank() && state.getString("resourceKey").isNotBlank())
    }
    private var last: JSONObject? = null
    private var problem: String? = null
    init {
        require(hostKey.isNotBlank() && serviceId.isNotBlank())
        try { last = disk.read()?.let(::JSONObject); if (disk.recovered) problem = "MCP操作记录从备份恢复，请核对运行器状态" }
        catch (_: Exception) { problem = "MCP操作记录无法读取，原文件已保留" }
    }
    suspend fun apply(record: SharedMcpRecord): String = operation.withLock { client.mcpMutation.withLock {
        check(problem == null) { problem.orEmpty() }
        val definition = record.definition
        definition.validate()
        require(!record.retired && definition.hostKey == hostKey && "opencode" in record.desiredRunners) { "共享插件未选择此机器的 OpenCode 或已退役" }
        val nativeConfig = dynamicConfiguration(definition)
        check(last == null) { "已有加载记录，请核对原操作；不覆盖记录或重复写入" }
        check(!client.mcpStatus().has(definition.name)) { "原生运行器已有同名 MCP，未覆盖" }
        val pending = JSONObject().put("operationId", UUID.randomUUID().toString()).put("serviceId", serviceId)
            .put("resourceKey", definition.key).put("revision", record.revision).put("name", definition.name).put("phase", "sending")
        disk.write(pending.toString()); last = pending
        try {
            client.addMcp(definition.name, nativeConfig)
            val status = client.mcpStatus().getJSONObject(definition.name).getString("status")
            check(status in setOf("connected", "needs_auth", "needs_client_registration", "failed", "disabled")) { "原生 MCP 状态无法识别" }
            disk.write(JSONObject(pending.toString()).put("phase", status).toString())
            last = JSONObject(pending.toString()).put("phase", status)
            status
        } catch (e: Exception) {
            val unknown = JSONObject(pending.toString()).put("phase", "unknown")
            runCatching { disk.write(unknown.toString()); last = unknown }
            throw e
        }
    } }
    companion object {
        internal fun dynamicConfiguration(definition: SharedMcpDefinition): JSONObject {
            // POST /mcp does not run config-file interpolation in OpenCode 1.18.32.
            // Local MCP children already inherit the owned server's target-machine environment.
            require(definition.headerVariables.isEmpty()) { "OpenCode 请求头变量需要通过启动配置加载，当前动态连接尚不支持" }
            return SharedMcpSettings.forRunner(definition, "opencode").getJSONObject("mcp").getJSONObject(definition.name)
                .apply { remove("environment") }
        }
    }
    /** Read-only after ambiguous writes; never issues another add request. */
    suspend fun observedStatus(): String? = operation.withLock {
        check(problem == null) { problem.orEmpty() }
        val saved = last ?: return@withLock null
        check(saved.getString("serviceId") == serviceId) { "记录属于旧运行器进程，不能据此判断当前状态" }
        client.mcpStatus().optJSONObject(saved.getString("name"))?.optString("status")
    }
}
