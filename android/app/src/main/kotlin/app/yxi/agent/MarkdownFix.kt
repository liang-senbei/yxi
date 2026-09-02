package app.yxi.agent

/**
 * 把 GitHub 风格 Markdown 里**用 HTML 写的图**改成 Markdown 图，好让渲染器认。
 *
 * ⚠️ 病根：很多仓库的 README / 论文稿用 `<img src="figs/x.png" width="900" />` 而不是 `![](figs/x.png)`
 * （为了控制宽度、居中）。我们的渲染器只认 Markdown 图，整行 `<img …>` 被当成 HTML 块原样吞掉，
 * 用户看到的是**图全没了**（unitree_rl_mjlab 那份 thesis.md 一张都不显示）。
 *
 * 只做最小的事：`<img>` → `![alt](src)`；把 `<p>` / `<div>` / `<center>` 这类只为居中的壳剥掉
 * （它们会让整段变成 HTML 块，里面的 Markdown 就不解析了）；`<b>` → `**`；`<br>` → 换行。
 * 别的 HTML 一律不碰。
 */
object MarkdownFix {

    private val IMG = Regex("""<img\b([^>]*?)/?>""", RegexOption.IGNORE_CASE)
    private val ATTR_SRC = Regex("""\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ATTR_ALT = Regex("""\balt\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val SHELL = Regex("""</?(?:p|div|center|span|figure|figcaption)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val BOLD = Regex("""</?(?:b|strong)\s*>""", RegexOption.IGNORE_CASE)
    private val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

    fun apply(md: String): String {
        if (!md.contains('<')) return md
        var s = IMG.replace(md) { m ->
            val attrs = m.groupValues[1]
            val src = ATTR_SRC.find(attrs)?.groupValues?.get(1) ?: return@replace m.value
            val alt = ATTR_ALT.find(attrs)?.groupValues?.get(1).orEmpty().replace("]", "")
            "![$alt]($src)"
        }
        s = SHELL.replace(s, "")
        s = BOLD.replace(s, "**")
        s = BR.replace(s, "  \n")
        return s
    }
}
