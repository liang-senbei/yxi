package app.yxi.desktop

import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

/** Machine-owned resource definition. Native credentials remain with each runtime. */
internal data class SharedMcpDefinition(val hostKey: String, val pluginId: String, val sourceId: String, val version: String,
    val name: String, val command: List<String> = emptyList(), val url: String? = null) {
    val identity get() = listOf(hostKey, pluginId, sourceId)
    val key get() = contentHash(JSONArray(listOf(hostKey, pluginId, sourceId, version)).toString().toByteArray())
    fun validate() {
        require(listOf(hostKey, pluginId, sourceId, version).all { it.isNotBlank() && it.length <= 2048 && it.none { c -> c < ' ' } })
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(name)) { "MCP 名称只能包含字母、数字、下划线或短横线" }
        require((url != null) != command.isNotEmpty()) { "请选择命令或远程 URL 其中一种连接方式" }
        if (command.isNotEmpty()) {
            require(command.size <= 128 && command.first().isNotBlank())
            require(command.all { it.length <= 16_384 && '\u0000' !in it })
        }
        url?.let {
            val uri = URI(it)
            require(it.length <= 8192 && it.none { c -> c < ' ' } && uri.userInfo == null && uri.fragment == null && uri.rawQuery == null) { "MCP URL 不能包含凭据、查询参数或片段；认证由运行器单独处理" }
            val host = uri.host ?: error("MCP URL 缺少主机")
            require(uri.scheme == "https" || (uri.scheme == "http" && host in setOf("localhost", "127.0.0.1", "[::1]", "::1"))) { "远程 MCP 必须使用 HTTPS，本机回环地址除外" }
        }
    }
    fun json() = JSONObject().put("hostKey", hostKey).put("pluginId", pluginId).put("sourceId", sourceId).put("version", version)
        .put("name", name).put("command", JSONArray(command)).put("url", url ?: JSONObject.NULL)
    companion object {
        fun parse(json: JSONObject): SharedMcpDefinition {
            val command = json.getJSONArray("command")
            return SharedMcpDefinition(json.getString("hostKey"), json.getString("pluginId"), json.getString("sourceId"), json.getString("version"), json.getString("name"),
                (0 until command.length()).map { command.getString(it) }, if (json.isNull("url")) null else json.getString("url")).also { it.validate() }
        }
    }
}

internal data class SharedMcpRecord(val definition: SharedMcpDefinition, val desiredRunners: Set<String>, val revision: Long, val retired: Boolean = false)

