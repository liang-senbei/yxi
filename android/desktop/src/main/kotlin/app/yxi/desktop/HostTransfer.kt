package app.yxi.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class HostImportRow(val host: Host, val duplicate: Boolean, val reassignedId: Boolean)
internal data class HostImportPlan(val original: List<Host>, val rows: List<HostImportRow>, val ignoredAuthentication: Boolean) {
    val additions get() = rows.filterNot { it.duplicate }.map { it.host }
    fun merge(current: List<Host>): List<Host> {
        check(current == original) { "服务器列表已变化，请重新打开导入预览" }
        return current + additions
    }
}
internal object HostTransfer {
    fun export(hosts: List<Host>): String = JSONObject().put("format", "yxi-hosts-export").put("version", 1)
        .put("hosts", JSONArray(hosts.map { h -> JSONObject().put("id", h.id).put("alias", h.alias).put("hostname", h.hostname).put("port", h.port).put("username", h.username).put("color", h.color) })).toString(2)
    private fun endpoint(host: Host) = listOf(host.hostname.trim().removeSurrounding("[", "]").trimEnd('.').lowercase(), host.port.toString(), host.username)
    fun preview(raw: String, existing: List<Host>): HostImportPlan {
        require(raw.length <= 4 * 1024 * 1024) { "导入文件最多4MiB" }
        val array = try {
            if (raw.trimStart().startsWith('[')) JSONArray(raw)
            else JSONObject(raw).let { require(it.optString("format") == "yxi-hosts-export" && it.optInt("version") == 1); it.getJSONArray("hosts") }
        } catch (_: Exception) { error("不是有效的服务器导出文件或旧版服务器列表") }
        require(array.length() <= 1000) { "单次最多导入1000台服务器" }
        val endpoints = existing.map(::endpoint).toMutableSet()
        val ids = existing.map { it.id }.toMutableSet()
        var ignored = false
        val rows = (0 until array.length()).map { index ->
            val item = array.optJSONObject(index) ?: error("第${index + 1}条不是服务器记录")
            val address = (item.opt("hostname") as? String)?.trim().orEmpty()
            val user = (item.opt("username") as? String)?.trim().orEmpty()
            val port = if (!item.has("port")) 22 else item.opt("port").toString().toIntOrNull()
            require(address.isNotBlank() && address.none { it.isWhitespace() || it < ' ' || it in "/\\" } && !address.contains("://")) { "第${index + 1}条服务器地址无效" }
            require(address.count { it == ':' } != 1 && address.startsWith('[') == address.endsWith(']')) { "第${index + 1}条地址不能附带端口，IPv6括号需完整" }
            require(user.isNotBlank() && user.none { it < ' ' }) { "第${index + 1}条用户名无效" }
            require(port != null && port in 1..65535) { "第${index + 1}条端口无效" }
            val incomingId = (item.opt("id") as? String).orEmpty()
            require(incomingId.length <= 200 && incomingId.none { it < ' ' }) { "第${index + 1}条标识无效" }
            ignored = ignored || item.optString("password").isNotBlank() || item.optString("keyPath").isNotBlank() || item.has("privateKey")
            val host = Host(incomingId, (item.opt("alias") as? String).orEmpty().trim(), address, port, user,
                color = item.optString("color").takeIf { it.isEmpty() || Regex("#[0-9a-fA-F]{6}").matches(it) }.orEmpty())
            val duplicate = !endpoints.add(endpoint(host))
            val conflict = !duplicate && incomingId.isNotBlank() && incomingId in ids
            val id = if (incomingId.isBlank() || conflict) UUID.randomUUID().toString() else incomingId
            if (!duplicate) ids.add(id)
            HostImportRow(host.copy(id = id), duplicate, conflict)
        }
        return HostImportPlan(existing.toList(), rows, ignored)
    }
}
