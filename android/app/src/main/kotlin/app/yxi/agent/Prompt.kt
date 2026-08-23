package app.yxi.agent

/**
 * 从 `tmux capture-pane` 的屏幕文本里认出**正在等你回答的那个选择器**。
 *
 * ⚠️ **为什么不从转录里读**：实测过了 —— Claude Code 的 `tool_use` 块
 * **要等工具跑完才写进 JSONL**。也就是说「问题挂在那儿等你」的那段时间里，
 * 转录里什么都没有（用户消息在、assistant 那条不在）。
 * 所以待答提示只能从屏幕来。历史照旧读转录，两者分工不同：
 * **转录是权威的历史，屏幕是唯一的「此刻」。**
 *
 * ⚠️ **顺带的好处**：屏幕上写着几号，我们就送几号。
 * 要是改成「从 JSON 读选项、按下标送键」，一旦顺序对不上就会**点 A 选中 B** ——
 * 那种错误不会报错，只会默默选错。**屏幕上的数字就是契约。**
 *
 * 按键协议全部实测过（送给 `tmux send-keys`）：
 *   · 单选：送数字 → **直接选中并确认**，不用再送 Enter
 *   · 多选：送数字 → 切换勾选；`Right` → 跳到 Submit 页；再送 `1` → 提交
 *   · 多个问题：`Left` / `Right` 在问题标签之间切换
 *   · 通用：`Up`/`Down`/`j`/`k` 移动，`Esc` 取消
 */
data class Pending(
    /** 问题正文。取不到就是空串（照样能选，只是没抬头）。 */
    val title: String,
    val options: List<Option>,
    /** 多选：数字是切换勾选，要 `Right` + `1` 才算提交。 */
    val multiSelect: Boolean,
    /**
     * 这一整块提示的指纹（问题正文 + 上面几行上下文 + 所有选项）。
     *
     * ⚠️ **只比「几号 + 选项文案」是不够的。** 两个不同的权限提示，选项**一模一样**：
     * 都是 `1. Yes / 2. Yes, and always… / 3. No`，连标题都同样是 `Do you want to proceed?`。
     * 你在终端里答掉了 A、屏幕上换成了 B，那种比对照样放行 ——
     * **于是你以为在批 A，实际批的是 B。**
     * 真正能区分两者的是上面那几行（命令本身），所以指纹要把它们算进去。
     */
    val fingerprint: String,
) {
    data class Option(
        /** 屏幕上那个数字，**送键就送它**。 */
        val number: Int,
        val label: String,
        val description: String = "",
        /** 多选时当前是否已勾上。 */
        val checked: Boolean = false,
    )
}

object Prompt {

    private val OPTION = Regex("""^\s*[❯>]?\s*(\d+)\.\s+(.*\S)\s*$""")
    private val CHECKBOX = Regex("""^\[([ xX✔✓])]\s*(.*)$""")

    /**
     * 底部这行是选择器的标志。没有它就说明当前没在等人选。
     *
     * ⚠️ **脚注不止一种。** 一开始只认 `to navigate`，结果**权限提示完全认不出来** ——
     * 而那恰恰是最该被认出来的一种。真机上抓到的两种：
     *   · `Enter to select · ↑/↓ to navigate · Esc to cancel`（AskUserQuestion / 计划批准）
     *   · `Esc to cancel · Tab to amend · ctrl+e to explain`（**权限提示**，没有 navigate）
     * 共同点是 `to cancel`，就拿它当锚。
     */
    private fun isFooter(l: String) =
        "to cancel" in l || ("to navigate" in l && ("Enter to" in l || "to select" in l))

    /**
     * **光标行** —— `❯ 1. Yes, and use auto mode` 这种。
     *
     * ⚠️ **这是比脚注更稳的锚。** 脚注的形态到现在已经变过**三次**了：
     *   · `Enter to select · ↑/↓ to navigate · Esc to cancel`
     *   · `Esc to cancel · Tab to amend · ctrl+e to explain`（权限提示，见 #46）
     *   · `ctrl+g to edit in VS Code · ~/.claude/plans/xxx.md`（**计划批准**，两个锚都没有）
     * 第三种是实测抓到的：脚注既没有 `to cancel` 也没有 `to navigate`，
     * 于是**整个计划批准框在手机上是隐形的 —— 用户根本批不了计划**。见 #82。
     *
     * 而 `❯` + 编号这个形态，在我手上**全部五份真实抓屏里都在**（单选/多选/权限/计划）。
     * 它语义上就是「此刻选中的那一项」，选择器活着它就在。
     *
     * ⚠️ 注意 `❯` 单独出现时是**输入框**（`❯ 用 AskUserQuestion 工具问我…`），
     * 所以必须连编号一起要求，光看 `❯` 会把用户打的字当成选项。
     */
    private val CURSOR = Regex("""^\s*❯\s*\d+\.\s+\S.*$""")

