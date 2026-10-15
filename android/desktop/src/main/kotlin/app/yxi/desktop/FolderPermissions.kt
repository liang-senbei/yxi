package app.yxi.desktop

import app.yxi.agent.PermissionMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 按文件夹记住新会话的权限模式（M4i，PRD §6「权限模式」：参照端按文件夹记住上次的模式，计划模式只作用于当前会话）。
 * 存在偏好 [PREF] 里：一个 JSON 数组，每项 {k: 主机 id + 换行 + 目录, m: 模式的 nativeId}，最近用的在最后，最多 [MAX] 项（多了丢最早的）。
 * 计划模式（只对这一个会话）和完全访问（Yxi 要求每次明确选）不记；记的时候原来的那条留着。
 */
internal object FolderPermissions {
    const val PREF = "ui.folderPermissions"
    const val MAX = 200

    private fun key(host: String, directory: String) = host + "\n" + directory.trim().trimEnd('/').ifEmpty { "/" }

    private fun entries(raw: String): List<JSONObject> = runCatching { JSONArray(raw) }.getOrNull()
        ?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

    /** 这台主机、这个目录上次建会话用的模式；没记过（或记的值认不出）返回 null。 */
    fun recall(raw: String, host: String, directory: String): PermissionMode? {
        val k = key(host, directory)
        val id = entries(raw).lastOrNull { it.optString("k") == k }?.optString("m") ?: return null
        return PermissionMode.entries.firstOrNull { it.nativeId == id }
    }

    /** 记下这次用的模式，返回新的偏好值；不该记（计划 / 完全访问）或没变时返回 null。 */
    fun remember(raw: String, host: String, directory: String, mode: PermissionMode): String? {
        if (mode == PermissionMode.Plan || mode == PermissionMode.Bypass) return null
        val k = key(host, directory)
        val old = entries(raw)
        if (old.lastOrNull()?.let { it.optString("k") == k && it.optString("m") == mode.nativeId } == true) return null
        val kept = old.filter { it.optString("k") != k } + JSONObject().put("k", k).put("m", mode.nativeId)
        return JSONArray(kept.takeLast(MAX)).toString()
    }

    fun recall(host: String, directory: String): PermissionMode? = recall(Store.pref(PREF, "[]"), host, directory)
    fun remember(host: String, directory: String, mode: PermissionMode) {
        remember(Store.pref(PREF, "[]"), host, directory, mode)?.let { Store.setPref(PREF, it, "权限模式没记住") }
    }
}
