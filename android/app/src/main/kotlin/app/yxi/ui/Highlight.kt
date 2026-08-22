package app.yxi.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import app.yxi.ui.theme.Copper
import app.yxi.ui.theme.Dim
import app.yxi.ui.theme.DiffAddFg
import app.yxi.ui.theme.OnSurfaceVariant
import app.yxi.ui.theme.Teal

/**
 * 极简语法高亮：**注释 / 字符串 / 数字 / 关键字**，四类，就这些。
 *
 * ⚠️ **故意不上语法高亮库。** 完整的 tree-sitter / prettify 类方案要几 MB 依赖和一堆语法文件，
 * 而这里的目的只是「在手机上瞄一眼代码别糊成一片」。四类着色已经能把结构撑起来，
 * 认不出的语言退化成纯文本也没坏处 —— 这是**故意留的天花板**，
 * 真需要精确高亮时再换库（那时也只用改这一个文件）。
 */
object Highlight {

    private val COMMON = setOf(
        "if", "else", "for", "while", "return", "break", "continue", "class", "function", "func",
        "def", "import", "from", "package", "new", "true", "false", "null", "nil", "none", "try",
        "catch", "except", "finally", "throw", "raise", "switch", "case", "default", "const",
        "let", "var", "val", "fun", "public", "private", "protected", "static", "void", "int",
        "string", "bool", "type", "struct", "interface", "enum", "async", "await", "this", "self",
        "and", "or", "not", "in", "is", "as", "with", "do", "then", "fi", "esac", "done", "elif",
        "export", "local", "echo", "set", "unset", "sudo", "override", "suspend", "object",
        "when", "data", "companion", "internal", "lateinit", "by", "it",
    )

    /** 行注释的起头符号，按扩展名分。 */
    private fun lineComment(ext: String): List<String> = when (ext) {
        "py", "sh", "bash", "zsh", "yml", "yaml", "toml", "ini", "conf", "cfg", "properties", "env" -> listOf("#")
        "sql" -> listOf("--")
        // markdown / 纯文本没有注释。不排除的话 `#` 会被当注释符 ——
        // 标题变灰凑巧好看，但 `https://x/#anchor` 之后整行都会灰掉
        "md", "txt", "csv", "log" -> emptyList()
        "" -> listOf("#")          // 没扩展名的多半是脚本
        else -> listOf("//", "#")  // 两种都认，认错了顶多少上一点色
    }

    private val TOKEN = Regex("""[A-Za-z_][A-Za-z0-9_]*|\d+(\.\d+)?""")

    fun of(text: String, ext: String): AnnotatedString {
        val marks = lineComment(ext)
        val kw = SpanStyle(color = Copper, fontWeight = FontWeight.Medium)
        val str = SpanStyle(color = DiffAddFg)
        val num = SpanStyle(color = Teal)
        val cmt = SpanStyle(color = Dim)

        return buildAnnotatedString {
            text.lineSequence().forEachIndexed { i, line ->
                if (i > 0) append('\n')
                // 行注释：从注释符号开始整行都是注释，注释里不再分词
                val at = marks.mapNotNull { m -> line.indexOf(m).takeIf { it >= 0 } }.minOrNull()
                val code = if (at != null) line.substring(0, at) else line
                highlightCode(code, kw, str, num)
                if (at != null) withStyle(cmt) { append(line.substring(at)) }
            }
        }
    }

    /** 先切字符串，字符串之外的部分再分词 —— 否则字符串里的 `if` 会被染成关键字。 */
    private fun androidx.compose.ui.text.AnnotatedString.Builder.highlightCode(
        code: String, kw: SpanStyle, str: SpanStyle, num: SpanStyle,
    ) {
        var i = 0
        var plain = StringBuilder()
        fun flush() {
            if (plain.isEmpty()) return
            val s = plain.toString(); plain = StringBuilder()
            var last = 0
            TOKEN.findAll(s).forEach { m ->
                append(s.substring(last, m.range.first))
                val t = m.value
                when {
                    t.first().isDigit() -> withStyle(num) { append(t) }
                    t.lowercase() in COMMON -> withStyle(kw) { append(t) }
                    else -> append(t)
                }
                last = m.range.last + 1
            }
            append(s.substring(last))
        }
        while (i < code.length) {
            val c = code[i]
            if (c == '"' || c == '\'' || c == '`') {
                flush()
                val start = i; i++
                while (i < code.length && code[i] != c) { if (code[i] == '\\') i++; i++ }
                if (i < code.length) i++
                withStyle(str) { append(code.substring(start, minOf(i, code.length))) }
            } else {
                plain.append(c); i++
            }
        }
        flush()
    }
}
