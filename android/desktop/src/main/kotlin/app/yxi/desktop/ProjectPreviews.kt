package app.yxi.desktop

import androidx.compose.runtime.*
import java.io.File
import org.json.JSONObject

/** Explicit project start addresses, never a history of browser redirects. */
internal class ProjectPreviews(file: File) {
    private val disk = DurableFile(file) { parse(it) }
    private var records by mutableStateOf<Map<String, String>>(emptyMap())
    var error by mutableStateOf("")
        private set
    private var readable = true
    init {
        try {
            records = disk.read()?.let(::parse).orEmpty()
            if (disk.recovered) error = "项目预览设置已从备份恢复"
        } catch (e: Exception) { readable = false; error = "项目预览设置无法读取，原文件已保留：${e.message}" }
    }
    fun address(project: String): String? = records[project]
    @Synchronized fun save(project: String, input: String, expected: String?): String {
        require(project.isNotBlank())
        val address = PreviewAddress.parse(input).url
        update(project, address, expected)
        return address
    }
    @Synchronized fun remove(project: String, expected: String?) = update(project, null, expected)
    private fun update(project: String, value: String?, expected: String?) {
        check(readable) { error }
        check(records[project] == expected) { "项目预览设置已变化，请重新打开设置" }
        val next = records.toMutableMap().apply { if (value == null) remove(project) else put(project, value) }
        try {
            disk.write(JSONObject().put("version", 1).put("projects", JSONObject(next)).toString(2))
            records = next; error = ""
        } catch (e: Exception) { error = "项目预览地址未保存：${e.message}"; throw e }
    }
    companion object {
        private fun parse(raw: String): Map<String, String> {
            val root = JSONObject(raw); require(root.getInt("version") == 1)
            val projects = root.getJSONObject("projects")
            return projects.keys().asSequence().associateWith { key ->
                require(key.isNotBlank())
                val address = projects.getString(key)
                require(PreviewAddress.parse(address).url == address)
                address
            }
        }
    }
}
