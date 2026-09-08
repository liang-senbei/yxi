package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 正文 14/20、代码 13（design/desktop-reference.md §4.9）。 */
val BodyStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
val CodeStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 18.sp)

/** 最简 markdown 渲染：围栏代码块 + 行内粗体 / 代码 / 标题 / 圆点。 */
@Composable
fun AssistantBody(md: String) {
    val blocks = remember(md) { mdBlocks(md) }
    val t = Tokens.current
    SelectionContainer {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEach { (code, text) ->
                if (code) CodeBlock(text) else Text(inlineMd(text), style = BodyStyle, color = t.textPrimary)
            }
        }
    }
}

/** 代码块：surface1 底 + 细描边（surface1 和面板底色只差一档，光靠底色看不出边界）。 */
@Composable
fun CodeBlock(text: String, modifier: Modifier = Modifier) {
    val t = Tokens.current
    val shape = RoundedCornerShape(Radius)
    SelectionContainer {
        Text(text, Modifier.fillMaxWidth().background(t.surface1, shape).border(1.dp, t.border, shape).then(modifier).padding(10.dp, 8.dp), style = CodeStyle, color = t.textPrimary)
    }
}

/** 按 ``` 围栏切成 (是代码块, 文本)；代码块外的连续正文合成一块。 */
fun mdBlocks(md: String): List<Pair<Boolean, String>> {
    val out = ArrayList<Pair<Boolean, String>>()
    val cur = StringBuilder()
    var code = false
    fun flush() { if (cur.isNotBlank()) out += code to cur.toString().trim('\n'); cur.clear() }
    md.replace("\r", "").split('\n').forEach { line ->
        if (line.trimStart().startsWith("```")) { flush(); code = !code } else cur.append(line).append('\n')
    }
    flush()
    return out
}

private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")
private val HEADING = Regex("""^#{1,6}\s+""")
private val BULLET = Regex("""^(\s*)[-*]\s+""")

/** 行内：`**粗体**`、`` `code` ``；行首 `# 标题` 整行加粗，`- ` 换成圆点。 */
fun inlineMd(text: String): AnnotatedString = buildAnnotatedString {
    text.split('\n').forEachIndexed { n, raw ->
        if (n > 0) append('\n')
        val heading = HEADING.containsMatchIn(raw)
        val line = BULLET.replace(HEADING.replace(raw, ""), "$1• ")
        val start = length   // 标记符（** 和反引号）不进正文，所以行的起点要在追加前记下
        var at = 0
        INLINE.findAll(line).forEach { m ->
            append(line.substring(at, m.range.first))
            val bold = m.groupValues[1]
            if (bold.isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            else withStyle(SpanStyle(fontFamily = Mono, fontSize = 13.sp)) { append(m.groupValues[2]) }
            at = m.range.last + 1
        }
        append(line.substring(at))
        if (heading) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
    }
}
