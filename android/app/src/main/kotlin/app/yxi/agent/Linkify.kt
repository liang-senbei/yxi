package app.yxi.agent

/**
 * 把 AI 回复里的**文件路径**变成可点的链接 —— 点一下直接跳进文件模式看那个文件。
 *
 * 做法是**改写 markdown 源码**（`/a/b.md` → `[/a/b.md](yxi-file:///a/b.md)`），
 * 而不是去改渲染器：
 *
 * ⚠️ 试过库的 [com.mikepenz.markdown.model.MarkdownAnnotator]，**走不通**。
 * 它的回调粒度是 AST 节点，而 intellij-markdown 把正文切得极碎 ——
 * `/root/src/a.md` 会散成 `/` `root` `/` `src` … 一串独立节点，
 * 在单个节点里根本看不出这是一条路径。**要跨节点的判断，就别在节点回调里做。**
 *
 * 点击怎么接住：库最终把链接交给 `LocalUriHandler`，所以 [app.yxi.ui.ChatScreen]
 * 在外面套一层自己的 UriHandler，认出 [SCHEME] 就转成「打开这个文件」。
 */
object Linkify {
    const val SCHEME = "yxi-file://"

    /**
     * 一段路径里允许出现的字符。
     *
     * ⚠️ **必须带上中文**：用户机器上真有 `/opt/workspace/诗歌`、`/opt/workspace/文件传`
     * 这样的目录。只认 ASCII 的话，`/a/b/跳转样例.md` 会在 `/b/` 处被切断，
     * 点开的是**上一级目录**，看起来像「跳错地方了」。
     *
     * ⚠️ **只收汉字本体（U+4E00–U+9FFF），不能顺手把 U+3000–U+303F 也放进来** ——
     * 那一段是「。，、《》」这些标点，收进来的话 `/a/b。后面这句话` 会被整条吃掉。
     * 中文句子里本来就不写空格，一旦吃穿就是一整句变成假链接。
     */
    private const val SEG = """[A-Za-z0-9._+@一-鿿-]+"""

    /**
     * 认路径。**故意认得保守** —— 认错的代价是把正文里一段普通文字变成假链接，
     * 点了还会跳走，比漏认难受得多。
     *
     * 两条硬规矩：
     *   · 要么 `~` 开头（`~/x` 一段就够，`~` 已经足够表明这是路径）
     *   · 要么 `/` 开头且**至少两段**（`/a/b`）
     *
     * ⚠️ **「至少两段」不是随便定的。** 只要一段的话，`整个 /22 段注册给一家代理商`
     * 里的 `/22` 就会被认成路径 —— 这句真的出现在用户的对话里。
     * 同理 `and/or`、`读/写` 不以 `/` 开头，天然不中。
     *
     * ⚠️ 前面那个 lookbehind 挡的是 `http://`、`git@x:/a/b` 这种 —— 
     * 冒号或字母紧挨着的斜杠不是路径开头。
     *
     * ⚠️ **末尾那个可选的 `/` 不能省。** 目录常常写成 `` `/a/b/` `` ——
     * 行内代码那一支用的是 `matches`（整段都得是路径），少了它这种就整段不认。
     */
    private val PATH = Regex("""(?<![\w:/~.-])(~(?:/$SEG)+|(?:/$SEG){2,})/?""")

    /** 已经是 markdown 链接的段落，原样放过，别套两层。 */
    private val LINK = Regex("""\[[^\]\n]*\]\([^)\n]*\)""")

    /** 行内代码。⚠️ 整段就是一条路径时**连反引号一起**包进链接文字里 —— 见 [apply]。 */
    private val CODE = Regex("""`[^`\n]+`""")

    private val FENCE = Regex("""^\s*(```|~~~)""")

    /**
     * 把 [md] 里认出来的路径改写成链接。
     *
     * 三种上下文分开处理：
     *   · **围栏代码块**里的一律不动 —— 那是给人照抄的原文，插链接就毁了
     *   · **行内代码**整段是路径时，写成 ``[`/a/b.md`](…)``。
     *     markdown 允许链接文字里放行内代码，所以点得动、还是等宽的。
     *     ⚠️ 不能只把反引号**里面**换成链接语法：代码段里的 `[]()` 不会被解析，
     *     屏幕上会原样冒出一串 `[/a/b](yxi-file:///a/b)`
     *   · **普通正文**里直接替换，但先把已有的 markdown 链接摘出去
     */
    fun apply(md: String): String {
        var fenced = false
        return md.lineSequence().joinToString("\n") { line ->
            if (FENCE.containsMatchIn(line)) { fenced = !fenced; return@joinToString line }
            if (fenced) return@joinToString line
            line.splitKeeping(CODE) { piece, isCode ->
                if (isCode) {
                    val inner = piece.trim('`')
                    if (PATH.matches(inner)) "[$piece](${SCHEME}$inner)" else piece
                } else {
                    piece.splitKeeping(LINK) { p, isLink ->
                        if (isLink) p else PATH.replace(p) { m ->
                            // 句末标点会被 SEG 里的 `.` 吃进去：`见 /a/b/c.` → 把尾巴那个点还回去
                            val raw = m.value
                            val path = raw.trimEnd('.', ',')
                            "[$path](${SCHEME}$path)" + raw.substring(path.length)
                        }
                    }
                }
            }
        }
    }

    /** 按 [re] 切开，命中的片段和没命中的片段各自交给 [f]，再拼回去。 */
    private inline fun String.splitKeeping(re: Regex, f: (String, Boolean) -> String): String {
        val sb = StringBuilder()
        var at = 0
        re.findAll(this).forEach { m ->
            if (m.range.first > at) sb.append(f(substring(at, m.range.first), false))
            sb.append(f(m.value, true))
            at = m.range.last + 1
        }
        if (at < length) sb.append(f(substring(at), false))
        return sb.toString()
    }
}
