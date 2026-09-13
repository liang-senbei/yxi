package app.yxi.desktop

import org.json.JSONObject

internal fun trialFeedback(changes: Map<String, String>, originalText: String): String = buildString {
    val css = changes.filterKeys { it != StyleTrial.TEXT }
    if (css.isNotEmpty()) {
        append("\n临时样式试调（尚未写入源文件）：\n")
        append(css.entries.joinToString("\n") { "> ${it.key}: ${it.value};" }); append('\n')
    }
    if (changes.containsKey(StyleTrial.TEXT)) {
        append("\n纯文本试调（不是HTML，尚未写入源文件）：\n")
        append("> 原文：${JSONObject.quote(originalText)}\n")
        append("> 新文：${JSONObject.quote(changes.getValue(StyleTrial.TEXT))}\n")
    }
}
