package app.yxi.desktop

import org.json.JSONArray
import org.json.JSONObject

/** Display-only projection; never used as the editor's value or save payload. */
internal fun configPreview(raw: String, path: String): String {
    val lower = path.lowercase()
    // dotenv 惯例是 basename 以 .env 开头（.env.local / .env.production），不能只认 .env 结尾
    if (lower.endsWith(".toml") || lower.endsWith(".env") || lower.substringAfterLast('/').startsWith(".env"))
        return "配置源码默认隐藏。点击“查看并编辑源码”读取完整内容。"
    if (!lower.endsWith(".json")) return raw
    fun sensitive(key: String): Boolean {
        val name = key.lowercase().replace(Regex("[^a-z0-9]"), "")
        return listOf("token", "apikey", "secret", "password", "authorization", "credential", "privatekey", "cookie").any { it in name } || name == "headers"
    }
    fun scrub(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().also { clean -> value.keys().forEach { key ->
            clean.put(key, if (sensitive(key) && !value.isNull(key)) "••••••" else scrub(value.opt(key)))
        } }
        is JSONArray -> JSONArray().also { clean -> (0 until value.length()).forEach { clean.put(scrub(value.opt(it))) } }
        else -> value
    }
    return runCatching {
        val parsed = org.json.JSONTokener(raw).nextValue()
        when (val clean = scrub(parsed)) {
            is JSONObject -> clean.toString(2)
            is JSONArray -> clean.toString(2)
            else -> error("Not a configuration object")
        }
    }.getOrElse { "配置预览暂不可用。点击“查看并编辑源码”查看原文；原文件未修改。" }
}
