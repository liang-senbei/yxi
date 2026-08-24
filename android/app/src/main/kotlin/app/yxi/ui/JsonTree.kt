package app.yxi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.yxi.ui.theme.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

private val Mono = FontFamily.Monospace

/**
 * JSON 折叠树。**默认只展开第一层** —— 手机屏幕小，一个几千行的配置全铺开等于没法看。
 * 解析不了就退回纯文本（很多 `.json` 其实是 JSONL 或带注释的 JSON5）。
 */
@Composable
fun JsonBody(text: String) {
    val root = remember(text) { runCatching { JSONTokener(text).nextValue() }.getOrNull() }
    if (root == null || (root !is JSONObject && root !is JSONArray)) {
        CodeFallback(text)
        return
    }
    // 展开状态。用 SnapshotStateList 而不是 Set —— Compose 没有可观察的 Set，
    // 自己包一层反而要写个 @Composable 工厂，那就没法在 remember 里调了
    val expanded = remember(text) { mutableStateListOf("") }
    val rows by remember(text, expanded) { derivedStateOf { flatten(root, "", "", 0, expanded) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp, 4.dp, 14.dp, 28.dp)) {
        items(rows.size, key = { rows[it].path }) { i ->
            val r = rows[i]
            Row(
                Modifier.fillMaxWidth()
                    .clickable(enabled = r.foldable) {
                        if (r.path in expanded) expanded.remove(r.path) else expanded.add(r.path)
                    }
                    .padding(start = (r.depth * 14).dp, top = 3.dp, bottom = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (!r.foldable) " " else if (r.path in expanded) "▾" else "▸",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = Dim,
                )
                if (r.key.isNotEmpty()) {
                    Text(r.key, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = Copper)
                    Text(":", style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = Dim)
                }
                Text(
                    r.value,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    color = if (r.foldable) Muted else valueColor(r.value),
                )
            }
        }
    }
}

@Composable
private fun CodeFallback(text: String) = LazyColumn(
    Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 28.dp),
) {
    val lines = text.lines()
    items(lines.size) { i ->
        Text(lines[i], style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = OnSurfaceVariant)
    }
}

/** ⚠️ `@Composable`：配色跟着风格走，要读当前 Palette。 */
@androidx.compose.runtime.Composable
private fun valueColor(v: String) = when {
    v.startsWith("\"") -> DiffAddFg
    v == "true" || v == "false" || v == "null" -> Teal
    v.firstOrNull()?.isDigit() == true || v.startsWith("-") -> Teal
    else -> OnSurfaceVariant
}

private data class Row(val path: String, val depth: Int, val key: String, val value: String, val foldable: Boolean)

/** 树 → 行列表。只有展开的分支才往下递归，所以巨大的 JSON 也不会一次全建出来。 */
private fun flatten(node: Any?, path: String, key: String, depth: Int, expanded: List<String>): List<Row> {
    val out = ArrayList<Row>()
    when (node) {
        is JSONObject -> {
            out += Row(path, depth, key, "{${node.length()}}", node.length() > 0)
            if (path in expanded) node.keys().forEach { k ->
                out += flatten(node.opt(k), "$path/$k", k, depth + 1, expanded)
            }
        }
        is JSONArray -> {
            out += Row(path, depth, key, "[${node.length()}]", node.length() > 0)
            if (path in expanded) for (i in 0 until node.length()) {
                out += flatten(node.opt(i), "$path/$i", "$i", depth + 1, expanded)
            }
        }
        is String -> out += Row(path, depth, key, "\"$node\"", false)
        else -> out += Row(path, depth, key, node.toString(), false)
    }
    return out
}
