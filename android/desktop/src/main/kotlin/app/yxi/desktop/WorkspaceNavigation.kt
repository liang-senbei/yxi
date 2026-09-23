package app.yxi.desktop

import androidx.compose.runtime.*
import app.yxi.agent.Session
import app.yxi.agent.SessionState
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal fun normalizeProjectPath(path: String): String {
    if (!path.startsWith('/')) return path.trim().trimEnd('/')
    val parts = mutableListOf<String>()
    path.split('/').forEach { when (it) { "", "." -> Unit; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex); else -> parts += it } }
    return "/" + parts.joinToString("/")
}
private fun navigationKey(vararg parts: String) = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }
internal fun projectKey(host: Host, path: String) = navigationKey(host.id, host.hostname.lowercase(), host.port.toString(), host.username, normalizeProjectPath(path))
internal fun taskNavigationKey(host: Host, session: Session) = navigationKey(host.id, host.hostname.lowercase(), host.port.toString(), host.username,
    session.runtimeId.ifEmpty { "legacy:${session.name}:${normalizeProjectPath(session.cwd)}" })

internal data class ProjectSection(val path: String, val sessions: List<Session>) {
    val label get() = path.substringAfterLast('/').ifBlank { if (path == "/") "/" else "未识别路径" }
}
internal data class FavoriteLaunch(val key: String, val title: String, val directory: String, val agent: String)
internal fun projectSections(sessions: List<Session>): List<ProjectSection> = sessions.groupBy { normalizeProjectPath(it.cwd) }
    .map { (path, members) -> ProjectSection(path, members.sortedByDescending { it.lastActivity }) }
    .sortedWith(compareBy<ProjectSection> { it.label.lowercase() }.thenBy { it.path })

