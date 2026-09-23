package app.yxi.desktop

import app.yxi.agent.RemoteAtomicJson
import org.json.JSONObject

internal object RemoteClaudeSharedMcp {
    data class Prepared(val path: String, val hash: String)
    internal val preflightScript get() = requireNotNull(javaClass.getResource("/app/yxi/desktop/claude-mcp-preflight.py")).readText()
    suspend fun stage(conn: Conn, records: List<SharedMcpRecord>, requestId: String): Prepared {
        require(requestId.matches(Regex("[a-f0-9]{32}")))
        val hostKey = projectKey(conn.host, "/")
        require(records.isNotEmpty() && records.all { !it.retired && it.definition.hostKey == hostKey && "claude" in it.desiredRunners })
        require(records.map { it.definition.name }.distinct().size == records.size)
        val entries = JSONObject()
        records.forEach { record -> entries.put(record.definition.name,
            SharedMcpSettings.forRunner(record.definition, "claude").getJSONObject("mcpServers").getJSONObject(record.definition.name)) }
        val home = conn.ssh.exec("printf %s \"\$HOME\"").trim()
        check(home.startsWith('/') && home.none { it < ' ' }) { "无法核对服务器家目录" }
        val path = "$home/.yxi/shared-mcp/$requestId.json"
        val text = JSONObject().put("mcpServers", entries).toString()
        val previous = RemoteAtomicJson.read(conn.ssh, path)
        if (previous.text != null) check(previous.text == text) { "此启动请求的共享配置已变化，未覆盖" }
        else RemoteAtomicJson.write(conn.ssh, path, text, "missing")?.let { error(it) }
        val saved = RemoteAtomicJson.read(conn.ssh, path)
        check(saved.text == text) { "服务器共享配置写入后无法核对" }
        return Prepared(path, RemoteAtomicJson.hash(text.toByteArray()))
    }
}