    /**
     * @param screen `tmux capture-pane -p` 的原样输出
     * @return 没有在等人选就返回 null
     */
    fun parse(screen: String): Pending? {
        val lines = screen.lines()
        // 先用脚注（四条测试盯着的老路子）；认不出来再退回光标锚。
        // 退回而不是替换：老路子是实测钉住的，没必要拿新写法去赌它。
        val footer = lines.indexOfLast(::isFooter).takeIf { it >= 0 }
            ?: endOfOptionsAfterCursor(lines)
        if (footer < 0) return null

        // ⚠️ **从脚注往上收，编号必须连续递减到 1。**
        // 不能只按「脚注上面 N 行里的编号行」算 —— 计划正文本身就常常是
        // `1. 烧水 / 2. 下面 / 3. 出锅` 这种编号列表，**就贴在选择器上面**。
        // 把它当成选项，用户点第 3 项时送出去的 `3` 会落到真选项的第 3 个上，
        // **点 A 选中 B，而且不报错**。连续性这条规则才挡得住。
        val numbered = HashMap<Int, Int>()      // 选项号 → 行号
        var expected = -1
        for (i in footer - 1 downTo 0) {
            val m = OPTION.matchEntire(lines[i])
            if (m == null) {
                if (expected == 0) break        // 已经收到 1 号，上面的不要了
                continue
            }
            val n = m.groupValues[1].toInt()
            when {
                expected == -1 -> { numbered[n] = i; expected = n - 1 }
                n == expected -> { numbered[n] = i; expected = n - 1 }
                else -> break                   // 编号断了 —— 上面那些不是这一组的
            }
            if (expected == 0) break
        }
        if (numbered.isEmpty() || !numbered.containsKey(1)) return null

        // 正向再走一遍，把选项之间的说明行挂到上一个选项上
        val firstLine = numbered.getValue(1)
        val opts = ArrayList<Pending.Option>()
        var multi = false
        // 不含脚注那行本身 —— 含了它会被当成最后一项的「说明」（真机上看见过）
        for (i in firstLine until footer) {
            val m = OPTION.matchEntire(lines[i])
            if (m != null && numbered[m.groupValues[1].toInt()] == i) {
                var label = m.groupValues[2]
                var checked = false
                CHECKBOX.matchEntire(label)?.let { c ->
                    multi = true
                    checked = c.groupValues[1].isNotBlank()
                    label = c.groupValues[2].trim()
                }
                opts += Pending.Option(m.groupValues[1].toInt(), label, checked = checked)
            } else if (opts.isNotEmpty() && !isNoise(lines[i])) {
                val last = opts.removeAt(opts.lastIndex)
                opts += if (last.description.isEmpty()) last.copy(description = lines[i].trim()) else last
            }
        }

        // 标题 = 1 号选项上面最后一行「像话」的文本
        val title = (firstLine - 1 downTo 0)
            .map { lines[it] }
            .firstOrNull { !isNoise(it) && OPTION.matchEntire(it) == null }
            ?.trim().orEmpty()

        // 指纹：从 1 号选项**往上 8 行**一直到脚注，去掉空白后哈希。
        // 往上 8 行是为了把权限提示里的命令正文圈进来 —— 那才是区分两个提示的东西。
        val from = (firstLine - 8).coerceAtLeast(0)
        val fp = lines.subList(from, footer)
            .joinToString("\n") { it.trim() }
            .filter { !it.isWhitespace() }
            .hashCode().toString(16)

        return Pending(title, opts, multi, fp)
    }

    /** 分隔线、标签栏（`←  ☒ 配菜  ✔ Submit  →`）、提示脚注这些不是内容。 */
    /**
     * 找不到已知脚注时的退路：从**最后一个光标行**出发，往下走到选项块结束，
     * 返回「脚注该在的位置」（第一行不再属于这个选项块的行号）。
     *
     * 往下走而不是就地返回：光标可能停在 2 号，下面还有 3 号、4 号。
     */
    private fun endOfOptionsAfterCursor(lines: List<String>): Int {
        val cursor = lines.indexOfLast { CURSOR.matches(it) }
        if (cursor < 0) return -1
        var end = cursor + 1
        while (end < lines.size) {
            val l = lines[end]
            // 选项、选项的说明行（缩进的非结构文本）都还算这一块
            if (OPTION.matchEntire(l) != null || (l.isNotBlank() && l.first() == ' ' && !isNoise(l))) {
                end++
            } else break
        }
        // ⚠️ **至少两行才算选项块。** 用户在输入框里打「1. 先做这个」，那一行渲染出来
        // 就是 `❯ 1. 先做这个` —— **跟光标行长得一模一样**。只有一行的话，
        // 会凭空冒出一张只有一个选项的「等你选」卡片，用户点一下就往他正在写的
        // 句子里打个 `1` 进去，还可能弹一条假通知。
        //
        // 真选择器至少有两个选项、或一个选项加一行说明；输入框下面紧跟着就是那条横线。
        //
        // 注：实测 Android 上暂时撞不上 —— 输入框那行 `❯` 后面是 **U+00A0**，
        // 而 Java 的 `\s` 只认 ASCII 空白（同样的正则在 Swift/ICU 上就会命中，
        // iOS 侧真机抓到了）。**但那是侥幸不是设计**，别指望它。
        return if (end > cursor + 1) end else -1
    }

    private fun isNoise(l: String): Boolean {
        val t = l.trim()
        return t.isEmpty() ||
            t.all { it == '─' || it == '-' || it == '━' } ||
            t.startsWith("←") || t.endsWith("→") ||
            t.startsWith("shift+tab") || t.startsWith("ctrl+") ||
            t.startsWith("❯ ")   // 用户自己刚敲的那行命令
    }
}
