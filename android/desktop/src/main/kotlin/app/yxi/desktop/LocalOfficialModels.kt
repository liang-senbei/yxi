package app.yxi.desktop

import org.json.JSONObject

internal data class LocalOfficialModel(val id: String, val label: String, val isDefault: Boolean)

internal object LocalOfficialModels {
    suspend fun load(fetch: suspend (String?) -> JSONObject): List<LocalOfficialModel> {
        val models = linkedMapOf<String, LocalOfficialModel>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(20) {
            val result = fetch(cursor).getJSONObject("result")
            val rows = result.getJSONArray("data")
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                if (row.optBoolean("hidden")) continue
                val id = row.getString("model")
                require(id.isNotBlank() && id.none { it < ' ' }) { "运行器返回无效模型 ID" }
                models[id] = LocalOfficialModel(id, row.optString("displayName").ifBlank { id }, row.optBoolean("isDefault"))
            }
            cursor = if (result.isNull("nextCursor")) null else result.optString("nextCursor").takeIf { it.isNotBlank() }
            if (cursor == null) {
                check(models.isNotEmpty()) { "运行器没有返回可选官方模型" }
                return models.values.toList()
            }
            check(cursors.add(cursor!!)) { "模型列表分页标识重复，请刷新" }
        }
        error("模型列表超过分页上限，未使用不完整列表")
    }
}
