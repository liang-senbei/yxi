package app.yxi.agent

/**
 * 从 `tmux capture-pane` 认出**此刻它在忙什么**。
 *
 * ⚠️ **状态词只有屏幕有。** `✽ Scampering… (4m 48s · ↓ 10.2k tokens)` 纯粹是 TUI 的渲染，
 * 永远不落盘。而它恰恰是「Claude 还活着、正在干活」的唯一信号 ——
 * 没有它，长时间的工具调用在手机上看起来就是界面卡死了。
 *
 * ⚠️ **排队的输入不走这里，走转录**（`queue-operation` / `queued_command`，
 * 见 [Transcript]）。一开始是从这块屏幕文本里刮的，能用，但要处理折行拼接、
 * 还要跟转录比对去重才能分清「排队中」和「已处理」——
 * 而转录里本来就有原文和进出队时刻。**能从权威来源拿的就别刮屏。**
 *
 * 分工：**历史和排队读转录（结构化、权威），此刻在忙什么读屏幕（唯一来源）。**
 * 和 [Prompt] 认「正在等你选」是同一个道理。
 */
data class Live(
    /** 正在跑（脚注里有 `esc to interrupt`）。 */
    val busy: Boolean,
    /** 状态词原文，如 `Scampering… (4m 48s · ↓ 10.2k tokens)`。不忙时为 null。 */
    val status: String?,
) {
    companion object {
        val IDLE = Live(busy = false, status = null)

        /** 输入框那两条横线。整行几乎全是 `─` 才算。 */
        private fun isDivider(l: String): Boolean {
            val t = l.trim()
            return t.length >= 8 && t.all { it == '─' }
        }

        /**
         * 状态行：**行首一个符号 + 一个以 `…` 结尾的词**，如 `✽ Scampering…`。
         * 收尾的形态是 `✻ Baked for 13s`（过去式 + for），那表示已经不忙了。
         *
         * ⚠️ **省略号前面那个词必须是拉丁字母。** 中文不写空格，所以顶格的中文排队输入
         * `❯ 排队丙：这条带省略号…后面还有字` 会整条命中 `^(\S) (\S*….*)$` ——
         * 手机上就显示成「正在 排队丙：这条带省略号…」。而排队行**渲染在状态行下面**，
         * 倒着找的话先撞上它。范围放到 U+024F 是为了保住 `Sautéing…` 里的 `é`。
         *
         * ⚠️ 必须要求**顶格**。屏幕上完全可能有别的东西**提到**这些字样 ——
         * 比如把画面贴进对话里给人看，那几行就带着缩进出现在转录区。
         * 顶格是 TUI 渲染状态行的唯一形态，拿它当锚点。
         */
        private val STATUS = Regex("""^(\S) ([A-Za-z\u00C0-\u024F]+….*)$""")

        /** @param screen `tmux capture-pane -p` 的原样输出 */
        fun parse(screen: String): Live {
            val lines = screen.split('\n').map { it.trimEnd() }
            if (lines.isEmpty()) return IDLE

            // 输入框 = 最后两条横线之间。它下面是脚注，上面是转录区。
            val dividers = lines.indices.filter { isDivider(lines[it]) }
            val boxTop = dividers.getOrNull(dividers.size - 2) ?: -1
            val boxBottom = dividers.lastOrNull() ?: lines.size

            val busy = "esc to interrupt" in lines.drop(boxBottom + 1).joinToString("\n")
            val above = if (boxTop >= 0) lines.take(boxTop) else lines

            // 取**最后一条**：屏幕上留着历次的 `✻ Baked for 13s`，只有最后那条是此刻的
            val status = above.asReversed().firstNotNullOfOrNull { l ->
                STATUS.matchEntire(l)?.groupValues?.get(2)?.trim()
            }?.takeIf { busy }

            return Live(busy, status)
        }
    }
}
