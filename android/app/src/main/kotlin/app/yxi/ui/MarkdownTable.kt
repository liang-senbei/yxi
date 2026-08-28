package app.yxi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import org.intellij.markdown.ast.getTextInNode

/**
 * 聊天里 markdown 表格的**自己画的版本** —— 换掉库的默认表格。
 *
 * 病根：库默认给每列均分屏宽 + 单行，长内容全变省略号，用户读不到（原话：表格里全是省略号）。
 * 这里：**每列定宽 + 内容换行**，整张表塞进**横向滚动**里 —— 窄表原样看全，宽表左右滑，
 * 一个字都不截。用交替行底色分隔，不靠会在横滚里算歪的横线。
 *
 * ⚠️ 直接从 AST 节点取**原始表格文本**（`getTextInNode`）自己按 `|` 切，不去啃库的表格 AST 细节。
 */
@Composable
fun MarkdownScrollTable(model: MarkdownComponentModel) {
    val raw = remember(model.node, model.content) { model.node.getTextInNode(model.content).toString() }
    val rows = remember(raw) { parseMdTable(raw) }
    if (rows.isEmpty()) return
    val cols = rows.maxOf { it.size }
    val colW = 158.dp
    val line = MaterialTheme.colorScheme.outlineVariant

    Column(
        Modifier
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, line, RoundedCornerShape(12.dp))
            .horizontalScroll(rememberScrollState()),
    ) {
        rows.forEachIndexed { r, cells ->
            val header = r == 0
            Row(
                Modifier
                    .height(IntrinsicSize.Min)
                    .background(
                        when {
                            header -> MaterialTheme.colorScheme.surfaceContainerHigh
                            r % 2 == 0 -> MaterialTheme.colorScheme.surfaceContainerLowest
                            else -> Color.Transparent
                        }
                    ),
            ) {
                for (c in 0 until cols) {
                    if (c > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(line))
                    Text(
                        cells.getOrElse(c) { "" },
                        Modifier.width(colW).padding(12.dp, 9.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                        ),
                        color = if (header) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 把 markdown 表格源码切成 `行 × 单元格`。第 0 行是表头，分隔行（`|---|:--:|`）丢掉。
 * ragged 的行不补齐 —— 渲染时按最大列数 `getOrElse` 兜空。
 */
internal fun parseMdTable(raw: String): List<List<String>> {
    val out = ArrayList<List<String>>()
    for (lineRaw in raw.trim().lines()) {
        val t = lineRaw.trim()
        if (!t.contains('|')) continue
        // 分隔行：只由 | - : 空格组成，且含 -
        if (t.contains('-') && t.all { it == '|' || it == '-' || it == ':' || it == ' ' }) continue
        // 去掉首尾的 |，按 | 切（\| 转义的竖线先临时替换）
        val cells = t.trim().removePrefix("|").removeSuffix("|")
            .split(Regex("(?<!\\\\)\\|"))
            .map { it.trim().replace("\\|", "|") }
        if (cells.isNotEmpty()) out.add(cells)
    }
    return out
}