/** Client-side organisation, with an injected durable file for isolated tests. Never mutates remote sessions. */
class WorkspaceNavigation(file: File) {
    private val disk = DurableFile(file) { raw ->
        val j = JSONObject(raw)
        require(j.optInt("version") == 1) { "不支持的任务整理数据版本" }
        require(j.get("tasks") is JSONObject && j.get("projects") is JSONObject)
        listOf("tasks", "projects").forEach { group -> j.getJSONObject(group).let { o -> o.keys().forEach { require(o.get(it) is JSONObject) } } }
    }
    private var data by mutableStateOf(JSONObject().put("version", 1).put("tasks", JSONObject()).put("projects", JSONObject()))
    var error by mutableStateOf("")
        private set
    private var readable = true
    init {
        runCatching { disk.read()?.let { data = JSONObject(it) }; if (disk.recovered) error = "任务整理记录已从备份恢复" }
            .onFailure { readable = false; error = "任务整理记录无法读取，原文件已保留：${it.message}" }
    }
    val mode get() = data.optString("mode", "全部").takeIf { it in listOf("全部", "待处理", "归档") } ?: "全部"
    private fun task(key: String) = data.getJSONObject("tasks").optJSONObject(key) ?: JSONObject()
    fun title(key: String): String? = task(key).optString("title").takeIf { it.isNotBlank() }
    fun pinned(key: String) = task(key).optInt("pin", 0) > 0
    fun pinOrder(key: String) = task(key).optInt("pin", Int.MAX_VALUE)
    fun archived(key: String) = task(key).optBoolean("archived", false)
    fun muted(key: String) = task(key).optBoolean("muted", false)
    fun favorite(key: String) = task(key).optBoolean("favorite", false)
    internal fun group(key: String) = task(key).optString("group", "")
    internal fun setGroup(key: String, group: String) = editTask(key) { it.put("group", group) }
    internal fun setNativeFavorite(key: String, enabled: Boolean) = editTask(key) { it.put("favorite", enabled) }
    /** Codex retains its thread identity in the registry; a favorite reopens that thread. */
    internal fun setCodexFavorite(task: CodexTaskRecord, enabled: Boolean) = editTask(task.key) {
        it.put("favorite", enabled)
    }
    fun setFavorite(key: String, host: Host, session: Session, enabled: Boolean) = editTask(key) {
        if (enabled) {
            require(session.cwd.startsWith('/') && session.cwd.none { c -> c < ' ' }) { "收藏需要有效的服务器目录" }
            it.put("favorite", true).put("favoriteHost", projectKey(host, "/"))
                .put("favoritePath", session.cwd).put("favoriteAgent", if (session.isCodex) "codex" else "claude")
                .put("favoriteTitle", title(key) ?: session.short)
        } else it.put("favorite", false)
    }
    internal fun favorites(host: Host): List<FavoriteLaunch> {
        val tasks = data.getJSONObject("tasks")
        return tasks.keys().asSequence().mapNotNull { key ->
            val item = tasks.getJSONObject(key)
            if (!item.optBoolean("favorite") || item.optString("favoriteHost") != projectKey(host, "/")) null
            else FavoriteLaunch(key, title(key) ?: item.optString("favoriteTitle"), item.optString("favoritePath"), item.optString("favoriteAgent"))
                .takeIf { it.directory.startsWith('/') && it.agent in listOf("claude", "codex") }
        }.sortedBy { it.title.lowercase() }.toList()
    }
    internal fun removeFavorite(key: String) = editTask(key) { it.put("favorite", false) }
    internal fun moveFavorite(oldKey: String, newKey: String, host: Host, session: Session) = edit { root ->
        require(session.cwd.startsWith('/') && session.cwd.none { it < ' ' })
        val tasks = root.getJSONObject("tasks")
        val item = tasks.optJSONObject(newKey) ?: JSONObject()
        item.put("favorite", true).put("favoriteHost", projectKey(host, "/")).put("favoritePath", session.cwd)
            .put("favoriteAgent", if (session.isCodex) "codex" else "claude").put("favoriteTitle", title(newKey) ?: session.short)
        tasks.put(newKey, item)
        if (oldKey != newKey) tasks.optJSONObject(oldKey)?.put("favorite", false)
    }
    fun setMuted(key: String, value: Boolean) = editTask(key) { it.put("muted", value) }
    fun shouldNotify(key: String, onlyPinned: Boolean): Boolean {
        if (!readable || muted(key)) return false
        val tasks = data.getJSONObject("tasks")
        val hasPins = tasks.keys().asSequence().any { tasks.getJSONObject(it).optInt("pin", 0) > 0 }
        return !onlyPinned || !hasPins || pinned(key)
    }
    fun collapsed(key: String, default: Boolean) = data.getJSONObject("projects").optJSONObject(key)?.optBoolean("collapsed", default) ?: default
    fun projectTitle(key: String): String? = data.getJSONObject("projects").optJSONObject(key)?.optString("title")?.takeIf { it.isNotBlank() }
    fun renameProject(key: String, title: String) = editProject(key) {
        require(title.trim().length <= 160) { "显示名称最多160个字符" }
        if (title.isBlank()) it.remove("title") else it.put("title", title.trim())
    }
    private fun editProject(key: String, change: (JSONObject) -> Unit) = edit { root ->
        val projects = root.getJSONObject("projects")
        val item = projects.optJSONObject(key) ?: JSONObject()
        change(item); projects.put(key, item)
    }
    private fun edit(change: (JSONObject) -> Unit) {
        if (!readable) return // Do not replace unreadable pin/archive/title data with an empty fallback.
        runCatching {
            val next = JSONObject(data.toString()); change(next); disk.write(next.toString(2)); data = next; error = ""
        }.onFailure { error = "整理操作未保存：${it.message}" }
    }
    private fun editTask(key: String, change: (JSONObject) -> Unit) = edit { root ->
        val tasks = root.getJSONObject("tasks")
        val item = tasks.optJSONObject(key) ?: JSONObject()
        change(item); tasks.put(key, item)
    }
    fun setMode(value: String) { require(value in listOf("全部", "待处理", "归档")); if (mode != value) edit { it.put("mode", value) } }
    fun rename(key: String, title: String) = editTask(key) {
        require(title.trim().length <= 160) { "显示名称最多160个字符" }
        if (title.isBlank()) it.remove("title") else it.put("title", title.trim())
    }
    fun setArchived(key: String, value: Boolean) = editTask(key) { it.put("archived", value) }
    fun togglePin(key: String) {
        val tasks = data.getJSONObject("tasks")
        val nextRank = tasks.keys().asSequence().map { tasks.getJSONObject(it).optInt("pin", 0) }.maxOrNull().orEmptyRank() + 1
        editTask(key) { if (pinned(key)) it.remove("pin") else it.put("pin", nextRank) }
    }
    fun movePin(key: String, delta: Int, siblings: List<String>) {
        val order = siblings.filter(::pinned).sortedBy(::pinOrder)
        val index = order.indexOf(key); val other = index + delta
        if (index < 0 || other !in order.indices) return
        val first = pinOrder(key); val second = pinOrder(order[other])
        edit { root -> val tasks = root.getJSONObject("tasks"); tasks.getJSONObject(key).put("pin", second); tasks.getJSONObject(order[other]).put("pin", first) }
    }
    fun setCollapsed(key: String, value: Boolean) {
        if (collapsed(key, !value) != value) editProject(key) { it.put("collapsed", value) }
    }
    private fun Int?.orEmptyRank() = this ?: 0
    fun visible(key: String, state: SessionState): Boolean = when (mode) {
        "归档" -> archived(key)
        "待处理" -> state == SessionState.NeedsYou
        else -> !archived(key) || state == SessionState.NeedsYou
    }
}
