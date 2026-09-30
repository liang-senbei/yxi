package app.yxi.desktop

import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject
import java.io.File

/** 简单偏好（主题 / 界面风格 / 通知档 / 关窗行为…），prefs.json；Compose 里读要能重组，所以放在 state 里。
 *  先写盘、后改内存：写不进去就保持原值（界面不切换），失败原因交给调用方说出来。
 *  单独成类是为了测试能指到临时文件，不碰用户真实的 prefs.json。 */
internal class PrefStore(file: File, onReadError: (String) -> Unit = {}) {
    private val data = DurableFile(file) { JSONObject(it) }
    private val values = mutableStateMapOf<String, String>().apply {
        runCatching { JSONObject(data.read() ?: "{}").let { j -> j.keys().forEach { put(it, j.getString(it)) } } }
            .onFailure { onReadError("无法读取桌面偏好：${it.message}") }
    }

    fun get(key: String, default: String): String = values[key] ?: default

    /** 存上返回 null；存不上返回「[failure]：原因」，内存里的值不动。 */
    fun set(key: String, value: String, failure: String = "偏好未保存"): String? =
        runCatching { data.write(JSONObject(values.toMap() + (key to value)).toString()); values[key] = value }
            .exceptionOrNull()?.let { "$failure：${it.message ?: it.javaClass.simpleName}" }
}
