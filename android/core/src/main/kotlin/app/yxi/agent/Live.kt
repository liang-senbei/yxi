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
    /** 收尾那条 `✻ Baked for 13s` 里的耗时（`13s`）。刚跑完才有，用来显示「刚跑完·13s」。 */
    val doneFor: String? = null,
) {
    companion object {
        val IDLE = Live(busy = false, status = null)

        /**
         * 输入框那两条横线。
         *
         * ⚠️⚠️ **不能要求「整行纯横线」**：Claude Code 现在把**会话名画在上边框里** ——
         * `──────────── omggrow_order ─`。要求纯横线的话上边框认不出来，
         * 一屏只剩下边框一条，于是 `dividers.size < 2`：
         *   · [Model.borrowable] 直接返回 false → 点切模型永远弹「它正忙着，或者输入框里有没发完的字」，
         *     **跟忙不忙、有没有草稿都无关**（老板 2026-09-06 报的）；
         *   · 这里 `boxTop` 落回 -1 → 状态行改在**整屏**里找，正文里的字可能被当成状态。
         * 判据改成「横线占大头」：至少 8 根、且占整行 60% 以上 —— 带名字的上边框是 72%，正文过不了。
         */
        fun isDivider(l: String): Boolean {
            val t = l.trim()
            if (t.length < 8) return false
            val dashes = t.count { it == '─' }
            return dashes >= 8 && dashes * 5 >= t.length * 3
        }

        /**
         * 输入框里还有没有字。**`null` = 判断不了**（读不到屏、找不到那两条边框）。
         *
         * ⚠️ 跟 [Model.borrowable] 的区别：那个还要求「不忙」（借会话送键的前提）；
         * 这个**只问输入框空不空**。发完消息之后 Claude 正在跑是常态，
         * 拿 borrowable 判会一直是 false，就会误以为没发出去、反复补回车。
         * ⚠️ **`null` 和 `false` 不能混**：读不到屏就别下结论，更别补回车（#276 的老规矩）。
         */
        /** 排队时输入框里的占位提示 —— 它出现 = 话已经收下进队列了，不是没发出去。 */
        private val QUEUED_HINT = Regex("""^press up to edit queued messages?\.?$""", RegexOption.IGNORE_CASE)

        fun inputEmpty(screen: String): Boolean? {
            if (screen.isBlank()) return null
            // ⚠️⚠️ **选单/审批框也是两条横线夹着的**（Claude Code 的 AskUserQuestion、
            //    计划审批、权限确认都自己画横线）。只看「横线之间有几行」的话，
            //    这些屏一律被判成 `false` = 「输入框里还堆着东西」，
            //    而调用方（[app.yxi.agent.SessionProbe.send]）看到 false 会**补一次回车** ——
            //    那就是**替用户按下了默认选项**。审查用本仓库真实截屏验过：
            //    计划审批框的默认行是「Yes, and use auto mode」，补的第一个回车就把它按了。
            //    更要命的是通知里「回一句」那条路：代码**故意**对危险命令不给一键批准按钮
            //    （见 EventService 里引的那次事故：脚本删掉了 authorized_keys），只给自由文本回复 ——
            //    补回车正好从这条路把那道防线绕开。
            //    **所以：只要屏上是个待选的框，就返回 null（我不知道），绝不返回 false。**
            if (app.yxi.agent.Prompt.parse(screen) != null) return null

            val lines = screen.split('\n').map { it.trimEnd() }
            val dividers = lines.indices.filter { isDivider(lines[it]) }
            if (dividers.size < 2) return null
            val body = lines.subList(dividers[dividers.size - 2] + 1, dividers.last())
            // ⚠️ 输入框的标志是**第一行以 ❯ 开头**。不长这样就不是输入框（是别的框），
            //    返回 null —— 宁可不下结论，也不能让调用方去补回车。
            // ⚠️⚠️ **但不能要求「只有一行」**：带附件的草稿本来就是多行的
            //    （头部若干行路径 + 正文），那正是这个判据要抓的场景。
            //    我第一版写成 `singleOrNull` 把它一起判成 null 了，被自己的用例当场抓住。
            val first = body.firstOrNull()?.trimStart() ?: return null
            if (!first.startsWith("❯")) return null
            val inBox = body.joinToString("").trimStart().removePrefix("❯").trim()
            // ⚠️⚠️ **框里那句「Press up to edit queued messages」是占位提示，不是用户的草稿。**
            //    Claude Code 忙着的时候你发的话会进**排队**，这时它把这句画进输入框里
            //    （真会话实测，见 PromptRealTest 里那两份抓屏）。原来判成「还堆着东西」→
            //    补三次回车 → 再报「没发出去」→ 把话还回输入框。老板 2026-09-06 录屏为证：
            //    服务器上那条消息**到了五次**，他每次看到「没发出去」就再发一遍。
            //    **有这句恰恰证明消息已经收下了**（排队 = 收下了，只是还没轮到）。
            if (QUEUED_HINT.matches(inBox)) return true
            return inBox.isBlank()
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

        /**
         * **任何**一条状态行 —— 进行中和收尾两种形态都算。
         *
         * 进行中：`✽ Gusting… (1m 2s · ↓ 1.7k tokens)`
         * 收尾了：`✻ Baked for 13s`
         *
         * ⚠️ 分组 2 是分隔符：`…` 表示还在跑，` for ` 表示跑完了。
         * **屏幕上最后一条状态行属于哪种，就是此刻的状态** —— 这是最直接的判据。
         *
         * ⚠️⚠️ **词里可能有撇号和连字符**：Claude Code 的状态词有一批是掉字母 g 的口语形式
         * （`Beboppin'` `Jivin'` `Moseyin'`），原来的 `[A-Za-z]+` 撞上撇号整行就不匹配 ——
         * 表现是**状态行时有时无**（抽到普通词就显示、抽到带撇号的就消失），用户报「很不稳定」。
         * 撇号有 ASCII 和排版两种，都认。
         * ⚠️ **但不能允许词里有空格**：那样 `- Waited for 3s` 这种正文会被当成「跑完了」。
         * ⚠️⚠️ **更不能允许行首有空格**（我试过，被 `屏幕上只是提到状态词不算数` 当场打回）：
         *    转录里**引用**的状态行是带缩进的，顶格的才是此刻真的状态行 —— 缩进与否正是判据本身。
         */
        private val STATUS_ANY =
            Regex("""^(\S) ([A-Za-z\u00C0-\u024F][A-Za-z\u00C0-\u024F'\u2019\-]{0,30})(…| for )(.*)$""")

        /** @param screen `tmux capture-pane -p` 的原样输出 */
        fun parse(screen: String): Live {
            val lines = screen.split('\n').map { it.trimEnd() }
            if (lines.isEmpty()) return IDLE

            // 输入框 = 最后两条横线之间。它下面是脚注，上面是转录区。
            val dividers = lines.indices.filter { isDivider(lines[it]) }
            val boxTop = dividers.getOrNull(dividers.size - 2) ?: -1
            val boxBottom = dividers.lastOrNull() ?: lines.size

            val above = if (boxTop >= 0) lines.take(boxTop) else lines

            // ⚠️ **不能只看脚注里有没有 `esc to interrupt`。**
            // 手机上的终端很窄，脚注会被截断成 `… · e…` —— 那几个字根本没露出来，
            // 于是「在忙」永远判成 false，对话里的状态条整个消失
            // （而终端里明明写着 `Cogitating…`）。用户报的就是这个。
            //
            // **真正的判据是屏幕上最后一条状态行属于哪种形态**：
            // `Gusting…` = 还在跑，`Baked for 13s` = 跑完了。
            // 脚注仍然认 —— 宽屏时它是个额外的确证，但不再是唯一依据。
            val footerBusy = "esc to interrupt" in lines.drop(boxBottom + 1).joinToString("\n")
            val last = above.asReversed().firstNotNullOfOrNull { STATUS_ANY.matchEntire(it) }
            val running = last != null && last.groupValues[3] == "…"
            val busy = footerBusy || running

            // ⚠️ 只有「还在跑」那一条才给文案。收尾那条（`Baked for 13s`）不是状态，是结果。
            val status = last?.takeIf { running }
                ?.let { (it.groupValues[2] + it.groupValues[3] + it.groupValues[4]).trim() }
            // 收尾形态 `✻ Baked for 13s`：group4 是耗时。刚跑完才有，喂给「刚跑完·13s」。
            val doneFor = last?.takeIf { !running }?.groupValues?.get(4)?.trim()?.ifBlank { null }

            return Live(busy, status, doneFor)
        }
    }
}
