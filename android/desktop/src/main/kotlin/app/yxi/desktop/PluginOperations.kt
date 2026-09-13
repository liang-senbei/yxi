package app.yxi.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class PluginOperationEntry(val host: String, val id: String, val request: String, val status: String)
internal class PluginOperations(file: File) {
    private val disk = DurableFile(file) { decode(it) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    var entries by mutableStateOf<List<PluginOperationEntry>>(emptyList()); private set
    val running = mutableStateListOf<String>()
    var error by mutableStateOf(""); private set
    private var readable = true
    init {
        try {
            val marker = File(file.parentFile, file.name + ".needs-review")
            val recoveryNeeded = if (file.exists()) runCatching { decode(file.readText()) }.isFailure else File(file.parentFile, file.name + ".bak").exists()
            if (recoveryNeeded) DurableFile.replace(marker, "Plugin ledger requires server reconciliation")
            entries = disk.read()?.let(::decode).orEmpty().map { if (it.status == "sending") it.copy(status = "unknown") else it }
            if (marker.exists()) { readable = false; error = "操作记录从备份恢复，可能缺少最近操作；请先核对服务器记录" }
        }
        catch (_: Exception) { readable = false; error = "插件操作记录无法读取，已保留原文件" }
    }
    fun latest(host: String) = entries.lastOrNull { it.host == host }
    fun unresolved(host: String) = entries.any { it.host == host && it.status in setOf("sending", "unknown") }
    private fun save(next: List<PluginOperationEntry>) {
        check(readable) { error }
        try {
            disk.write(JSONObject().put("version", 1).put("entries", JSONArray(next.map { JSONObject().put("host", it.host).put("id", it.id).put("request", it.request).put("status", it.status) })).toString())
            entries = next; error = ""
        } catch (e: Exception) { error = "插件操作记录未保存，请保留窗口并查询原操作"; throw e }
    }
    internal fun begin(host: String, request: JSONObject): PluginOperationEntry {
        check(!unresolved(host)) { "请先查询此主机尚未确认的操作" }
        val entry = PluginOperationEntry(host, request.getString("operation"), request.toString(), "sending")
        check(entries.none { it.id == entry.id })
        save(entries + entry)
        return entry
    }
    internal fun finish(id: String, state: String) {
        val status = when (state) { "configured", "restored" -> state; "changed", "invalid", "invalid-directory", "unsupported-config-home", "unsupported-linked-config", "restore-unavailable" -> "rejected"; else -> "unknown" }
        save(entries.map { if (it.id == id && !(status == "unknown" && it.status in setOf("configured", "restored"))) it.copy(status = status) else it })
    }
    fun submit(conn: Conn, request: JSONObject) {
        val entry = begin(projectKey(conn.host, "/"), request)
        perform(conn, entry, request)
    }
    fun query(conn: Conn, entry: PluginOperationEntry) {
        check(entry.host == projectKey(conn.host, "/"))
        if (entry.id in running) return
        perform(conn, entry, JSONObject().put("action", "status").put("operation", entry.id))
    }
    private fun perform(conn: Conn, entry: PluginOperationEntry, request: JSONObject) {
        running.add(entry.id)
        scope.launch {
            try { finish(entry.id, PluginOperationPlan.result(conn.ssh.exec(PluginOperationPlan.command(request))).getString("state")) }
            catch (_: Exception) { runCatching { finish(entry.id, "unknown") } }
            finally { running.remove(entry.id) }
        }
    }
    companion object {
        private fun decode(raw: String): List<PluginOperationEntry> {
            val root = JSONObject(raw); require(root.getInt("version") == 1)
            val array = root.getJSONArray("entries")
            return (0 until array.length()).map {
                val o = array.getJSONObject(it)
                PluginOperationEntry(o.getString("host"), o.getString("id"), o.getString("request"), o.getString("status")).also { e ->
                    require(Regex("[a-f0-9]{32}").matches(e.id))
                    require(JSONObject(e.request).getString("operation") == e.id)
                    require(e.status in setOf("sending", "unknown", "configured", "restored", "rejected"))
                }
            }.also { require(it.map { e -> e.id }.distinct().size == it.size) }
        }
    }
}
