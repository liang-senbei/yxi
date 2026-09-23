package app.yxi.desktop

import org.json.JSONArray

internal data class AcpConfigValue(val id: String, val name: String)
internal data class AcpConfigSelector(val id: String, val name: String, val category: String, val current: String, val values: List<AcpConfigValue>)

internal fun acpConfigSelectors(raw: JSONArray): List<AcpConfigSelector> {
    require(raw.length() <= 128)
    return (0 until raw.length()).mapNotNull { index ->
        val item = raw.getJSONObject(index)
        if (item.optString("type") != "select") return@mapNotNull null
        val options = item.getJSONArray("options")
        require(options.length() <= 2048)
        val values = (0 until options.length()).flatMap { position ->
            val option = options.getJSONObject(position)
            val group = option.optJSONArray("options")
            if (group == null) listOf(AcpConfigValue(option.getString("value"), option.getString("name")))
            else {
                require(group.length() <= 2048)
                (0 until group.length()).map { i -> group.getJSONObject(i).let { AcpConfigValue(it.getString("value"), it.getString("name")) } }
            }
        }
        require(values.size <= 4096 && values.map { it.id }.distinct().size == values.size)
        require(values.all { it.id.isNotBlank() && it.name.isNotBlank() })
        AcpConfigSelector(item.getString("id"), item.getString("name"), item.optString("category"), item.getString("currentValue"), values)
    }.also { selectors -> require(selectors.map { it.id }.distinct().size == selectors.size && selectors.all { it.id.isNotBlank() && it.name.isNotBlank() }) }
}
