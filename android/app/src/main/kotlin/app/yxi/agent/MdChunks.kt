package app.yxi.agent

/**
 * 把一段 markdown 切成**能分别渲染的块**，用来把网址预览卡摆到**它所属的那一段下面**，
 * 而不是全堆在整条消息的末尾（老板 2026-09-07：「网页渲染最好紧紧跟在对应的链接下面」）。
 *
 * 切的规矩只有一条：**在空行处断开，但绝不切断一个整体**。三种整体：
 *  · **围栏代码块**（``` / ~~~）—— 里面的空行是代码的一部分，切开会变成两个残缺的块；
 *  · **列表** —— 中间带空行的「松散列表」很常见，切开的话**有序列表的编号会从 1 重来**
 *    （第 3 条变成新列表的第 1 条），这是最容易被忽略的一种坏法；
 *  · **表格** —— 表格行之间本来就没有空行，天然不会被切到，这里只是记一笔。
 *
 * ⚠️ 切完拼回去要**语义等价**（[split] 的结果按段落顺序渲染，跟整段渲染看起来一样）。
 * 唯一可见的差别是块与块之间的间距由外层容器决定，不再由 markdown 渲染器内部决定。
 */
object MdChunks {

    /** `- x` / `* x` / `+ x` / `1. x` / `1) x`，允许前面有缩进 */
    private val LIST_ITEM = Regex("""^\s*(?:[-*+]\s+|\d+[.)]\s+)""")

    private fun isListItem(line: String) = LIST_ITEM.containsMatchIn(line)

    /** 续行：缩进的段落（列表项的第二段、代码缩进）也算还在这个列表里 */
    private fun isIndented(line: String) = line.startsWith("  ") || line.startsWith("\t")

    /** 引用式链接定义：`[1]: https://…`（可带标题）。整行就是一条定义。 */
    private val LINK_DEF = Regex("""^\s{0,3}\[[^\]]+]:\s*\S+.*$""")

    /**
     * 切块。空输入返回空表；没有空行就原样一块。
     * 保证：每块非空、去掉了首尾空行；引用式链接定义会补进每一块（见函数末尾）。
     */
    fun split(md: String): List<String> {
        if (md.isBlank()) return emptyList()
        // ⚠️ 先去掉 \r：CRLF 的空行是 "\r"，`isBlank()` 认它不空 → 一个块都切不出来，
        //    功能**静默失效**（卡片又堆回末尾），不报错最难查（审查查出）。
        val lines = md.replace("\r", "").split("\n")
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var curHasList = false
        var fence: String? = null

        fun flush() {
            val s = cur.toString().trim('\n')
            if (s.isNotBlank()) out += s
            cur.clear(); curHasList = false
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trimStart()

            if (fence == null && (t.startsWith("```") || t.startsWith("~~~"))) {
                fence = t.take(3)
            } else if (fence != null && t.startsWith(fence)) {
                cur.append(line).append('\n'); fence = null; i++; continue
            }

            if (line.isBlank() && fence == null) {
                // 往后找第一行非空，判断这个空行是「段落之间」还是「列表内部」
                val next = ((i + 1) until lines.size).firstOrNull { lines[it].isNotBlank() }?.let { lines[it] }
                val listContinues = next != null && (isListItem(next) || isIndented(next))
                if (curHasList && listContinues) {
                    // 松散列表：空行留在块里，编号才不会重来
                    cur.append('\n'); i++; continue
                }
                flush(); i++; continue
            }

            if (fence == null && isListItem(line)) curHasList = true
            cur.append(line).append('\n')
            i++
        }
        flush()

        // ⚠️ **引用式链接的定义要发给每一块。** `见[规约][1]` 和底下的 `[1]: https://…` 被切进不同块之后，
        //    含用法的那块单独渲染时找不到定义，链接会**渲染成纯文本** `[规约][1]`（审查查出）。
        //    做法：把所有定义行收集起来，追加到每个「不是纯定义」的块尾。定义行本身不显示，
        //    所以重复追加不会在界面上多出东西。
        val defs = out.flatMap { it.split("\n") }.filter { LINK_DEF.matches(it) }
        if (defs.isEmpty()) return out
        val tail = "\n\n" + defs.joinToString("\n")
        return out.map { c ->
            if (c.split("\n").all { it.isBlank() || LINK_DEF.matches(it) }) c else c + tail
        }
    }
}