/** These are desired bindings, never a claim that a runtime loaded or authenticated the server. */
internal class SharedMcpRegistry(file: File) {
    private val disk = DurableFile(file) { decode(it) }
    private val recovery = File(file.parentFile, file.name + ".needs-review")
    var records by mutableStateOf<List<SharedMcpRecord>>(emptyList()); private set
    var problem by mutableStateOf(""); private set
    var recoveryReviewRequired by mutableStateOf(false); private set
    init {
        try {
            records = disk.read()?.let(::decode).orEmpty()
            if (disk.recovered) DurableFile.replace(recovery, "Review shared MCP definitions before writes")
            recoveryReviewRequired = recovery.exists()
            if (recoveryReviewRequired) problem = "共享插件索引从备份恢复，请核对后再修改"
        } catch (_: Exception) { problem = "共享插件索引无法读取，已保留原文件" }
    }
    fun forHost(hostKey: String, includeRetired: Boolean = false) = records.filter { it.definition.hostKey == hostKey && (includeRetired || !it.retired) }
    fun confirmRecoveryReviewed() {
        check(recoveryReviewRequired)
        check(recovery.delete()) { "核对标记无法更新" }
        recoveryReviewRequired = false; problem = ""
    }
    @Synchronized fun save(definition: SharedMcpDefinition, desiredRunners: Set<String>, expectedRevision: Long?): SharedMcpRecord {
        check(problem.isBlank()) { problem }
        definition.validate()
        require(desiredRunners.all { it in setOf("claude", "codex", "opencode") })
        val previous = records.singleOrNull { it.definition.key == definition.key }
        check(previous?.revision == expectedRevision) { "共享插件已变化，请刷新后重试" }
        check(previous?.retired != true) { "历史版本不能直接修改，请选择恢复或替换版本" }
        check(records.none { !it.retired && it.definition.key != definition.key &&
            (it.definition.identity == definition.identity || (it.definition.hostKey == definition.hostKey && it.definition.name == definition.name)) }) { "此机器已登记同名 MCP 或其它活动版本，请使用版本替换" }
        val next = SharedMcpRecord(definition.copy(command = definition.command.toList()), desiredRunners.toSet(), (previous?.revision ?: -1) + 1)
        val updated = records.filterNot { it.definition.key == definition.key } + next
        commit(updated)
        return next
    }
    /** Desired version transition only; native binding receipts remain separate and may still reference the old version. */
    @Synchronized fun replaceVersion(currentKey: String, expectedRevision: Long, definition: SharedMcpDefinition): SharedMcpRecord {
        val current = current(currentKey, expectedRevision)
        check(!current.retired)
        definition.validate()
        require(definition.identity == current.definition.identity && definition.name == current.definition.name && definition.key != currentKey) { "版本替换必须属于同一机器、插件、来源及名称" }
        val historic = records.singleOrNull { it.definition.key == definition.key }
        check(historic == null || (historic.retired && historic.definition == definition)) { "历史版本内容不一致，不能覆盖回滚记录" }
        val next = SharedMcpRecord(definition.copy(command = definition.command.toList()), current.desiredRunners.toSet(), (historic?.revision ?: -1) + 1)
        val updated = records.filterNot { it.definition.key == definition.key }.map {
            if (it.definition.key == currentKey) it.copy(retired = true, revision = it.revision + 1) else it
        } + next
        commit(updated)
        return next
    }
    @Synchronized fun retire(key: String, expectedRevision: Long) {
        val current = current(key, expectedRevision)
        check(!current.retired)
        commit(records.map { if (it.definition.key == key) it.copy(retired = true, revision = it.revision + 1) else it })
    }
    @Synchronized fun restore(key: String, expectedRevision: Long): SharedMcpRecord {
        val current = current(key, expectedRevision)
        check(current.retired)
        check(records.none { !it.retired && (it.definition.identity == current.definition.identity ||
            (it.definition.hostKey == current.definition.hostKey && it.definition.name == current.definition.name)) }) { "已有活动版本或同名 MCP，未恢复" }
        val next = current.copy(retired = false, revision = current.revision + 1)
        commit(records.map { if (it.definition.key == key) next else it })
        return next
    }
    private fun current(key: String, revision: Long): SharedMcpRecord {
        check(problem.isBlank()) { problem }
        return records.single { it.definition.key == key }.also { check(it.revision == revision) { "共享插件已变化，请刷新" } }
    }
    private fun commit(updated: List<SharedMcpRecord>) {
        disk.write(JSONArray(updated.map { JSONObject().put("definition", it.definition.json()).put("runners", JSONArray(it.desiredRunners.sorted()))
            .put("revision", it.revision).put("retired", it.retired) }).toString())
        records = updated
    }
    private fun decode(raw: String): List<SharedMcpRecord> {
        require(raw.length <= 4 * 1024 * 1024)
        val rows = JSONArray(raw)
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index); val runners = row.getJSONArray("runners")
            SharedMcpRecord(SharedMcpDefinition.parse(row.getJSONObject("definition")), (0 until runners.length()).map { runners.getString(it) }.toSet(), row.getLong("revision"), row.optBoolean("retired", false))
                .also { require(it.revision >= 0 && it.desiredRunners.all { runner -> runner in setOf("claude", "codex", "opencode") }) }
        }.also { values ->
            require(values.map { it.definition.key }.distinct().size == values.size)
            val active = values.filterNot { it.retired }
            require(active.map { it.definition.hostKey to it.definition.name }.distinct().size == active.size)
            require(active.map { it.definition.identity }.distinct().size == active.size)
        }
    }
}

/** Native configuration fragments only. Applying them requires native read-back and conflict checks. */
internal object SharedMcpSettings {
    fun forRunner(definition: SharedMcpDefinition, runner: String): JSONObject {
        definition.validate()
        val local = definition.command.isNotEmpty()
        val entry = JSONObject()
        return when (runner) {
            "claude" -> {
                if (local) entry.put("type", "stdio").put("command", definition.command.first()).put("args", JSONArray(definition.command.drop(1)))
                else entry.put("type", "http").put("url", definition.url)
                JSONObject().put("mcpServers", JSONObject().put(definition.name, entry))
            }
            "codex" -> {
                if (local) entry.put("command", definition.command.first()).put("args", JSONArray(definition.command.drop(1))) else entry.put("url", definition.url)
                JSONObject().put("mcp_servers", JSONObject().put(definition.name, entry.put("enabled", true)))
            }
            "opencode" -> {
                if (local) entry.put("type", "local").put("command", JSONArray(definition.command)) else entry.put("type", "remote").put("url", definition.url)
                JSONObject().put("mcp", JSONObject().put(definition.name, entry.put("enabled", true)))
            }
            else -> error("此运行器的共享 MCP 配置尚未适配")
        }
    }
}
