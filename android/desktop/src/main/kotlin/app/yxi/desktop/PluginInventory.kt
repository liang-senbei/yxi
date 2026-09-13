package app.yxi.desktop

import app.yxi.ssh.Shell
import org.json.JSONObject

internal data class InstalledPlugin(val id: String, val version: String, val scope: String, val project: String, val path: String, val present: Boolean, val userEnabled: Boolean?)
internal data class PluginInventory(val plugins: List<InstalledPlugin>, val warnings: List<String>) {
    companion object {
        fun command(): String = "python3 -c " + Shell.q(PluginInventory::class.java.getResource("/app/yxi/desktop/plugin-inventory.py")!!.readText())
        fun parse(raw: String): PluginInventory {
            val line = raw.lineSequence().lastOrNull { it.startsWith("__YXI_PLUGINS__:") } ?: error("插件状态未读到，请检查连接与服务器 Python3 后重试")
            val json = JSONObject(line.removePrefix("__YXI_PLUGINS__:"))
            require(json.getString("runner") == "claude")
            val items = json.getJSONArray("plugins")
            val warnings = json.getJSONArray("warnings")
            return PluginInventory((0 until items.length()).map {
                val p = items.getJSONObject(it)
                InstalledPlugin(p.getString("id"), p.getString("version"), p.getString("scope"), p.getString("project"), p.getString("path"), p.getBoolean("present"), p.opt("userEnabled") as? Boolean)
            }, (0 until warnings.length()).map { warnings.getString(it) })
        }
    }
}
