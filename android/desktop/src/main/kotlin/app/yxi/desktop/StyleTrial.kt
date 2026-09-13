package app.yxi.desktop

import org.json.JSONObject

object StyleTrial {
    const val TEXT = "text-content"
    val properties = linkedMapOf("font-size" to "字号", "font-family" to "字体", TEXT to "文字", "color" to "文字颜色", "background-color" to "背景颜色", "padding" to "内边距", "margin" to "外边距", "gap" to "元素间距", "border-radius" to "圆角")
    val fonts = linkedMapOf("system-ui" to "系统字体", "sans-serif" to "无衬线", "serif" to "衬线", "monospace" to "等宽")
    fun isColor(property: String) = property == "color" || property == "background-color"
    fun range(property: String) = if (property == "font-size") 8f..96f else 0f..120f
    fun initial(property: String, computed: String): String {
        if (property == TEXT) return computed
        if (property == "font-family") return computed.trim().takeIf { it in fonts }.orEmpty()
        if (isColor(property)) {
            val rgb = Regex("rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)").find(computed)
            if (rgb != null) return "#" + (1..3).joinToString("") { "%02x".format(rgb.groupValues[it].toInt().coerceIn(0, 255)) }
            return computed.takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) } ?: "#5359db"
        }
        return computed.substringBefore(' ').removeSuffix("px").takeIf { it.toFloatOrNull()?.isFinite() == true } ?: if (property == "font-size") "32" else "16"
    }
    fun normalize(property: String, input: String): String {
        require(property in properties) { "不支持的样式属性" }
        if (property == TEXT) {
            require(input.length <= 1000 && '\u0000' !in input) { "文字试调最多1000字符，不含空字节" }
            return input
        }
        val value = input.trim()
        if (property == "font-family") {
            require(value in fonts) { "请选择提供的字体类型" }
            return value
        }
        if (isColor(property)) {
            require(Regex("#[0-9a-fA-F]{6}").matches(value)) { "颜色请填写六位十六进制，例如 #5359db" }
            return value.lowercase()
        }
        val number = value.removeSuffix("px").toDoubleOrNull()
        val range = range(property)
        require(number != null && number.isFinite() && number >= range.start && number <= range.endInclusive) { "请输入 ${range.start.toInt()}–${range.endInclusive.toInt()} 之间的像素值" }
        return java.math.BigDecimal.valueOf(number).stripTrailingZeros().toPlainString() + "px"
    }
    fun editorValue(property: String, value: String) = if (property == TEXT) value else value.removeSuffix("px")
    fun script(token: String, operation: String, action: String, property: String = "", value: String = "", group: String = operation): String {
        require(action in listOf("apply", "undo", "reset"))
        val body = JSONObject().put("token", token).put("operation", operation).put("action", action).put("property", property).put("value", if (action == "apply") normalize(property, value) else "").put("group", group)
        return "(()=>{const r=$body;if(window.__yxiStyleTrial)window.__yxiStyleTrial(r);else window.cefQuery({request:JSON.stringify({type:'style-result',token:r.token,operation:r.operation,status:'stale'})});})();"
    }
}
